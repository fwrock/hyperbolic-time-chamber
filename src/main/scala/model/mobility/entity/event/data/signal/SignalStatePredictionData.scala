package org.interscity.htc
package model.mobility.entity.event.data.signal

import core.entity.event.data.BaseEventData
import core.types.Tick
import model.mobility.entity.state.enumeration.TrafficSignalPhaseStateEnum
import model.mobility.entity.state.enumeration.TrafficSignalPhaseStateEnum.Green

/** Event-driven: Time-bounded signal state prediction
  *
  * Allows vehicles to plan travel without synchronous queries.
  * Provides deterministic, time-bounded signal state information.
  *
  * @param currentState        Current phase state
  * @param validUntil          Tick until current state is guaranteed
  * @param nextState           State after validUntil (if known)
  * @param nextTransitionTick  Tick of next transition (if within horizon)
  */
case class SignalStatePredictionData(
  currentState: TrafficSignalPhaseStateEnum,
  validUntil: Tick,
  nextState: Option[TrafficSignalPhaseStateEnum],
  nextTransitionTick: Option[Tick]
) extends BaseEventData {
  
  /** Check if signal will be green at given tick */
  def isGreenAt(tick: Tick): Boolean = {
    if (tick < validUntil) {
      currentState == Green
    } else {
      nextState.contains(Green)
    }
  }
  
  /** Get tick when signal will next be green */
  def nextGreenTick: Tick = {
    if (currentState == Green) {
      validUntil
    } else {
      nextTransitionTick.getOrElse(Long.MaxValue)
    }
  }
  
  /** Check if signal will interfere with arrival */
  def willBeRed(arrivalTick: Tick): Boolean = {
    !isGreenAt(arrivalTick)
  }
}
