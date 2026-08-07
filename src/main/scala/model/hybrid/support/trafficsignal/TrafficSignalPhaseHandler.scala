package org.interscity.htc
package model.hybrid.support.trafficsignal

import org.interscity.htc.model.hybrid.entity.state.TrafficSignalState
import org.interscity.htc.model.hybrid.entity.state.model.{ Phase, SignalState }
import org.interscity.htc.model.hybrid.entity.event.data.signal.TrafficSignalChangeStatusData
import org.interscity.htc.model.hybrid.entity.state.enumeration.{ EventTypeEnum, TrafficSignalPhaseStateEnum }
import org.interscity.htc.model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.{ Green, Red }
import org.interscity.htc.model.hybrid.entity.state.enumeration.EventTypeEnum.TrafficSignalChangeStatus
import org.interscity.htc.core.metrics.model.hybrid.TrafficSignalMetrics
import org.interscity.htc.core.entity.actor.ShardActorId
import org.interscity.htc.core.types.Tick

class TrafficSignalPhaseHandler(
  getStateFn:     () => TrafficSignalState,
  entityIdFn:     () => String,
  currentTickFn:  () => Tick,
  simulationEnd:  Tick,
  reportFn:       (Map[String, Any], String) => Unit,
  sendMessageFn:  (String, String, AnyRef, String) => Unit,
  getDependencyFn: String => ShardActorId,
  scheduleNextFn:  Tick => Unit,
  finishFn:        () => Unit,
  logDebugFn:      String => Unit
) {

  private def state: TrafficSignalState = getStateFn()

  def handlePhaseTransition(currentTick: Tick): Unit = {
    val currentCycleTick = (currentTick - state.startTick + state.offset) % state.cycleDuration

    val transitionPoints = state.phases.flatMap(p => Seq(p.greenStart, p.greenStart + p.greenDuration))
    val nextTickTime = currentTick + transitionPoints.map {
      point =>
        val delta = point - currentCycleTick
        if (delta > 0) delta else delta + state.cycleDuration
    }.min

    logDebugFn(
      s"TrafficSignal tick=$currentTick, currentCycleTick=$currentCycleTick, nextTick=$nextTickTime"
    )

    state.phases.foreach { phase =>
      val newState = calcNewState(currentCycleTick, phase)

      state.signalStates.get(phase.origin).foreach { signalState =>
        signalState.remainingTime = nextTickTime - currentTick
        signalState.nextTick      = nextTickTime
        if (signalState.state != newState) {
          TrafficSignalMetrics.phaseChanges.labels(newState.toString).inc()
          notifyNodes(
            SignalState(
              state         = newState,
              remainingTime = signalState.remainingTime,
              nextTick      = nextTickTime
            ),
            state.nodes,
            phase.origin,
            nextTickTime
          )
        }
        signalState.state = newState
      }
    }

    if (nextTickTime < simulationEnd) scheduleNextFn(nextTickTime)
    else finishFn()
  }

  private def notifyNodes(
    signalState: SignalState,
    nodes:       List[String],
    phaseOrigin: String,
    nextTick:    Tick
  ): Unit = {
    reportFn(
      Map(
        "event_type"    -> "signal_phase_change",
        "signal_id"     -> entityIdFn(),
        "phase_origin"  -> phaseOrigin,
        "phase_state"   -> signalState.state.toString,
        "remaining_time"-> signalState.remainingTime,
        "next_tick"     -> nextTick,
        "affected_nodes"-> nodes.size,
        "tick"          -> currentTickFn()
      ),
      "signal_phase_change"
    )

    nodes.foreach { node =>
      val dependency = getDependencyFn(node)
      sendMessageFn(
        dependency.entityId,
        dependency.classType,
        TrafficSignalChangeStatusData(
          signalState = signalState,
          phaseOrigin = phaseOrigin,
          nextTick    = nextTick
        ),
        TrafficSignalChangeStatus.toString
      )
    }
  }

  private def calcNewState(currentCycleTick: Tick, phase: Phase): TrafficSignalPhaseStateEnum =
    if (currentCycleTick >= phase.greenStart && currentCycleTick < phase.greenStart + phase.greenDuration)
      Green
    else
      Red
}
