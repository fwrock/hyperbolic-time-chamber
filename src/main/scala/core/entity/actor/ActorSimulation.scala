package org.interscity.htc
package core.entity.actor

import core.enumeration.CreationTypeEnum

import com.fasterxml.jackson.annotation.{ JsonAlias, JsonCreator, JsonProperty }
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed

case class ActorSimulation @JsonCreator() (
  @JsonProperty("id") id: String,
  @JsonProperty("name") name: String,
  @JsonProperty("typeActor") typeActor: String,
  @JsonProperty("creationType") creationType: CreationTypeEnum = LoadBalancedDistributed,
  @JsonProperty("poolConfiguration") poolConfiguration: PoolDistributedConfiguration =
    PoolDistributedConfiguration(),
  @JsonProperty("data") data: ActorDataSimulation,
  @JsonAlias(Array("dependencies")) @JsonProperty("relationships") relationships: Map[
    String,
    ShardActorId
  ] = null
)
