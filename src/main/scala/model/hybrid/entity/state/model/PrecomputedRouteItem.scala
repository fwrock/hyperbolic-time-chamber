package org.interscity.htc
package model.hybrid.entity.state.model

import com.fasterxml.jackson.annotation.JsonProperty

case class PrecomputedRouteItem(
  @JsonProperty("linkId") linkId: Long,
  @JsonProperty("nodeId") nodeId: Long
)
