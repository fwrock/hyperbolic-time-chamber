package org.interscity.htc
package core.entity.actor

case class ActorSimulation(
  id: String,
  name: String,
  typeActor: String,
  data: ActorDataSimulation,
  dependencies: Map[String, String] = null
)
