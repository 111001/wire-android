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

import android.content.Context
import android.support.v4.view.PagerAdapter
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.View
import android.view.ViewGroup
import com.waz.utils.events.EventContext
import com.waz.utils.returning
import com.waz.zclient.{Injectable, Injector, R}
import com.waz.zclient.utils.ViewUtils
import com.waz.zclient.conversation.ParticipantDetailsTab

class TabbedParticipantPagerAdapter(participantOtrDeviceAdapter: ParticipantOtrDeviceAdapter)(implicit context: Context, injector: Injector, eventContext: EventContext)
  extends PagerAdapter with Injectable {
  import TabbedParticipantPagerAdapter._

  override def instantiateItem(container: ViewGroup, position: Int): java.lang.Object = returning(
    tabs(position) match {
      case tab@ParticipantTab('details, label) => returning( new ParticipantDetailsTab(context) ) {
        _.setTag(tab)
      }
      case ParticipantTab('devices, label) => returning( new RecyclerView(context) ) { rv =>
        rv.setLayoutManager(new LinearLayoutManager(context))
        rv.setHasFixedSize(true)
        rv.setAdapter(participantOtrDeviceAdapter)
        ViewUtils.setPaddingBottom(rv, context.getResources.getDimensionPixelSize(R.dimen.participants__otr_device__padding_bottom))
        rv.setClipToPadding(false)
      }
      case _ => throw new RuntimeException("Unexpected ViewPager position")
    }
  ) { view => container.addView(view) }


  override def destroyItem(container: ViewGroup, position: Int, view: Any): Unit =
    container.removeView(view.asInstanceOf[View])

  override def getCount: Int = tabs.length

  override def isViewFromObject(view: View, obj: Any): Boolean = view == obj

  override def getPageTitle(position: Int): CharSequence = context.getString(tabs(position).label)
}

object TabbedParticipantPagerAdapter {
  case class ParticipantTab(tag: Symbol, label: Int)

  val detailsTab = ParticipantTab('details, R.string.otr__participant__tab_details)
  val devicesTab = ParticipantTab('devices, R.string.otr__participant__tab_devices)

  val tabs = List(detailsTab, devicesTab)
}
