package org.interscity.htc
package model.mobility.entity.state.model

import com.fasterxml.jackson.annotation.{ JsonCreator, JsonProperty }

case class SubwayStationNode(
  @JsonProperty("stationId") stationId: Long,
  @JsonProperty("nodeId") nodeId: Long
)
