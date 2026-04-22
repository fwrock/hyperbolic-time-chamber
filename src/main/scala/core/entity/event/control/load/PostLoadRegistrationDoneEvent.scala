package org.interscity.htc
package core.entity.event.control.load

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.BaseEvent
import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

/** Sent by PostLoadRegistrationCoordinator to LoadDataManager when all registered actors have
  * completed their post-load registration steps (all PostLoadRegistrationAckEvents received,
  * or the retry limit was reached).
  *
  * Upon receiving this event, LoadDataManager proceeds to send FinishLoadDataEvent to
  * SimulationManager, allowing the simulation to start.
  *
  * @param actorRef
  *   The coordinator actor reference (BaseEvent.actorRef).
  */
case class PostLoadRegistrationDoneEvent(
  actorRef: ActorRef = null
) extends BaseEvent[DefaultBaseEventData](actorRef = actorRef)
