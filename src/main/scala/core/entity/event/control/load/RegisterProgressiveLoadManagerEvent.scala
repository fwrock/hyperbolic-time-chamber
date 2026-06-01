package org.interscity.htc
package core.entity.event.control.load

import org.apache.pekko.actor.ActorRef
import core.types.Tick

import org.interscity.htc.core.entity.event.BaseEvent
import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

/** Sent to the GlobalTimeManager to register the ProgressiveLoadDataManager for tick-windowed actor
  * creation coordination.
  *
  * @param progressiveLoadManager
  *   reference to the ProgressiveLoadDataManager
  * @param lookAheadTicks
  *   how many ticks ahead to pre-load actors
   * @param ackTo
   *   optional actor to notify once registration is applied (startup ordering handshake)
  */
case class RegisterProgressiveLoadManagerEvent(
  progressiveLoadManager: ActorRef,
    lookAheadTicks: Tick = 1000L,
    ackTo: Option[ActorRef] = None
) extends BaseEvent[DefaultBaseEventData]()
