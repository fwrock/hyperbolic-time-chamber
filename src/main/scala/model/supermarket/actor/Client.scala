package org.interscity.htc
package model.supermarket.actor

import core.actor.BaseActor
import model.supermarket.entity.state.ClientState

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.actor.{ Dependency, Identify }
import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import org.interscity.htc.core.entity.event.data.BaseEventData
import org.interscity.htc.model.supermarket.entity.enumeration.ClientStatusEnum.{ Finished, InService, Start, Waiting }
import org.interscity.htc.model.supermarket.entity.event.data.{ FinishClientServiceData, NewClientServiceData, StartClientServiceData }

import scala.collection.mutable

class Client(
  private val id: String,
  private val timeManager: ActorRef,
  private val creatorManager: ActorRef = null,
  private val data: Any,
  override protected val dependencies: mutable.Map[String, Dependency] =
    mutable.Map[String, Dependency]()
) extends BaseActor[ClientState](
      actorId = id,
      timeManager = timeManager,
      creatorManager = creatorManager,
      data = data,
      dependencies = dependencies
    ) {

  override def actSpontaneous(event: SpontaneousEvent): Unit =
    logEvent(s"${event}")
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

  private def enterQueue(): Unit = {
    logEvent("Entering queue")
    val cashier = dependencies(state.cashierId)
    sendMessageTo(
      cashier.id,
      cashier.classType,
      NewClientServiceData(
        amountThings = state.amountThings
      )
    )
  }

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
