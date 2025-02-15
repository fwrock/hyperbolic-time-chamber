package org.interscity.htc
package model.interscsimulator.entity.state

import core.entity.state.BaseState

import org.interscity.htc.core.types.CoreTypes.Tick
import org.interscity.htc.model.interscsimulator.entity.state.model.PointOfInterest

case class PointOfInterestState(
  startTick: Tick = 0,
  pointsOfInterest: List[PointOfInterest]
) extends BaseState(
      startTick = startTick
    )
