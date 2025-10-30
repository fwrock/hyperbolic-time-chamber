package org.interscity.htc
package model.mobility.actor

import core.actor.{BaseActor, SimulationBaseActor}

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.{Dependency, Identify}
import org.interscity.htc.core.entity.actor.properties.{Properties, SimulationBaseProperties}
import org.interscity.htc.core.entity.event.ActorInteractionEvent
import org.interscity.htc.core.entity.event.control.load.InitializeEvent
import org.interscity.htc.model.mobility.entity.event.data.bus.{BusLoadPassengerData, BusRequestPassengerData, RegisterBusStopData, RegisterPassengerData}
import org.interscity.htc.model.mobility.entity.state.BusStopState

import scala.collection.mutable

class BusStop(
  private val properties: SimulationBaseProperties
) extends SimulationBaseActor[BusStopState](
      properties = properties
    ) {

  override def onInitialize(event: InitializeEvent): Unit =
    val dependency = getDependency(state.nodeId)
    sendMessageTo(
      dependency.id,
      dependency.classType,
      RegisterBusStopData(
        label = state.label
      )
    )

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: RegisterPassengerData   => handleRegisterPassenger(event, d)
      case d: BusRequestPassengerData => handleBusRequestPassenger(event, d)
      case _ =>
        logInfo("Event not handled")
    }

  private def handleBusRequestPassenger(
    event: ActorInteractionEvent,
    data: BusRequestPassengerData
  ): Unit =
    state.people.get(data.label) match {
      case Some(people) =>
        val peopleToLoad = people.take(data.availableSpace)
        state.people.put(data.label, people.drop(data.availableSpace))
        sendLoadPeopleToBus(peopleToLoad, event)
      case None =>
        sendLoadPeopleToBus(mutable.Seq(), event)
    }

  private def sendLoadPeopleToBus(
    peopleToLoad: mutable.Seq[Identify],
    event: ActorInteractionEvent
  ): Unit =
    sendMessageTo(
      event.actorRefId,
      event.actorClassType,
      data = BusLoadPassengerData(
        people = peopleToLoad
      )
    )

  private def handleRegisterPassenger(
    event: ActorInteractionEvent,
    data: RegisterPassengerData
  ): Unit = {
    val person = event.toIdentity
    state.people.get(data.label) match {
      case Some(people) =>
        state.people.put(data.label, people :+ person)
      case None =>
        state.people.put(data.label, mutable.Seq(person))
    }
  }
}
