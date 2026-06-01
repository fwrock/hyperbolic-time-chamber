package org.interscity.htc
package core.entity.event.control.load

import core.types.Tick

import org.interscity.htc.core.entity.event.BaseEvent
import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

/** Sent by ProgressiveLoadDataManager back to GlobalTimeManager to signal that all actors with
  * startTick <= readyUpToTick have been created and initialized.
  *
  * @param readyUpToTick
  *   the maximum tick for which all actors are guaranteed to be ready
  * @param actorsCreated
  *   number of actors created in this window
  */
case class TickWindowReady(
  readyUpToTick: Tick,
  actorsCreated: Long
) extends BaseEvent[DefaultBaseEventData]
