package org.interscity.htc
package core.entity.event

import core.types.CoreTypes.Tick

import core.entity.event.data.BaseEventData

import org.apache.pekko.actor.{ ActorContext, ActorRef }
import org.apache.pekko.event.Logging

sealed trait Command

/** Base class for all events.
  *
  * @param lamportTick
  *   the Lamport clock tick
  * @param data
  *   the data of the event
  * @param tick
  *   the tick when the event was created
  * @param actorRef
  *   the actor that sent the event, shard region actor
  * @param actorRefId
  *   the id of the actor that sent the event, shard region actor id
  * @param eventType
  *   the type of the event
  * @tparam D
  *   the type of the data of the event
  */
abstract class BaseEvent[D <: BaseEventData](
  lamportTick: Tick = 0,
  data: D = null,
  tick: Tick = Long.MinValue,
  actorRef: ActorRef = null,
  actorRefId: String = null,
  eventType: String = "default"
) extends Command {

  def logEvent(context: ActorContext, actorRef: ActorRef): Unit = {
    val log = Logging.getLogger(context.system, actorRef)
    log.info(
      s"${this.getClass.getSimpleName} at tick $tick with Lamport clock $lamportTick sending by ${if (actorRef != null) actorRef.path.name else "null"}"
    )
  }

  def getLamportClock: Tick =
    lamportTick

  def getTick: Tick =
    tick

  def getActorRef: ActorRef =
    actorRef

  def getData: D = data
}
