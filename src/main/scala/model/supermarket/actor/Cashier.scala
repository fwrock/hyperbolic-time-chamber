package org.interscity.htc
package model.supermarket.actor

import core.actor.BaseActor

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.model.supermarket.entity.event.data.{ FinishClientServiceData, NewClientServiceData, StartClientServiceData }
import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import org.interscity.htc.model.supermarket.entity.enumeration.CashierStatusEnum.{ Busy, Free, Waiting }
import org.interscity.htc.model.supermarket.entity.model.ClientQueued
import org.interscity.htc.model.supermarket.entity.state.CashierState
import org.interscity.htc.model.supermarket.util.CashierUtil
import org.interscity.htc.model.supermarket.util.CashierUtil.serviceTime

class Cashier(
  val properties: Properties
) extends BaseActor[CashierState](
      properties = properties
    ) {

  override def handleEvent: Receive = {
    case event => logInfo(s"Event not handled ${event}")
  }

  override def actSpontaneous(event: SpontaneousEvent): Unit =
    try
      state.status match {
        case Free =>
          if (state.queue.nonEmpty) {
            val queued = state.queue.dequeue()
            state.clientInService = Some(queued.client)
            sendMessageTo(queued.client.id, queued.client.classType, StartClientServiceData())
            state.status = Busy
            onFinishSpontaneous(Some(currentTick + serviceTime(queued.amountThings)))
          } else {
            state.status = Waiting
            onFinishSpontaneous()
          }
        case Busy =>
          state.clientInService.foreach(
            client =>
              sendMessageTo(
                client.id,
                client.classType,
                FinishClientServiceData()
              )
          )
          state.clientInService = None
          state.status = Free
          onFinishSpontaneous(Some(currentTick + CashierUtil.breakTime))
        case Waiting =>
          onFinishSpontaneous()
        case _ =>
          onFinishSpontaneous()
      }
    catch {
      case e: Exception =>
        e.printStackTrace()
        onFinishSpontaneous()
    }

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    try
      event.data match {
        case e: NewClientServiceData =>
          handleNewClientService(event, e)
        case _ =>
          logInfo(s"Event not handled ${event}")
      }
    catch
      case e: Exception =>
        e.printStackTrace()

  private def handleNewClientService(
    event: ActorInteractionEvent,
    data: NewClientServiceData
  ): Unit =
    if (state.queue.isEmpty && (state.status == Waiting || state.status == Free)) {
      state.status = Busy
      sendMessageTo(
        entityId = event.actorRefId,
        shardId = event.shardRefId,
        StartClientServiceData()
      )
      onFinishSpontaneous(Some(currentTick + serviceTime(data.amountThings)))
    } else {
      state.queue.enqueue(
        ClientQueued(
          client = Identify(
            id = event.actorRefId,
            resourceId = event.shardRefId,
            classType = event.actorClassType,
            actorRef = event.actorPathRef
          ),
          amountThings = data.amountThings
        )
      )
    }
}
