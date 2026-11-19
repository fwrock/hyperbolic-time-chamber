package org.interscity.htc
package model.hybrid.entity.state.model

import com.fasterxml.jackson.databind.annotation.JsonDeserialize

/** Two junction connectors whose paths cross inside the junction (G04).
  *
  * Occupancy of either connector excludes the other. When both are requested, `priorityConnector`
  * decides; with no priority the older request wins (tick, vehicle id, request sequence). The rule
  * must name its source: priority is never deduced from the numeric order of IDs.
  */
final case class JunctionConflict(
  connectorA: Long,
  connectorB: Long,
  @JsonDeserialize(contentAs = classOf[java.lang.Long])
  priorityConnector: Option[Long] = None,
  source: String = ""
) {
  require(connectorA != connectorB, "a connector cannot conflict with itself")
  require(priorityConnector.forall(p => p == connectorA || p == connectorB),
    "conflict priority must name one of its connectors")
  require(source != null && source.trim.nonEmpty, "a junction conflict must name the source of its rule")
  def involves(connector: Long): Boolean = connector == connectorA || connector == connectorB
  def other(connector: Long): Long = if (connector == connectorA) connectorB else connectorA
}
