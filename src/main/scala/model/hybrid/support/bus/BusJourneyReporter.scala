package org.interscity.htc
package model.hybrid.support.bus

import core.types.Tick
import core.actor.trace.ActorTrace
import org.interscity.htc.core.metrics.model.hybrid.MovableMetrics
import org.interscity.htc.model.hybrid.entity.state.BusState
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.Finished

/** Tracks journey metrics and SUMO-compatible trip statistics for a Bus actor.
  *
  * Owns all SUMO TripInfo variables, updateHaltingState, finishJourney and reportSumoTripInfo
  * so Bus.scala stays free of tracking boilerplate.
  *
  * @param reportFn      Actor report callback
  * @param entityIdFn    Supplier for the bus entity ID
  * @param currentTickFn Supplier for the current simulation tick
  */
class BusJourneyReporter(
  private val reportFn: (Map[String, Any], String) => Unit,
  private val entityIdFn: () => String,
  private val currentTickFn: () => Tick
) {

  private var journeyFinishedReported: Boolean = false

  // SUMO TripInfo variables
  var sumoDepartTick: Option[Tick]  = None
  var sumoDepartSpeed: Double        = 0.0
  var sumoArrivalSpeed: Double       = 0.0
  var sumoDepartLane: Option[String] = None
  var sumoDepartPos: Double          = 0.0
  var sumoArrivalLane: Option[String] = None
  var sumoArrivalPos: Double          = 0.0
  var sumoWaitingTimeSeconds: Double  = 0.0
  var sumoWaitingCount: Int           = 0
  var sumoStopTimeSeconds: Double     = 0.0
  var sumoIdealTravelTimeSeconds: Double      = 0.0
  var sumoCurrentMicroTimeStepSeconds: Double = 1.0
  var sumoIsHalting: Boolean = false
  var sumoRerouteNo: Int     = 0
  private var sumoTripInfoReported: Boolean = false

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
  }

  def updateHaltingState(speed: Double, deltaSeconds: Double): Unit = {
    val isHaltingNow = speed < 0.1
    if (isHaltingNow) {
      if (!sumoIsHalting) sumoWaitingCount += 1
      sumoWaitingTimeSeconds += math.max(0.0, deltaSeconds)
    }
    sumoIsHalting = isHaltingNow
  }

  def finishJourney(reason: String, finalNode: String, state: BusState): Unit = {
    if (journeyFinishedReported) return
    journeyFinishedReported = true

    val entityId = entityIdFn()
    val tick     = currentTickFn()

    MovableMetrics.journeysCompleted.labels("Bus").inc()
    MovableMetrics.journeyDistanceMeters.labels("Bus").observe(state.distance)
    if (state.destination == finalNode) MovableMetrics.journeySuccesses.labels("Bus").inc()
    else MovableMetrics.journeyFailures.labels("Bus", reason).inc()

    reportFn(
      Map(
        "event_type"          -> "journey_completed",
        "vehicle_id"          -> entityId,
        "bus_id"              -> entityId,
        "origin"              -> state.origin,
        "destination"         -> state.destination,
        "final_node"          -> finalNode,
        "reached_destination" -> (state.destination == finalNode),
        "completion_reason"   -> reason,
        "total_distance"      -> state.distance,
        "tick"                -> tick
      ),
      "journey_completed"
    )

    reportSumoTripInfo(reason, finalNode, state)
    state.status = Finished
  }

  private def reportSumoTripInfo(reason: String, finalNode: String, state: BusState): Unit = {
    if (sumoTripInfoReported) return
    val entityId       = entityIdFn()
    val tick           = currentTickFn()
    val plannedDepart  = state.startTick
    val depart         = sumoDepartTick.getOrElse(plannedDepart)
    val duration       = math.max(0L, tick - depart)
    val timeLoss       = math.max(0.0, duration.toDouble - math.max(0.0, sumoIdealTravelTimeSeconds))
    val vaporized      = reason == "actor_destructed_before_completion"
    val departDelay    = math.max(0L, depart - plannedDepart)

    reportFn(
      Map(
        "event_type"        -> "sumo_tripinfo",
        "vehicle_id"        -> entityId,
        "vehicle_type"      -> "bus",
        "vType"             -> "bus",
        "origin"            -> state.origin,
        "destination"       -> state.destination,
        "final_node"        -> finalNode,
        "completion_reason" -> reason,
        "depart"            -> depart,
        "arrival"           -> tick,
        "departLane"        -> sumoDepartLane.getOrElse(""),
        "departPos"         -> sumoDepartPos,
        "arrivalLane"       -> sumoArrivalLane.getOrElse(""),
        "arrivalPos"        -> sumoArrivalPos,
        "duration"          -> duration,
        "routeLength"       -> state.distance,
        "waitingTime"       -> sumoWaitingTimeSeconds,
        "waitingCount"      -> sumoWaitingCount,
        "stopTime"          -> sumoStopTimeSeconds,
        "timeLoss"          -> timeLoss,
        "departDelay"       -> departDelay,
        "rerouteNo"         -> sumoRerouteNo,
        "arrivalSpeed"      -> sumoArrivalSpeed,
        "departSpeed"       -> sumoDepartSpeed,
        "speedFactor"       -> 1.0,
        "vaporized"         -> vaporized,
        "tick"              -> tick
      ),
      "sumo_tripinfo"
    )
    sumoTripInfoReported = true
  }
}
