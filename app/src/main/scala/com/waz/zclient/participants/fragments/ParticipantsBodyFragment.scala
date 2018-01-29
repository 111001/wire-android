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

import android.content.Context
import android.os.Bundle
import android.support.annotation.Nullable
import android.support.v7.widget.{GridLayoutManager, RecyclerView}
import android.view.animation.{AlphaAnimation, Animation}
import android.view.{LayoutInflater, View, ViewGroup}
import android.widget.LinearLayout
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog.verbose
import com.waz.api.{IConversation, NetworkMode, User}
import com.waz.model.{ConvId, ConversationData, UserId}
import com.waz.threading.Threading
import com.waz.utils._
import com.waz.utils.events.{EventStream, SourceStream}
import com.waz.zclient.common.controllers.{SoundController, ThemeController, UserAccountsController}
import com.waz.zclient.controllers.confirmation.{ConfirmationRequest, IConfirmationController, TwoButtonConfirmationCallback}
import com.waz.zclient.conversation.ConversationController
import com.waz.zclient.core.stores.connect.{ConnectStoreObserver, IConnectStore}
import com.waz.zclient.core.stores.conversation.ConversationChangeRequester
import com.waz.zclient.core.stores.network.NetworkAction
import com.waz.zclient.integrations.IntegrationDetailsController
import com.waz.zclient.pages.BaseFragment
import com.waz.zclient.pages.main.conversation.controller.IConversationScreenController
import com.waz.zclient.pages.main.pickuser.controller.IPickUserController
import com.waz.zclient.participants.{ParticipantsChatheadAdapter, ParticipantsController}
import com.waz.zclient.utils.ContextUtils._
import com.waz.zclient.utils.{LayoutSpec, RichView, ViewUtils}
import com.waz.zclient.views.images.ImageAssetImageView
import com.waz.zclient.views.menus.{FooterMenu, FooterMenuCallback}
import com.waz.zclient.{FragmentHelper, R}

import scala.concurrent.Future

