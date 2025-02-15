package org.interscity.htc
package system.entity.event

import core.entity.event.data.BaseEventData

import org.apache.pekko.actor.ActorRef

sealed trait Command

/** Base class for all events.
 *
 * @param actorRef
 *   the actor that sent the event, shard region actor
 * @param actorRefId
 *   the id of the actor that sent the event, shard region actor id
 * @param eventType
 *   the type of the event
 */
abstract class BaseEvent(
                                              actorRef: ActorRef = null,
                                              actorRefId: String = null,
                                              eventType: String = "default"
                                            ) extends Command {

  def getActorRef: ActorRef = actorRef

  def getActorRefId: String = actorRefId
}
