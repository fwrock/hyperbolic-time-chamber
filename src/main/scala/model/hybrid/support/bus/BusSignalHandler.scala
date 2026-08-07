package org.interscity.htc
package model.hybrid.support.bus

import core.types.Tick
import org.interscity.htc.model.hybrid.entity.event.data.vehicle.{ CancelLinkAccessRequestData, RequestLinkAccessData }
import org.interscity.htc.model.hybrid.entity.event.node.LinkAccessData
import org.interscity.htc.model.hybrid.entity.state.BusState
import org.interscity.htc.model.hybrid.entity.state.enumeration.{EventTypeEnum, LinkCapacityStateEnum, MovableStatusEnum}
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.{WaitingCapacity, WaitingSignal, WaitingSignalState}
import org.interscity.htc.model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red
import org.interscity.htc.model.hybrid.util.CityMapUtil

/** Handles traffic signal and downstream-link-capacity interaction for Bus actors.
  *
  * Responsibilities:
  *   - requestSignalState: destination check or send link access request to node
  *   - handleLinkAccess: react to Green/Red/capacity-Full (or a later capacity-freed grant),
  *     guard against stale duplicates
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
  private val setSignalWaitNeedsReverifyFn: Boolean => Unit,
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
                    RequestLinkAccessData(targetLinkId = linkId),
                    EventTypeEnum.RequestLinkAccess.toString
                  )
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

  def handleLinkAccess(data: LinkAccessData, state: BusState): Unit = {
    if (state.status != WaitingSignalState && state.status != WaitingCapacity) {
      logDebugFn(
        s"${entityIdFn()}: Ignoring stale LinkAccessData " +
          s"(current status=${state.status}, expected WaitingSignalState/WaitingCapacity). Race condition guard."
      )
      return
    }

    if (data.phase == Red) {
      val tick = currentTickFn()
      val waitUntilTick = data.nextTick
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

      state.status = WaitingSignal
      setSignalWaitUntilTickFn(Some(waitUntilTick))
      setSignalWaitNeedsReverifyFn(true)
      scheduleEventFn(waitUntilTick)
    } else if (data.capacityState == LinkCapacityStateEnum.Full) {
      state.status = WaitingCapacity
    } else {
      // Green + Available: already fully verified at reply time, no re-verification needed.
      val tick = currentTickFn()
      state.status = WaitingSignal
      setSignalWaitUntilTickFn(Some(tick))
      setSignalWaitNeedsReverifyFn(false)
      scheduleEventFn(tick)
    }
  }

  /** Call from `onDestruct`, before any state clearing, when a bus is destroyed while
    * `WaitingCapacity`. See `CarSignalHandler.cancelPendingCapacityRequest` for the full
    * rationale.
    */
  def cancelPendingCapacityRequest(state: BusState): Unit =
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
