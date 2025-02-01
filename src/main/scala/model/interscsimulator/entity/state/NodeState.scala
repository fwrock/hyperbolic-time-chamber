package org.interscity.htc
package model.interscsimulator.entity.state

import core.entity.state.BaseState

import org.interscity.htc.core.types.CoreTypes.Tick

case class NodeState(
  startTick: Tick,
  latitude: Double,
  longitude: Double,
  links: List[String]
) extends BaseState(startTick = startTick)
