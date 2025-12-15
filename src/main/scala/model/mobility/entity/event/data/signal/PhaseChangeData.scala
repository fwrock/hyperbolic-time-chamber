package org.interscity.htc
package model.mobility.entity.event.data.signal

import core.entity.event.data.BaseEventData
import core.types.Tick
import model.mobility.entity.state.enumeration.TrafficSignalPhaseStateEnum

/** Event-driven: Traffic signal phase transition (pre-scheduled)
  *
  * Replaces continuous phase checking every tick. All phase transitions
  * are pre-scheduled during initialization.
  *
  * @param phaseId     Phase identifier
  * @param phaseOrigin Origin node for this phase
  * @param newState    New phase state (Green/Red)
  * @param validUntil  Tick until state remains stable
  */
case class PhaseChangeData(
  phaseId: String,
  phaseOrigin: String,
  newState: TrafficSignalPhaseStateEnum,
  validUntil: Tick
) extends BaseEventData
