package org.interscity.htc
package model.hybrid.actor

import core.actor.SimulationBaseActor
import org.interscity.htc.model.hybrid.entity.state.*

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.actor.ShardActorId
import org.htc.protobuf.core.entity.event.control.execution.DestructEvent
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import org.interscity.htc.core.types.Tick
import org.interscity.htc.core.util.SimulationUtil
import org.interscity.htc.model.hybrid.entity.event.data.bus.LineNotOperationalData
import org.interscity.htc.model.hybrid.entity.state.enumeration.BusStationStateEnum.{ Finish, Start, Working, WorkingWithOutBus }
import org.interscity.htc.model.hybrid.entity.state.model.{ BusInformation, SubRoutePair }
import org.interscity.htc.core.metrics.model.hybrid.BusStationMetrics
import org.interscity.htc.model.hybrid.support.busstation.{ BusStationBusCreator, BusStationRouteCalculator }
import scala.collection.mutable
import core.util.StringPool

class BusStation(
  protected val properties: Properties
) extends SimulationBaseActor[BusStationState](
      properties = properties
    ) {

  override protected def internStateStrings(s: BusStationState): BusStationState =
    s.copy(
      name        = StringPool.intern(s.name),
      origin      = StringPool.intern(s.origin),
      destination = StringPool.intern(s.destination)
    )

  private lazy val simulationEnd: Tick = SimulationUtil.loadSimulationConfig().duration
  private var pendingSpawnTick: Option[Tick] = None

  private lazy val routeCalculator = new BusStationRouteCalculator(
    getStateFn      = () => state,
    entityIdFn      = () => getEntityId,
    currentTickFn   = () => currentTick,
    getBlockingEcFn = () => context.system.dispatchers.lookup("pekko.actor.default-blocking-io-dispatcher"),
    logInfoFn       = logInfo,
    logWarnFn       = logWarn,
    logDebugFn      = logDebug
  )

  private lazy val busCreator = new BusStationBusCreator(
    getStateFn          = () => state,
    entityIdFn          = () => getEntityId,
    currentTickFn       = () => currentTick,
    reportFn            = (data, label) => report(data, label),
    spawnDynamicActorFn = (ct, eid, sd) => spawnDynamicActor(classType = ct, entityId = eid, stateData = sd),
    routeCalculator     = routeCalculator,
    logInfoFn           = logInfo,
    logWarnFn           = logWarn,
    logDebugFn          = logDebug
  )

  override protected def onDynamicActorInitialized(entityId: String, classType: String): Unit = {
    val tick = pendingSpawnTick
    pendingSpawnTick = None
    onFinishSpontaneous(tick)
  }

  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    if (currentTick >= simulationEnd) {
      logInfo(
        s"BusStation ${getEntityId} reached simulation end tick=$simulationEnd, stopping scheduling"
      )
      onFinishSpontaneous(None)
    } else
      state.status match {
        case Start =>
          if (!routeCalculator.isCalculateRoutingComplete) {
            routeCalculator.calculateRoutes()
          }
          dispatchFirstBus()
        case Working =>
          if (state.buses.nonEmpty) {
            val bus = state.buses.dequeue()
            try {
              busCreator.createBus(bus)
              dependencies(bus.actorId) = ShardActorId(
                entityId = bus.actorId,
                classType = classOf[Bus].getName
              )
              pendingSpawnTick = Some(currentTick + state.interval)
              deferFinishSpontaneous()  // finish arrives via onDynamicActorInitialized
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
          onFinishSpontaneous(None)
        case _ =>
          logWarn(s"Event current status not handled ${state.status}")
          onFinishSpontaneous(None)
      }
  }

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case _ =>
        logWarn("Event not handled")
    }

  private def dispatchFirstBus(): Unit = {
    if (routeCalculator.isCalculateRoutingComplete) {
      if (state.buses.nonEmpty) {
        val bus = state.buses.dequeue()
        try {
          busCreator.createBus(bus)
          dependencies(bus.actorId) = ShardActorId(
            entityId = bus.actorId,
            classType = classOf[Bus].getName
          )
          state.status = Working
          pendingSpawnTick = Some(currentTick + state.interval)
          deferFinishSpontaneous()  // finish arrives via onDynamicActorInitialized
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
    } else if (routeCalculator.hasAnyRouteSegment) {
      def badSegments(
        route: Option[mutable.Map[SubRoutePair, mutable.Queue[(Identify, Identify)]]],
        stops: List[String]
      ): String =
        stops.sliding(2).filterNot { pair =>
          route.exists(_.get(SubRoutePair(pair.head, pair.last)).exists(_.nonEmpty))
        }.map(p => s"${p.head}\u2192${p.last}").mkString(", ")

      val missingGoing     = badSegments(state.goingRoute,     routeCalculator.orderedBusStopIds)
      val missingReturning = badSegments(state.returningRoute, routeCalculator.orderedBusStopIds.reverse)
      logWarn(
        s"BusStation ${getEntityId} route calculation partial — creating buses with available segments. " +
          s"Missing/unreachable going: [$missingGoing]. Missing/unreachable returning: [$missingReturning]."
      )

      def skippedDestinations(
        route: Option[mutable.Map[SubRoutePair, mutable.Queue[(Identify, Identify)]]],
        stops: List[String]
      ): Set[String] =
        stops.sliding(2).filterNot { pair =>
          route.exists(_.get(SubRoutePair(pair.head, pair.last)).exists(_.nonEmpty))
        }.map(_.last).toSet

      val skippedGoing     = skippedDestinations(state.goingRoute,     routeCalculator.orderedBusStopIds)
      val skippedReturning = skippedDestinations(state.returningRoute, routeCalculator.orderedBusStopIds.reverse)
      val fullySkipped     = skippedGoing & skippedReturning

      if (fullySkipped.nonEmpty) {
        val lineLabel = state.buses.headOption.flatMap(b => Option(b.label)).getOrElse(state.name)
        logWarn(
          s"BusStation ${getEntityId} partial route — ${fullySkipped.size} stop(s) unreachable in " +
            s"both directions; sending LineNotOperational: ${fullySkipped.mkString(", ")}"
        )
        fullySkipped.foreach { stopId =>
          sendMessageTo(
            entityId  = stopId,
            shardId   = classOf[BusStop].getName,
            data      = LineNotOperationalData(label = lineLabel),
            eventType = "LineNotOperational"
          )
        }
      }

      if (state.buses.nonEmpty) {
        val bus = state.buses.dequeue()
        try {
          busCreator.createBus(bus)
          dependencies(bus.actorId) = ShardActorId(
            entityId = bus.actorId,
            classType = classOf[Bus].getName
          )
          state.status = Working
          pendingSpawnTick = Some(currentTick + state.interval)
          deferFinishSpontaneous()  // finish arrives via onDynamicActorInitialized
        } catch {
          case e: IllegalStateException =>
            logWarn(s"Skipping bus ${bus.actorId} — route unavailable even with partial: ${e.getMessage}")
            BusStationMetrics.busesSkippedNoRoute.labels(state.name).inc()
            state.status = if (state.buses.nonEmpty) Working else WorkingWithOutBus
            onFinishSpontaneous(Some(currentTick + state.interval))
          case e: Exception =>
            logError(s"Unexpected error creating bus ${bus.actorId} with partial route: ${e.getMessage}")
            state.status = if (state.buses.nonEmpty) Working else WorkingWithOutBus
            onFinishSpontaneous(Some(currentTick + state.interval))
        }
      } else {
        state.status = WorkingWithOutBus
        onFinishSpontaneous(None)
      }
    } else {
      logWarn(
        s"BusStation ${getEntityId} route calculation produced no usable segments — no buses will be created."
      )
      state.status = WorkingWithOutBus
      val lineLabel = state.buses.headOption.flatMap(b => Option(b.label)).getOrElse(state.name)
      state.busStops.keys.foreach { stopId =>
        sendMessageTo(
          entityId  = stopId,
          shardId   = classOf[BusStop].getName,
          data      = LineNotOperationalData(label = lineLabel),
          eventType = "LineNotOperational"
        )
      }
      onFinishSpontaneous(None)
    }
  }

  override def onDestruct(event: DestructEvent): Unit =
    state.status = Finish
}

