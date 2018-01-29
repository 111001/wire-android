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
package com.waz.zclient.conversation

import android.content.Context
import android.support.annotation.StringRes
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import com.waz.api.User
import com.waz.model.UserId
import com.waz.service.ZMessaging
import com.waz.threading.Threading
import com.waz.utils.events.Signal
import com.waz.zclient.messages.UsersController
import com.waz.zclient.ui.text.TypefaceTextView
import com.waz.zclient.utils.UiStorage
import com.waz.zclient.views.ShowAvailabilityView
import com.waz.zclient.views.images.ImageAssetImageView
import com.waz.zclient.views.menus.{FooterMenu, FooterMenuCallback}
import com.waz.zclient.{R, ViewHelper}

class ParticipantDetailsTab(val context: Context, val attrs: AttributeSet, val defStyleAttr: Int) extends LinearLayout(context, attrs, defStyleAttr) with ViewHelper {
  def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)
  def this(context: Context) = this(context, null)

  inflate(R.layout.single_participant_tab_details)

  private implicit val uiStorage = inject[UiStorage]
  private lazy val usersController = inject[UsersController]

  private val imageAssetImageView = findById[ImageAssetImageView ](R.id.iaiv__single_participant)
  private val footerMenu = findById[FooterMenu](R.id.fm__footer)
  private val guestIndicationText = findById[TypefaceTextView](R.id.participant_guest_indicator)
  private lazy val userAvailability = findById[ShowAvailabilityView](R.id.participant_availability)

  imageAssetImageView.setDisplayType(ImageAssetImageView.DisplayType.CIRCLE)
  setOrientation(LinearLayout.VERTICAL)

  private val userId = Signal[UserId]()

  private val isGuest = for{
    z       <- inject[Signal[ZMessaging]]
    uId     <- userId
    data    <- z.users.userSignal(uId)
    isGuest <- if (data.isWireBot) Signal.const(false) else z.teams.isGuest(uId)
  } yield isGuest

  isGuest.on(Threading.Ui) {
    case true =>
      guestIndicationText.setVisibility(View.VISIBLE)
      guestIndicationText.setText(getResources.getString(R.string.participant_tab_guest_indicator_label))
    case _ =>
      guestIndicationText.setVisibility(View.GONE)
      guestIndicationText.setText("")
  }

  private val avStatus = usersController.availabilityVisible.zip(userId.flatMap(usersController.availability))

  avStatus.on(Threading.Ui) {
    case (false, _) => userAvailability.setVisibility(View.GONE)
    case (true, av) =>
      userAvailability.setVisibility(View.VISIBLE)
      userAvailability.set(av)
  }

  def setUser(user: User): Unit = {
    Option(user).fold {
      imageAssetImageView.resetBackground()
    }{ user =>
      imageAssetImageView.connectImageAsset(user.getPicture)
    }
    userId ! UserId(user.getId)
  }

  def updateFooterMenu(@StringRes leftAction: Int, @StringRes leftActionLabel: Int, @StringRes rightAction: Int, @StringRes rightActionLabel: Int, callback: FooterMenuCallback): Unit =
    Option(footerMenu).foreach { v =>
      v.setLeftActionText(getContext.getString(leftAction))
      v.setLeftActionLabelText(getContext.getString(leftActionLabel))
      v.setRightActionText(getContext.getString(rightAction))
      v.setRightActionLabelText(getContext.getString(rightActionLabel))
      v.setCallback(callback)
    }

}


