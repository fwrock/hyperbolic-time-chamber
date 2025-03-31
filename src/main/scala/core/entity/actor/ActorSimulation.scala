package org.interscity.htc
package core.entity.actor

import core.enumeration.CreationTypeEnum

import com.fasterxml.jackson.annotation.{JsonCreator, JsonProperty}
import org.htc.protobuf.core.entity.actor.Dependency
import org.interscity.htc.core.enumeration.CreationTypeEnum.Simple

case class ActorSimulation @JsonCreator() (
  @JsonProperty("id") id: String,
  @JsonProperty("name") name: String,
  @JsonProperty("typeActor") typeActor: String,
  @JsonProperty("creationType") creationType: CreationTypeEnum = Simple,
  @JsonProperty("poolConfiguration") poolConfiguration: PoolDistributedConfiguration =
    PoolDistributedConfiguration(),
  @JsonProperty("data") data: ActorDataSimulation,
  @JsonProperty("dependencies") dependencies: Map[String, Dependency] = null
)
