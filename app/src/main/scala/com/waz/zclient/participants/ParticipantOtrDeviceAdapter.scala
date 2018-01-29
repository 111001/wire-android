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
package com.waz.zclient.participants

import java.util.Locale

import android.content.Context
import android.support.v7.widget.RecyclerView
import android.view.{LayoutInflater, View, ViewGroup}
import android.widget.{ImageView, TextView}
import com.waz.api.{OtrClientType, Verification}
import com.waz.model.otr.Client
import com.waz.service.ZMessaging
import com.waz.utils.events.{EventContext, EventStream, Signal, SourceStream}
import com.waz.zclient.common.controllers.global.AccentColorController
import com.waz.zclient.ui.utils.TextViewUtils
import com.waz.zclient.utils.ViewUtils
import com.waz.zclient.{Injectable, Injector, R}

class ParticipantOtrDeviceAdapter(implicit context: Context, injector: Injector, eventContext: EventContext)
  extends RecyclerView.Adapter[ParticipantOtrDeviceAdapter.ViewHolder] with Injectable {
  import ParticipantOtrDeviceAdapter._

  private lazy val zms = inject[Signal[ZMessaging]]
  private lazy val participantsController = inject[ParticipantsController]
  private lazy val accentColorController = inject[AccentColorController]

  private var devices = List.empty[Client]
  private var userName = ""
  private var accentColor = 0

  val onHeaderClick = EventStream[Unit]()
  val onClientClick = EventStream[Client]()

  private lazy val clients = for {
    Some(userId)  <- participantsController.otherParticipant
    Some(manager) <- ZMessaging.currentAccounts.activeAccountManager
    clients       <- manager.storage.otrClientsStorage.optSignal(userId)
  } yield clients.fold(List.empty[Client])(_.clients.values.toList.sortBy(_.regTime).reverse)

  (for {
    z            <- zms
    cs           <- clients
    Some(userId) <- participantsController.otherParticipant
    user         <- z.users.userSignal(userId)
    color        <- accentColorController.accentColor
  } yield (cs, user.name, color)).onUi { case (cs, name, color) =>
    devices = cs
    userName = name
    accentColor = color.getColor
    notifyDataSetChanged()
  }

  override def onCreateViewHolder(parent: ViewGroup, viewType: Int): ParticipantOtrDeviceAdapter.ViewHolder =
    viewType match {
      case VIEW_TYPE_HEADER =>
        val view = LayoutInflater.from(parent.getContext).inflate(R.layout.row_participant_otr_header, parent, false)
        new ParticipantOtrDeviceAdapter.OtrHeaderViewHolder(view, onHeaderClick)
      case VIEW_TYPE_OTR_CLIENT =>
        val view = LayoutInflater.from(parent.getContext).inflate(R.layout.row_participant_otr_device, parent, false)
        new ParticipantOtrDeviceAdapter.OtrClientViewHolder(view, onClientClick)
    }

  override def onBindViewHolder(holder: ParticipantOtrDeviceAdapter.ViewHolder, position: Int): Unit = holder match {
    case h: OtrHeaderViewHolder => h.bind(userName, accentColor, position == getItemCount - 1)
    case h: OtrClientViewHolder => h.bind(devices(position - 1), userName, accentColor, position == getItemCount - 1)
    case _                      =>
  }

  override def getItemViewType(position: Int): Int = position match {
    case 0 => VIEW_TYPE_HEADER
    case _ => VIEW_TYPE_OTR_CLIENT
  }

  override def getItemCount: Int = if (devices.isEmpty) 1 else devices.size + 1
}

object ParticipantOtrDeviceAdapter {
  private val VIEW_TYPE_HEADER = 0
  private val VIEW_TYPE_OTR_CLIENT = 1
  private val OTR_CLIENT_TEXT_TEMPLATE = "[[%s]]\n%s"

  def deviceClassName(client: Client)(implicit ctx: Context): String = ctx.getString(
    client.devType match {
      case OtrClientType.DESKTOP => R.string.otr__participant__device_class__desktop
      case OtrClientType.PHONE   => R.string.otr__participant__device_class__phone
      case OtrClientType.TABLET  => R.string.otr__participant__device_class__tablet
      case _                     => R.string.otr__participant__device_class__unknown
    }
  )

  // TODO: This is the same code as in DevicesView and OtrClients. Consider putting it in one place.
  def displayId(client: Client): String =
    f"${client.id.str.toUpperCase(Locale.ENGLISH)}%16s" replace (' ', '0') grouped 4 map { group =>
      val (bold, normal) = group.splitAt(2)
      s"[[$bold]] $normal"
    } mkString " "

  abstract class ViewHolder(itemView: View) extends RecyclerView.ViewHolder(itemView) with View.OnClickListener

  class OtrHeaderViewHolder(view: View, onClick: SourceStream[Unit]) extends ParticipantOtrDeviceAdapter.ViewHolder(view) {

    def bind(name: String, accentColor: Int, lastItem: Boolean): Unit = {
      val headerTextView = ViewUtils.getView[TextView](view, R.id.ttv__row__otr_header)
      val linkTextView = ViewUtils.getView[TextView](view, R.id.ttv__row__otr_details_link)

      if (lastItem) {
        headerTextView.setText(view.getContext.getString(R.string.otr__participant__device_header__no_devices, name))
        linkTextView.setVisibility(View.GONE)
        ViewUtils.setPaddingTop(linkTextView, linkTextView.getContext.getResources.getDimensionPixelSize(R.dimen.wire__padding__small))
        linkTextView.setOnClickListener(null)
      } else {
        headerTextView.setText(headerTextView.getContext.getString(R.string.otr__participant__device_header, name))
        linkTextView.setText(TextViewUtils.getHighlightText(linkTextView.getContext, linkTextView.getContext.getString(R.string.otr__participant__device_header__link_text), accentColor, false))
        linkTextView.setVisibility(View.VISIBLE)
        ViewUtils.setPaddingTop(linkTextView, 0)
        linkTextView.setOnClickListener(this)
      }
    }

    override def onClick(v: View): Unit = this.onClick ! Unit
  }

  class OtrClientViewHolder(view: View, val onClick: SourceStream[Client]) extends ParticipantOtrDeviceAdapter.ViewHolder(view) {

    private var client: Option[Client] = None

    def bind(client: Client, name: String, accentColor: Int, lastItem: Boolean): Unit = {
      this.client = Some(client)

      val textView = ViewUtils.getView[TextView](view, R.id.ttv__row_otr_device)

      val clientText = String.format(
        OTR_CLIENT_TEXT_TEMPLATE,
        deviceClassName(client)(textView.getContext),
        textView.getContext.getString(R.string.pref_devices_device_id, displayId(client))
      ).toUpperCase(Locale.getDefault)

      textView.setText(TextViewUtils.getBoldText(textView.getContext, clientText))

      ViewUtils.getView[ImageView](itemView, R.id.iv__row_otr_icon).setImageResource(
        if (client.verified == Verification.VERIFIED) R.drawable.shield_full
        else R.drawable.shield_half
      )

      ViewUtils.getView[View](itemView, R.id.v__row_otr__divider).setVisibility(if (lastItem) View.GONE else View.VISIBLE)

      itemView.setOnClickListener(this)
    }

    override def onClick(v: View): Unit = client.foreach { this.onClick ! _ }
  }
}
