package org.interscity.htc
package model.hybrid.entity.state.model

import com.fasterxml.jackson.annotation.{ JsonCreator, JsonProperty }

case class SubwayRouteEntry(
  @JsonProperty("stationNode") stationNode: SubwayStationNode,
  @JsonProperty("railLinkId") railLinkId: String
)
