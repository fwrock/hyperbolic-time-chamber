package org.interscity.htc
package model.hybrid.actor

import core.actor.SimulationBaseActor
import org.interscity.htc.model.hybrid.entity.state.*

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.actor.ShardActorId
import org.htc.protobuf.core.entity.event.control.execution.DestructEvent
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import org.interscity.htc.core.types.Tick
import org.interscity.htc.core.util.ActorCreatorUtil.createShardedActorSeveralArgs
import org.interscity.htc.core.util.JsonUtil.toJson
import org.interscity.htc.core.util.{ ActorCreatorUtil, JsonUtil }
import org.interscity.htc.core.util.SimulationUtil
import org.interscity.htc.model.hybrid.entity.state.{ BusState, BusStationState }
import org.interscity.htc.model.hybrid.entity.state.enumeration.BusStationStateEnum.{ Finish, Ready, RouteWaiting, Start, Working, WorkingWithOutBus }
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum
import org.interscity.htc.model.hybrid.entity.state.model.{ BusInformation, SubRoutePair }
import org.interscity.htc.model.hybrid.util.GPSUtil

import scala.collection.mutable

class BusStation(
  protected val properties: Properties
) extends SimulationBaseActor[BusStationState](
      properties = properties
    ) {

  private lazy val simulationEnd: Tick = SimulationUtil.loadSimulationConfig().duration

  /** Ordered bus stop IDs derived from their numeric suffix (fallback to lexicographic). Ensures
    * deterministic route building and lookup.
    */
  private def orderedBusStopIds: List[String] = {
    def suffixNumber(id: String): Option[Long] = {
      val digits = id.reverse.takeWhile(_.isDigit).reverse
      if (digits.nonEmpty) Some(digits.toLong) else None
    }

    state.busStops.keys.toList.sortBy {
      id =>
        suffixNumber(id) match {
          case Some(num) => (0L, num, id)
          case None      => (1L, Long.MaxValue, id)
        }
    }
  }

  override def actSpontaneous(event: SpontaneousEvent): Unit =
    if (currentTick >= simulationEnd) {
      logInfo(
        s"BusStation ${getEntityId} reached simulation end tick=$simulationEnd, stopping scheduling"
      )
      onFinishSpontaneous(None)
    } else
      state.status match {
        case Start =>
          state.status = RouteWaiting
          calculateRoutesFromMap()
        case Working =>
          if (state.buses.nonEmpty) {
            val bus = state.buses.dequeue()
            try {
              val actorRef = createBus(bus)
              val className = classOf[Bus].getName
              dependencies(bus.actorId) = ShardActorId(
                entityId = bus.actorId,
                classType = className
              )
              onFinishSpontaneous(Some(currentTick + state.interval))
            } catch {
              case e: IllegalStateException =>
                logError(s"Failed to create bus ${bus.actorId}: ${e.getMessage}")
                onFinishSpontaneous(Some(currentTick + state.interval))
              case e: Exception =>
                logError(s"Unexpected error creating bus ${bus.actorId}: ${e.getMessage}")
                onFinishSpontaneous(Some(currentTick + state.interval))
            }
          } else {
            state.status = WorkingWithOutBus
            onFinishSpontaneous(Some(currentTick + state.interval))
          }
        case _ =>
          logWarn(s"Event current status not handled ${state.status}")
          onFinishSpontaneous(None)
      }

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case _ =>
        logWarn("Event not handled")
    }

  /** Calculate all bus routes directly from the in-memory static map. This is synchronous and
    * doesn't require actor messages.
    */
  private def calculateRoutesFromMap(): Unit = {
    logDebug(s"BusStation ${getEntityId} calculating routes from in-memory map")

    val goingStops = orderedBusStopIds
    for (pair <- goingStops.sliding(2)) {
      val originBusStopId = pair.head
      val destinationBusStopId = pair.last

      state.busStops.get(originBusStopId) match {
        case Some(originNodeId) =>
          state.busStops.get(destinationBusStopId) match {
            case Some(destinationNodeId) =>
              GPSUtil.calcRoute(originId = originNodeId, destinationId = destinationNodeId) match {
                case Some((cost, pathQueue)) =>
                  val identifyPath = pathQueue.map {
                    case (from, to) =>
                      (Identify(id = from), Identify(id = to))
                  }
                  state.goingRoute.foreach {
                    routeMap =>
                      routeMap.put(
                        SubRoutePair(originBusStopId, destinationBusStopId),
                        identifyPath
                      )
                  }
                  logDebug(
                    s"Going route calculated: $originBusStopId -> $destinationBusStopId (${pathQueue.size} segments)"
                  )
                case None =>
                  logWarn(
                    s"Could not calculate going route: $originBusStopId -> $destinationBusStopId"
                  )
              }
            case None =>
              logWarn(s"Destination bus stop $destinationBusStopId has no node mapping")
          }
        case None =>
          logWarn(s"Origin bus stop $originBusStopId has no node mapping")
      }
    }

    val returningStops = orderedBusStopIds.reverse
    for (pair <- returningStops.sliding(2)) {
      val originBusStopId = pair.head
      val destinationBusStopId = pair.last

      state.busStops.get(originBusStopId) match {
        case Some(originNodeId) =>
          state.busStops.get(destinationBusStopId) match {
            case Some(destinationNodeId) =>
              GPSUtil.calcRoute(originId = originNodeId, destinationId = destinationNodeId) match {
                case Some((cost, pathQueue)) =>
                  val identifyPath = pathQueue.map {
                    case (from, to) =>
                      (Identify(id = from), Identify(id = to))
                  }
                  state.returningRoute.foreach {
                    routeMap =>
                      routeMap.put(
                        SubRoutePair(originBusStopId, destinationBusStopId),
                        identifyPath
                      )
                  }
                  logDebug(
                    s"Returning route calculated: $originBusStopId -> $destinationBusStopId (${pathQueue.size} segments)"
                  )
                case None =>
                  logWarn(
                    s"Could not calculate returning route: $originBusStopId -> $destinationBusStopId"
                  )
              }
            case None =>
              logWarn(s"Destination bus stop $destinationBusStopId has no node mapping")
          }
        case None =>
          logWarn(s"Origin bus stop $originBusStopId has no node mapping")
      }
    }

    state.goingRoute.foreach {
      routeMap =>
        logDebug(s"Going route keys: ${routeMap.keys.mkString(", ")}")
    }
    state.returningRoute.foreach {
      routeMap =>
        logDebug(s"Returning route keys: ${routeMap.keys.mkString(", ")}")
    }

    if (isCalculateRoutingComplete) {
      logDebug(s"BusStation ${getEntityId} route calculation complete")
      state.status = Ready
      if (state.buses.nonEmpty) {
        val bus = state.buses.dequeue()
        try {
          val actorRef = createBus(bus)
          val className = classOf[Bus].getName
          dependencies(bus.actorId) = ShardActorId(
            entityId = bus.actorId,
            classType = className
          )
          state.status = Working
          onFinishSpontaneous(Some(currentTick + state.interval))
        } catch {
          case e: IllegalStateException =>
            logError(s"Failed to create bus ${bus.actorId}: ${e.getMessage}")
            state.status = if (state.buses.nonEmpty) Working else WorkingWithOutBus
            onFinishSpontaneous(Some(currentTick + state.interval))
          case e: Exception =>
            logError(s"Unexpected error creating bus ${bus.actorId}: ${e.getMessage}")
            state.status = if (state.buses.nonEmpty) Working else WorkingWithOutBus
            onFinishSpontaneous(Some(currentTick + state.interval))
        }
      } else {
        logDebug("No buses to create, entering WorkingWithOutBus state")
        state.status = WorkingWithOutBus
        onFinishSpontaneous(None)
      }
    } else {
      logWarn(s"BusStation ${getEntityId} route calculation incomplete, cannot create buses")
      state.status = WorkingWithOutBus
      onFinishSpontaneous(None)
    }
  }

  private def createBus(bus: BusInformation): ActorRef = {
    val route = calcBusBestRoute()
    if (route.isEmpty) {
      logWarn(s"Cannot create bus ${bus.actorId} - no valid route available")
      throw new IllegalStateException(s"No route available for bus ${bus.actorId}")
    }

    val busStartTick = currentTick + 1
    val busProperties = Properties(
      entityId = bus.actorId,
      resourceId = properties.resourceId,
      timeManagers = timeManagers,
      creatorManager = creatorManager,
      reporters = properties.reporters,
      data = toJson({
        val busState = BusState(
          startTick = busStartTick,
          busStops = state.busStops.toMap,
          capacity = bus.capacity,
          size = bus.size,
          origin = state.origin,
          destination = state.destination,
          numberOfPorts = bus.numberOfPorts,
          label = bus.label,
          storedBestRoute = Some(route.toList)
        )
        busState.bestRoute = Some(route.clone())
        busState.status = MovableStatusEnum.Start
        busState
      }),
      relationships = mutable.Map[String, ShardActorId](),
      actorType = properties.actorType,
      defaultTimeManagerType = properties.defaultTimeManagerType
    )

    logDebug(s"Creating bus ${bus.actorId} at tick $busStartTick (route size=${route.size})")

    report(
      data = Map(
        "event_type" -> "bus_created",
        "station_id" -> getEntityId,
        "bus_id" -> bus.actorId,
        "capacity" -> bus.capacity,
        "route_length" -> route.size,
        "number_of_ports" -> bus.numberOfPorts,
        "label" -> bus.label,
        "start_tick" -> busStartTick,
        "tick" -> currentTick
      ),
      label = "bus_created"
    )

    createShardedActorSeveralArgs(
      system = context.system,
      actorClass = classOf[Bus],
      entityId = bus.actorId,
      busProperties
    )
  }

  private def calcBusBestRoute(): mutable.Queue[(String, String)] = {
    val bestRoute = mutable.Queue[(String, String)]()

    if (state.goingRoute.isDefined && state.goingRoute.get.nonEmpty) {
      val goingRouteData = getTotalRoute(state.goingRoute.get, orderedBusStopIds)
      bestRoute ++= goingRouteData.map(
        pair => (pair._1.id, pair._2.id)
      )
    } else {
      logWarn("No going route defined for bus station")
    }

    if (state.returningRoute.isDefined && state.returningRoute.get.nonEmpty) {
      val returningRouteData = getTotalRoute(state.returningRoute.get, orderedBusStopIds.reverse)
      bestRoute ++= returningRouteData.map(
        pair => (pair._1.id, pair._2.id)
      )
    } else {
      logWarn("No returning route defined for bus station")
    }

    if (bestRoute.isEmpty) {
      logWarn(
        "Bus route calculation resulted in empty route - this may cause buses to terminate immediately"
      )
    }

    bestRoute
  }

  private def getTotalRoute(
    route: mutable.Map[SubRoutePair, mutable.Queue[(Identify, Identify)]],
    orderedStops: List[String]
  ): mutable.Queue[(Identify, Identify)] = {
    val totalRoute = mutable.Queue[(Identify, Identify)]()
    for (pair <- orderedStops.sliding(2)) {
      val key = SubRoutePair(pair.head, pair.last)
      route.get(key) match {
        case Some(pathPart) =>
          totalRoute ++= pathPart
          logDebug(s"Added route segment: ${pair.head} -> ${pair.last} (${pathPart.size} links)")
        case None =>
          logWarn(s"Missing route segment: ${pair.head} -> ${pair.last}")
          logWarn(s"Available route keys: ${route.keys.mkString(", ")}")
      }
    }
    totalRoute
  }

  private def isCalculateRoutingComplete: Boolean =
    isCalculateRoutingComplete(state.goingRoute) &&
      isCalculateRoutingComplete(state.returningRoute)

  private def isCalculateRoutingComplete(
    route: Option[mutable.Map[SubRoutePair, mutable.Queue[(Identify, Identify)]]]
  ): Boolean =
    route match
      case Some(r) =>
        if (state.busStops.size <= 1) {
          false
        } else {
          val expectedSize = state.busStops.size - 1
          val actualSize = r.keys.size
          logDebug(s"Route completion check: actual=$actualSize, expected=$expectedSize")
          actualSize == expectedSize
        }
      case None => false

  override def onDestruct(event: DestructEvent): Unit =
    state.status = Finish
}
