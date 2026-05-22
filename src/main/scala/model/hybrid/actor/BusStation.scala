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
import org.interscity.htc.model.hybrid.entity.state.enumeration.BusStationStateEnum.{ Finish, Start, Working, WorkingWithOutBus }
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum
import org.interscity.htc.model.hybrid.entity.state.model.{ BusInformation, SubRoutePair }
import org.interscity.htc.model.hybrid.util.GPSUtil
import org.interscity.htc.core.metrics.model.hybrid.BusStationMetrics

import scala.collection.mutable
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.*

class BusStation(
  protected val properties: Properties
) extends SimulationBaseActor[BusStationState](
      properties = properties
    ) {

  private lazy val simulationEnd: Tick = SimulationUtil.loadSimulationConfig().duration

  /** Set to true once calculateRoutes() has been attempted (even if some segments are unreachable).
    * Prevents a redundant blocking recalculation at tick 0 for stations where PLR already
    * confirmed that certain bus-stop pairs are unreachable in the road network.
    */
  private var routeCalculationAttempted = false

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

  override def requiresPostLoadRegistration: Boolean = true

  override def handlePostLoadRegistration(): Unit = {
    if (isCalculateRoutingComplete) {
      logDebug(s"BusStation ${getEntityId} routes already calculated, skipping PLR retry")
    } else {
      logInfo(s"BusStation ${getEntityId} pre-calculating routes during post-load registration")
      calculateRoutes()
    }
  }

  /** Shared helper: builds a list of Futures that each calculate one sub-route segment.
    * Runs on the supplied (blocking) ExecutionContext. Pure reads — no actor state written.
    */
  private def mkRouteFutures(stops: List[String], going: Boolean)(implicit
    ec: scala.concurrent.ExecutionContext
  ): List[Future[Option[(SubRoutePair, mutable.Queue[(Identify, Identify)])]]] =
    stops.sliding(2).toList.map { pair =>
      val originStop = pair.head
      val destStop   = pair.last
      (state.busStops.get(originStop), state.busStops.get(destStop)) match {
        case (Some(originNode), Some(destNode)) =>
          Future {
            GPSUtil.calcRouteCompact(originId = originNode, destinationId = destNode) match {
              case Some((_, pathQueue)) =>
                val identifyPath = pathQueue.map { case (f, t) => (Identify(id = f), Identify(id = t)) }
                Some(SubRoutePair(originStop, destStop) -> identifyPath)
              case None =>
                logWarn(s"No route found: $originStop -> $destStop (going=$going)")
                None
            }
          }
        case (originOpt, destOpt) =>
          if (originOpt.isEmpty) logWarn(s"Origin bus stop $originStop has no node mapping")
          if (destOpt.isEmpty)   logWarn(s"Destination bus stop $destStop has no node mapping")
          Future.successful(None)
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
          // Routes should already be pre-calculated via handlePostLoadRegistration().
          // If not (e.g. running without PLR phase), calculate now.
          // Guard: skip recalculation if it was already attempted during PLR — unreachable
          // stop pairs won't become reachable at tick 0 (road network is static), and
          // blocking again would stall the dispatcher for up to 30 minutes per station.
          if (!isCalculateRoutingComplete && !routeCalculationAttempted) {
            logInfo(s"BusStation ${getEntityId} routes not pre-calculated, calculating now (may block)")
            calculateRoutes()
          }
          dispatchFirstBus()
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
                logWarn(s"Skipping bus ${bus.actorId} — route unavailable: ${e.getMessage}")
                BusStationMetrics.busesSkippedNoRoute.labels(state.name).inc()
                onFinishSpontaneous(Some(currentTick + state.interval))
              case e: Exception =>
                logError(s"Unexpected error creating bus ${bus.actorId}: ${e.getMessage}")
                onFinishSpontaneous(Some(currentTick + state.interval))
            }
          } else {
            state.status = WorkingWithOutBus
            onFinishSpontaneous(Some(currentTick + state.interval))
          }
        case WorkingWithOutBus =>
          // All buses have been created (or route was incomplete). Nothing more to schedule.
          onFinishSpontaneous(None)
        case _ =>
          logWarn(s"Event current status not handled ${state.status}")
          onFinishSpontaneous(None)
      }

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case _ =>
        logWarn("Event not handled")
    }

  /** Dispatch the first bus after routes have been calculated. Transitions state to Working or
    * WorkingWithOutBus and calls onFinishSpontaneous accordingly.
    */
  private def dispatchFirstBus(): Unit = {
    if (isCalculateRoutingComplete) {
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
            logWarn(s"Skipping bus ${bus.actorId} — route unavailable: ${e.getMessage}")
            BusStationMetrics.busesSkippedNoRoute.labels(state.name).inc()
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
      // Report segments that are either absent from the map OR present but empty
      // (SP returned no path — unreachable bus-stop pair in the road network).
      def badSegments(
        route: Option[mutable.Map[SubRoutePair, mutable.Queue[(Identify, Identify)]]],
        stops: List[String]
      ): String =
        stops.sliding(2).filterNot { pair =>
          route.exists(_.get(SubRoutePair(pair.head, pair.last)).exists(_.nonEmpty))
        }.map(p => s"${p.head}\u2192${p.last}").mkString(", ")

      val missingGoing     = badSegments(state.goingRoute,     orderedBusStopIds)
      val missingReturning = badSegments(state.returningRoute, orderedBusStopIds.reverse)
      logWarn(
        s"BusStation ${getEntityId} route calculation incomplete — no buses will be created. " +
          s"Missing/unreachable going: [$missingGoing]. Missing/unreachable returning: [$missingReturning]."
      )
      state.status = WorkingWithOutBus
      onFinishSpontaneous(None)
    }
  }

  /** Calculate all bus route segments in parallel.
    *
    * Going and returning segments are all independent — fired as concurrent Futures on the blocking
    * IO dispatcher. Wrapped in scala.concurrent.blocking so the ForkJoinPool spawns compensation
    * threads instead of starving when many BusStations calculate simultaneously. With a warm
    * CompactGraph + ALT index each segment completes in ~50ms, so the total wall-clock time is
    * O(max_segment_time) rather than O(N × avg_segment_time).
    */
  private def calculateRoutes(): Unit = {
    logDebug(s"BusStation ${getEntityId} calculating routes in parallel")

    implicit val blockingEc: scala.concurrent.ExecutionContext =
      context.system.dispatchers.lookup("pekko.actor.default-blocking-io-dispatcher")

    val goingFutures  = mkRouteFutures(orderedBusStopIds, going = true)
    val returnFutures = mkRouteFutures(orderedBusStopIds.reverse, going = false)

    val (goingResults, returnResults) = scala.concurrent.blocking {
      (
        Await.result(Future.sequence(goingFutures), 30.minutes).flatten,
        Await.result(Future.sequence(returnFutures), 30.minutes).flatten
      )
    }

    goingResults.foreach  { case (pair, path) => state.goingRoute.foreach(_.put(pair, path))     }
    returnResults.foreach { case (pair, path) => state.returningRoute.foreach(_.put(pair, path)) }

    routeCalculationAttempted = true

    if (isCalculateRoutingComplete)
      logInfo(
        s"BusStation ${getEntityId} route calculation complete " +
          s"(${orderedBusStopIds.size - 1} going + ${orderedBusStopIds.size - 1} returning segments)"
      )
    else
      logWarn(s"BusStation ${getEntityId} route calculation incomplete after calculateRoutes()")
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
      data = toJson {
        val busState = BusState(
          startTick = busStartTick,
          busStops = state.busStops.toMap,
          capacity = bus.capacity,
          size = bus.size,
          origin = state.origin,
          destination = state.destination,
          numberOfPorts = bus.numberOfPorts,
          label = bus.label,
          storedBestRoute = Some(route.toList),
          speedFactor = bus.speedFactor
        )
        busState.bestRoute = Some(route.clone())
        busState.status = MovableStatusEnum.Start
        busState
      },
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

    BusStationMetrics.busesCreated.labels(bus.label).inc()

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
          val actualSize   = r.keys.size
          // A segment present in the map but with an empty path means SP found no route
          // between those bus stops. Treat that as incomplete so we don't attempt to spawn
          // a bus with an empty route and hit an IllegalStateException.
          val allNonEmpty  = r.values.forall(_.nonEmpty)
          logDebug(s"Route completion check: actual=$actualSize, expected=$expectedSize, allNonEmpty=$allNonEmpty")
          actualSize == expectedSize && allNonEmpty
        }
      case None => false

  override def onDestruct(event: DestructEvent): Unit =
    state.status = Finish
}
