package org.interscity.htc
package core.util

import org.htc.protobuf.core.entity.actor.{ Dependency, Identify }
import org.interscity.htc.core.entity.actor.ShardActorId

/** Builds protobuf identity envelopes without resolving physical actor references.
  *
  * Identify carries no shard bucket or composition relation. Preserve the original ShardActorId
  * separately wherever routing or partial-simulation membership still needs that metadata.
  */
object IdentifyUtil {

  /** Copies a legacy dependency identity and the optional caller-supplied actor path. */
  def fromDependency(dependency: Dependency, actorPathRef: String = null): Identify =
    Identify(
      id = dependency.id,
      classType = dependency.classType,
      actorRef = actorPathRef
    )

  /** Adapts a logical relationship to the legacy dependency API without adding an actor path. */
  def fromDependency(relationship: ShardActorId): Identify = fromRelationship(relationship)

  /** Copies entity/class identity with an optional actor path, omitting bucket/relation metadata.
    */
  def fromRelationship(relationship: ShardActorId, actorPathRef: String = null): Identify =
    Identify(
      id = relationship.entityId,
      classType = relationship.classType,
      actorRef = actorPathRef
    )

}
