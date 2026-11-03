package org.interscity.htc
package model.hybrid.entity.state.model

/** Pedestrian space on each side of a road, independent of its vehicle lane count. */
final case class SidewalkConfig(
  enabled: Boolean = true,
  width: Double = 1.8,
  gap: Double = 0.3
) {
  require(width.isFinite && width > 0.0, "sidewalk width must be finite and positive metres")
  require(gap.isFinite && gap >= 0.0, "sidewalk gap must be finite and nonnegative metres")
}

object SidewalkConfig {
  val Default: SidewalkConfig = new SidewalkConfig()
}
