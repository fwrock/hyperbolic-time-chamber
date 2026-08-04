package org.interscity.htc
package model.hybrid.support.motorcycle

import core.types.Tick
import org.interscity.htc.model.hybrid.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.hybrid.entity.event.node.SignalStateData
import org.interscity.htc.model.hybrid.entity.state.MotorcycleState
import org.interscity.htc.model.hybrid.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.{
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
  leavingLinkFn:               () => Unit,
  selfDestructFn:              () => Unit,
  isPersonCentricFn:           () => Boolean,
  logDebugFn:                  String => Unit,
  sendMessageFn:               (String, String, AnyRef, String) => Unit,
  getCurrentNodeFn:            () => String,
  getNextLinkFn:               () => String,
  getTripDestinationFn:        () => Option[String],
  finishJourneyFn:             (String, String) => Unit,
  onFinishPrivateVehicleFn:    String => Unit,
  aggressivenessFn:            () => Double,
  setSignalWaitUntilTickFn:    Option[Tick] => Unit
) {

  def requestSignalState(state: MotorcycleState): Unit = {
    val currentPathNode = state.currentPath.map(_._2).orNull
    val routeDepleted   = state.bestRoute.forall(_.isEmpty)
    val tripDest        = getTripDestinationFn().getOrElse(state.destination)
    if (tripDest == currentPathNode || routeDepleted) {
      val nodeId = getCurrentNodeFn()
      if (nodeId != null) {
        finishJourneyFn("reached_destination", nodeId)
        onFinishPrivateVehicleFn(nodeId)
      } else {
        finishJourneyFn("no_current_node", "unknown")
        onFinishPrivateVehicleFn("unknown")
      }
      onFinishSpontaneousFn(None)
      if (!isPersonCentricFn()) selfDestructFn()
    } else {
      state.status = WaitingSignalState
      val nodeId = getCurrentNodeFn()
      nodeId match {
        case nid if nid != null =>
          CityMapUtil.nodesById.get(nid) match {
            case Some(node) =>
              getNextLinkFn() match {
                case linkId if linkId != null =>
                  sendMessageFn(
                    node.id,
                    node.classType,
                    RequestSignalStateData(targetLinkId = linkId),
                    EventTypeEnum.RequestSignalState.toString
                  )
                // Consistency-critical: do NOT resolve here. Node always replies on every
                // branch — wait for that reply as an interaction event; handleSignalState
                // resolves the spontaneous event.
                case null =>
                  leavingLinkFn()
              }
            case None =>
              leavingLinkFn()
          }
        case null =>
          leavingLinkFn()
      }
    }
  }

  def handleSignalState(data: SignalStateData, state: MotorcycleState): Unit = {
    if (state.status != WaitingSignalState) {
      logDebugFn(
        s"${entityIdFn()}: Ignoring stale SignalStateData " +
          s"(current status=${state.status}, expected WaitingSignalState). Race condition guard."
      )
      return
    }
    if (data.phase == Red) {
      state.status = WaitingSignal
      setSignalWaitUntilTickFn(Some(data.nextTick))
      val waitTicks = math.max(0L, data.nextTick - currentTickFn())
      if (waitTicks > 0) journeyReporter.updateHaltingState(0.0, waitTicks.toDouble)
      reportFn(
        Map(
          "event_type"     -> "signal_wait",
          "vehicle_type"   -> "motorcycle",
          "vehicle_id"     -> entityIdFn(),
          "phase"          -> data.phase.toString,
          "wait_until_tick" -> data.nextTick,
          "aggressiveness" -> aggressivenessFn(),
          "tick"           -> currentTickFn()
        ),
        "signal_wait"
      )
      onFinishSpontaneousFn(Some(data.nextTick))
    } else {
      leavingLinkFn()
    }
  }
}
