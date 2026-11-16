package org.interscity.htc
package core.entity.state

import org.interscity.htc.core.entity.actor.ShardActorId
import org.interscity.htc.core.entity.actor.Property

/** Stores legacy property values and logical actor relationships.
  *
  * The maps are immutable containers; their Property records retain mutable values. Relationships
  * carry logical identifiers and routing metadata, allowing state to avoid physical ActorRefs.
  */
case class State(
  properties: Map[String, Property] = Map.empty,
  relationships: Map[String, ShardActorId] = Map.empty
)
