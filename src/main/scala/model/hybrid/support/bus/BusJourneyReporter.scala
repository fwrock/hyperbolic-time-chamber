package org.interscity.htc
package model.hybrid.support.bus

import core.types.Tick
import core.actor.trace.ActorTrace
import org.interscity.htc.core.metrics.model.hybrid.MovableMetrics
import org.interscity.htc.model.hybrid.entity.state.{BusJourneyState, BusState}
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.Finished

/** Tracks journey metrics and SUMO-compatible trip statistics for a Bus actor.
  *
  * Reads counters and completion guards through the actor's checkpointed journey state.
  *
  * @param reportFn      Actor report callback
  * @param entityIdFn    Supplier for the bus entity ID
  * @param currentTickFn Supplier for the current simulation tick
  */
class BusJourneyReporter(
  private val reportFn: (Map[String, Any], String) => Unit,
  private val entityIdFn: () => String,
  private val currentTickFn: () => Tick,
  private val journeyStateFn: () => BusJourneyState
) {

  private def journeyFinishedReported: Boolean = journeyStateFn().journeyFinishedReported
  private def journeyFinishedReported_=(value: Boolean): Unit = journeyStateFn().journeyFinishedReported = value

  def sumoDepartTick: Option[Tick] = journeyStateFn().sumoDepartTick
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
  }

  /** Only for explicit discard without checkpoint restoration. */
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

  def finishJourney(reason: String, finalNode: Long, state: BusState): Unit = {
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

  private def reportSumoTripInfo(reason: String, finalNode: Long, state: BusState): Unit = {
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
