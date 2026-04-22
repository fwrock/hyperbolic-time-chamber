package org.interscity.htc
package core.entity.state

import org.interscity.htc.core.entity.actor.ShardActorId
import org.interscity.htc.core.entity.actor.Property

case class State(
  properties: Map[String, Property] = Map.empty,
  relationships: Map[String, ShardActorId] = Map.empty
)
