package org.interscity.htc
package core.util

import org.htc.protobuf.core.entity.actor.{ Dependency, Identify }
import org.interscity.htc.core.entity.actor.ShardActorId

object IdentifyUtil {

  def fromDependency(dependency: Dependency, actorPathRef: String = null): Identify =
    Identify(
      id = dependency.id,
      classType = dependency.classType,
      actorRef = actorPathRef
    )

  /** Overload accepting [[ShardActorId]] for callers that pass the result of
    * [[core.actor.SimulationBaseActor.getDependency]] after the Dependency → ShardActorId
    * migration.
    */
  def fromDependency(relationship: ShardActorId): Identify =
    Identify(
      id = relationship.entityId,
      classType = relationship.classType,
      actorRef = null
    )

  def fromRelationship(relationship: ShardActorId, actorPathRef: String = null): Identify =
    Identify(
      id = relationship.entityId,
      classType = relationship.classType,
      actorRef = actorPathRef
    )

}
