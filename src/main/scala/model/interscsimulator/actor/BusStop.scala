package org.interscity.htc
package model.interscsimulator.actor

import core.actor.BaseActor

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.actor.Identify
import org.interscity.htc.core.entity.event.ActorInteractionEvent
import org.interscity.htc.core.entity.event.data.BaseEventData
import org.interscity.htc.model.interscsimulator.entity.event.data.bus.{BusLoadPeopleData, BusRequestPassengerData, RegisterBusStopData, RegisterPassengerData}
import org.interscity.htc.model.interscsimulator.entity.state.BusStopState

import scala.collection.mutable

class BusStop(
  override protected val actorId: String,
  private val timeManager: ActorRef,
  private val data: String = null,
  override protected val dependencies: mutable.Map[String, ActorRef] =
    mutable.Map[String, ActorRef]()
) extends BaseActor[BusStopState](
      actorId = actorId,
      timeManager = timeManager,
      data = data,
      dependencies = dependencies
    ) {

  override def onStart(): Unit = {
    sendMessageTo(
      state.nodeId,
      dependencies(state.nodeId),
      RegisterBusStopData(
        label = state.label
      )
    )
  }

  override def actInteractWith[D <: BaseEventData](event: ActorInteractionEvent[D]): Unit = {
    event match {
      case e: ActorInteractionEvent[RegisterPassengerData] => handleRegisterPassenger(e)
      case e: ActorInteractionEvent[BusRequestPassengerData] => handleBusRequestPassenger(e)
      case _ =>
        logEvent("Event not handled")
    }
  }
  
  private def handleBusRequestPassenger(event: ActorInteractionEvent[BusRequestPassengerData]): Unit = {
    state.people.get(event.data.label) match {
      case Some(people) =>
        val peopleToLoad = people.take(event.data.availableSpace)
        state.people.put(event.data.label, people.drop(event.data.availableSpace))
        sendLoadPeopleToBus(peopleToLoad, event)
      case None =>
        sendLoadPeopleToBus(mutable.Seq(), event)
    }
  }
  
  private def sendLoadPeopleToBus(peopleToLoad: mutable.Seq[Identify], event: ActorInteractionEvent[BusRequestPassengerData]): Unit = {
    sendMessageTo(
      actorId = event.actorRefId,
      actorRef = event.actorRef,
      data = BusLoadPeopleData(
        people = peopleToLoad
      )
    )
  }

  private def handleRegisterPassenger(event: ActorInteractionEvent[RegisterPassengerData]): Unit = {
    val person = Identify(event.actorRefId, event.actorRef)
    state.people.get(event.data.label) match {
      case Some(people) =>
        state.people.put(event.data.label, people :+ person)
      case None =>
        state.people.put(event.data.label, mutable.Seq(person))
    }
  }
}
