package org.interscity.htc
package model.supermarket.actor

import core.actor.BaseActor

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.{Dependency, Identify}
import org.htc.protobuf.core.entity.event.communication.SpontaneousEvent
import org.htc.protobuf.model.entity.event.data.{FinishClientServiceData, NewClientServiceData, StartClientServiceData}
import org.interscity.htc.core.entity.event.ActorInteractionEvent
import org.interscity.htc.model.supermarket.entity.enumeration.CashierStatusEnum.{Busy, Free, Waiting}
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
    state.status match {
      case Free =>
        logEvent("Cashier is free")
        if (state.queue.nonEmpty) {
          logEvent("Cashier is attending a client")
          val queued = state.queue.dequeue()
          state.clientInService = Some(queued.client)
          sendMessageTo(queued.client.id, queued.client.classType, StartClientServiceData())
          state.status = Busy
          onFinishSpontaneous(Some(currentTick + serviceTime(queued.amountThings)))
        } else {
          logEvent("Cashier is waiting")
          state.status = Waiting
          onFinishSpontaneous()
        }
      case Busy =>
        logEvent("Cashier is busy")
        sendMessageTo(
          state.clientInService.get.id,
          state.clientInService.get.classType,
          FinishClientServiceData()
        )
        state.clientInService = None
        state.status = Free
        onFinishSpontaneous(Some(currentTick + CashierUtil.breakTime))
      case Waiting =>
        logEvent("Cashier is waiting")
        onFinishSpontaneous()
      case _ =>
        logEvent(s"Event current status not handled ${state.status}")
    }

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case e: NewClientServiceData => handleNewClientService(event, e)
      case _ =>
        logEvent(s"Event not handled ${event}")
    }

  private def handleNewClientService(event: ActorInteractionEvent, data: NewClientServiceData): Unit =
    if (state.queue.isEmpty && (state.status == Waiting || state.status == Free)) {
      state.status = Busy
      sendMessageTo(
        event.actorRefId,
        event.actorClassType,
        StartClientServiceData()
      )
      onFinishSpontaneous(Some(currentTick + serviceTime(data.amountThings)))
    } else {
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
