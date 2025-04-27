package org.interscity.htc
package model.supermarket.actor

import core.actor.BaseActor
import model.supermarket.entity.state.ClientState

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import org.interscity.htc.model.supermarket.entity.enumeration.ClientStatusEnum.{ Finished, InService, Start, Waiting }
import org.interscity.htc.model.supermarket.entity.event.data.{ FinishClientServiceData, NewClientServiceData, StartClientServiceData }

class Client(
  val properties: Properties
) extends BaseActor[ClientState](
      properties = properties
    ) {

  override def actSpontaneous(event: SpontaneousEvent): Unit =
    try {
      if (state == null) {
        onFinishSpontaneous()
        return
      }
      state.status match {
        case Start =>
          state.status = Waiting
          enterQueue()
          onFinishSpontaneous()
        case Waiting =>
          onFinishSpontaneous()
        case _ =>
          logInfo(s"Event current status not handled ${state.status}")
      }
    } catch
      case e: Exception =>
        e.printStackTrace()
        onFinishSpontaneous()

  private def enterQueue(): Unit =
    try {
      val cashier = dependencies(state.cashierId)
      sendMessageTo(
        cashier.id,
        cashier.resourceId,
        NewClientServiceData(
          amountThings = state.amountThings
        )
      )
    } catch {
      case e: Exception =>
        log.error(e.getMessage)
    }

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    try
      event.data match {
        case d: StartClientServiceData =>
          handleStartClientService(d)
        case d: FinishClientServiceData =>
          handleFinishClientService(d)
        case _ =>
          logInfo(s"Event not handled ${event}")
      }
    catch
      case e: Exception =>
        e.printStackTrace()

  private def handleStartClientService(data: StartClientServiceData): Unit =
    state.status = InService

  private def handleFinishClientService(
    data: FinishClientServiceData
  ): Unit = {
    state.status = Finished
    onFinishSpontaneous()
  }
}
