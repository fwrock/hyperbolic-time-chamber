package org.interscity.htc
package model.mobility.entity.state.model

/** Checkpointed logical destination for boundary observations; never retains an ActorRef. */
final case class MicroBoundarySubscriber(id: Long, shardId: String) {
  require(
    shardId == "mobility.actor.Link" || shardId == "mobility.actor.Node",
    "unsupported MICRO boundary subscriber shard"
  )
}
