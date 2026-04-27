package org.interscity.htc
package core.entity.actor

import com.fasterxml.jackson.annotation.{ JsonAlias, JsonCreator, JsonProperty }

/** A typed reference to another actor that this actor needs to communicate with.
  *
  * Replaces the older [[Dependency]] / protobuf Dependency concept with a richer type that carries
  * the assigned shard bucket so that cross-shard routing works correctly even when the
  * LoadBalanceManager assigns non-hash-based buckets.
  *
  * @param entityId
  *   The fully-qualified entity ID (e.g. `htcaid:node;123`). Accepted in JSON as `"entityId"` or
  *   legacy `"id"`.
  * @param classType
  *   The actor class name used as the Pekko ShardRegion name.
  * @param shardBucket
  *   The shard bucket assigned by the LoadBalanceManager. Empty string = fall back to hash-based
  *   routing in [[core.util.ActorCreatorUtil.extractShardId]]. Accepted in JSON as `"shardBucket"`
  *   or legacy `"resourceId"`.
  */
case class ShardActorId @JsonCreator() (
  @JsonAlias(Array("entityId")) @JsonProperty("id") entityId: String,
  @JsonProperty("classType") classType: String,
  @JsonAlias(Array("shardBucket")) @JsonProperty("resourceId") shardBucket: String = ""
) {

  /** Backward-compat alias for [[entityId]].
    * @deprecated
    *   Use entityId instead.
    */
  @deprecated("Use entityId instead", "2.8.0")
  def id: String = entityId

  /** Backward-compat alias for [[shardBucket]].
    * @deprecated
    *   Use shardBucket instead.
    */
  @deprecated("Use shardBucket instead", "2.8.0")
  def resourceId: String = shardBucket

  /** Stub present only so that existing code reading the protobuf `Dependency.actorType` field
    * continues to compile during migration. Always returns an empty string.
    * @deprecated
    *   actorType is not part of ShardActorId.
    */
  @deprecated("actorType is not part of ShardActorId", "2.8.0")
  def actorType: String = ""
}
