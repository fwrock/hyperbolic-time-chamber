package org.interscity.htc
package model.supermarket.actor

import core.actor.BaseActor
import model.supermarket.entity.state.ClientState

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.actor.Identify
import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import org.interscity.htc.core.entity.event.data.BaseEventData
import org.interscity.htc.model.supermarket.entity.enumeration.ClientStatusEnum.{ Finished, InService, Start, Waiting }
import org.interscity.htc.model.supermarket.entity.event.data.{ FinishClientServiceData, NewClientServiceData, StartClientServiceData }

import scala.collection.mutable

class Client(
  override protected val actorId: String,
  private val timeManager: ActorRef,
  private val data: Any,
  override protected val dependencies: mutable.Map[String, Identify] =
    mutable.Map[String, Identify]()
) extends BaseActor[ClientState](
      actorId = actorId,
      timeManager = timeManager,
      data = data,
      dependencies = dependencies
    ) {

  override def actSpontaneous(event: SpontaneousEvent): Unit =
    state.status match {
      case Start =>
        state.status = Waiting
        enterQueue()
        onFinishSpontaneous()
      case Waiting =>
        sendAcknowledgeTick()
      case _ =>
        logEvent(s"Event current status not handled ${state.status}")
    }

  private def enterQueue(): Unit =
    sendMessageTo(
      dependencies(state.cashierId),
      NewClientServiceData(
        amountThings = state.amountThings
      )
    )

  override def actInteractWith[D <: BaseEventData](event: ActorInteractionEvent[D]): Unit =
    event match {
      case e: ActorInteractionEvent[StartClientServiceData]  => handleStartClientService(e)
      case e: ActorInteractionEvent[FinishClientServiceData] => handleFinishClientService(e)
      case _ =>
        logEvent(s"Event not handled ${event}")
    }

  private def handleStartClientService(event: ActorInteractionEvent[StartClientServiceData]): Unit =
    state.status = InService

  private def handleFinishClientService(
    event: ActorInteractionEvent[FinishClientServiceData]
  ): Unit = {
    state.status = Finished
    onFinishSpontaneous(destruct = true)
  }

}
