package org.interscity.htc
package model.hybrid.support.bus

import core.types.Tick
import org.interscity.htc.model.hybrid.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.hybrid.entity.event.node.SignalStateData
import org.interscity.htc.model.hybrid.entity.state.BusState
import org.interscity.htc.model.hybrid.entity.state.enumeration.{EventTypeEnum, MovableStatusEnum}
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.{WaitingSignal, WaitingSignalState}
import org.interscity.htc.model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red
import org.interscity.htc.model.hybrid.util.CityMapUtil

/** Handles traffic signal interaction for Bus actors.
  *
  * Responsibilities:
  *   - requestSignalState: destination check or send signal request to node
  *   - handleSignalState: react to Red/Green, guard against stale duplicates
  */
class BusSignalHandler(
  private val reportFn: (Map[String, Any], String) => Unit,
  private val entityIdFn: () => String,
  private val currentTickFn: () => Tick,
  private val journeyReporter: BusJourneyReporter,
  private val onFinishSpontaneousFn: Option[Tick] => Unit,
  private val scheduleEventFn: Tick => Unit,
  private val onFinishDestructFn: () => Unit,
  private val leavingLinkFn: () => Unit,
  private val finishJourneyFn: (String, String) => Unit,
  private val getCurrentNodeFn: () => String,
  private val getNextLinkFn: () => String,
  private val sendMessageFn: (String, String, AnyRef, String) => Unit,
  private val logWarnFn: String => Unit,
  private val logDebugFn: String => Unit,
  private val setSignalWaitUntilTickFn: Option[Tick] => Unit,
  private val restoreRouteIfMissingFn: String => Unit
) {

  def requestSignalState(state: BusState): Unit = {
    restoreRouteIfMissingFn("requestSignalState")
    val routeDepleted = state.bestRoute.forall(_.isEmpty)
    if (routeDepleted) {
      val currentNodeId = getCurrentNodeFn()
      logDebugFn(s"Bus ${entityIdFn()} reached destination: $currentNodeId")
      finishJourneyFn("reached_destination", Option(currentNodeId).getOrElse("unknown"))
      onFinishDestructFn()
    } else {
      state.status = WaitingSignalState
      getCurrentNodeFn() match {
        case nodeId if nodeId != null =>
          CityMapUtil.nodesById.get(nodeId) match {
            case Some(node) =>
              getNextLinkFn() match {
                case linkId if linkId != null =>
                  sendMessageFn(
                    node.id,
                    node.classType,
                    RequestSignalStateData(targetLinkId = linkId),
                    EventTypeEnum.RequestSignalState.toString
                  )
                // Consistency-critical: do NOT poll. Genuinely deregister from the TimeManager
                // (a real FinishEvent, not a deferred safety-net suppression) and wait for the
                // reply as an interaction event; handleSignalState re-registers via
                // scheduleEvent when it lands (see CarSignalHandler for the full rationale).
                onFinishSpontaneousFn(None)
                case null =>
                  logWarnFn("No next link available")
                  leavingLinkFn()
              }
            case None =>
              logWarnFn(s"Node $nodeId not found")
              leavingLinkFn()
          }
        case null =>
          logWarnFn("No current node")
          leavingLinkFn()
      }
    }
  }

  def handleSignalState(data: SignalStateData, state: BusState): Unit = {
    if (state.status != WaitingSignalState) {
      logDebugFn(
        s"${entityIdFn()}: Ignoring stale SignalStateData " +
          s"(current status=${state.status}, expected WaitingSignalState). Race condition guard."
      )
      return
    }
    val tick = currentTickFn()
    val waitUntilTick = if (data.phase == Red) data.nextTick else tick

    if (data.phase == Red) {
      val waitTicks = math.max(0L, waitUntilTick - tick)
      if (waitTicks > 0) journeyReporter.updateHaltingState(speed = 0.0, deltaSeconds = waitTicks.toDouble)
      reportFn(
        Map(
          "event_type"         -> "signal_wait",
          "vehicle_type"       -> "bus",
          "vehicle_id"         -> entityIdFn(),
          "phase"              -> data.phase.toString,
          "wait_until_tick"    -> waitUntilTick,
          "capacity"           -> state.capacity,
          "current_passengers" -> state.people.size,
          "tick"               -> tick
        ),
        "signal_wait"
      )
    }

    // See CarSignalHandler.handleSignalState for why scheduleEvent (not onFinishSpontaneous)
    // is the correct re-entry point here: this bus fully deregistered when it sent the
    // request, so there is no pending spontaneous event to resolve directly.
    state.status = WaitingSignal
    setSignalWaitUntilTickFn(Some(waitUntilTick))
    scheduleEventFn(waitUntilTick)
  }
}
