package org.interscity.htc
package core.entity.event.control.load

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.BaseEvent
import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

/** Sent by a simulation actor to the PostLoadRegistrationCoordinator (via coordinatorRef
  * stored in PostLoadRegistrationEvent) once its registration step is complete.
  *
  * The coordinator uses these ACKs to track completion and, when all expected ACKs are received,
  * notifies LoadDataManager via PostLoadRegistrationDoneEvent.
  *
  * @param entityId
  *   The entity ID of the actor that finished its registration.
  * @param actorRef
  *   The sending actor reference (BaseEvent.actorRef).
  */
case class PostLoadRegistrationAckEvent(
  entityId: String,
  actorRef: ActorRef = null
) extends BaseEvent[DefaultBaseEventData](actorRef = actorRef)
