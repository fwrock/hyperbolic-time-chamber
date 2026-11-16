package org.interscity.htc
package model.hybrid.entity.event.data.signal

import core.types.Tick
import model.hybrid.entity.state.model.SignalState
import model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum
import com.fasterxml.jackson.databind.annotation.JsonDeserialize

/** Entries in one same-tick native controller publication; ordinary accounted transport. */
final case class SignalAspectUpdate(phaseOrigin: Long, signalState: SignalState, nextTick: Tick)
final case class MovementAspectUpdate(targetLinkId: Long,
  @JsonDeserialize(contentAs = classOf[java.lang.Integer]) laneIds: List[Int],
  phase: TrafficSignalPhaseStateEnum, nextTick: Tick)
