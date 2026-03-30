package org.interscity.htc
package model.hybrid.entity.state.model

import com.fasterxml.jackson.annotation.{ JsonCreator, JsonProperty }

case class SubwayStationNode(
  @JsonProperty("stationId") stationId: String,
  @JsonProperty("nodeId") nodeId: String
)
