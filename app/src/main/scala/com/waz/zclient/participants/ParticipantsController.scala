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
import android.view.View
import com.waz.model._
import com.waz.service.ZMessaging
import com.waz.utils.events.{EventContext, EventStream, Signal}
import com.waz.zclient.conversation.ConversationController
import com.waz.zclient.utils.{ConversationMembersSignal, UiStorage}
import com.waz.zclient.{Injectable, Injector}
import com.waz.ZLog.ImplicitTag._

import scala.concurrent.Future

class ParticipantsController(implicit injector: Injector, context: Context, ec: EventContext) extends Injectable {

  import com.waz.threading.Threading.Implicits.Background

  implicit private lazy val uiStorage = inject[UiStorage]

  private lazy val zms = inject[Signal[ZMessaging]]
  private lazy val convController = inject[ConversationController]

  private val selectedParticipant = Signal[Option[UserId]](None)

  val otherParticipants = for {
    z         <- zms
    convId    <- convController.currentConvId
    memberIds <- ConversationMembersSignal(convId)
  } yield memberIds.filterNot(_ == z.selfUserId)

  otherParticipants.onChanged { others =>
    selectedParticipant.head.foreach {
      case Some(userId) if !others.contains(userId) => unselectParticipant()
      case _                                        =>
    }
  }

  lazy val otherParticipant = (for {
    others   <- otherParticipants
    selected <- selectedParticipant
  } yield (others, selected)).map {
    case (others, Some(userId))             => others.find(_ == userId)
    case (others, None) if others.size == 1 => others.headOption
    case _                                  => None
  }

  lazy val isGroup = convController.currentConvIsGroup

  lazy val isWithBot = for {
    z       <- zms
    others  <- otherParticipants
    withBot <- Signal.sequence(others.map(id => z.users.userSignal(id).map(_.isWireBot)).toSeq: _*)
  } yield withBot.contains(true)

  lazy val groupOrBot = for {
    group      <- isGroup
    groupOrBot <- if (group) Signal.const(true) else isWithBot
  } yield groupOrBot

  lazy val conv = convController.currentConv

  val showParticipantsRequest = EventStream[(View, Boolean)]()

  def selectParticipant(userId: UserId): Future[Unit] = otherParticipants.head.map {
    case others if others.contains(userId) => selectedParticipant ! Some(userId)
    case _                                 =>
  }

  def unselectParticipant(): Unit = selectedParticipant ! None

  def getUser(userId: UserId): Future[Option[UserData]] = zms.head.flatMap(_.users.getUser(userId))

  def addMembers(userIds: Set[UserId]): Future[Unit] =
    convController.currentConvId.head.flatMap { convId => convController.addMembers(convId, userIds) }

  def blockUser(userId: UserId): Future[Option[UserData]] = zms.head.flatMap(_.connection.blockConnection(userId))

  def unblockUser(userId: UserId): Future[ConversationData] = zms.head.flatMap(_.connection.unblockConnection(userId))
}
