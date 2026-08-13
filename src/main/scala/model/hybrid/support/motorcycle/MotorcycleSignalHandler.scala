package org.interscity.htc
package model.hybrid.support.motorcycle

import core.types.Tick
import org.interscity.htc.model.hybrid.entity.event.data.vehicle.{ CancelLinkAccessRequestData, RequestLinkAccessData }
import org.interscity.htc.model.hybrid.entity.event.node.LinkAccessData
import org.interscity.htc.model.hybrid.entity.state.MotorcycleState
import org.interscity.htc.model.hybrid.entity.state.enumeration.{ EventTypeEnum, LinkCapacityStateEnum }
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.{
  WaitingCapacity,
  WaitingSignal,
  WaitingSignalState
}
import org.interscity.htc.model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red
import org.interscity.htc.model.hybrid.util.CityMapUtil

class MotorcycleSignalHandler(
  reportFn:                    (Map[String, Any], String) => Unit,
  entityIdFn:                  () => String,
  currentTickFn:               () => Tick,
  journeyReporter:             MotorcycleJourneyReporter,
  onFinishSpontaneousFn:       Option[Tick] => Unit,
  scheduleEventFn:             Tick => Unit,
  leavingLinkFn:               () => Unit,
  selfDestructFn:              () => Unit,
  isPersonCentricFn:           () => Boolean,
  logDebugFn:                  String => Unit,
  sendMessageFn: (Long, String, AnyRef, String) => Unit,
  getCurrentNodeFn: () => Long,
  getNextLinkFn: () => Long,
  getTripDestinationFn: () => Option[Long],
  finishJourneyFn: (String, Long) => Unit,
  onFinishPrivateVehicleFn: Long => Unit,
  aggressivenessFn:            () => Double,
  setSignalWaitUntilTickFn:    Option[Tick] => Unit,
  setSignalWaitNeedsReverifyFn: Boolean => Unit
) {

  def requestSignalState(state: MotorcycleState): Unit = {
    val currentPathNode = state.currentPath.map(_._2).orNull
    val routeDepleted   = state.bestRoute.forall(_.isEmpty)
    val tripDest        = getTripDestinationFn().getOrElse(state.destination)
    if (tripDest == currentPathNode || routeDepleted) {
      val nodeId = getCurrentNodeFn()
      if (nodeId != 0L) {
        finishJourneyFn("reached_destination", nodeId)
        onFinishPrivateVehicleFn(nodeId)
      } else {
        finishJourneyFn("no_current_node", 0L)
        onFinishPrivateVehicleFn(0L)
      }
      onFinishSpontaneousFn(None)
      if (!isPersonCentricFn()) selfDestructFn()
    } else {
      state.status = WaitingSignalState
      val nodeId = getCurrentNodeFn()
      nodeId match {
        case nid if nid != 0L =>
          CityMapUtil.nodesById.get(nid) match {
            case Some(node) =>
              getNextLinkFn() match {
                case linkId if linkId != 0L =>
                  sendMessageFn(
                    node.id,
                    node.classType,
                    RequestLinkAccessData(targetLinkId = linkId),
                    EventTypeEnum.RequestLinkAccess.toString
                  )
                onFinishSpontaneousFn(None)
                case 0L =>
                  leavingLinkFn()
              }
            case None =>
              leavingLinkFn()
          }
        case 0L =>
          leavingLinkFn()
      }
    }
  }

  def handleLinkAccess(data: LinkAccessData, state: MotorcycleState): Unit = {
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
      if (waitTicks > 0) journeyReporter.updateHaltingState(0.0, waitTicks.toDouble)
      reportFn(
        Map(
          "event_type"     -> "signal_wait",
          "vehicle_type"   -> "motorcycle",
          "vehicle_id"     -> entityIdFn(),
          "phase"          -> data.phase.toString,
          "wait_until_tick" -> waitUntilTick,
          "aggressiveness" -> aggressivenessFn(),
          "tick"           -> tick
        ),
        "signal_wait"
      )

      // See CarSignalHandler.handleLinkAccess for why this needs re-verification.
      state.status = WaitingSignal
      setSignalWaitUntilTickFn(Some(waitUntilTick))
      setSignalWaitNeedsReverifyFn(true)
      scheduleEventFn(waitUntilTick)
    } else if (data.capacityState == LinkCapacityStateEnum.Full) {
      state.status = WaitingCapacity
    } else {
      val tick = currentTickFn()
      state.status = WaitingSignal
      setSignalWaitUntilTickFn(Some(tick))
      setSignalWaitNeedsReverifyFn(false)
      scheduleEventFn(tick)
    }
  }

  /** Call from `onDestruct`, before any state clearing, when a motorcycle is destroyed while
    * `WaitingCapacity`. See `CarSignalHandler.cancelPendingCapacityRequest` for the full
    * rationale.
    */
  def cancelPendingCapacityRequest(state: MotorcycleState): Unit =
    if (state.status == WaitingCapacity) {
      val nodeId = getCurrentNodeFn()
      val linkId = getNextLinkFn()
      if (nodeId != 0L && linkId != 0L) {
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
