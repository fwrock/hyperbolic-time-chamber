package org.interscity.htc
package core.entity.event.control.load

import org.apache.pekko.actor.ActorRef
import core.types.Tick

/** Sent to the GlobalTimeManager to register the ProgressiveLoadDataManager for tick-windowed actor
  * creation coordination.
  *
  * @param progressiveLoadManager
  *   reference to the ProgressiveLoadDataManager
  * @param lookAheadTicks
  *   how many ticks ahead to pre-load actors
  */
case class RegisterProgressiveLoadManagerEvent(
  progressiveLoadManager: ActorRef,
  lookAheadTicks: Tick = 1000L
)
