package org.interscity.htc
package model.interscsimulator.entity.state

import core.entity.state.BaseState

import org.interscity.htc.core.types.CoreTypes.Tick

case class PointOfInterestState (
                                  startTick: Tick = 0,
                                ) extends BaseState(
    startTick = startTick
)
