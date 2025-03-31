package org.interscity.htc
package core.entity.state

import org.htc.protobuf.core.entity.actor.Relationship
import org.interscity.htc.core.entity.actor.Property

case class State(
  properties: Map[String, Property] = Map.empty,
  relationships: Map[String, Relationship] = Map.empty
)
