package org.interscity.htc
package model.hybrid.support.motorcycle

import core.types.Tick
import org.interscity.htc.model.hybrid.entity.state.MotorcycleState
import org.interscity.htc.model.hybrid.entity.state.DriverAttributes
import org.interscity.htc.core.metrics.model.hybrid.MovableMetrics
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.Finished

class MotorcycleJourneyReporter(
  reportFn:        (Map[String, Any], String) => Unit,
  entityIdFn:      () => String,
  currentTickFn:   () => Tick,
  tripOriginFn:    () => Option[String],
  tripDestFn:      () => Option[String],
  tripStartTickFn: () => Option[Tick],
  driverAttrsFn:   () => DriverAttributes
) {

  var sumoDepartTick: Option[Tick]    = None
  var sumoDepartSpeed: Double         = 0.0
  var sumoArrivalSpeed: Double        = 0.0
  var sumoDepartLane: Option[String]  = None
  var sumoDepartPos: Double           = 0.0
  var sumoArrivalLane: Option[String] = None
  var sumoArrivalPos: Double          = 0.0
  var sumoWaitingTimeSeconds: Double  = 0.0
  var sumoWaitingCount: Int           = 0
  var sumoStopTimeSeconds: Double     = 0.0
  var sumoIdealTravelTimeSeconds: Double        = 0.0
  var sumoCurrentMicroTimeStepSeconds: Double   = 1.0
  var sumoIsHalting: Boolean          = false
  var sumoRerouteNo: Int              = 0
  private var journeyFinishedReported: Boolean = false

  def reset(): Unit = {
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
    journeyFinishedReported         = false
  }

  def updateHaltingState(speed: Double, deltaSeconds: Double): Unit = {
    val isHaltingNow = speed < 0.1
    if (isHaltingNow) {
      if (!sumoIsHalting) sumoWaitingCount += 1
      sumoWaitingTimeSeconds += math.max(0.0, deltaSeconds)
    }
    sumoIsHalting = isHaltingNow
  }

  def finishJourney(reason: String, finalNode: String, state: MotorcycleState): Unit = {
    if (journeyFinishedReported) return
    journeyFinishedReported = true
    val destination = tripDestFn().getOrElse(state.destination)
    val origin      = tripOriginFn().getOrElse(state.origin)
    val vehicleType = "Motorcycle"
    MovableMetrics.journeysCompleted.labels(vehicleType).inc()
    MovableMetrics.journeyDistanceMeters.labels(vehicleType).observe(state.distance)
    if (destination == finalNode) MovableMetrics.journeySuccesses.labels(vehicleType).inc()
    else MovableMetrics.journeyFailures.labels(vehicleType, reason).inc()
    reportFn(
      Map(
        "event_type"          -> "journey_completed",
        "vehicle_id"          -> entityIdFn(),
        "motorcycle_id"       -> entityIdFn(),
        "origin"              -> origin,
        "destination"         -> destination,
        "final_node"          -> finalNode,
        "reached_destination" -> (destination == finalNode),
        "completion_reason"   -> reason,
        "total_distance"      -> state.distance,
        "tick"                -> currentTickFn()
      ),
      "journey_completed"
    )
    reportSumoTripInfo(reason, finalNode, state)
    state.status = Finished
  }

  private def reportSumoTripInfo(
    reason: String,
    finalNode: String,
    state: MotorcycleState
  ): Unit = {
    val destination   = tripDestFn().getOrElse(state.destination)
    val origin        = tripOriginFn().getOrElse(state.origin)
    val plannedDepart = tripStartTickFn().getOrElse(state.startTick)
    val depart        = sumoDepartTick.getOrElse(plannedDepart)
    val arrival       = currentTickFn()
    val duration      = math.max(0L, arrival - depart)
    val timeLoss =
      math.max(0.0, duration.toDouble - math.max(0.0, sumoIdealTravelTimeSeconds))
    val departDelay = math.max(0L, depart - plannedDepart)
    reportFn(
      Map(
        "event_type"       -> "sumo_tripinfo",
        "vehicle_id"       -> entityIdFn(),
        "vehicle_type"     -> "motorcycle",
        "vType"            -> "motorcycle",
        "origin"           -> origin,
        "destination"      -> destination,
        "final_node"       -> finalNode,
        "completion_reason" -> reason,
        "depart"           -> depart,
        "arrival"          -> arrival,
        "departLane"       -> sumoDepartLane.getOrElse(""),
        "departPos"        -> sumoDepartPos,
        "arrivalLane"      -> sumoArrivalLane.getOrElse(""),
        "arrivalPos"       -> sumoArrivalPos,
        "duration"         -> duration,
        "routeLength"      -> state.distance,
        "waitingTime"      -> sumoWaitingTimeSeconds,
        "waitingCount"     -> sumoWaitingCount,
        "stopTime"         -> sumoStopTimeSeconds,
        "timeLoss"         -> timeLoss,
        "departDelay"      -> departDelay,
        "rerouteNo"        -> sumoRerouteNo,
        "arrivalSpeed"     -> sumoArrivalSpeed,
        "departSpeed"      -> sumoDepartSpeed,
        "speedFactor"      -> driverAttrsFn().maxSpeedFactor,
        "vaporized"        -> (reason == "actor_destructed_before_completion"),
        "tick"             -> currentTickFn()
      ),
      "sumo_tripinfo"
    )
  }
}
