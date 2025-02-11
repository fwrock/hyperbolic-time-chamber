package org.interscity.htc
package model.interscsimulator.actor

import core.actor.BaseActor
import model.interscsimulator.entity.state.{ SubwayState, SubwayStationState }

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.actor.Identify
import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import org.interscity.htc.core.entity.event.data.BaseEventData
import org.interscity.htc.core.util.ActorCreatorUtil.createShardedActor
import org.interscity.htc.core.util.JsonUtil.toJson
import org.interscity.htc.core.util.{ ActorCreatorUtil, JsonUtil }
import org.interscity.htc.model.interscsimulator.entity.event.data.subway.{ RegisterSubwayPassengerData, RegisterSubwayStationData, SubwayLoadPassengerData, SubwayRequestPassengerData }
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.SubwayStationStateEnum
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.SubwayStationStateEnum.{ Start, Working }
import org.interscity.htc.model.interscsimulator.entity.state.model.{ RoutePathItem, SubwayInformation, SubwayLineInformation }

import scala.collection.mutable

class SubwayStation(
  override protected val actorId: String = null,
  private val timeManager: ActorRef = null,
  private val data: String = null,
  override protected val dependencies: mutable.Map[String, ActorRef] =
    mutable.Map[String, ActorRef]()
) extends BaseActor[SubwayStationState](
      actorId = actorId,
      timeManager = timeManager,
      data = data,
      dependencies = dependencies
    ) {

  override def onStart(): Unit =
    sendMessageTo(
      state.nodeId,
      dependencies(state.nodeId),
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
        logEvent(s"Event current status not handled ${state.status}")

  override def actInteractWith[D <: BaseEventData](event: ActorInteractionEvent[D]): Unit =
    event match {
      case e: ActorInteractionEvent[RegisterSubwayPassengerData] => handleRegisterPassenger(e)
      case e: ActorInteractionEvent[SubwayRequestPassengerData]  => handleSubwayRequestPassenger(e)
      case _                                                     => logEvent("Event not handled")
    }

  private def handleRegisterPassenger(
    event: ActorInteractionEvent[RegisterSubwayPassengerData]
  ): Unit = {
    val person = Identify(event.actorRefId, event.actorRef)
    state.people.get(event.data.line) match {
      case Some(people) =>
        state.people.put(event.data.line, people :+ person)
      case None =>
        state.people.put(event.data.line, mutable.Seq(person))
    }
  }

  private def handleSubwayRequestPassenger(
    event: ActorInteractionEvent[SubwayRequestPassengerData]
  ): Unit =
    state.people.get(event.data.line) match {
      case Some(people) =>
        val peopleToLoad = people.take(event.data.availableSpace)
        state.people.put(event.data.line, people.drop(event.data.availableSpace))
        sendLoadPeopleToSubway(peopleToLoad, event)
      case None =>
        sendLoadPeopleToSubway(mutable.Seq(), event)
    }

  private def sendLoadPeopleToSubway(
    peopleToLoad: mutable.Seq[Identify],
    event: ActorInteractionEvent[SubwayRequestPassengerData]
  ): Unit =
    sendMessageTo(
      actorId = event.actorRefId,
      actorRef = event.actorRef,
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
              dependencies(subway.actorId) = actorRef
              lines(line).nextTick = currentTick + lines(line).interval
              onFinishSpontaneous(Some(lines(line).nextTick))
            }
          case None =>
            logEvent(s"Subway not found for line $line")
    }

  private def createSubway(subway: SubwayInformation): ActorRef =
    createShardedActor(
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
          destination = convertLineToSubwayStations(subway.line).values.last,
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
  ): mutable.Queue[(RoutePathItem, RoutePathItem)] = {
    val route = mutable.Queue[(RoutePathItem, RoutePathItem)]()
    val lineRoute = state.linesRoute(line)
    for (i <- 0 until lineRoute.size - 1)
      route.enqueue(
        (
          RoutePathItem(
            actorId = lineRoute(i)._1.nodeId,
            actorRef = dependencies(lineRoute(i)._1.nodeId)
          ),
          RoutePathItem(
            actorId = lineRoute(i)._2,
            actorRef = dependencies(lineRoute(i)._2)
          )
        )
      )
    route
  }
}
