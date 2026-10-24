package org.interscity.htc
package model.hybrid.support.link

import core.types.Tick
import org.interscity.htc.model.hybrid.entity.state.LinkState
import org.interscity.htc.model.hybrid.entity.state.model.DynamicLinkCost
import org.interscity.htc.model.hybrid.util.DynamicWeightCache
import org.interscity.htc.core.metrics.model.hybrid.LinkMetrics

import scala.util.Try

/** Counters belong to checkpointed LinkState so replay cannot count an entry twice. */
class LinkMetricsReporter(
  reportFn:                   (Map[String, Any], String) => Unit,
  entityIdFn:                 () => String,
  currentTickFn:              () => Tick,
  cacheTtl:                   Int,
  costPublishInterval:        Int,
  getLinkStateFn:             () => LinkState,
  getVehicleEntryTickFn:      Long => Option[Tick],
  getVehicleWaitingSecondsFn: Long => Double,
  getRegisteredVehicleIdsFn:  () => Iterable[Long],
  logWarnFn:                  String => Unit,
  publishCostFn:              (DynamicLinkCost, Int) => Try[Unit] =
    (cost, ttl) => DynamicWeightCache.publishCost(cost, ttl)
) {

  private def metrics = getLinkStateFn().metrics
  private def lastCostPublishTick: Tick = metrics.lastCostPublishTick
  private def lastCostPublishTick_=(value: Tick): Unit = metrics.lastCostPublishTick = value
  private def summaryTick: Tick = metrics.summaryTick
  private def summaryTick_=(value: Tick): Unit = metrics.summaryTick = value
  private def tickLoaded: Int = metrics.tickLoaded
  private def tickLoaded_=(value: Int): Unit = metrics.tickLoaded = value
  def tickInserted: Int = metrics.tickInserted
  def tickInserted_=(value: Int): Unit = metrics.tickInserted = value
  def tickArrived: Int = metrics.tickArrived
  def tickArrived_=(value: Int): Unit = metrics.tickArrived = value
  def tickTravelTimeSum: Double = metrics.tickTravelTimeSum
  def tickTravelTimeSum_=(value: Double): Unit = metrics.tickTravelTimeSum = value
  def tickProcessingDurationMs: Long = metrics.tickProcessingDurationMs
  def tickProcessingDurationMs_=(value: Long): Unit = metrics.tickProcessingDurationMs = value
  def cumulativeLoadedVehicles: Long = metrics.cumulativeLoadedVehicles
  def cumulativeLoadedVehicles_=(value: Long): Unit = metrics.cumulativeLoadedVehicles = value
  private def cumulativeLoaded: Long = metrics.cumulativeLoaded
  private def cumulativeLoaded_=(value: Long): Unit = metrics.cumulativeLoaded = value
  private def cumulativeArrived: Long = metrics.cumulativeArrived
  private def cumulativeArrived_=(value: Long): Unit = metrics.cumulativeArrived = value
  private def cumulativeDiscarded: Long = metrics.cumulativeDiscarded
  private def cumulativeDiscarded_=(value: Long): Unit = metrics.cumulativeDiscarded = value

  def ensureSummaryTick(tick: Tick): Unit =
    if (summaryTick != tick) {
      summaryTick            = tick
      tickLoaded             = 0
      tickInserted           = 0
      tickArrived            = 0
      tickTravelTimeSum      = 0.0
      tickProcessingDurationMs = 0L
    }

  /** Called once per vehicle entry. Increments all load/insert counters and Prometheus gauges. */
  def onVehicleLoaded(isMicro: Boolean): Unit = {
    tickLoaded             += 1
    cumulativeLoadedVehicles += 1
    tickInserted           += 1
    cumulativeLoaded       += 1
    val mode = if (isMicro) "MICRO" else "MESO"
    LinkMetrics.vehiclesEntered.labels(mode).inc()
    if (isMicro) LinkMetrics.vehiclesActive.inc()
  }

  /** Called once per vehicle exit. Increments arrival counters and Prometheus gauges. */
  def onVehicleArrived(isMicro: Boolean, travelTime: Double): Unit = {
    tickArrived     += 1
    cumulativeArrived += 1
    val mode = if (isMicro) "MICRO" else "MESO"
    LinkMetrics.vehiclesExited.labels(mode).inc()
    if (isMicro) LinkMetrics.vehiclesActive.dec()
    if (travelTime > 0) {
      tickTravelTimeSum += travelTime
      LinkMetrics.travelTimeTicks.labels(mode).observe(travelTime)
    }
  }

  def emitSumoSummaryStep(tick: Tick): Unit = {
    val state   = getLinkStateFn()
    val secondsPerTick = if (state.isMicroMode) state.microTimeStep * state.microTicksPerGlobalTick else 1.0
    val running = state.totalVehicles
    val halting = if (state.isMicroMode)
      state.vehiclesByLane.valuesIterator.map(_.count(_.velocity <= 0.1)).sum
    else 0
    val meanSpeed = if (state.isMicroMode) {
      val all = state.vehiclesByLane.valuesIterator.flatMap(_.iterator).toVector
      if (all.nonEmpty) all.map(_.velocity).sum / all.size else 0.0
    } else state.currentSpeed
    val meanSpeedRelative = if (state.freeSpeed > 0) meanSpeed / state.freeSpeed else 0.0
    val vehicleIds        = getRegisteredVehicleIdsFn().toVector
    val meanTravelTime =
      if (vehicleIds.nonEmpty)
        vehicleIds.map(id => math.max(0L, tick - getVehicleEntryTickFn(id).getOrElse(tick)).toDouble * secondsPerTick).sum / vehicleIds.size
      else 0.0
    val meanWaitingTime =
      if (vehicleIds.nonEmpty)
        vehicleIds.map(id => getVehicleWaitingSecondsFn(id)).sum / vehicleIds.size
      else 0.0
    val waiting = math.max(0L, cumulativeLoadedVehicles - cumulativeLoaded).toInt
    reportFn(
      Map(
        "event_type"      -> "sumo_summary_step",
        "scope"           -> "link",
        "link_id"         -> entityIdFn(),
        "mode"            -> state.simulationMode.toString,
        "time"            -> (tick.toDouble * secondsPerTick),
        "loaded"          -> cumulativeLoadedVehicles,
        "inserted"        -> tickInserted,
        "running"         -> running,
        "waiting"         -> waiting,
        "ended"           -> cumulativeArrived,
        "arrived"         -> tickArrived,
        "collisions"      -> 0,
        "teleports"       -> 0,
        "halting"         -> halting,
        "stopped"         -> 0,
        "meanWaitingTime" -> meanWaitingTime,
        "meanTravelTime"  -> meanTravelTime,
        "meanSpeed"       -> meanSpeed,
        "meanSpeedRelative" -> meanSpeedRelative,
        "discarded"       -> cumulativeDiscarded,
        "duration"        -> tickProcessingDurationMs,
        "tick"            -> tick
      ),
      "sumo_summary_step"
    )
  }

  /** Publishes the dynamic cost only if `costPublishInterval` ticks have elapsed since the last
    * publish. Shared by both MESO (called on vehicle enter/leave) and MICRO (called every global
    * tick) link dynamics, so neither mode floods Redis/Kafka with a publish per event.
    */
  def maybePublishDynamicCost(tick: Tick): Unit =
    if (tick - lastCostPublishTick >= costPublishInterval) {
      publishDynamicCost()
      lastCostPublishTick = tick
    }

  def publishDynamicCost(): Unit = {
    val state       = getLinkStateFn()
    val freeFlowSpeed = state.freeSpeed / 3.6
    // MICRO velocity is m/s; MESO speed is km/h. An empty link has no stopped traffic.
    val routingSpeed = if (state.isMicroMode) {
      if (state.totalVehiclesInMicro == 0) freeFlowSpeed else state.currentSpeed
    } else state.currentSpeed / 3.6
    val dynamicCost = DynamicLinkCost.fromLinkState(
      linkId          = entityIdFn().toLong,
      length          = state.length,
      currentSpeed    = routingSpeed,
      freeFlowSpeed   = freeFlowSpeed,
      vehicleCount    = state.registered.size,
      capacity        = state.capacity,
      congestionFactor = state.congestionFactor,
      tick            = currentTickFn()
    )
    publishCostFn(dynamicCost, cacheTtl) match {
      case scala.util.Success(_) =>
      case scala.util.Failure(e) =>
        logWarnFn(s"Failed to publish dynamic cost to Kafka: ${e.getMessage}")
    }
  }
}
