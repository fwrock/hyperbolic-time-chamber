package org.interscity.htc
package core.entity.event

import core.actor.rollback.MessageId
import core.types.Tick

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.enumeration.CreationTypeEnum

case class ActorInteractionEvent(
  tick: Tick,
  lamportTick: Tick,
  actorRefId: String,
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
    */
  def messageId: MessageId = MessageId(senderId = actorRefId, tick = tick, seq = seq)
}
