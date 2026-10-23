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
import org.interscity.htc.core.enumeration.TimeManagerTypeEnum
import scala.collection.mutable
import core.util.StringPool

class BusStation(
  protected val properties: Properties
) extends SimulationBaseActor[BusStationState](
      properties = properties
    ) {

  override protected def validateRuntimeCapabilities(): Unit =
    org.interscity.htc.model.hybrid.util.HybridRuntimeCapabilities.requireAccountedBirth(getCurrentTimeManagerType)

  override protected def internStateStrings(s: BusStationState): BusStationState =
    s.copy(
      name        = StringPool.intern(s.name),
      origin      = s.origin,
      destination = s.destination
    )

  private lazy val simulationEnd: Tick = SimulationUtil.loadSimulationConfig().duration
  private var pendingSpawnTick: Option[Tick] = None

  private var awaitingDynamicInit: Boolean = false
  private val dynamicInitPollIntervalTicks: Tick = 1L

  private case class PendingBusSpawn(creationTick: Tick, bus: BusInformation, stateJson: String)
  private var pendingBusSpawn: Option[PendingBusSpawn] = None

  private def isTimeWarp: Boolean = getCurrentTimeManagerType == TimeManagerTypeEnum.TIME_WARP

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

  override protected def onDynamicActorInitialized(entityId: String, classType: String): Unit =
    awaitingDynamicInit = false

  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    if (currentTick >= simulationEnd) {
      logInfo(
        s"BusStation ${getEntityId} reached simulation end tick=$simulationEnd, stopping scheduling"
      )
      onFinishSpontaneous(None)
    } else if (awaitingDynamicInit) {
      onFinishSpontaneous(Some(currentTick + dynamicInitPollIntervalTicks))
    } else if (pendingBusSpawn.isDefined && event.gvt.exists(_ >= pendingBusSpawn.get.creationTick)) {
      firePendingBusSpawn()
      onFinishSpontaneous(Some(currentTick + dynamicInitPollIntervalTicks))
    } else if (pendingBusSpawn.isDefined) {
      onFinishSpontaneous(Some(currentTick + dynamicInitPollIntervalTicks))
    } else if (pendingSpawnTick.exists(currentTick < _)) {
      onFinishSpontaneous(pendingSpawnTick)
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
              createOrStashBus(bus)
              if (!isTimeWarp) {
                pendingSpawnTick = Some(currentTick + state.interval)
                awaitingDynamicInit = true
              }
              onFinishSpontaneous(Some(currentTick + dynamicInitPollIntervalTicks))
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
          createOrStashBus(bus)
          state.status = Working
          if (!isTimeWarp) {
            pendingSpawnTick = Some(currentTick + state.interval)
            awaitingDynamicInit = true
          }
          onFinishSpontaneous(Some(currentTick + dynamicInitPollIntervalTicks))
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
        stops: List[Long]
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
        stops: List[Long]
      ): Set[Long] =
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
          createOrStashBus(bus)
          state.status = Working
          if (!isTimeWarp) {
            pendingSpawnTick = Some(currentTick + state.interval)
            awaitingDynamicInit = true
          }
          onFinishSpontaneous(Some(currentTick + dynamicInitPollIntervalTicks))
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

  /** Prepares the bus eagerly (route validation + state build + `report()`, all pure/GVT-buffer
    * safe) in both modes, but only performs the irrevocable `spawnDynamicActor` immediately under
    * conservative (non-Time-Warp) execution. Under Time Warp the prepared bus is stashed in
    * [[pendingBusSpawn]] and only spawned once [[firePendingBusSpawn]] fires, gated on
    * `event.gvt` passing the dequeue tick — see `docs/TIME_WARP_DESIGN.md`'s dynamic-actor-spawn
    * entry for why this is safe.
    */
  private def createOrStashBus(bus: BusInformation): Unit =
    if (isTimeWarp) {
      val stateJson = busCreator.prepareBus(bus)
      pendingBusSpawn = Some(PendingBusSpawn(currentTick, bus, stateJson))
    } else {
      busCreator.createBus(bus)
      dependencies(bus.actorId.toString) = ShardActorId(
        entityId = bus.actorId,
        classType = classOf[Bus].getName
      )
    }

  private def firePendingBusSpawn(): Unit =
    pendingBusSpawn.foreach {
      p =>
        busCreator.spawnPrepared(p.bus, p.stateJson)
        dependencies(p.bus.actorId.toString) = ShardActorId(
          entityId = p.bus.actorId,
          classType = classOf[Bus].getName
        )
        pendingSpawnTick = Some(currentTick + state.interval)
        awaitingDynamicInit = true
        pendingBusSpawn = None
    }

  /** GVT monotonicity (`docs/TIME_WARP_DESIGN.md` §3: GVT never decreases and no rollback can
    * ever reach a tick at or below it again) guarantees that once a rollback target is at or
    * before a stashed bus's dequeue tick, that dequeue never happened from the restored state's
    * perspective — nothing external was ever created for it, so the stash entry is simply
    * dropped rather than replayed.
    */
  override protected def onRollbackDiscardPending(targetTick: Tick): Unit =
    pendingBusSpawn = pendingBusSpawn.filter(_.creationTick < targetTick)

  override def onDestruct(event: DestructEvent): Unit =
    state.status = Finish
}

