package org.interscity.htc
package model.hybrid.support.car

import core.types.Tick
import org.interscity.htc.model.hybrid.entity.event.data.vehicle.{ CancelLinkAccessRequestData, RequestLinkAccessData }
import org.interscity.htc.model.hybrid.entity.event.node.LinkAccessData
import org.interscity.htc.model.hybrid.entity.state.CarState
import org.interscity.htc.model.hybrid.entity.state.enumeration.{EventTypeEnum, LinkCapacityStateEnum, MovableStatusEnum}
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.{Ready, WaitingCapacity, WaitingSignal, WaitingSignalState}
import org.interscity.htc.model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red
import org.interscity.htc.model.hybrid.util.CityMapUtil

/** Handles traffic signal and downstream-link-capacity interaction for Car actors.
  *
  * Responsibilities:
  *   - requestSignalState: determine if at destination or request link access from node
  *   - handleLinkAccess: react to Green/Red/capacity-Full response (or a later, unsolicited
  *     capacity-freed grant), guard against stale duplicates
  */
class CarSignalHandler(
  private val reportFn: (Map[String, Any], String) => Unit,
  private val entityIdFn: () => String,
  private val currentTickFn: () => Tick,
  private val journeyReporter: CarJourneyReporter,
  private val onFinishSpontaneousFn: Option[Tick] => Unit,
  private val scheduleEventFn: Tick => Unit,
  private val leavingLinkFn: () => Unit,
  private val selfDestructFn: () => Unit,
  private val isPersonCentricFn: () => Boolean,
  private val logWarnFn: String => Unit,
  private val logStaleEventDebugFn: String => Unit,
  private val sendMessageFn: (String, String, AnyRef, String) => Unit,
  private val getCurrentNodeFn: () => String,
  private val getNextLinkFn: () => String,
  private val getTripDestinationFn: () => Option[String],
  private val setSignalWaitUntilTickFn: Option[Tick] => Unit,
  private val setSignalWaitNeedsReverifyFn: Boolean => Unit,
  private val onSignalWaitFn: Long => Unit
) {

  def requestSignalState(state: CarState): Unit = {
    val currentPathNode = state.currentPath.map(_._2).orNull
    val routeDepleted   = state.bestRoute.forall(_.isEmpty)

    if (currentPathNode == null && !routeDepleted) {
      logWarnFn(
        s"${entityIdFn()} requestSignalState with null currentPathNode but non-empty route " +
          s"at tick=${currentTickFn()}; recovering to Ready"
      )
      state.status = Ready
      onFinishSpontaneousFn(Some(currentTickFn() + 1))
      return
    }

    if (getTripDestinationFn().getOrElse(state.destination) == currentPathNode || routeDepleted) {
      // NOTE: leavingLink() already calls onFinish(nextNodeId) internally, which invokes
      // onFinishPrivateVehicle() and finishJourney(). Do NOT duplicate those calls here —
      // doing so would cause a second TripCompletedData to Person.
      leavingLinkFn()
      if (!isPersonCentricFn()) selfDestructFn()
    } else {
      state.status = WaitingSignalState
      val nodeId = getCurrentNodeFn()
      if (nodeId != null) {
        CityMapUtil.nodesById.get(nodeId) match {
          case Some(node) =>
            val linkId = getNextLinkFn()
            if (linkId != null) {
              sendMessageFn(
                node.id,
                node.classType,
                RequestLinkAccessData(targetLinkId = linkId),
                EventTypeEnum.RequestLinkAccess.toString
              )
              // Consistency-critical: do NOT poll. Node always replies on every branch
              // (NodeEventHandler.handleRequestLinkAccess) — genuinely deregister from the
              // TimeManager (onFinishSpontaneous(None), a real FinishEvent, not a deferred
              // safety-net suppression) and wait for the reply as an interaction event.
              // handleLinkAccess re-registers via scheduleEvent when the reply lands — the
              // same disengage-then-scheduleEvent shape already used by Person's StartTrip ->
              // Car handoff, which correctly re-notifies the Global TM of new work (see
              // LocalTimeManagerBase.scheduleEvent's wasIdle re-notification). A plain
              // onFinishSpontaneous(Some(tick)) called later from actInteractWith would NOT
              // re-notify the Global TM the same way, risking premature termination if this
              // was the last scheduled work.
              onFinishSpontaneousFn(None)
            } else {
              leavingLinkFn()
            }
          case None =>
            leavingLinkFn()
        }
      } else {
        leavingLinkFn()
      }
    }
  }

  def handleLinkAccess(data: LinkAccessData, state: CarState): Unit = {
    // Guard against stale/duplicate responses. Accepts both the immediate reply
    // (WaitingSignalState) and a later, unsolicited capacity-freed grant (WaitingCapacity).
    if (state.status != WaitingSignalState && state.status != WaitingCapacity) {
      logStaleEventDebugFn(
        s"${entityIdFn()}: Ignoring stale LinkAccessData " +
          s"(current status=${state.status}, expected WaitingSignalState/WaitingCapacity). Race condition guard."
      )
      return
    }

    if (data.phase == Red) {
      val tick = currentTickFn()
      // Saturation headway ~2s per vehicle (HCM 6th ed. §19)
      val headwayTicks     = 2L
      val waitUntilTick    = data.nextTick + data.queuePosition.toLong * headwayTicks
      val waitTicks        = math.max(0L, waitUntilTick - tick)
      if (waitTicks > 0) journeyReporter.updateHaltingState(speed = 0.0, deltaSeconds = waitTicks.toDouble)
      if (waitTicks > 0) onSignalWaitFn(waitTicks)

      reportFn(
        Map(
          "event_type"         -> "signal_wait",
          "vehicle_type"       -> "car",
          "vehicle_id"         -> entityIdFn(),
          "phase"              -> data.phase.toString,
          "queue_position"     -> data.queuePosition,
          "wait_until_tick"    -> waitUntilTick,
          "adjusted_next_tick" -> waitUntilTick,
          "tick"               -> tick
        ),
        "signal_wait"
      )

      // Capacity is independent of signal phase and wasn't checked for this Red reply (only
      // matters once Green — see NodeEventHandler.handleRequestLinkAccess). By the time this
      // deterministic wait ends, downstream capacity could still be Full, so the WaitingSignal
      // branch in actSpontaneous must re-verify (call requestSignalState again) rather than
      // proceed unilaterally — flagged here, consumed there.
      state.status = WaitingSignal
      setSignalWaitUntilTickFn(Some(waitUntilTick))
      setSignalWaitNeedsReverifyFn(true)
      scheduleEventFn(waitUntilTick)
    } else if (data.capacityState == LinkCapacityStateEnum.Full) {
      // Node has buffered this car (FIFO) for the target link's capacity. Stay genuinely
      // deregistered — no scheduleEvent call here at all — and wait purely for the later,
      // unsolicited LinkAccessData grant as an interaction event, exactly like the initial
      // request. See docs/CONGESTION_PROPAGATION_DESIGN.md.
      state.status = WaitingCapacity
    } else {
      // Green + Available: proceed. Already fully verified (both signal and capacity) at the
      // moment this reply was produced — no re-verification needed when the wait (immediate)
      // resolves.
      val tick = currentTickFn()
      state.status = WaitingSignal
      setSignalWaitUntilTickFn(Some(tick))
      setSignalWaitNeedsReverifyFn(false)
      scheduleEventFn(tick)
    }
  }

  /** Call from `onDestruct`, before any state clearing, when a car is destroyed while
    * `WaitingCapacity` (buffered at a Node, never yet granted) — otherwise that stale buffer
    * entry sits forever and could eventually waste a real capacity slot on a dead actor. No-op
    * for any other status. See docs/CONGESTION_PROPAGATION_DESIGN.md.
    */
  def cancelPendingCapacityRequest(state: CarState): Unit =
    if (state.status == WaitingCapacity) {
      val nodeId = getCurrentNodeFn()
      val linkId = getNextLinkFn()
      if (nodeId != null && linkId != null) {
        CityMapUtil.nodesById.get(nodeId).foreach { node =>
          sendMessageFn(
            node.id,
            node.classType,
            CancelLinkAccessRequestData(targetLinkId = linkId),
            EventTypeEnum.CancelLinkAccessRequest.toString
          )
        }
      }
    }
}
