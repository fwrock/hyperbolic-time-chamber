package org.interscity.htc
package model.hybrid.support.car

import core.types.Tick
import org.interscity.htc.model.hybrid.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.hybrid.entity.event.node.SignalStateData
import org.interscity.htc.model.hybrid.entity.state.CarState
import org.interscity.htc.model.hybrid.entity.state.enumeration.{EventTypeEnum, MovableStatusEnum}
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.{Ready, WaitingSignal, WaitingSignalState}
import org.interscity.htc.model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red
import org.interscity.htc.model.hybrid.util.CityMapUtil

/** Handles traffic signal interaction for Car actors.
  *
  * Responsibilities:
  *   - requestSignalState: determine if at destination or request signal phase from node
  *   - handleSignalState: react to Red/Green response, guard against stale duplicates
  */
class CarSignalHandler(
  private val reportFn: (Map[String, Any], String) => Unit,
  private val entityIdFn: () => String,
  private val currentTickFn: () => Tick,
  private val journeyReporter: CarJourneyReporter,
  private val onFinishSpontaneousFn: Option[Tick] => Unit,
  private val deferFinishSpontaneousFn: () => Unit,
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
                RequestSignalStateData(targetLinkId = linkId),
                EventTypeEnum.RequestSignalState.toString
              )
              // Consistency-critical: do NOT resolve here. Node always replies on every
              // branch (NodeEventHandler.handleRequestSignalState) — wait for that reply
              // as an interaction event; handleSignalState resolves the spontaneous event.
              // deferFinishSpontaneousFn() suppresses SimulationBaseActor's own "actSpontaneous
              // returned without resolving" safety net (which would otherwise auto-recover with
              // onFinishSpontaneous(currentTick + 1) AND log a [BUG] error on every request).
              deferFinishSpontaneousFn()
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

  def handleSignalState(data: SignalStateData, state: CarState): Unit = {
    // Guard against stale/duplicate responses from the retry mechanism.
    if (state.status != WaitingSignalState) {
      logStaleEventDebugFn(
        s"${entityIdFn()}: Ignoring stale SignalStateData " +
          s"(current status=${state.status}, expected WaitingSignalState). Race condition guard."
      )
      return
    }

    if (data.phase == Red) {
      val tick      = currentTickFn()
      // Saturation headway ~2s per vehicle (HCM 6th ed. §19)
      val headwayTicks     = 2L
      val adjustedNextTick = data.nextTick + data.queuePosition.toLong * headwayTicks
      val waitTicks        = math.max(0L, adjustedNextTick - tick)
      state.status = WaitingSignal
      setSignalWaitUntilTickFn(Some(adjustedNextTick))
      if (waitTicks > 0) journeyReporter.updateHaltingState(speed = 0.0, deltaSeconds = waitTicks.toDouble)
      if (waitTicks > 0) onSignalWaitFn(waitTicks)

      reportFn(
        Map(
          "event_type"         -> "signal_wait",
          "vehicle_type"       -> "car",
          "vehicle_id"         -> entityIdFn(),
          "phase"              -> data.phase.toString,
          "queue_position"     -> data.queuePosition,
          "wait_until_tick"    -> adjustedNextTick,
          "adjusted_next_tick" -> adjustedNextTick,
          "tick"               -> tick
        ),
        "signal_wait"
      )

      onFinishSpontaneousFn(Some(adjustedNextTick))
    } else {
      leavingLinkFn()
    }
  }
}
