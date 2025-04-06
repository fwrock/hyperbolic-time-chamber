package org.interscity.htc
package model.supermarket.actor

import core.actor.BaseActor
import model.supermarket.entity.state.ClientState

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.Dependency
import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
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
    try {
      logEvent(
        s"DD Spontaneous event at tick ${event.tick} and lamport ${lamportClock.getClock} with status ${state.status}"
      )
      state.status match {
        case Start =>
          logEvent(
            s"DD Spontaneous event at tick ${event.tick} and lamport ${lamportClock.getClock} changing status of ${state.status} to ${Waiting}"
          )
          state.status = Waiting
          enterQueue()
          onFinishSpontaneous()
        case Waiting =>
          onFinishSpontaneous()
        case _ =>
          logEvent(s"Event current status not handled ${state.status}")
      }
    } catch
      case e: Exception =>
        log.error(
          s"DD $actorId Error spontaneous event at tick ${event.tick} and lamport ${lamportClock.getClock}",
          e
        )
        e.printStackTrace()
        onFinishSpontaneous()

  private def enterQueue(): Unit = {
    logEvent(
      s"DD Entering queue at tick ${currentTick} and lamport ${lamportClock.getClock} with status ${state.status}"
    )
    try {
      val cashier = dependencies(state.cashierId)
      sendMessageTo(
        cashier.id,
        cashier.classType,
        NewClientServiceData(
          amountThings = state.amountThings
        )
      )
    } catch {
      case e: Exception =>
        log.warning(s"$actorId - $dependencies - ${state.cashierId}")
        log.error(
          s"DD Error entering queue at tick ${currentTick} and lamport ${lamportClock.getClock} with status ${state.status}"
        )
        log.error(e.getMessage)
    }
  }

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: StartClientServiceData =>
        logEvent(
          s"DD start client service at tick ${event.tick} and lamport ${event.lamportTick} with status ${state.status}"
        )
        handleStartClientService(d)
      case d: FinishClientServiceData =>
        logEvent(
          s"DD finish client service at tick ${event.tick} and lamport ${event.lamportTick} with status ${state.status}"
        )
        handleFinishClientService(d)
      case _ =>
        logEvent(s"Event not handled ${event}")
    }

  private def handleStartClientService(data: StartClientServiceData): Unit =
    state.status = InService

  private def handleFinishClientService(
    data: FinishClientServiceData
  ): Unit = {
    state.status = Finished
    onFinishSpontaneous()
  }

}
