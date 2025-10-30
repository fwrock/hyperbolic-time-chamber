package org.interscity.htc
package model.mobility.actor

import core.actor.{BaseActor, SimulationBaseActor}
import model.mobility.entity.state.TrafficSignalState

import org.interscity.htc.core.entity.actor.properties.{Properties, SimulationBaseProperties}
import org.interscity.htc.core.entity.event.SpontaneousEvent
import org.interscity.htc.core.types.Tick
import org.interscity.htc.model.mobility.entity.event.data.signal.TrafficSignalChangeStatusData
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum.TrafficSignalChangeStatus
import org.interscity.htc.model.mobility.entity.state.enumeration.{EventTypeEnum, TrafficSignalPhaseStateEnum}
import org.interscity.htc.model.mobility.entity.state.enumeration.TrafficSignalPhaseStateEnum.{Green, Red}
import org.interscity.htc.model.mobility.entity.state.model.{Phase, SignalState}

import scala.collection.mutable

class TrafficSignal(
  private val properties: SimulationBaseProperties
) extends SimulationBaseActor[TrafficSignalState](
      properties = properties
    ) {

  override protected def actSpontaneous(event: SpontaneousEvent): Unit =
    handlePhaseTransition(event.tick)

  private def handlePhaseTransition(currentTick: Tick): Unit =
    state.phases.foreach {
      phase =>
        val currentCycleTick = (currentTick + state.offset) % state.cycleDuration
        val newState = calcNewState(currentCycleTick, phase)
        val nextTickTime =
          currentCycleTick + state.cycleDuration - (currentCycleTick % state.cycleDuration)

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
        /*
        if (changedOrigins.nonEmpty) {
          state.nodes.foreach {
            node =>
              notifyNodes(SignalState(
                state = newState,
                remainingTime = signalState.remainingTime,
                nextTick = nextTickTime,
              ), state.nodes.filterNot(changedOrigins.contains), phase.origin, nextTickTime)
          }
        }*/
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
