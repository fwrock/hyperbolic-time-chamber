package org.interscity.htc
package model.hybrid.actor

import core.actor.SimulationBaseActor
import org.interscity.htc.model.hybrid.entity.state.*

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.{ Dependency, Identify }
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.ActorInteractionEvent
import org.interscity.htc.core.entity.event.control.load.InitializeEvent
import org.interscity.htc.model.hybrid.entity.event.data.bus.{ BusLoadPassengerData, BusRequestPassengerData, RegisterBusStopData, RegisterPassengerData }
import org.interscity.htc.model.hybrid.entity.state.BusStopState

import scala.collection.mutable

class BusStop(
  private val properties: Properties
) extends SimulationBaseActor[BusStopState](
      properties = properties
    ) {

  override def onInitialize(event: InitializeEvent): Unit =
    super.onInitialize(event)

  override def requiresPostLoadRegistration: Boolean = true

  override def handlePostLoadRegistration(): Unit = {
    // Prefer lookup by nodeId; fall back to scanning dependencies for a Node entry
    // (handles data generated with the legacy "node" field name instead of "nodeId")
    val dependencyOpt =
      getDependencyOption(state.nodeId).orElse(
        if (state.nodeId == null || state.nodeId.isEmpty)
          dependencies.values.find(d => d.classType != null && d.classType.endsWith("Node"))
        else
          None
      )

    dependencyOpt match {
      case Some(dependency) =>
        sendMessageTo(
          dependency.id,
          dependency.classType,
          RegisterBusStopData(
            label = state.label
          )
        )
        logDebug(s"BusStop ${getEntityId} registered with node ${dependency.id}")
      case None =>
        logWarn(
          s"BusStop ${getEntityId} could not find node dependency: ${state.nodeId}. Registration with node skipped."
        )
    }
  }

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: RegisterPassengerData   => handleRegisterPassenger(event, d)
      case d: BusRequestPassengerData => handleBusRequestPassenger(event, d)
      case _ =>
        logWarn("Event not handled")
    }

  private def handleBusRequestPassenger(
    event: ActorInteractionEvent,
    data: BusRequestPassengerData
  ): Unit =
    state.people.get(data.label) match {
      case Some(people) =>
        val peopleToLoad = people.take(data.availableSpace)
        state.people.put(data.label, people.drop(data.availableSpace))

        // Report passenger loading
        report(
          data = Map(
            "event_type" -> "passengers_loaded",
            "bus_stop_id" -> getEntityId,
            "bus_id" -> event.actorRefId,
            "route_label" -> data.label,
            "passengers_loaded" -> peopleToLoad.size,
            "available_space" -> data.availableSpace,
            "passengers_waiting" -> state.people.get(data.label).map(_.size).getOrElse(0),
            "tick" -> currentTick
          ),
          label = "bus_stop_passengers_loaded"
        )

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

        // Report passenger arrival at bus stop
        report(
          data = Map(
            "event_type" -> "passenger_arrived_at_stop",
            "bus_stop_id" -> getEntityId,
            "person_id" -> person.id,
            "route_label" -> data.label,
            "passengers_waiting" -> state.people.get(data.label).map(_.size).getOrElse(0),
            "tick" -> currentTick
          ),
          label = "bus_stop_passenger_arrived"
        )
      case None =>
        state.people.put(data.label, mutable.Seq(person))
    }
  }
}
