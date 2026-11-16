package org.interscity.htc
package model.hybrid.entity.state.model

enum LaneChangingModel:
  case Disabled, Mobil, Lc2013

final case class LaneChangingConfig(
  model: LaneChangingModel = LaneChangingModel.Disabled,
  politeness: Double = 0.5,
  safeDeceleration: Double = 4.0,
  accelerationThreshold: Double = 0.1,
  biasToRight: Double = 0.2,
  minGapMeters: Double = 2.0,
  cooldownSeconds: Double = 2.0,
  strategicLookaheadMeters: Double = 150.0,
  strategicCooldownSeconds: Double = 0.2,
  cooperativeStrategicChanges: Boolean = true
) {
  require(politeness.isFinite && politeness >= 0 && politeness <= 1, "invalid MOBIL politeness")
  require(safeDeceleration.isFinite && safeDeceleration > 0, "invalid lane-change safe deceleration")
  require(accelerationThreshold.isFinite && accelerationThreshold > 0, "invalid MOBIL threshold")
  require(biasToRight.isFinite && biasToRight >= 0, "invalid lane-change bias")
  require(minGapMeters.isFinite && minGapMeters >= 0, "invalid lane-change gap")
  require(cooldownSeconds.isFinite && cooldownSeconds >= 0, "invalid lane-change cooldown")
  require(strategicLookaheadMeters.isFinite && strategicLookaheadMeters >= 0, "invalid strategic lookahead")
  require(strategicCooldownSeconds.isFinite && strategicCooldownSeconds >= 0, "invalid strategic cooldown")
}

final case class LaneConnection(outgoingLinkId: Long, fromLane: Int, toLane: Int) {
  require(fromLane >= 0 && toLane >= 0, "lane connection indices must be nonnegative")
}
