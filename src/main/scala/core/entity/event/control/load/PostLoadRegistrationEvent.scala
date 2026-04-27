package org.interscity.htc
package core.entity.event.control.load

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.BaseEvent
import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

/** Sent via EntityEnvelopeEvent to shard entities that previously signalled
  * NeedsPostLoadRegistrationEvent, after ALL eager sources have finished loading. Guarantees all
  * infrastructure actors (Node, Link, etc.) are fully initialized before actors that need to
  * register with them (BusStop, SubwayStation) do so.
  *
  * @param coordinatorRef
  *   Reference to the PostLoadRegistrationCoordinator. The receiving actor MUST reply with
  *   PostLoadRegistrationAckEvent(entityId) once its registration step is complete. Also stored as
  *   BaseEvent.actorRef for framework compatibility.
  */
case class PostLoadRegistrationEvent(
  coordinatorRef: ActorRef = null
) extends BaseEvent[DefaultBaseEventData](actorRef = coordinatorRef)
