package org.interscity.htc
package model.mobility.actor

import core.actor.{BaseActor, SimulationBaseActor}
import model.mobility.entity.state.{SubwayState, SubwayStationState}

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.{Dependency, Identify}
import org.interscity.htc.core.entity.actor.properties.{Properties, SimulationBaseProperties}
import org.interscity.htc.core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import org.interscity.htc.core.entity.event.control.load.InitializeEvent
import org.interscity.htc.core.util.ActorCreatorUtil.createShardedActorSeveralArgs
import org.interscity.htc.core.util.JsonUtil.toJson
import org.interscity.htc.core.util.{ActorCreatorUtil, IdentifyUtil, JsonUtil}
import org.interscity.htc.model.mobility.entity.event.data.subway.{RegisterSubwayPassengerData, RegisterSubwayStationData, SubwayLoadPassengerData, SubwayRequestPassengerData}
import org.interscity.htc.model.mobility.entity.state.enumeration.SubwayStationStateEnum
import org.interscity.htc.model.mobility.entity.state.enumeration.SubwayStationStateEnum.{Start, Working}
import org.interscity.htc.model.mobility.entity.state.model.{RoutePathItem, SubwayInformation, SubwayLineInformation}

import scala.collection.mutable

class SubwayStation(
  private val properties: SimulationBaseProperties
) extends SimulationBaseActor[SubwayStationState](
      properties = properties
    ) {

  override def onInitialize(event: InitializeEvent): Unit =
    val node = getDependency(state.nodeId)
    sendMessageTo(
      node.id,
      node.classType,
      RegisterSubwayStationData(
        lines = state.lines.keys.toSeq
      )
    )

  override def actSpontaneous(event: SpontaneousEvent): Unit =
    state.status match
      case Start =>
        state.status = Working
        createSubwayFrom(state.lines)
      case Working =>
        createSubwayFrom(filterLinesByNextTick())
      case _ =>
        logInfo(s"Event current status not handled ${state.status}")

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: RegisterSubwayPassengerData => handleRegisterPassenger(event, d)
      case d: SubwayRequestPassengerData  => handleSubwayRequestPassenger(event, d)
      case _                              => logInfo("Event not handled")
    }

  private def handleRegisterPassenger(
    event: ActorInteractionEvent,
    data: RegisterSubwayPassengerData
  ): Unit = {
    val person = Identify(event.actorRefId, event.actorClassType, event.actorPathRef)
    state.people.get(data.line) match {
      case Some(people) =>
        state.people.put(data.line, people :+ person)
      case None =>
        state.people.put(data.line, mutable.Seq(person))
    }
  }

  private def handleSubwayRequestPassenger(
    event: ActorInteractionEvent,
    data: SubwayRequestPassengerData
  ): Unit =
    state.people.get(data.line) match {
      case Some(people) =>
        val peopleToLoad = people.take(data.availableSpace)
        state.people.put(data.line, people.drop(data.availableSpace))
        sendLoadPeopleToSubway(peopleToLoad, event, data)
      case None =>
        sendLoadPeopleToSubway(mutable.Seq(), event, data)
    }

  private def sendLoadPeopleToSubway(
    peopleToLoad: mutable.Seq[Identify],
    event: ActorInteractionEvent,
    data: SubwayRequestPassengerData
  ): Unit =
    sendMessageTo(
      event.actorRefId,
      event.actorClassType,
      data = SubwayLoadPassengerData(
        people = peopleToLoad
      )
    )

  private def filterLinesByNextTick(): mutable.Map[String, SubwayLineInformation] =
    state.lines.filter {
      case (_, line) => line.nextTick <= currentTick
    }

  private def createSubwayFrom(lines: mutable.Map[String, SubwayLineInformation]): Unit =
    lines.keys.foreach {
      line =>
        state.subways.get(line) match
          case Some(subways) =>
            if (subways.nonEmpty && state.garage) {
              val subway = subways.dequeue()
              val actorRef = createSubway(subway)
              dependencies(subway.actorId) = Dependency(subway.actorId, classOf[Subway].getName)
              lines(line).nextTick = currentTick + lines(line).interval
              onFinishSpontaneous(Some(lines(line).nextTick))
            }
          case None =>
            logInfo(s"Subway not found for line $line")
    }

  private def createSubway(subway: SubwayInformation): ActorRef =
    createShardedActorSeveralArgs(
      system = context.system,
      actorClass = classOf[Subway],
      entityId = subway.actorId,
      getTimeManager,
      toJson(
        SubwayState(
          startTick = currentTick,
          capacity = subway.capacity,
          numberOfPorts = subway.numberOfPorts,
          velocity = subway.velocity,
          stopTime = subway.stopTime,
          line = subway.line,
          bestRoute = Some(convertLineRouteToPath(subway.line)),
          subwayStations = convertLineToSubwayStations(subway.line),
          origin = state.nodeId,
          destination = convertLineToSubwayStations(subway.line).values.last
        )
      ),
      dependencies
    )

  private def convertLineToSubwayStations(line: String): mutable.Map[String, String] = {
    val lineRoute = state.linesRoute(line)
    val subwayStations = mutable.Map[String, String]()
    for (i <- lineRoute.indices)
      subwayStations.put(lineRoute(i)._1.stationId, lineRoute(i)._1.nodeId)
    subwayStations
  }

  private def convertLineRouteToPath(
    line: String
  ): mutable.Queue[(String, String)] = {
    val route = mutable.Queue[(String, String)]()
    val lineRoute = state.linesRoute(line)
    for (i <- 0 until lineRoute.size - 1)
      route.enqueue(
        (
          lineRoute(i)._2,
          lineRoute(i)._1.nodeId
        )
      )
    route
  }
}
