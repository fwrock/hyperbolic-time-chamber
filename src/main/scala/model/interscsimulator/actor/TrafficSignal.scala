package org.interscity.htc
package model.interscsimulator.actor

import core.actor.BaseActor

import org.apache.pekko.actor.ActorRef
import core.entity.event.{ FinishEvent, ScheduleEvent, SpontaneousEvent }
import model.interscsimulator.entity.state.TrafficSignalState

import org.interscity.htc.core.entity.actor.Identify
import org.interscity.htc.core.types.CoreTypes.Tick
import org.interscity.htc.model.interscsimulator.entity.event.data.signal.TrafficSignalChangeStatusData
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.EventTypeEnum.TrafficSignalChangeStatus
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.{ EventTypeEnum, TrafficSignalPhaseStateEnum }
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.TrafficSignalPhaseStateEnum.{ Green, Red }
import org.interscity.htc.model.interscsimulator.entity.state.model.{ Phase, SignalState }

import scala.collection.mutable

class TrafficSignal(
  override protected val actorId: String = null,
  private val timeManager: ActorRef = null,
  private val data: String = null,
  override protected val dependencies: mutable.Map[String, Identify] =
    mutable.Map[String, Identify]()
) extends BaseActor[TrafficSignalState](
      actorId = actorId,
      timeManager = timeManager,
      data = data,
      dependencies = dependencies
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
        sendMessageTo(dependencies(node), data, TrafficSignalChangeStatus.toString)
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
