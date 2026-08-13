package org.interscity.htc
package core.entity.event

import core.actor.rollback.MessageId
import core.types.Tick

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.enumeration.CreationTypeEnum

case class ActorInteractionEvent(
  tick: Tick,
  lamportTick: Tick,
  // Sender's logical entity id -- Long like Identify.id/Dependency.id, converted from/to the
  // Pekko-facing String exactly once, at the message-machinery boundary (SimulationBaseActor's
  // sendMessageTo*/the wire serializers), never re-derived in business-logic handlers. `actorRef`
  // below is the genuinely unavoidable Pekko path/selection string -- keep that as String.
  actorRefId: Long,
  shardRefId: String,
  actorPathRef: String,
  actorClassType: String,
  eventType: String = "default",
  data: AnyRef,
  actorType: String = CreationTypeEnum.LoadBalancedDistributed.toString,
  resourceId: String,
  // Time Warp (docs/TIME_WARP_DESIGN.md §10): see communication.proto's ActorInteraction.seq/
  // isAntiMessage for the full rationale. 0/false for every conservative-mode send.
  seq: Long = 0L,
  isAntiMessage: Boolean = false
) {

  def toIdentity: Identify = Identify(
    id = actorRefId,
    classType = actorClassType,
    actorRef = actorPathRef
  )

  /** This send's identity for the anti-message cascade — `actorRefId` (the sender) paired with
    * `tick`/`seq`, matching `MessageId`'s shape exactly since both already carry the same
    * `(senderId, tick)` pair; `seq` is the only piece not already present elsewhere on this event.
    * `MessageId.senderId` is still String (unconverted elsewhere, e.g. `SimulationBaseActor`'s own
    * `MessageId(senderId = getEntityId, ...)`), so this is the one legitimate `.toString` -- the
    * inverse edge conversion, not a business-logic re-derivation.
    */
  def messageId: MessageId = MessageId(senderId = actorRefId.toString, tick = tick, seq = seq)
}
