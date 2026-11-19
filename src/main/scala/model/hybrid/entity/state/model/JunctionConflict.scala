package org.interscity.htc
package model.hybrid.entity.state.model

import com.fasterxml.jackson.databind.annotation.JsonDeserialize

/** Two junction connectors whose paths cross inside the junction (G04).
  *
  * Occupancy of either connector excludes the other. When both are requested, `priorityConnector`
  * decides; with no priority the older request wins (tick, vehicle id, request sequence). The rule
  * must name its source: priority is never deduced from the numeric order of IDs.
  */
/** Causal gap acceptance for the yielding connector of a conflict (G05).
  *
  * The yielding movement is granted only if every observed vehicle approaching the priority
  * movement needs at least `criticalGapSeconds` to reach the conflict point. All three values are
  * explicit parameters with units; none is a calibrated default.
  *
  * @param criticalGapSeconds  minimum time the yielding vehicle needs ahead of a priority arrival (s)
  * @param conflictOffsetM     distance from the priority approach stop line to the conflict point, along its connector (m)
  * @param assumedAccelerationMps2 acceleration assumed for a priority vehicle when estimating its earliest arrival (m/s^2)
  * @param followUpSeconds extra gap required for each vehicle of the yielding movement already admitted and not yet released (0 = one gap releases a whole queue)
  */
final case class GapAcceptance(criticalGapSeconds: Double, conflictOffsetM: Double,
                               assumedAccelerationMps2: Double = 2.6, followUpSeconds: Double = 0.0) {
  require(followUpSeconds.isFinite && followUpSeconds >= 0, "invalid follow-up gap")
  require(criticalGapSeconds.isFinite && criticalGapSeconds > 0, "invalid critical gap")
  require(conflictOffsetM.isFinite && conflictOffsetM >= 0, "invalid conflict offset")
  require(assumedAccelerationMps2.isFinite && assumedAccelerationMps2 > 0, "invalid assumed acceleration")
}

final case class JunctionConflict(
  connectorA: Long,
  connectorB: Long,
  @JsonDeserialize(contentAs = classOf[java.lang.Long])
  priorityConnector: Option[Long] = None,
  source: String = "",
  gapAcceptance: Option[GapAcceptance] = None
) {
  require(gapAcceptance.isEmpty || priorityConnector.nonEmpty, "gap acceptance needs a priority connector")
  require(connectorA != connectorB, "a connector cannot conflict with itself")
  require(priorityConnector.forall(p => p == connectorA || p == connectorB),
    "conflict priority must name one of its connectors")
  require(source != null && source.trim.nonEmpty, "a junction conflict must name the source of its rule")
  def involves(connector: Long): Boolean = connector == connectorA || connector == connectorB
  def other(connector: Long): Long = if (connector == connectorA) connectorB else connectorA
}
