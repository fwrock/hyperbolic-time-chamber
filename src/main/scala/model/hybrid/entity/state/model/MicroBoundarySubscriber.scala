package org.interscity.htc
package model.hybrid.entity.state.model

/** Checkpointed logical destination for boundary observations; never retains an ActorRef. */
final case class MicroBoundarySubscriber(id: Long, shardId: String) {
  require(
    shardId == "hybrid.actor.Link" || shardId == "hybrid.actor.Node",
    "unsupported MICRO boundary subscriber shard"
  )
}
