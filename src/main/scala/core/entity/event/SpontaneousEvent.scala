package org.interscity.htc
package core.entity.event

import core.types.{ EventId, SubTick, Tick }

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

/** Spontaneous event that triggers actor execution
  * 
  * @param tick
  *   current simulation tick
  * @param actorRef
  *   reference to the time manager
  * @param safeHorizon
  *   maximum tick the actor can safely advance to without external synchronization
  *   (default: same as tick for conservative execution)
  */
case class SpontaneousEvent(
  tick: Tick,
  actorRef: ActorRef,
  safeHorizon: Tick = -1 // -1 means use tick (no lookahead)
) extends BaseEvent[DefaultBaseEventData](tick = tick, actorRef = actorRef) {
  
  /** Returns the effective safe horizon (tick if not set) */
  def effectiveSafeHorizon: Tick = if (safeHorizon == -1) tick else safeHorizon
  
  /** Returns true if lookahead is enabled (horizon > tick) */
  def hasLookahead: Boolean = safeHorizon > tick
}
