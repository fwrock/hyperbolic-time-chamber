package org.interscity.htc
package model.hybrid.actor

import core.actor.SimulationBaseActor
import org.interscity.htc.model.hybrid.entity.state.*

import org.interscity.htc.model.hybrid.entity.state.TrafficSignalState

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.SpontaneousEvent
import org.interscity.htc.core.entity.event.control.load.InitializeEvent
import org.interscity.htc.core.types.Tick
import org.interscity.htc.model.hybrid.entity.event.data.signal.TrafficSignalChangeStatusData
import org.interscity.htc.model.hybrid.entity.state.enumeration.EventTypeEnum.TrafficSignalChangeStatus
import org.interscity.htc.model.hybrid.entity.state.enumeration.{ EventTypeEnum, TrafficSignalPhaseStateEnum }
import org.interscity.htc.model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.{ Green, Red }
import org.interscity.htc.model.hybrid.entity.state.model.{ Phase, SignalState }

import scala.collection.mutable

class TrafficSignal(
  private val properties: Properties
) extends SimulationBaseActor[TrafficSignalState](
      properties = properties
    ) {

  override def onInitialize(event: InitializeEvent): Unit = {
    super.onInitialize(event)
    // Agendar primeiro tick considerando o offset
    val firstTick = state.startTick + state.offset
    logInfo(s"TrafficSignal ${getEntityId} initialized. First tick: $firstTick, cycleDuration: ${state.cycleDuration}, offset: ${state.offset}")
    onFinishSpontaneous(Some(firstTick))
  }

  override protected def actSpontaneous(event: SpontaneousEvent): Unit =
    handlePhaseTransition(event.tick)

  private def handlePhaseTransition(currentTick: Tick): Unit = {
    // Calcular posição no ciclo atual
    val currentCycleTick = (currentTick - state.startTick + state.offset) % state.cycleDuration
    
    // Próximo tick é no início do próximo ciclo
    val ticksSinceStart = currentTick - state.startTick + state.offset
    val nextCycleStart = ((ticksSinceStart / state.cycleDuration) + 1) * state.cycleDuration
    val nextTickTime = state.startTick + nextCycleStart - state.offset
    
    logDebug(s"TrafficSignal tick=$currentTick, currentCycleTick=$currentCycleTick, nextTick=$nextTickTime")
    
    state.phases.foreach {
      phase =>
        val newState = calcNewState(currentCycleTick, phase)

        val changedOrigins = mutable.Set[String]()

        state.signalStates.get(phase.origin).foreach {
          signalState =>
            signalState.remainingTime = phase.greenStart + phase.greenDuration - currentCycleTick
            if (signalState.state != newState) {
              notifyNodes(
                SignalState(
                  state = newState,
                  remainingTime = signalState.remainingTime,
                  nextTick = nextTickTime
                ),
                state.nodes,
                phase.origin,
                nextTickTime
              )
              changedOrigins.add(phase.origin)
            }
            signalState.state = newState
        }
    }
    
    // Agendar próximo tick (uma vez para todas as fases)
    onFinishSpontaneous(Some(nextTickTime))
  }

  private def notifyNodes(
    signalState: SignalState,
    nodes: List[String],
    phaseOrigin: String,
    nextTick: Tick
  ): Unit =
    nodes.foreach {
      node =>
        val data = TrafficSignalChangeStatusData(
          signalState = signalState,
          phaseOrigin = phaseOrigin,
          nextTick = nextTick
        )
        val dependency = getDependency(node)
        sendMessageTo(dependency.id, dependency.classType, data, TrafficSignalChangeStatus.toString)
    }

  private def calcNewState(currentCycleTick: Tick, phase: Phase): TrafficSignalPhaseStateEnum =
    if (
      currentCycleTick >= phase.greenStart && currentCycleTick < phase.greenStart + phase.greenDuration
    ) {
      Green
    } else {
      Red
    }
}
