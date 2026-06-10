package org.interscity.htc
package model.hybrid.support.person

import core.entity.event.ActorInteractionEvent
import core.types.Tick
import model.hybrid.entity.state.{ArrivalLogistics, PersonState}
import model.hybrid.entity.event.data.bus.{BusUnloadPassengerData, RegisterPassengerData}
import model.hybrid.entity.event.data.subway.{RegisterSubwayPassengerData, SubwayUnloadPassengerData}
import model.hybrid.entity.state.enumeration.TravelMode
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import core.actor.trace.ActorTrace
import core.util.StringPool

/** Handles public transport (bus/subway) trips for person actors.
  *
  * Responsibilities:
  *   - Register person at PT stops
  *   - Handle unload requests from PT vehicles
  *   - Manage multi-leg PT journeys
  *   - Handle PT line not operational scenarios
  *   - Cancel PT wait timeouts
  *
  * @param personId Person actor ID
  * @param metricsReporter Metrics reporter
  * @param reportFn Reporting function for events
  * @param sendMessageFn Function to send messages to other actors
  * @param logDebug Debug logging function
  * @param logWarn Warning logging function
  */
class PersonPTTripHandler(
  personId: String,
  metricsReporter: PersonMetricsReporter,
  reportFn: (Map[String, Any], String) => Unit,
  sendMessageFn: (String, String, Any, String, Any) => Unit,
  logDebug: String => Unit,
  logWarn: String => Unit
) {

  /** Initiate public transport trip (Bus or Subway).
    *
    * Person registers at the boarding stop (BusStop/SubwayStation) for the specified line.
    * Returns updated state and timeout tick for PT wait, or None if routing info missing.
    *
    * Required ArrivalLogistics fields for PT:
    *   - line: bus/subway line label
    *   - boardingStopId: BusStop/SubwayStation actor ID
    *   - boardingStopClassType: actor class type for shard routing
    *   - alightingNodeId: node where Person should alight
    *
    * @param origin Origin node ID
    * @param destination Destination node ID
    * @param logistics Arrival logistics with PT routing info
    * @param state Current person state
    * @param currentTick Current simulation tick
    * @return Some((updated state, timeout tick)) if successful, None if routing info missing
    */
  def initiatePTTrip(
    origin: String,
    destination: String,
    logistics: ArrivalLogistics,
    state: PersonState,
    currentTick: Tick
  ): Option[(PersonState, Tick)] =
    (
      logistics.line,
      logistics.boardingStopId,
      logistics.boardingStopClassType,
      logistics.alightingNodeId
    ) match {
      case (Some(line), Some(stopId), Some(stopClassType), Some(alightingNode)) =>
        val registrationData =
          if (logistics.travelMode == TravelMode.Subway) RegisterSubwayPassengerData(line = line)
          else RegisterPassengerData(label = line)

        sendMessageFn(
          stopId,
          stopClassType,
          registrationData,
          "RegisterPassenger",
          LoadBalancedDistributed
        )

        ActorTrace.trace(personId, currentTick, "person_pt_registered",
          s"stop=$stopId line=$line alighting=$alightingNode mode=${logistics.mode}")

        logDebug(s"$personId registered at $stopId for $line, alighting at $alightingNode")

        reportFn(
          Map(
            "event_type" -> "pt_trip_start",
            "person_id" -> personId,
            "mode" -> logistics.mode,
            "line" -> line,
            "origin" -> origin,
            "destination" -> destination,
            "boarding_stop" -> stopId,
            "alighting_node" -> alightingNode,
            "tick" -> currentTick
          ),
          "person_pt_trip_start"
        )

        val updatedState = state.copy(
          ptAlightingNodeId = Some(StringPool.intern(alightingNode)),
          ptLine = Some(StringPool.intern(line)),
          ptWaitingSince = Some(currentTick)
        )
        val timeoutTick = currentTick + state.ptWaitTimeoutTicks
        Some((updatedState, timeoutTick))

      case _ =>
        logDebug(
          s"$personId PT trip missing routing info (line=${logistics.line}, " +
            s"boardingStop=${logistics.boardingStopId}, alightingNode=${logistics.alightingNodeId}). "
        )
        None
    }

  /** Handle unload request from PT vehicle (Bus or Subway).
    *
    * The vehicle asks "are you getting off at this node?" Person checks if the node matches its
    * alighting destination and responds. Returns updated state and whether person is alighting.
    *
    * @param event The interaction event from the vehicle
    * @param nodeId The node ID the vehicle is currently at
    * @param ptType "bus" or "subway" for logging and response routing
    * @param state Current person state
    * @param currentTick Current simulation tick
    * @return (updated state, is alighting, travel time if alighting)
    */
  def handlePTUnloadRequest(
    event: ActorInteractionEvent,
    nodeId: String,
    ptType: TravelMode,
    state: PersonState,
    currentTick: Tick
  ): (PersonState, Boolean, Option[Long]) = {
    val isArrival = state.ptAlightingNodeId.contains(nodeId)

    val responseData =
      if (ptType == TravelMode.Subway) SubwayUnloadPassengerData(isArrival = isArrival)
      else BusUnloadPassengerData(isArrival = isArrival)

    sendMessageFn(
      event.actorRefId,
      event.actorClassType,
      responseData,
      "UnloadPassengerResponse",
      null
    )

    if (isArrival) {
      val travelTime = state.currentTripStartTick
        .map(start => currentTick - start)
        .getOrElse(0L)
      val currentMode = state.currentTripMode.getOrElse(ptType.toString.toLowerCase)

      logDebug(s"$personId alighting from $ptType at node $nodeId after ${travelTime}s")

      reportFn(
        Map(
          "event_type" -> "pt_trip_completed",
          "person_id" -> personId,
          "pt_type" -> ptType,
          "vehicle_id" -> event.actorRefId,
          "line" -> state.ptLine.getOrElse("unknown"),
          "alighting_node" -> nodeId,
          "travel_time" -> travelTime,
          "tick" -> currentTick
        ),
        "person_pt_trip_completed"
      )

      val updatedState = state.completeTrip(0.0).copy(
        currentPhysicalNodeId = Some(nodeId)
      )
      (updatedState, true, Some(travelTime))
    } else {
      logDebug(
        s"$personId staying on $ptType at node $nodeId " +
          s"(alighting at ${state.ptAlightingNodeId.getOrElse("?")})"
      )
      (state, false, None)
    }
  }

  /** Handle notification that a PT line will not operate.
    *
    * Sent by BusStop when BusStation reports route calculation failure.
    * Returns updated state with trip completed.
    *
    * @param line PT line that won't operate
    * @param state Current person state
    * @param currentTick Current simulation tick
    * @param currentActivityType Activity type (for metrics)
    * @return (updated state, travel time)
    */
  def handlePTLineNotOperational(
    line: String,
    state: PersonState,
    currentTick: Tick,
    currentActivityType: String
  ): (PersonState, Long) = {
    logWarn(s"$personId PT line $line not operational — skipping trip")
    val currentMode = state.currentTripMode.getOrElse("pt")
    val travelTime = state.currentTripStartTick.map(start => currentTick - start).getOrElse(0L)

    metricsReporter.recordTripEnd(currentActivityType, currentMode, "pt_line_not_operational")

    val updatedState = state.completeTrip(0.0).copy(ptWaitingSince = None)
    (updatedState, travelTime)
  }

  /** Check if person has pending transfer legs from multi-leg routing.
    *
    * @param state Current person state
    * @return true if there are pending transfer legs
    */
  def hasPendingTransferLegs(state: PersonState): Boolean =
    state.pendingTransferLegs.nonEmpty

  /** Get next transfer leg and update state.
    *
    * @param state Current person state
    * @return (next leg logistics, destination node, updated state)
    */
  def getNextTransferLeg(state: PersonState): (ArrivalLogistics, String, PersonState) = {
    val nextLeg = state.pendingTransferLegs.head
    val restLegs = state.pendingTransferLegs.tail
    val destNodeId = nextLeg.alightingNodeId.getOrElse("")
    val updatedState = state.copy(pendingTransferLegs = restLegs)

    logDebug(
      s"$personId transfer: mode=${nextLeg.mode} dest=${destNodeId} " +
        s"(${restLegs.size} legs remaining)"
    )

    (nextLeg, destNodeId, updatedState)
  }
}
