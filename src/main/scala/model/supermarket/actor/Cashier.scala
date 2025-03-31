package org.interscity.htc
package model.supermarket.actor

import core.actor.BaseActor

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.{ Dependency, Identify }
import org.htc.protobuf.model.entity.event.data.{ FinishClientServiceData, NewClientServiceData, StartClientServiceData }
import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import org.interscity.htc.model.supermarket.entity.enumeration.CashierStatusEnum.{ Busy, Free, Waiting }
import org.interscity.htc.model.supermarket.entity.model.ClientQueued
import org.interscity.htc.model.supermarket.entity.state.CashierState
import org.interscity.htc.model.supermarket.util.CashierUtil
import org.interscity.htc.model.supermarket.util.CashierUtil.serviceTime

import scala.collection.mutable

class Cashier(
  private val id: String,
  private val timeManager: ActorRef,
  private val creatorManager: ActorRef = null,
  private val data: Any,
  override protected val dependencies: mutable.Map[String, Dependency] =
    mutable.Map[String, Dependency]()
) extends BaseActor[CashierState](
      actorId = id,
      timeManager = timeManager,
      creatorManager = creatorManager,
      data = data,
      dependencies = dependencies
    ) {

  override def handleEvent: Receive = {
    case event => logEvent(s"Event not handled ${event}")
  }

  override def actSpontaneous(event: SpontaneousEvent): Unit =
    logEvent(
      s"DD Spontaneous event at tick ${event.tick} and lamport ${lamportClock.getClock} with status ${state.status}"
    )
    state.status match {
      case Free =>
        if (state.queue.nonEmpty) {
          logEvent("Cashier is attending a client")
          val queued = state.queue.dequeue()
          state.clientInService = Some(queued.client)
          sendMessageTo(queued.client.id, queued.client.classType, StartClientServiceData())
          logEvent(
            s"DD Spontaneous event (queue non empty) at tick ${event.tick} and lamport ${lamportClock.getClock} changing status of ${state.status} to ${Busy}"
          )
          state.status = Busy
          onFinishSpontaneous(Some(currentTick + serviceTime(queued.amountThings)))
        } else {
          logEvent("Cashier is waiting")
          logEvent(
            s"DD Spontaneous event (queue empty) at tick ${event.tick} and lamport ${lamportClock.getClock} changing status of ${state.status} to ${Waiting}"
          )
          state.status = Waiting
          logEvent(
            s"DD Send Finish event  to tick ${currentTick} and lamport ${lamportClock.getClock}"
          )
          onFinishSpontaneous()
        }
      case Busy =>
        sendMessageTo(
          state.clientInService.get.id,
          state.clientInService.get.classType,
          FinishClientServiceData()
        )
        state.clientInService = None
        logEvent(
          s"DD Spontaneous event at tick ${event.tick} and lamport ${lamportClock.getClock} changing status of ${state.status} to ${Free}"
        )
        state.status = Free
        logEvent(
          s"DD Send Finish event schedule to tick ${currentTick + CashierUtil.breakTime} and lamport ${lamportClock.getClock}"
        )
        onFinishSpontaneous(Some(currentTick + CashierUtil.breakTime))
      case Waiting =>
        logEvent(
          s"DD Send Finish event  to tick ${currentTick} and lamport ${lamportClock.getClock} with status ${state.status}"
        )
        onFinishSpontaneous()
      case _ =>
        logEvent(s"Event current status not handled ${state.status}")
    }

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case e: NewClientServiceData =>
        logEvent(
          s"DD new client service  to tick ${event.tick} and lamport ${event.lamportTick} with status ${state.status}"
        )
        handleNewClientService(event, e)
      case _ =>
        logEvent(s"Event not handled ${event}")
    }

  private def handleNewClientService(
    event: ActorInteractionEvent,
    data: NewClientServiceData
  ): Unit =
    if (state.queue.isEmpty && (state.status == Waiting || state.status == Free)) {
      state.status = Busy
      sendMessageTo(
        event.actorRefId,
        event.actorClassType,
        StartClientServiceData()
      )
      logEvent(
        s"DD Free cashier - Send Finish event schedule to tick ${currentTick + serviceTime(data.amountThings)} and lamport ${lamportClock.getClock}"
      )
      onFinishSpontaneous(Some(currentTick + serviceTime(data.amountThings)))
    } else {
      logEvent(
        s"DD Queued client at tick ${event.tick} and lamport ${event.lamportTick} queue size ${state.queue.size} with status ${state.status}"
      )
      state.queue.enqueue(
        ClientQueued(
          client = Identify(
            id = event.actorRefId,
            classType = event.actorClassType,
            actorRef = event.actorPathRef
          ),
          amountThings = data.amountThings
        )
      )
    }
}
