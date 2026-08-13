package org.interscity.htc
package core.entity.actor

import org.apache.pekko.actor.ActorRef

case class Identify(
  id: Long,
  shardId: String,
  classType: String,
  actorRef: ActorRef
) {
  def toDependency: Dependency =
    Dependency(id = id, resourceId = shardId, classType = classType)

  def toRelationship: ShardActorId =
    ShardActorId(entityId = id, classType = classType, shardBucket = shardId)
}
