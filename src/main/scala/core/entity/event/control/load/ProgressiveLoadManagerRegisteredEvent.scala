package org.interscity.htc
package core.entity.event.control.load

import core.types.Tick

import org.interscity.htc.core.entity.event.BaseEvent
import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

/** Ack sent by GlobalTimeManager when progressive manager registration is fully applied.
  *
  * Used as a startup-ordering handshake so SimulationManager can safely send
  * StartSimulationTimeEvent only after GTM is guaranteed to wait for the first progressive window.
  */
case class ProgressiveLoadManagerRegisteredEvent(
  lookAheadTicks: Tick
) extends BaseEvent[DefaultBaseEventData]()

