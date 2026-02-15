package org.interscity.htc
package model.hybrid.actor

import core.actor.SimulationBaseActor
import org.interscity.htc.model.hybrid.actor.Subway
import org.interscity.htc.model.hybrid.entity.state.*
import org.interscity.htc.model.hybrid.entity.state.{ SubwayState, SubwayStationState }

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.{ Dependency, Identify }
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import org.interscity.htc.core.entity.event.control.load.InitializeEvent
import org.interscity.htc.core.util.ActorCreatorUtil.createShardedActorSeveralArgs
import org.interscity.htc.core.util.JsonUtil.toJson
import org.interscity.htc.core.util.{ ActorCreatorUtil, IdentifyUtil, JsonUtil }
import org.interscity.htc.model.hybrid.entity.event.data.subway.{ RegisterSubwayPassengerData, RegisterSubwayStationData, SubwayLoadPassengerData, SubwayRequestPassengerData }
import org.interscity.htc.model.hybrid.entity.state.enumeration.{ EventTypeEnum, SubwayStationStateEnum }
import org.interscity.htc.model.hybrid.entity.state.enumeration.SubwayStationStateEnum.{ Start, Working }
import org.interscity.htc.model.hybrid.entity.state.model.{ RoutePathItem, SubwayInformation, SubwayLineInformation }
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed

import scala.collection.mutable

class SubwayStation(
  private val properties: Properties
) extends SimulationBaseActor[SubwayStationState](
      properties = properties
    ) {

  override def onInitialize(event: InitializeEvent): Unit = {
    super.onInitialize(event)
    getDependencyOption(state.nodeId) match {
      case Some(node) =>
        sendMessageTo(
          node.id,
          node.classType,
          RegisterSubwayStationData(
            lines = state.lines.keys.toList
          ),
          "RegisterSubwayStation",
          LoadBalancedDistributed
        )
        logInfo(s"SubwayStation ${getEntityId} registered with node ${node.id}")
      case None =>
        logWarn(s"SubwayStation ${getEntityId} could not find node dependency: ${state.nodeId}. Registration with node skipped.")
    }
  }

  override def actSpontaneous(event: SpontaneousEvent): Unit =
    state.status match
      case Start =>
        state.status = Working
        createSubwayFrom(state.lines)
        scheduleNextTick()
      case Working =>
        createSubwayFrom(filterLinesByNextTick())
        scheduleNextTick()
      case _ =>
        logInfo(s"Event current status not handled ${state.status}")
        onFinishSpontaneous(None)

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
      case Some(peopleQueue) =>
        val peopleToLoad = peopleQueue.take(data.availableSpace)
        state.people.put(data.line, peopleQueue.drop(data.availableSpace))
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
          case Some(subwayQueue) =>
            if (subwayQueue.nonEmpty && state.garage) {
              val subway = subwayQueue.dequeue()
              try {
                val actorRef = createSubway(subway)
                dependencies(subway.actorId) = Dependency(subway.actorId, classOf[Subway].getName)
                lines(line).nextTick = currentTick + lines(line).interval
              } catch {
                case e: IllegalStateException =>
                  logError(s"Failed to create subway ${subway.actorId} for line $line: ${e.getMessage}")
                  // Put the subway back in the queue for later retry
                  subwayQueue.enqueue(subway)
                case e: Exception =>
                  logError(s"Unexpected error creating subway ${subway.actorId} for line $line: ${e.getMessage}")
                  // Put the subway back in the queue for later retry
                  subwayQueue.enqueue(subway)
              }
            }
          case None =>
            logInfo(s"Subway not found for line $line")
    }

  private def scheduleNextTick(): Unit = {
    val nextTickOpt = state.lines.values
      .map { line =>
        if (line.nextTick <= currentTick) {
          line.nextTick = currentTick + line.interval
        }
        line.nextTick
      }
      .toList
      .sorted
      .headOption
    onFinishSpontaneous(nextTickOpt, destruct = false)
  }

  private def createSubway(subway: SubwayInformation): ActorRef = {
    val route = convertLineRouteToPath(subway.line)
    if (route.isEmpty) {
      logWarn(s"Cannot create subway ${subway.actorId} for line ${subway.line} - no valid route available")
      throw new IllegalStateException(s"No route available for subway line ${subway.line}")
    }
    
    val subwayStations = convertLineToSubwayStations(subway.line)
    if (subwayStations.isEmpty) {
      logWarn(s"Cannot create subway ${subway.actorId} for line ${subway.line} - no subway stations available")
      throw new IllegalStateException(s"No subway stations available for line ${subway.line}")
    }
    
    val subwayProperties = Properties(
      entityId = subway.actorId,
      resourceId = properties.resourceId,
      timeManagers = properties.timeManagers,
      creatorManager = properties.creatorManager,
      reporters = properties.reporters,
      data = toJson(
        SubwayState(
          startTick = currentTick,
          capacity = subway.capacity,
          numberOfPorts = subway.numberOfPorts,
          velocity = subway.velocity,
          stopTime = subway.stopTime,
          line = subway.line,
          bestRoute = Some(route),
          subwayStations = subwayStations,
          origin = state.nodeId,
          destination = subwayStations.values.last
        )
      ),
      dependencies = mutable.Map[String, Dependency](),
      actorType = properties.actorType,
      defaultTimeManagerType = properties.defaultTimeManagerType
    )

    createShardedActorSeveralArgs(
      system = context.system,
      actorClass = classOf[Subway],
      entityId = subway.actorId,
      subwayProperties
    )
  }

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
    val lineRoute = state.linesRoute.get(line)
    
    lineRoute match {
      case Some(routeQueue) =>
        // linesRoute format: Queue[SubwayRouteEntry]
        // Output format: Queue[(rail_link_id, node_id)]
        routeQueue.foreach { routeEntry =>
          route.enqueue((routeEntry.railLinkId, routeEntry.stationNode.nodeId))
        }
        logInfo(s"Built route for line $line: ${route.size} segments using RAIL LINKS")
      case None =>
        logWarn(s"No route found for line $line")
    }
    
    route
  }
}