class ParticipantsBodyFragment extends BaseFragment[ParticipantsBodyFragment.Container] with FragmentHelper
  with ConnectStoreObserver {

  implicit def ctx: Context = getActivity
  import Threading.Implicits.Ui

  private var userRequester: IConnectStore.UserRequester = _

  private lazy val convController = inject[ConversationController]
  private lazy val participantsController = inject[ParticipantsController]
  private lazy val convScreenController = inject[IConversationScreenController]
  private lazy val userAccountsController = inject[UserAccountsController]
  private lazy val pickUserController = inject[IPickUserController]
  private lazy val themeController = inject[ThemeController]
  private lazy val confirmationController = inject[IConfirmationController]
  private lazy val integrationDetailsController = inject[IntegrationDetailsController]

  private lazy val participantsView = returning(view[RecyclerView](R.id.pgv__participants)) { view =>
    val layoutManager =
      returning(new GridLayoutManager(getContext, getInt(R.integer.participant_column__count))) {
        _.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
          override def getSpanSize(position: Int): Int = participantsAdapter.getSpanSize(position)
        })
      }

    view.foreach { v =>
      v.setAdapter(participantsAdapter)
      v.setLayoutManager(layoutManager)
    }
  }

  private lazy val participantsAdapter =
    returning(new ParticipantsChatheadAdapter(getInt(R.integer.participant_column__count))) {
      _.onClick.onUi { userId =>
        participantsController.getUser(userId).flatMap {
          case Some(user) => (user.providerId, user.integrationId) match {
            case (Some(pId), Some(iId)) => convController.currentConv.head.map { conv =>
              integrationDetailsController.setRemoving(conv.id, userId)
              convScreenController.showIntegrationDetails(pId, iId)
            }
            case _ =>
              verbose(s"onClick: ${user.displayName}")
              convScreenController.showUser(userId)
              participantsController.selectParticipant(userId)
          }
          case _ => Future.successful(())
        }
      }
    }

  private lazy val footerMenu = view[FooterMenu](R.id.fm__participants__footer)

  private lazy val topBorder = view[View](R.id.v_participants__footer__top_border)
  private lazy val footerWrapper = view[LinearLayout](R.id.ll__participants__footer_wrapper)

  private lazy val imageAssetImageView = returning(view[ImageAssetImageView](R.id.iaiv__participant_body)) {
    _.foreach(_.setDisplayType(ImageAssetImageView.DisplayType.CIRCLE))
  }

  override def onCreateAnimation(transit: Int, enter: Boolean, nextAnim: Int): Animation = {
    val parent = getParentFragment
    // Apply the workaround only if this is a child fragment, and the parent is being removed.
    if (!enter && parent != null && parent.isRemoving) {
      // This is a workaround for the bug where child fragments disappear when
      // the parent is removed (as all children are first removed from the parent)
      // See https://code.google.com/p/android/issues/detail?id=55228
      returning( new AlphaAnimation(1, 1) ) {
        _.setDuration(ViewUtils.getNextAnimationDuration(parent))
      }
    } else super.onCreateAnimation(transit, enter, nextAnim)
  }

  override def onCreate(savedInstanceState: Bundle): Unit = {
    super.onCreate(savedInstanceState)
    userRequester = getArguments.getSerializable(ParticipantsBodyFragment.ARG_USER_REQUESTER).asInstanceOf[IConnectStore.UserRequester]
  }

  override def onCreateView(inflater: LayoutInflater, viewGroup: ViewGroup, savedInstanceState: Bundle): View =
    returning(inflater.inflate(R.layout.fragment_group_participant, viewGroup, false)) {
      // Toggle color background
      _.onClick(backgroundClicked ! Unit)
    }

  val backgroundClicked: SourceStream[Unit] = EventStream()

  override def onViewCreated(view: View, @Nullable savedInstanceState: Bundle): Unit = {
    super.onViewCreated(view, savedInstanceState)

    participantsView
    topBorder
    footerWrapper
    imageAssetImageView

    participantsController.conv.onUi { conversationUpdated }
  }

  override def onStart(): Unit = {
    super.onStart()
    if (userRequester == IConnectStore.UserRequester.POPOVER) {
      getStoreFactory.connectStore.addConnectRequestObserver(this)
      getStoreFactory.connectStore.loadUser(
        getStoreFactory.singleParticipantStore.getUser.getId, userRequester
      )
    }

  }

  override def onStop(): Unit = {
    getStoreFactory.connectStore.removeConnectRequestObserver(this)
    super.onStop()
  }

  private def getOldUserAPI(userId: UserId): User = getStoreFactory.pickUserStore.getUser(userId.str)

  private def conversationUpdated(conv: ConversationData): Unit = {
    verbose(s"conversationUpdated: ${conv.name}, showing the footer menu")
    footerMenu.foreach(_.setVisible(true))
    if (conv.convType == IConversation.Type.ONE_TO_ONE) {
      footerMenu.foreach(_.setLeftActionText(getString(R.string.glyph__plus)))
      participantsController.otherParticipant.head.foreach {
        case Some(userId) => getStoreFactory.singleParticipantStore.setUser(getOldUserAPI(userId))
        case _            => getStoreFactory.singleParticipantStore.setUser(null)
      }

    } else {
      imageAssetImageView.foreach(_.setVisibility(View.GONE))

      // Check if self user is member for group conversation and has permission to add
      val displayAddButton = conv.isActive &&
        userAccountsController.hasAddConversationMemberPermission(conv.id)
      footerMenu.foreach { fm =>
        fm.setLeftActionText(if (displayAddButton) getString(R.string.glyph__add_people) else "")
        fm.setLeftActionLabelText(if (displayAddButton) getString(R.string.conversation__action__add_people) else "")
      }
    }

    footerMenu.foreach(_.setRightActionText(getString(R.string.glyph__more)))

    footerMenu.foreach(_.setCallback(new FooterMenuCallback() {
      override def onLeftActionClicked(): Unit = {
        if (userRequester == IConnectStore.UserRequester.POPOVER) {
          val user = getStoreFactory.singleParticipantStore.getUser
          if (user.isMe) {
            convScreenController.hideParticipants(true, false)
            // Go to conversation with this user
            pickUserController.hidePickUserWithoutAnimations(getContainer.getCurrentPickerDestination)
            convController.selectConv(new ConvId(user.getConversation.getId), ConversationChangeRequester.CONVERSATION_LIST)
            return
          }
        }

        if (conv.isActive && userAccountsController.hasAddConversationMemberPermission(conv.id))
          convScreenController.addPeopleToConversation()
      }

      override def onRightActionClicked(): Unit = getStoreFactory.networkStore.doIfHasInternetOrNotifyUser(new NetworkAction() {
        override def execute(networkMode: NetworkMode): Unit =
          if (conv.isActive && userRequester != IConnectStore.UserRequester.POPOVER)
            convScreenController.showConversationMenu(false, conv.id)

        override def onNoNetwork(): Unit = ViewUtils.showAlertDialog(getActivity,
          R.string.alert_dialog__no_network__header, R.string.leave_conversation_failed__message,
          R.string.alert_dialog__confirmation, null, true
        )
      })

    }))
  }

  private def connectImageAsset(user: User): Unit = imageAssetImageView.foreach { view =>
    view.setVisible(true)
    view.connectImageAsset(user.getPicture)
  }

  override def onConnectUserUpdated(user: User, userType: IConnectStore.UserRequester): Unit =
    if (userType == userRequester && Option(user).isDefined) {
      verbose(s"onConnectUserUpdated: ${user.getName}, showing the footer menu")
      connectImageAsset(user)
      footerMenu.foreach(_.setVisible(true))

      participantsController.groupOrBot.head.foreach { groupOrBot =>
        footerMenu.foreach { menu =>
          menu.setLeftActionText(getString(
            if (user.isMe) R.string.glyph__people
            else if (!groupOrBot) R.string.glyph__add_people
            else R.string.glyph__conversation
          ))

          menu.setLeftActionLabelText(getString(
            if (user.isMe) R.string.popover__action__profile
            else if (!groupOrBot) R.string.conversation__action__create_group
            else R.string.popover__action__open
          ))

          menu.setRightActionText(
            if (groupOrBot) getString(R.string.glyph__minus)
            else if (user.isMe) ""
            else getString(R.string.glyph__block)
          )

          menu.setRightActionLabelText(
            if (user.isMe) ""
            else if (!groupOrBot) getString(R.string.popover__action__block)
            else getString(R.string.popover__action__remove)
          )

          menu.setCallback(new FooterMenuCallback() {
            override def onLeftActionClicked(): Unit =
              if (user.isMe || groupOrBot) {
                convScreenController.hideParticipants(true, false)
                pickUserController.hidePickUserWithoutAnimations(getContainer.getCurrentPickerDestination)
                convController.selectConv(
                  new ConvId(user.getConversation.getId),
                  ConversationChangeRequester.CONVERSATION_LIST
                )
              } else convScreenController.addPeopleToConversation()

            override def onRightActionClicked(): Unit = if (!groupOrBot && user.isMe)
              getStoreFactory.networkStore.doIfHasInternetOrNotifyUser(new NetworkAction() {
                override def execute(networkMode: NetworkMode): Unit =
                  if (user.isMe) convController.currentConvId.head.foreach { showLeaveConfirmation }
                  else getContainer.showRemoveConfirmation(UserId(user.getId))

                override def onNoNetwork(): Unit = ViewUtils.showAlertDialog(
                  getActivity,
                  R.string.alert_dialog__no_network__header,
                  if (user.isMe) R.string.leave_conversation_failed__message
                  else R.string.remove_from_conversation__no_network__message,
                  R.string.alert_dialog__confirmation, null, true
                )
              })
          })
        }

      }
    }

  override def onInviteRequestSent(conversation: IConversation): Unit = {}

  private def showLeaveConfirmation(convId: ConvId) = {
    val callback = new TwoButtonConfirmationCallback() {
      override def positiveButtonClicked(checkboxIsSelected: Boolean): Unit =
        if (
          Option(getStoreFactory).isDefined && Option(getControllerFactory).isDefined &&
          !getStoreFactory.isTornDown && !getControllerFactory.isTornDown
        ) {
          convController.leave(convId)
          if (LayoutSpec.isTablet(getActivity)) convScreenController.hideParticipants(false, true)
        }

      override def negativeButtonClicked(): Unit = {}

      override def onHideAnimationEnd(confirmed: Boolean, canceled: Boolean, checkboxIsSelected: Boolean): Unit = {}
    }

    val request = new ConfirmationRequest.Builder()
      .withHeader(getString(R.string.confirmation_menu__meta_remove))
      .withMessage(getString(R.string.confirmation_menu__meta_remove_text))
      .withPositiveButton(getString(R.string.confirmation_menu__confirm_leave))
      .withNegativeButton(getString(R.string.confirmation_menu__cancel))
      .withConfirmationCallback(callback)
      .withCheckboxLabel(getString(R.string.confirmation_menu__delete_conversation__checkbox__label))
      .withWireTheme(themeController.getThemeDependentOptionsTheme)
      .withCheckboxSelectedByDefault
      .build
    confirmationController.requestConfirmation(request, IConfirmationController.PARTICIPANTS)

    val ctrl = inject[SoundController]
    if (Option(ctrl).isDefined) ctrl.playAlert()
  }

}

object ParticipantsBodyFragment {
  val TAG: String = classOf[ParticipantsBodyFragment].getName
  private val ARG_USER_REQUESTER = "ARG_USER_REQUESTER"

  def newInstance(userRequester: IConnectStore.UserRequester): ParticipantsBodyFragment =
    returning(new ParticipantsBodyFragment) {
      _.setArguments(returning(new Bundle){
        _.putSerializable(ARG_USER_REQUESTER, userRequester)
      })
    }

  trait Container {

    def showRemoveConfirmation(userId: UserId): Unit

    def getCurrentPickerDestination: IPickUserController.Destination
  }

}
