package org.interscity.htc
package model.hybrid.support.bicycle

import core.types.Tick
import org.interscity.htc.model.hybrid.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.hybrid.entity.event.node.SignalStateData
import org.interscity.htc.model.hybrid.entity.state.BicycleState
import org.interscity.htc.model.hybrid.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.{
  WaitingSignal,
  WaitingSignalState
}
import org.interscity.htc.model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red
import org.interscity.htc.model.hybrid.util.CityMapUtil

class BicycleSignalHandler(
  reportFn:                    (Map[String, Any], String) => Unit,
  entityIdFn:                  () => String,
  currentTickFn:               () => Tick,
  journeyReporter:             BicycleJourneyReporter,
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
  setSignalStateRetryCounterFn: Int => Unit,
  setSignalWaitUntilTickFn:    Option[Tick] => Unit
) {

  def requestSignalState(state: BicycleState): Unit = {
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
                  onFinishSpontaneousFn(Some(currentTickFn() + 1))
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

  def handleSignalState(data: SignalStateData, state: BicycleState): Unit = {
    if (state.status != WaitingSignalState) {
      logDebugFn(
        s"${entityIdFn()}: Ignoring stale SignalStateData " +
          s"(current status=${state.status}, expected WaitingSignalState). Race condition guard."
      )
      return
    }
    setSignalStateRetryCounterFn(0)
    if (data.phase == Red) {
      state.status = WaitingSignal
      setSignalWaitUntilTickFn(Some(data.nextTick))
      val waitTicks = math.max(0L, data.nextTick - currentTickFn())
      if (waitTicks > 0) journeyReporter.updateHaltingState(0.0, waitTicks.toDouble)
      reportFn(
        Map(
          "event_type"     -> "signal_wait",
          "vehicle_type"   -> "bicycle",
          "vehicle_id"     -> entityIdFn(),
          "phase"          -> data.phase.toString,
          "wait_until_tick" -> data.nextTick,
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
