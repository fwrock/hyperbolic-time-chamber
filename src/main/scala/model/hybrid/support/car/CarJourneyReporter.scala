package org.interscity.htc
package model.hybrid.support.car

import core.types.Tick
import core.actor.trace.ActorTrace
import org.interscity.htc.core.metrics.model.hybrid.MovableMetrics
import org.interscity.htc.model.hybrid.entity.state.{CarJourneyState, CarState, DriverAttributes}
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.Finished

import scala.collection.mutable

/** Tracks journey metrics and SUMO-compatible trip statistics for a Car actor.
  *
  * Reads journey data from checkpointed model state through a callback, so a restored actor
  * cannot retain physics inputs or completion guards from its discarded future.
  *
  * @param reportFn       Actor report callback
  * @param entityIdFn     Supplier for the car's entity ID
  * @param currentTickFn  Supplier for the current simulation tick
  * @param tripOriginFn   Supplier for the active trip origin (PrivateVehicle)
  * @param tripDestFn     Supplier for the active trip destination
  * @param tripStartTickFn Supplier for the trip start tick
  * @param driverAttrsFn  Supplier for driver attributes (speed factor, etc.)
  */
class CarJourneyReporter(
  private val reportFn: (Map[String, Any], String) => Unit,
  private val entityIdFn: () => String,
  private val currentTickFn: () => Tick,
  private val tripOriginFn: () => Option[Long],
  private val tripDestFn: () => Option[Long],
  private val tripStartTickFn: () => Option[Tick],
  private val driverAttrsFn: () => DriverAttributes,
  private val journeyStateFn: () => CarJourneyState,
  private val tripStartDistanceFn: () => Double = () => 0.0
) {

  private def journeyFinishedReported: Boolean = journeyStateFn().journeyFinishedReported
  private def journeyFinishedReported_=(value: Boolean): Unit = journeyStateFn().journeyFinishedReported = value

  def sumoDepartTick: Option[Tick] = journeyStateFn().sumoDepartTick
  def secondsPerTick: Double = journeyStateFn().secondsPerTick
  def sumoDepartTick_=(value: Option[Tick]): Unit = journeyStateFn().sumoDepartTick = value

  def sumoDepartSpeed: Double = journeyStateFn().sumoDepartSpeed
  def sumoDepartSpeed_=(value: Double): Unit = journeyStateFn().sumoDepartSpeed = value

  def sumoArrivalSpeed: Double = journeyStateFn().sumoArrivalSpeed
  def sumoArrivalSpeed_=(value: Double): Unit = journeyStateFn().sumoArrivalSpeed = value

  def sumoDepartLane: Option[String] = journeyStateFn().sumoDepartLane
  def sumoDepartLane_=(value: Option[String]): Unit = journeyStateFn().sumoDepartLane = value

  def sumoDepartPos: Double = journeyStateFn().sumoDepartPos
  def sumoDepartPos_=(value: Double): Unit = journeyStateFn().sumoDepartPos = value

  def sumoArrivalLane: Option[String] = journeyStateFn().sumoArrivalLane
  def sumoArrivalLane_=(value: Option[String]): Unit = journeyStateFn().sumoArrivalLane = value

  def sumoArrivalPos: Double = journeyStateFn().sumoArrivalPos
  def sumoArrivalPos_=(value: Double): Unit = journeyStateFn().sumoArrivalPos = value

  def sumoWaitingTimeSeconds: Double = journeyStateFn().sumoWaitingTimeSeconds
  def sumoWaitingTimeSeconds_=(value: Double): Unit = journeyStateFn().sumoWaitingTimeSeconds = value

  def sumoWaitingCount: Int = journeyStateFn().sumoWaitingCount
  def sumoWaitingCount_=(value: Int): Unit = journeyStateFn().sumoWaitingCount = value

  def sumoStopTimeSeconds: Double = journeyStateFn().sumoStopTimeSeconds
  def sumoStopTimeSeconds_=(value: Double): Unit = journeyStateFn().sumoStopTimeSeconds = value

  def sumoIdealTravelTimeSeconds: Double = journeyStateFn().sumoIdealTravelTimeSeconds
  def sumoIdealTravelTimeSeconds_=(value: Double): Unit = journeyStateFn().sumoIdealTravelTimeSeconds = value

  def sumoCurrentMicroTimeStepSeconds: Double = journeyStateFn().sumoCurrentMicroTimeStepSeconds
  def sumoCurrentMicroTimeStepSeconds_=(value: Double): Unit = journeyStateFn().sumoCurrentMicroTimeStepSeconds = value

  def sumoIsHalting: Boolean = journeyStateFn().sumoIsHalting
  def sumoIsHalting_=(value: Boolean): Unit = journeyStateFn().sumoIsHalting = value

  def sumoRerouteNo: Int = journeyStateFn().sumoRerouteNo
  def sumoRerouteNo_=(value: Int): Unit = journeyStateFn().sumoRerouteNo = value

  private def sumoTripInfoReported: Boolean = journeyStateFn().sumoTripInfoReported
  private def sumoTripInfoReported_=(value: Boolean): Unit = journeyStateFn().sumoTripInfoReported = value

  def reset(): Unit = {
    journeyFinishedReported         = false
    sumoDepartTick                  = None
    sumoDepartSpeed                 = 0.0
    sumoArrivalSpeed                = 0.0
    sumoDepartLane                  = None
    sumoDepartPos                   = 0.0
    sumoArrivalLane                 = None
    sumoArrivalPos                  = 0.0
    sumoWaitingTimeSeconds          = 0.0
    sumoWaitingCount                = 0
    sumoStopTimeSeconds             = 0.0
    sumoIdealTravelTimeSeconds      = 0.0
    sumoCurrentMicroTimeStepSeconds = 1.0
    sumoIsHalting                   = false
    sumoRerouteNo                   = 0
    sumoTripInfoReported            = false
    journeyStateFn().secondsPerTick = 1.0
    journeyStateFn().boundaryRemainderSeconds = 0.0
    journeyStateFn().boundaryExitTick = None
  }

  /** Explicit reset for callers discarding completion without restoring a state snapshot. */
  def clearCompletionGuards(): Unit = {
    journeyFinishedReported = false
    sumoTripInfoReported    = false
  }

  def updateHaltingState(speed: Double, deltaSeconds: Double): Unit = {
    val isHaltingNow = speed < 0.1
    if (isHaltingNow) {
      if (!sumoIsHalting) sumoWaitingCount += 1
      sumoWaitingTimeSeconds += math.max(0.0, deltaSeconds)
    }
    sumoIsHalting = isHaltingNow
  }

  def reportRouteEvents(
    route: mutable.Queue[(Long, Long)],
    source: String,
    origin: Long,
    destination: Long,
    bestCost: Double = 0.0,
    cost: Double = 0.0
  ): Unit = {
    val entityId = entityIdFn()
    val tick     = currentTickFn()
    MovableMetrics.journeysStarted.labels("Car").inc()
    // ActorTrace.trace(entityId, tick, "car_journey_started", // #actor-trace
    //   s"origin=$origin destination=$destination route=${route.size} cost=$cost source=$source") // #actor-trace
    reportFn(
      Map(
        "event_type"   -> "journey_started",
        "vehicle_id"   -> entityId,
        "car_id"       -> entityId,
        "origin"       -> origin,
        "destination"  -> destination,
        "route_cost"   -> (if (cost > 0) cost else bestCost),
        "route_length" -> route.size,
        "tick"         -> tick,
        "route_source" -> source
      ),
      "journey_started"
    )
    reportFn(
      Map(
        "event_type"   -> "route_planned",
        "car_id"       -> entityId,
        "route_links"  -> route.map(_._1).mkString(","),
        "route_nodes"  -> route.map(_._2).mkString(","),
        "tick"         -> tick,
        "route_source" -> source
      ),
      "route_planned"
    )
  }

  def finishJourney(reason: String, finalNode: Long, state: CarState): Unit = {
    if (journeyFinishedReported) return
    journeyFinishedReported = true

    val entityId    = entityIdFn()
    val tick        = currentTickFn()
    val destination = tripDestFn().getOrElse(state.destination)
    val origin      = tripOriginFn().getOrElse(state.origin)

    MovableMetrics.journeysCompleted.labels("Car").inc()
    MovableMetrics.journeyDistanceMeters.labels("Car").observe(state.distance - tripStartDistanceFn())
    if (destination == finalNode) MovableMetrics.journeySuccesses.labels("Car").inc()
    else MovableMetrics.journeyFailures.labels("Car", reason).inc()

    reportFn(
      Map(
        "event_type"          -> "journey_completed",
        "vehicle_id"          -> entityId,
        "car_id"              -> entityId,
        "origin"              -> origin,
        "destination"         -> destination,
        "final_node"          -> finalNode,
        "reached_destination" -> (destination == finalNode),
        "completion_reason"   -> reason,
        "total_distance"      -> state.distance,
        "best_cost"           -> state.bestCost,
        "tick"                -> tick
      ),
      "journey_completed"
    )
    MovableMetrics.journeyCompletedReason.labels("car", reason, s"${destination == finalNode}").inc()
    reportFn(
      Map(
        "event_type" -> "vehicle_event_count",
        "car_id"     -> entityId,
        "tick"       -> tick
      ),
      "vehicle_event_count"
    )

    reportSumoTripInfo(reason, finalNode, state)
    state.status = Finished
  }

  private def reportSumoTripInfo(reason: String, finalNode: Long, state: CarState): Unit = {
    if (sumoTripInfoReported) return
    val entityId      = entityIdFn()
    val tick          = currentTickFn()
    val destination   = tripDestFn().getOrElse(state.destination)
    val origin        = tripOriginFn().getOrElse(state.origin)
    val plannedDepart = tripStartTickFn().getOrElse(state.startTick)
    val depart        = sumoDepartTick.getOrElse(plannedDepart)
    val durationSecs  = math.max(0L, tick - depart).toDouble * secondsPerTick
    val timeLoss      = math.max(0.0, durationSecs - math.max(0.0, sumoIdealTravelTimeSeconds))
    val vaporized     = reason == "actor_destructed_before_completion"
    val departDelay   = math.max(0L, depart - plannedDepart).toDouble * secondsPerTick

    reportFn(
      Map(
        "event_type"        -> "sumo_tripinfo",
        "vehicle_id"        -> entityId,
        "vehicle_type"      -> "car",
        "vType"             -> "car",
        "origin"            -> origin,
        "destination"       -> destination,
        "final_node"        -> finalNode,
        "completion_reason" -> reason,
        "depart"            -> (depart.toDouble * secondsPerTick),
        "arrival"           -> (tick.toDouble * secondsPerTick),
        "departLane"        -> sumoDepartLane.getOrElse(""),
        "departPos"         -> sumoDepartPos,
        "arrivalLane"       -> sumoArrivalLane.getOrElse(""),
        "arrivalPos"        -> sumoArrivalPos,
        "duration"          -> durationSecs,
        "routeLength"       -> (state.distance - tripStartDistanceFn()),
        "waitingTime"       -> sumoWaitingTimeSeconds,
        "waitingCount"      -> sumoWaitingCount,
        "stopTime"          -> sumoStopTimeSeconds,
        "timeLoss"          -> timeLoss,
        "departDelay"       -> departDelay,
        "rerouteNo"         -> sumoRerouteNo,
        "arrivalSpeed"      -> sumoArrivalSpeed,
        "departSpeed"       -> sumoDepartSpeed,
        "speedFactor"       -> driverAttrsFn().maxSpeedFactor,
        "vaporized"         -> vaporized,
        "tick"              -> tick
      ),
      "sumo_tripinfo"
    )
    sumoTripInfoReported = true
  }
}
