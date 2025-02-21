package org.interscity.htc
package model.supermarket.actor

import core.actor.BaseActor

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.actor.Identify
import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import org.interscity.htc.core.entity.event.data.BaseEventData
import org.interscity.htc.model.supermarket.entity.enumeration.CashierStatusEnum.{ Busy, Free, Waiting }
import org.interscity.htc.model.supermarket.entity.event.data.{ FinishClientServiceData, NewClientServiceData, StartClientServiceData }
import org.interscity.htc.model.supermarket.entity.model.ClientQueued
import org.interscity.htc.model.supermarket.entity.state.CashierState
import org.interscity.htc.model.supermarket.util.CashierUtil
import org.interscity.htc.model.supermarket.util.CashierUtil.serviceTime

import scala.collection.mutable

class Cashier(
  override protected val actorId: String,
  private val timeManager: ActorRef,
  private val data: Any,
  override protected val dependencies: mutable.Map[String, ActorRef] =
    mutable.Map[String, ActorRef]()
) extends BaseActor[CashierState](
      actorId = actorId,
      timeManager = timeManager,
      data = data,
      dependencies = dependencies
    ) {

  override def actSpontaneous(event: SpontaneousEvent): Unit =
    state.status match {
      case Free =>
        if (state.queue.nonEmpty) {
          val queued = state.queue.dequeue()
          state.clientInService = Some(queued.client)
          sendMessageTo(queued.client.id, queued.client.actorRef, StartClientServiceData())
        } else {
          state.status = Waiting
        }
      case Busy =>
        sendMessageTo(
          state.clientInService.get.id,
          state.clientInService.get.actorRef,
          FinishClientServiceData()
        )
        state.clientInService = None
        state.status = Free
        onFinishSpontaneous(Some(currentTick + CashierUtil.breakTime))
      case _ =>
        logEvent(s"Event current status not handled ${state.status}")
    }

  override def actInteractWith[D <: BaseEventData](event: ActorInteractionEvent[D]): Unit =
    event match {
      case e: ActorInteractionEvent[NewClientServiceData] => handleNewClientService(e)
      case _ =>
        logEvent(s"Event not handled ${event}")
    }

  private def handleNewClientService(event: ActorInteractionEvent[NewClientServiceData]): Unit =
    if (state.queue.isEmpty && (state.status == Waiting || state.status == Free)) {
      state.status = Busy
      sendMessageTo(event.actorRefId, event.actorRef, StartClientServiceData())
      onFinishSpontaneous(Some(currentTick + serviceTime(event.data.amountThings)))
    } else {
      state.queue.enqueue(
        ClientQueued(
          client = Identify(
            id = event.actorRefId,
            actorRef = event.actorRef
          ),
          amountThings = event.data.amountThings
        )
      )
    }
}
