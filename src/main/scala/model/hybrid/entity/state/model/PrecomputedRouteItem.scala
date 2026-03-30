package org.interscity.htc
package model.hybrid.entity.state.model

import com.fasterxml.jackson.annotation.JsonProperty

case class PrecomputedRouteItem(
  @JsonProperty("linkId") linkId: String,
  @JsonProperty("nodeId") nodeId: String
)
