package org.interscity.htc
package model.hybrid.support.person

import core.entity.event.ActorInteractionEvent
import core.types.Tick
import core.util.StringPool
import model.hybrid.entity.event.data.bus.{ BusUnloadPassengerData, RegisterPassengerData }
import model.hybrid.entity.event.data.subway.{ RegisterSubwayPassengerData, SubwayUnloadPassengerData }
import model.hybrid.entity.state.plan.{ ConcreteMode, TransitLeg }
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import core.actor.trace.ActorTrace

/** Handles public transport (bus/subway) legs for person actors.
  *
  * Responsibilities:
  *   - Register person at PT stops
  *   - Answer unload requests from PT vehicles
  *
  * Unlike the pre-redesign handler, every [[TransitLeg]] is already a fully resolved, type-safe
  * leg (line, boarding stop, alighting stop are never optional) — there is no "PT trip missing
  * routing info" failure mode left to handle here; that used to be a runtime check against
  * `ArrivalLogistics`'s optional fields, now made structurally impossible.
  *
  * @param personId Person actor ID
  * @param reportFn Reporting function for events
  * @param sendMessageFn Function to send messages to other actors
  * @param logDebug Debug logging function
  */
class PersonPTTripHandler(
  personId: String,
  reportFn: (Map[String, Any], String) => Unit,
  sendMessageFn: (String, String, Any, String, Any) => Unit,
  logDebug: String => Unit
) {

  /** Register the person at the leg's boarding stop for the specified line. */
  def initiatePTTrip(leg: TransitLeg, currentTick: Tick): Unit = {
    val registrationData =
      if (leg.mode == ConcreteMode.Subway) RegisterSubwayPassengerData(line = leg.line)
      else RegisterPassengerData(label = leg.line)

    sendMessageFn(
      leg.boardingStop.actorId,
      leg.boardingStop.actorClassType,
      registrationData,
      "RegisterPassenger",
      LoadBalancedDistributed
    )

    // ActorTrace.trace(personId, currentTick, "person_pt_registered",
    //   s"stop=${leg.boardingStop.actorId} line=${leg.line} alighting=${leg.alightingStop.nodeId} mode=${leg.mode}")

    logDebug(s"$personId registered at ${leg.boardingStop.actorId} for ${leg.line}, alighting at ${leg.alightingStop.nodeId}")

    reportFn(
      Map(
        "event_type" -> "pt_trip_start",
        "person_id" -> personId,
        "mode" -> leg.mode.toString,
        "line" -> leg.line,
        "boarding_stop" -> leg.boardingStop.actorId,
        "alighting_node" -> leg.alightingStop.nodeId,
        "tick" -> currentTick
      ),
      "person_pt_trip_start"
    )
  }

  /** Answer an unload request from the boarded PT vehicle. Always replies — this is a
    * consistency-critical exchange from the vehicle's point of view (it's waiting on the answer
    * for every onboard passenger before it can decide whether to continue).
    *
    * @param event The interaction event from the vehicle
    * @param nodeId The node ID the vehicle is currently at
    * @param mode "Bus" or "Subway", used to pick the reply message shape
    * @param alightingNodeId The node the person is waiting to alight at
    * @return true if this was the alighting node (person is getting off here)
    */
  def handlePTUnloadRequest(
    event: ActorInteractionEvent,
    nodeId: String,
    mode: ConcreteMode,
    alightingNodeId: String
  ): Boolean = {
    val isArrival = alightingNodeId == nodeId

    val responseData =
      if (mode == ConcreteMode.Subway) SubwayUnloadPassengerData(isArrival = isArrival)
      else BusUnloadPassengerData(isArrival = isArrival)

    sendMessageFn(
      event.actorRefId,
      event.actorClassType,
      responseData,
      "UnloadPassengerResponse",
      null
    )

    logDebug(
      if (isArrival) s"$personId alighting from $mode at node $nodeId"
      else s"$personId staying on $mode at node $nodeId (alighting at $alightingNodeId)"
    )

    isArrival
  }
}
