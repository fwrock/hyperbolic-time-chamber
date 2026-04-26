package org.interscity.htc
package model.hybrid.actor

import core.actor.SimulationBaseActor
import org.interscity.htc.model.hybrid.actor.Subway
import org.interscity.htc.model.hybrid.entity.state.*
import org.interscity.htc.model.hybrid.entity.state.{ SubwayState, SubwayStationState }

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.actor.ShardActorId
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import org.interscity.htc.core.entity.event.control.load.InitializeEvent
import org.interscity.htc.core.types.Tick
import org.interscity.htc.core.util.ActorCreatorUtil.createShardedActorSeveralArgs
import org.interscity.htc.core.util.JsonUtil.toJson
import org.interscity.htc.core.util.{ ActorCreatorUtil, IdentifyUtil, JsonUtil }
import org.interscity.htc.core.util.SimulationUtil
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

  private lazy val simulationEnd: Tick = SimulationUtil.loadSimulationConfig().duration

  override def onInitialize(event: InitializeEvent): Unit =
    super.onInitialize(event)

  override def requiresPostLoadRegistration: Boolean = true

  override def handlePostLoadRegistration(): Unit = {
    val nodeOpt = getDependencyOption(state.nodeId).orElse(
      relationships.values.find(d => d.classType != null && d.classType.endsWith("Node"))
    )
    nodeOpt match {
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
        logDebug(s"SubwayStation ${getEntityId} registered with node ${node.id}")
      case None if state.nodeId != null && state.nodeId.nonEmpty =>
        logWarn(
          s"SubwayStation ${getEntityId}: relationships map empty (available keys: [${relationships.keys.mkString(", ")}]). " +
            s"Registering with node ${state.nodeId} via direct routing."
        )
        sendMessageTo(
          state.nodeId,
          "hybrid.actor.Node",
          RegisterSubwayStationData(
            lines = state.lines.keys.toList
          ),
          "RegisterSubwayStation",
          LoadBalancedDistributed
        )
      case None =>
        logWarn(
          s"SubwayStation ${getEntityId}: could not find node dependency and nodeId is null. Registration skipped."
        )
    }
  }

  override def actSpontaneous(event: SpontaneousEvent): Unit =
    if (currentTick >= simulationEnd) {
      logDebug(
        s"SubwayStation ${getEntityId} reached simulation end tick=$simulationEnd, stopping scheduling"
      )
      onFinishSpontaneous(None)
    } else
      state.status match
        case Start =>
          state.status = Working
          createSubwayFrom(state.lines)
          scheduleNextTick()
        case Working =>
          createSubwayFrom(filterLinesByNextTick())
          scheduleNextTick()
        case _ =>
          logWarn(s"Event current status not handled ${state.status}")
          onFinishSpontaneous(None)

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: RegisterSubwayPassengerData => handleRegisterPassenger(event, d)
      case d: SubwayRequestPassengerData => handleSubwayRequestPassenger(event, d)
      case _                              => logWarn("Event not handled")
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
                dependencies(subway.actorId) = ShardActorId(subway.actorId, classOf[Subway].getName)
                lines(line).nextTick = currentTick + lines(line).interval
              } catch {
                case e: IllegalStateException =>
                  logError(
                    s"Failed to create subway ${subway.actorId} for line $line: ${e.getMessage}"
                  )
                  subwayQueue.enqueue(subway)
                case e: Exception =>
                  logError(
                    s"Unexpected error creating subway ${subway.actorId} for line $line: ${e.getMessage}"
                  )
                  subwayQueue.enqueue(subway)
              }
            }
          case None =>
            logWarn(s"Subway not found for line $line")
    }

  private def scheduleNextTick(): Unit = {
    val nextTickOpt = state.lines.values.map {
      line =>
        if (line.nextTick <= currentTick) {
          line.nextTick = currentTick + line.interval
        }
        line.nextTick
    }
      .filter(_ < simulationEnd)
      .toList
      .sorted
      .headOption
    onFinishSpontaneous(nextTickOpt, destruct = false)
  }

  private def createSubway(subway: SubwayInformation): ActorRef = {
    val route = convertLineRouteToPath(subway.line)
    if (route.isEmpty) {
      logWarn(
        s"Cannot create subway ${subway.actorId} for line ${subway.line} - no valid route available"
      )
      throw new IllegalStateException(s"No route available for subway line ${subway.line}")
    }

    val subwayStations = convertLineToSubwayStations(subway.line)
    if (subwayStations.isEmpty) {
      logWarn(
        s"Cannot create subway ${subway.actorId} for line ${subway.line} - no subway stations available"
      )
      throw new IllegalStateException(s"No subway stations available for line ${subway.line}")
    }

    val subwayProperties = Properties(
      entityId = subway.actorId,
      resourceId = properties.resourceId,
      timeManagers = properties.timeManagers,
      creatorManager = properties.creatorManager,
      reporters = properties.reporters,
      data = toJson({
        val subwayState = SubwayState(
          startTick = currentTick,
          capacity = subway.capacity,
          numberOfPorts = subway.numberOfPorts,
          velocity = subway.velocity,
          stopTime = subway.stopTime,
          line = subway.line,
          subwayStations = subwayStations,
          origin = state.nodeId,
          destination = subwayStations.values.last
        )
        subwayState.bestRoute = Some(route)
        subwayState
      }),
      relationships = mutable.Map[String, ShardActorId](),
      actorType = properties.actorType,
      defaultTimeManagerType = properties.defaultTimeManagerType
    )

    report(
      data = Map(
        "event_type" -> "subway_created",
        "station_id" -> getEntityId,
        "subway_id" -> subway.actorId,
        "line" -> subway.line,
        "capacity" -> subway.capacity,
        "velocity" -> subway.velocity,
        "stop_time" -> subway.stopTime,
        "route_length" -> route.size,
        "number_of_stations" -> subwayStations.size,
        "tick" -> currentTick
      ),
      label = "subway_created"
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
        routeQueue.foreach {
          routeEntry =>
            route.enqueue((routeEntry.railLinkId, routeEntry.stationNode.nodeId))
        }
        logDebug(s"Built route for line $line: ${route.size} segments using RAIL LINKS")
      case None =>
        logWarn(s"No route found for line $line")
    }

    route
  }
}
