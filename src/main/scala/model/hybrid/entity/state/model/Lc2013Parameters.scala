package org.interscity.htc
package model.hybrid.entity.state.model

final case class Lc2013Parameters(
  strategic: Double = 1.0,
  cooperative: Double = 1.0,
  speedGain: Double = 1.0,
  keepRight: Double = 1.0,
  lookaheadLeft: Double = 2.0,
  speedGainRight: Double = 0.1,
  speedGainRemainTimeSeconds: Double = 20.0,
  keepRightTimeSeconds: Double = 5.0
) {
  require(List(strategic,cooperative,speedGain,keepRight,speedGainRight,speedGainRemainTimeSeconds)
    .forall(v => v.isFinite && v >= 0), "invalid LC2013 motivation parameter")
  require(lookaheadLeft.isFinite && lookaheadLeft > 0, "invalid LC2013 left lookahead")
  require(keepRightTimeSeconds.isFinite && keepRightTimeSeconds > 0, "invalid LC2013 keep-right time")
}

final case class Lc2013Memory(
  leftGain: Double = 0.0,
  rightGain: Double = 0.0,
  keepRightSeconds: Double = 0.0,
  lookAheadSpeed: Double = 0.0
)
