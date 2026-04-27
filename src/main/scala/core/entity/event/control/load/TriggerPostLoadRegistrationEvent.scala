package org.interscity.htc
package core.entity.event.control.load

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.BaseEvent
import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

/** Sent by LoadDataManager to PostLoadRegistrationCoordinator after all EAGER data sources have
  * finished loading. This triggers the coordinator to transition from accumulation phase to
  * execution phase: it fans out PostLoadRegistrationEvent to all entities that previously
  * registered via NeedsPostLoadRegistrationEvent and waits for their ACKs.
  *
  * @param actorRef
  *   The LoadDataManager actor reference (BaseEvent.actorRef).
  */
case class TriggerPostLoadRegistrationEvent(
  actorRef: ActorRef = null
) extends BaseEvent[DefaultBaseEventData](actorRef = actorRef)
