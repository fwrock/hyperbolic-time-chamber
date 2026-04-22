package org.interscity.htc
package core.entity.event.control.load

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.BaseEvent
import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

/** Sent by a simulation actor to its creator (CreatorLoadData) during onInitialize, before
  * sending InitializeEntityAckEvent, to signal that this actor requires a post-load
  * registration step before simulation can start.
  *
  * The creator forwards this directly to PostLoadRegistrationCoordinator, which accumulates
  * all such requests and dispatches them after all EAGER loading is complete.
  *
  * @param entityId
  *   The entity ID of the actor that needs post-load registration.
  * @param classType
  *   The short class name of the actor (e.g. "hybrid.actor.BusStop").
  *   Used by the coordinator to resolve the shard region.
  * @param actorRef
  *   The creator actor reference (BaseEvent.actorRef).
  */
case class NeedsPostLoadRegistrationEvent(
  entityId: String,
  classType: String,
  actorRef: ActorRef = null
) extends BaseEvent[DefaultBaseEventData](actorRef = actorRef)
