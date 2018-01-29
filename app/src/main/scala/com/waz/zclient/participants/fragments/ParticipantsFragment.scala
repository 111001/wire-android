/**
 * Wire
 * Copyright (C) 2018 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.participants.fragments

import android.animation.{Animator, AnimatorListenerAdapter, AnimatorSet, ObjectAnimator}
import android.content.Context
import android.os.Bundle
import android.support.annotation.Nullable
import android.support.v4.app.{Fragment, FragmentManager}
import android.view.animation.Animation
import android.view.{LayoutInflater, View, ViewGroup}
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.api.User.ConnectionStatus._
import com.waz.api._
import com.waz.model._
import com.waz.threading.Threading
import com.waz.utils.returning
import com.waz.zclient.common.controllers.{SoundController, ThemeController, UserAccountsController}
import com.waz.zclient.controllers.confirmation.{ConfirmationRequest, IConfirmationController, TwoButtonConfirmationCallback}
import com.waz.zclient.controllers.navigation.{INavigationController, Page}
import com.waz.zclient.controllers.singleimage.ISingleImageController
import com.waz.zclient.conversation.ConversationController
import com.waz.zclient.conversation.ConversationController.ConversationChange
import com.waz.zclient.core.stores.connect.IConnectStore
import com.waz.zclient.core.stores.conversation.ConversationChangeRequester
import com.waz.zclient.core.stores.conversation.ConversationChangeRequester._
import com.waz.zclient.pages.BaseFragment
import com.waz.zclient.pages.main.connect.{BlockedUserProfileFragment, ConnectRequestLoadMode, PendingConnectRequestFragment, SendConnectRequestFragment}
import com.waz.zclient.pages.main.conversation.controller.{ConversationScreenControllerObserver, IConversationScreenController}
import com.waz.zclient.pages.main.participants._
import com.waz.zclient.pages.main.participants.dialog.DialogLaunchMode
import com.waz.zclient.pages.main.pickuser.controller.{IPickUserController, PickUserControllerScreenObserver}
import com.waz.zclient.participants.{OptionsMenuFragment, ParticipantsController}
import com.waz.zclient.ui.animation.interpolators.penner.{Expo, Linear, Quart}
import com.waz.zclient.usersearch.PickUserFragment
import com.waz.zclient.utils.ContextUtils._
import com.waz.zclient.utils.{LayoutSpec, RichView, ViewUtils}
import com.waz.zclient.views.{DefaultPageTransitionAnimation, LoadingIndicatorView}
import com.waz.zclient.{FragmentHelper, OnBackPressedListener, R}

class ParticipantsFragment extends BaseFragment[ParticipantsFragment.Container] with FragmentHelper
  with ConversationScreenControllerObserver
  with OnBackPressedListener
  with PickUserControllerScreenObserver
  with ParticipantHeaderFragment.Container
  with ParticipantsBodyFragment.Container
  with TabbedParticipantBodyFragment.Container
  with SingleParticipantFragment.Container
  with SendConnectRequestFragment.Container
  with BlockedUserProfileFragment.Container
  with PendingConnectRequestFragment.Container
  with PickUserFragment.Container
  with SingleOtrClientFragment.Container {

  implicit def ctx: Context = getActivity
  import Threading.Implicits.Ui

  private lazy val bodyContainer = view[View](R.id.fl__participant__container)
  private lazy val participantsContainerView = view[View](R.id.ll__participant__container)
  private lazy val pickUserContainerView = view[View](R.id.fl__add_to_conversation__pickuser__container)

  private lazy val convChange = convController.convChanged.filter { _.to.isDefined }

  private lazy val loadingIndicatorView = returning( view[LoadingIndicatorView](R.id.liv__participants__loading_indicator) ) {
    _.foreach(_.setColor(getColorWithTheme(R.color.people_picker__loading__color, ctx)))
  }

  private lazy val conversationSettingsOverlayLayout = returning( view[View](R.id.fl__conversation_actions__sliding_overlay) ) {
    _.foreach(_.setVisible(false))
  }

  private lazy val convController         = inject[ConversationController]
  private lazy val userAccountsController = inject[UserAccountsController]
  private lazy val participantsController = inject[ParticipantsController]
  private lazy val screenController       = inject[IConversationScreenController]
  private lazy val pickUserController     = inject[IPickUserController]
  private lazy val singleImageController  = inject[ISingleImageController]
  private lazy val navigationController   = inject[INavigationController]

  private var userRequester: IConnectStore.UserRequester = _

  override def onCreateAnimation(transit: Int, enter: Boolean, nextAnim: Int): Animation =
    if (nextAnim == 0 || Option(getContainer).isEmpty || getControllerFactory.isTornDown || LayoutSpec.isTablet(getActivity))
      super.onCreateAnimation(transit, enter, nextAnim)
    else new DefaultPageTransitionAnimation(
      0,
      getDimenPx(R.dimen.open_new_conversation__thread_list__max_top_distance),
      enter,
      getInt(R.integer.framework_animation_duration_medium),
      if (enter) getInt(R.integer.framework_animation_duration_medium) else 0,
      1f
    )

  override def onCreate(savedInstanceState: Bundle): Unit = {
    super.onCreate(savedInstanceState)
    userRequester = getArguments.getSerializable(ParticipantsFragment.ARG_USER_REQUESTER).asInstanceOf[IConnectStore.UserRequester]
  }

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle): View =
    returning(inflater.inflate(R.layout.fragment_participant, container, false)) { _ =>
      val fragmentManager = getChildFragmentManager
      Option(fragmentManager.findFragmentById(R.id.fl__participant__overlay)).foreach {
        fragmentManager.beginTransaction.remove(_).commit
      }
    }

  override def onViewCreated(view: View, @Nullable savedInstanceState: Bundle): Unit = {
    val fragmentManager = getChildFragmentManager

    def showSingle() = fragmentManager.beginTransaction
      .replace(
        R.id.fl__participant__container,
        TabbedParticipantBodyFragment.newInstance(getArguments.getInt(ParticipantsFragment.ARG__FIRST__PAGE)),
        TabbedParticipantBodyFragment.TAG
      )
      .commit

    def showGroup() = {
      val fragment = ParticipantsBodyFragment.newInstance(userRequester)
      fragment.backgroundClicked.onUi { _ =>
        if(LayoutSpec.isTablet(getActivity)) participantsController.otherParticipant.head.foreach {
          case Some(userId) =>
            bodyContainer.foreach(singleImageController.setViewReferences)
            singleImageController.showSingleImage(getOldUserAPI(userId))
          case _ =>
        }
      }
      fragmentManager.beginTransaction.replace(R.id.fl__participant__container, fragment, ParticipantsBodyFragment.TAG).commit
    }

    if (Option(savedInstanceState).isEmpty) {
      fragmentManager.beginTransaction
        .replace(
          R.id.fl__participant__header__container,
          ParticipantHeaderFragment.newInstance(userRequester),
          ParticipantHeaderFragment.TAG
        )
        .commit

      participantsController.groupOrBot.head.foreach {
        case false                                                     => showSingle()
        case _ if userRequester == IConnectStore.UserRequester.POPOVER => showSingle()
        case _                                                         => showGroup()
      }

      fragmentManager.beginTransaction
        .replace(
          R.id.fl__participant__settings_box,
          OptionsMenuFragment.newInstance(false),
          OptionsMenuFragment.Tag
        )
        .commit
    }

    bodyContainer
    loadingIndicatorView
    participantsContainerView
    pickUserContainerView
    conversationSettingsOverlayLayout

    convChange.onUi {
      case ConversationChange(_, Some(convId), _) => onConversationLoaded(convId)
      case _ =>
    }

    convChange.onUi {
      _.requester match {
        case START_CONVERSATION | START_CONVERSATION_FOR_VIDEO_CALL | START_CONVERSATION_FOR_CALL | START_CONVERSATION_FOR_CAMERA =>
          getChildFragmentManager.popBackStackImmediate(PickUserFragment.TAG, FragmentManager.POP_BACK_STACK_INCLUSIVE)
          pickUserController.hidePickUserWithoutAnimations(getCurrentPickerDestination)
        case _ =>
      }
    }

    convController.currentConv.onUi { data => screenController.setMemberOfConversation(data.isActive) }
  }

  // TODO: I suspect it's ok to remove it after all is done
  private def onConversationLoaded(convId: ConvId): Unit = convController.loadConv(convId).foreach {
    case Some(data) =>
      screenController.setSingleConversation(data.convType == IConversation.Type.ONE_TO_ONE)
      screenController.setMemberOfConversation(data.isActive)
      val iConv = convController.iConv(convId)
      getStoreFactory.participantsStore.setCurrentConversation(iConv)
    case _ =>
  }

  override def onStart(): Unit = {
    super.onStart()

    if (userRequester == IConnectStore.UserRequester.POPOVER) {
      val user = getStoreFactory.singleParticipantStore.getUser
      getStoreFactory.connectStore.loadUser(user.getId, userRequester)
    } else onConversationLoaded(convController.getCurrentConvId)

    if (LayoutSpec.isPhone(getActivity)) { // ConversationScreenController is handled in ParticipantDialogFragment for tablets
      screenController.addConversationControllerObservers(this)
    }
    pickUserController.addPickUserScreenControllerObserver(this)
  }

  override def onStop(): Unit = {
    getStoreFactory.participantsStore.setCurrentConversation(null)
    if (LayoutSpec.isPhone(getActivity)) screenController.removeConversationControllerObservers(this)
    pickUserController.removePickUserScreenControllerObserver(this)
    super.onStop()
  }

  override def onDestroyView(): Unit = {
    singleImageController.clearReferences()

    super.onDestroyView()
  }

  override def dismissDialog(): Unit = getContainer.dismissDialog()

  override def onBackPressed: Boolean = withBackstackHead {
    case Some(f: PickUserFragment) if f.onBackPressed() => true
    case Some(f: SingleOtrClientFragment) =>
      screenController.hideOtrClient()
      true
    case Some(f: SingleParticipantFragment) if f.onBackPressed() => true
    case Some(f: OptionsMenuFragment) if f.close() => true
    case _ if pickUserController.isShowingPickUser(getCurrentPickerDestination) =>
      pickUserController.hidePickUser(getCurrentPickerDestination)
      true
    case _ if screenController.isShowingUser =>
      screenController.hideUser()
      true
    case _ if screenController.isShowingParticipant =>
      screenController.hideParticipants(true, false)
      true
    case _ => false
  }

  override def onShowParticipants(anchorView: View, isSingleConversation: Boolean, isMemberOfConversation: Boolean, showDeviceTabIfSingle: Boolean): Unit = {}

  override def onHideParticipants(backOrButtonPressed: Boolean, hideByConversationChange: Boolean, isSingleConversation: Boolean): Unit = {}

  override def onShowEditConversationName(show: Boolean): Unit =
    if (!LayoutSpec.isTablet(getActivity)) bodyContainer.foreach { view =>
      if (show) ViewUtils.fadeOutView(view)
      else ViewUtils.fadeInView(view)
    }

  override def onHeaderViewMeasured(participantHeaderHeight: Int): Unit = {}

  override def onScrollParticipantsList(verticalOffset: Int, scrolledToBottom: Boolean): Unit = {}

  override def onAddPeopleToConversation(): Unit =
    pickUserController.showPickUser(IPickUserController.Destination.PARTICIPANTS)

  override def onShowConversationMenu(inConvList: Boolean, convId: ConvId): Unit =
    if (!inConvList) getChildFragmentManager.findFragmentByTag(OptionsMenuFragment.Tag) match {
      case fragment: OptionsMenuFragment => fragment.open(convId)
      case _ =>
    }

  override def onShowOtrClient(otrClient: OtrClient, user: User): Unit =
    getChildFragmentManager
      .beginTransaction
      .setCustomAnimations(
        R.anim.open_profile,
        R.anim.close_profile,
        R.anim.open_profile,
        R.anim.close_profile
      )
      .add(
        R.id.fl__participant__overlay,
        SingleOtrClientFragment.newInstance(otrClient, user),
        SingleOtrClientFragment.TAG
      )
      .addToBackStack(SingleOtrClientFragment.TAG)
      .commit

  override def onShowCurrentOtrClient(): Unit =
    getChildFragmentManager
      .beginTransaction
      .setCustomAnimations(
        R.anim.open_profile,
        R.anim.close_profile,
        R.anim.open_profile,
        R.anim.close_profile
      )
      .add(
        R.id.fl__participant__overlay,
        SingleOtrClientFragment.newInstance,
        SingleOtrClientFragment.TAG
      )
      .addToBackStack(SingleOtrClientFragment.TAG)
      .commit

  override def onHideOtrClient(): Unit = getChildFragmentManager.popBackStackImmediate

  override def onShowLikesList(message: Message): Unit = {}

  override def onShowIntegrationDetails(providerId: ProviderId, integrationId: IntegrationId): Unit = {}

  override def onShowUser(userId: UserId): Unit = participantsController.getUser(userId).foreach {
    case None =>

    case Some(user) if user.isSelf =>
      getStoreFactory.singleParticipantStore.setUser(getOldUserAPI(userId))
      openUserProfileFragment(
        SingleParticipantFragment.newInstance(false, IConnectStore.UserRequester.PARTICIPANTS),
        SingleParticipantFragment.TAG
      )
      if (LayoutSpec.isPhone(getActivity))
        navigationController.setRightPage(Page.PARTICIPANT_USER_PROFILE, ParticipantsFragment.TAG)

    case Some(_) if userAccountsController.isTeamAccount && userAccountsController.isTeamMember(userId) =>
      showAcceptedUser(userId)

    case Some(user) if user.connection == User.ConnectionStatus.ACCEPTED =>
      showAcceptedUser(user.id)

    case Some(user) if user.connection == PENDING_FROM_OTHER || user.connection == PENDING_FROM_USER || user.connection == IGNORED =>
      openUserProfileFragment(
        PendingConnectRequestFragment.newInstance(userId.str, null, ConnectRequestLoadMode.LOAD_BY_USER_ID, IConnectStore.UserRequester.PARTICIPANTS),
        PendingConnectRequestFragment.TAG
      )
      if (LayoutSpec.isPhone(getActivity))
        navigationController.setRightPage(Page.PARTICIPANT_USER_PROFILE, ParticipantsFragment.TAG)

    case Some(user) if user.connection == BLOCKED =>
      openUserProfileFragment(
        BlockedUserProfileFragment.newInstance(userId.str, IConnectStore.UserRequester.PARTICIPANTS),
        BlockedUserProfileFragment.TAG
      )
      if (LayoutSpec.isPhone(getActivity))
        navigationController.setRightPage(Page.PARTICIPANT_USER_PROFILE, ParticipantsFragment.TAG)

    case Some(user) if user.connection == CANCELLED || user.connection == UNCONNECTED =>
      openUserProfileFragment(
        SendConnectRequestFragment.newInstance(userId.str, IConnectStore.UserRequester.PARTICIPANTS),
        SendConnectRequestFragment.TAG
      )
      navigationController.setRightPage(Page.SEND_CONNECT_REQUEST, ParticipantsFragment.TAG)
  }

  private def getOldUserAPI(userId: UserId): User = getStoreFactory.pickUserStore.getUser(userId.str)

  private def showAcceptedUser(userId: UserId) = {
    participantsController.selectParticipant(userId)
    getStoreFactory.singleParticipantStore.setUser(getOldUserAPI(userId))
    openUserProfileFragment(
      SingleParticipantFragment.newInstance(false, IConnectStore.UserRequester.PARTICIPANTS),
      SingleParticipantFragment.TAG
    )
    if (LayoutSpec.isPhone(getActivity))
      navigationController.setRightPage(Page.PARTICIPANT_USER_PROFILE, ParticipantsFragment.TAG)
  }

  private def openUserProfileFragment(fragment: Fragment, tag: String) = {
    val transaction = getChildFragmentManager.beginTransaction
    if (
      screenController.getPopoverLaunchMode != DialogLaunchMode.AVATAR &&
      screenController.getPopoverLaunchMode != DialogLaunchMode.COMMON_USER
    ) {
      animateParticipantsWithConnectUserProfile(false)
      transaction.setCustomAnimations(R.anim.open_profile, R.anim.close_profile, R.anim.open_profile, R.anim.close_profile)
    }
    else transaction.setCustomAnimations(R.anim.fade_in, R.anim.fade_out, R.anim.fade_in, R.anim.fade_out)
    transaction.add(R.id.fl__participant__overlay, fragment, tag).addToBackStack(tag).commit
  }

  private def animateParticipantsWithConnectUserProfile(show: Boolean) = {
    val animator = participantsContainerView.animate
    (show, LayoutSpec.isTablet(getActivity)) match {
      case (true, true) => animator.translationX(0)
        .setInterpolator(new Expo.EaseOut)
        .setDuration(getInt(R.integer.framework_animation_duration_long))
        .setStartDelay(0)
      case (true, false) => animator.alpha(1)
        .scaleY(1)
        .scaleX(1)
        .setInterpolator(new Expo.EaseOut)
        .setDuration(getInt(R.integer.reopen_profile_source__animation_duration))
        .setStartDelay(getInt(R.integer.reopen_profile_source__delay))
      case (false, true) => animator.translationX(-getDimenPx(R.dimen.participant_dialog__initial_width))
        .setInterpolator(new Expo.EaseOut)
        .setDuration(getInt(R.integer.framework_animation_duration_long))
        .setStartDelay(0)
      case (false, false) => animator.alpha(0)
        .scaleY(2)
        .scaleX(2)
        .setInterpolator(new Expo.EaseIn)
        .setDuration(getInt(R.integer.reopen_profile_source__animation_duration))
        .setStartDelay(0)
    }
    animator.start()
  }

  override def onHideUser(): Unit = if (screenController.isShowingUser) {
    getChildFragmentManager.popBackStackImmediate
    if (LayoutSpec.isPhone(getActivity)) {
      val rightPage =
        if (screenController.isShowingParticipant) Page.PARTICIPANT
        else Page.MESSAGE_STREAM
      getControllerFactory.getNavigationController.setRightPage(rightPage, ParticipantsFragment.TAG)
    }
    animateParticipantsWithConnectUserProfile(true)
  }

  override def showRemoveConfirmation(userId: UserId): Unit = { // Show confirmation dialog before removing user
    val callback = new TwoButtonConfirmationCallback() {
      override def positiveButtonClicked(checkboxIsSelected: Boolean): Unit = {
        dismissUserProfile()
        convController.removeMember(userId)
      }

      override def negativeButtonClicked(): Unit = {}
      override def onHideAnimationEnd(confirmed: Boolean, canceled: Boolean, checkboxIsSelected: Boolean): Unit = {}
    }

    participantsController.getUser(userId).foreach {
      case Some(userData) =>
        val request = new ConfirmationRequest.Builder()
          .withHeader(getString(R.string.confirmation_menu__header))
          .withMessage(getString(R.string.confirmation_menu_text_with_name, userData.getDisplayName))
          .withPositiveButton(getString(R.string.confirmation_menu__confirm_remove))
          .withNegativeButton(getString(R.string.confirmation_menu__cancel))
          .withConfirmationCallback(callback)
          .withWireTheme(inject[ThemeController].getThemeDependentOptionsTheme)
          .build
        getControllerFactory.getConfirmationController.requestConfirmation(request, IConfirmationController.PARTICIPANTS)
        inject[SoundController].playAlert()
      case _ =>
    }
  }

  override def onOpenUrl(url: String): Unit = getContainer.onOpenUrl(url)

  override def dismissUserProfile(): Unit = screenController.hideUser()

  override def dismissSingleUserProfile(): Unit = dismissUserProfile()

  override def onConversationUpdated(conversation: ConvId): Unit = {}

  override def onAcceptedConnectRequest(conversation: ConvId): Unit = {
    screenController.hideUser()
    verbose(s"onAcceptedConnectRequest $conversation")
    convController.selectConv(conversation, ConversationChangeRequester.START_CONVERSATION)
  }

  override def onUnblockedUser(restoredConversationWithUser: ConvId): Unit = {
    screenController.hideUser()
    verbose(s"onUnblockedUser $restoredConversationWithUser")
    convController.selectConv(restoredConversationWithUser, ConversationChangeRequester.START_CONVERSATION)
  }

  override def onConnectRequestWasSentToUser(): Unit = screenController.hideUser()

  override def showIncomingPendingConnectRequest(conv: ConvId): Unit = {}

  import java.util.List

  import scala.collection.JavaConverters._
  override def onSelectedUsers(userIds: List[UserId], requester: ConversationChangeRequester): Unit =
    participantsController.groupOrBot.head.foreach {
      case false =>
        pickUserController.hidePickUser(getCurrentPickerDestination)
        dismissDialog()
        convController.createGroupConversation(userIds.asScala, requester)
        if (!getStoreFactory.networkStore.hasInternetConnection)
          ViewUtils.showAlertDialog(
            getActivity,
            R.string.conversation__create_group_conversation__no_network__title,
            R.string.conversation__create_group_conversation__no_network__message,
            R.string.conversation__create_group_conversation__no_network__button,
            null,
            true
          )
      case true =>
        participantsController.addMembers(userIds.asScala.toSet)
        pickUserController.hidePickUser(getCurrentPickerDestination)
        if (!getStoreFactory.networkStore.hasInternetConnection)
          ViewUtils.showAlertDialog(
            getActivity,
            R.string.conversation__add_user__no_network__title,
            R.string.conversation__add_user__no_network__message,
            R.string.conversation__add_user__no_network__button,
            null,
            true
          )
    }

  override def getLoadingViewIndicator: LoadingIndicatorView = loadingIndicatorView

  override def getCurrentPickerDestination = IPickUserController.Destination.PARTICIPANTS

  override def onShowPickUser(destination: IPickUserController.Destination): Unit = if (LayoutSpec.isTablet(getActivity)) {
    if (getCurrentPickerDestination != destination) onHidePickUser(getCurrentPickerDestination)
    else participantsController.isGroup.head.foreach { groupConversation =>
      getChildFragmentManager.beginTransaction
        .setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
        .add(
          R.id.fl__add_to_conversation__pickuser__container,
          PickUserFragment.newInstance(true, convController.getCurrentConvId.str),
          PickUserFragment.TAG
        )
        .addToBackStack(PickUserFragment.TAG)
        .commit

      participantsContainerView.foreach { view =>
        val hideParticipantsAnimator = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f)
        hideParticipantsAnimator.setInterpolator(new Quart.EaseOut)
        hideParticipantsAnimator.setDuration(getInt(R.integer.framework_animation_duration_medium))
        hideParticipantsAnimator.addListener(new AnimatorListenerAdapter() {
          override def onAnimationEnd(animation: Animator): Unit =
            participantsContainerView.foreach(_.setVisible(false))
        })
        hideParticipantsAnimator.start()
      }
    }
  }

  override def onHidePickUser(destination: IPickUserController.Destination): Unit =
    if (LayoutSpec.isTablet(getActivity) && destination == getCurrentPickerDestination) {
      // Workaround for animation bug with nested child fragments
      // Animating fragment container views and then popping stack at end of animation
      participantsContainerView.foreach { view =>

        val hidePickUserAnimator = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f)
        hidePickUserAnimator.setInterpolator(new Linear.EaseIn)
        hidePickUserAnimator.addListener(new AnimatorListenerAdapter() {
          override def onAnimationEnd(animation: Animator): Unit = if (isResumed) {
            getChildFragmentManager.popBackStackImmediate(PickUserFragment.TAG, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            pickUserContainerView.foreach(_.setAlpha(1f))
          }
        })

        view.setAlpha(0)
        view.setVisible(true)

        val showParticipantsAnimator = ObjectAnimator.ofFloat(participantsContainerView.get, View.ALPHA, 0f, 1f)
        showParticipantsAnimator.setInterpolator(new Quart.EaseOut)
        showParticipantsAnimator.setDuration(getInt(R.integer.framework_animation_duration_medium))
        showParticipantsAnimator.setStartDelay(getInt(R.integer.framework_animation_delay_long))

        val hideSet = new AnimatorSet
        hideSet.playTogether(hidePickUserAnimator, showParticipantsAnimator)
        hideSet.start()

      }
  }

  override def onShowUserProfile(userId: UserId, anchorView: View): Unit = {}

  override def onHideUserProfile(): Unit = {}
}

object ParticipantsFragment {
  val TAG: String = classOf[ParticipantsFragment].getName
  private val ARG_USER_REQUESTER = "ARG_USER_REQUESTER"
  private val ARG__FIRST__PAGE = "ARG__FIRST__PAGE"

  def newInstance(userRequester: IConnectStore.UserRequester, firstPage: Int): ParticipantsFragment =
    returning(new ParticipantsFragment) {
      _.setArguments(returning(new Bundle) { args =>
        args.putSerializable(ARG_USER_REQUESTER, userRequester)
        args.putInt(ARG__FIRST__PAGE, firstPage)
      })
    }

  trait Container {
    def onOpenUrl(url: String): Unit

    def dismissDialog(): Unit
  }

}
