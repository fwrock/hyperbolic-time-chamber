package org.interscity.htc
package model.interscsimulator.entity.state

import core.types.CoreTypes.Tick

import org.interscity.htc.core.entity.state.BaseState
import org.interscity.htc.model.interscsimulator.entity.state.model.LinkRegister

import scala.collection.mutable

case class LinkState(
  startTick: Tick,
  from: String,
  to: String,
  length: Double,
  lanes: Int,
  speedLimit: Double,
  capacity: Double,
  freeSpeed: Double,
  jamDensity: Double = 0.0,
  permLanes: Double = 1.0,
  typeLink: String = "normal",
  modes: List[String] = List("car"),
  currentSpeed: Double = 0.0,
  congestionFactor: Double = 1.0,
  registered: mutable.Set[LinkRegister] = mutable.Set()
) extends BaseState(startTick = startTick)
