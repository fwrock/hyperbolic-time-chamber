package org.interscity.htc
package core.actor.rollback

/** A message sent while processing a [[LoggedEvent]], with enough addressing info to actually
  * route an anti-message to it later — not just the [[MessageId]] identifying *which* send, but
  * *where* it went.
  *
  * This is the fix for a gap left open when [[MessageId]]/`LoggedEvent.sentMessageIds` were first
  * introduced (`docs/TIME_WARP_DESIGN.md`'s step-3 log): `ActorInteractionEvent`'s sender-identity
  * fields (`actorRefId`/`shardRefId`) are the *sender's own* identity, not the receiver's — the
  * receiver's address is a routing-only parameter to `SimulationBaseActor.sendMessageTo` that's
  * never otherwise retained after the send. Without capturing it here, a rollback would know a
  * message needs to be retracted but not have anywhere to send the anti-message.
  *
  * @param messageId
  *   identity of the original send, for the receiver to look up in its own event log
  * @param receiverId
  *   the destination entity id passed to `sendMessageTo`
  * @param receiverShardId
  *   the destination shard id passed to `sendMessageTo` (meaningful only for
  *   `LoadBalancedDistributed` receivers; ignored for `PoolDistributed` ones, same as the original
  *   send)
  * @param receiverActorType
  *   `CreationTypeEnum` name of the receiver, so the anti-message is routed the same way the
  *   original send was (shard region vs. pool actor selection)
  */
final case class SentMessage(
  messageId: MessageId,
  receiverId: String,
  receiverShardId: String,
  receiverActorType: String
)
