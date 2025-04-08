package org.interscity.htc
package model.supermarket.actor

import core.actor.BaseActor
import model.supermarket.entity.state.ClientState

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import org.interscity.htc.model.supermarket.entity.enumeration.ClientStatusEnum.{ Finished, InService, Start, Waiting }
import org.interscity.htc.model.supermarket.entity.event.data.{ FinishClientServiceData, NewClientServiceData, StartClientServiceData }

class Client(
  private val id: String,
  private val shard: String,
  private val timeManager: ActorRef,
  private val creatorManager: ActorRef = null
) extends BaseActor[ClientState](
      actorId = id,
      shardId = shard,
      timeManager = timeManager,
      creatorManager = creatorManager
    ) {

  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    logInfo(
      s"DD Spontaneous event at tick ${event.tick} and lamport ${lamportClock.getClock} with status $state"
    )
    onFinishSpontaneous()
  }
  /*
    try {
      if (state == null) {
        onFinishSpontaneous()
        return
      }
      logInfo(
        s"DD Spontaneous event at tick ${event.tick} and lamport ${lamportClock.getClock} with status ${state.status}"
      )
      state.status match {
        case Start =>
          logInfo(
            s"DD Spontaneous event at tick ${event.tick} and lamport ${lamportClock.getClock} changing status of ${state.status} to ${Waiting}"
          )
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
        log.error(
          s"DD $actorId Error spontaneous event at tick ${event.tick} and lamport ${lamportClock.getClock}",
          e
        )
        e.printStackTrace()
        onFinishSpontaneous()

   */

  private def enterQueue(): Unit = {
    logInfo(
      s"DD Entering queue at tick ${currentTick} and lamport ${lamportClock.getClock} with status ${state.status} dependencySize=${dependencies.size}"
    )
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
        log.error(s"$actorId - dependencies=$dependencies - ${state.cashierId}")
        log.error(
          s"DD $actorId Error entering queue at tick ${currentTick} and lamport ${lamportClock.getClock} with status ${state.status}, isInitialized= $isInitialized"
        )
        log.error(e.getMessage)
    }
  }

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: StartClientServiceData =>
        logInfo(
          s"DD start client service at tick ${event.tick} and lamport ${event.lamportTick} with status ${state.status}"
        )
        handleStartClientService(d)
      case d: FinishClientServiceData =>
        logInfo(
          s"DD finish client service at tick ${event.tick} and lamport ${event.lamportTick} with status ${state.status}"
        )
        handleFinishClientService(d)
      case _ =>
        logInfo(s"Event not handled ${event}")
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
