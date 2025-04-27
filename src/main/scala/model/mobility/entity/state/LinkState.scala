package org.interscity.htc
package model.mobility.entity.state

import core.types.Tick

import org.interscity.htc.core.entity.state.BaseState
import org.interscity.htc.core.enumeration.ReportTypeEnum
import org.interscity.htc.model.mobility.entity.state.model.LinkRegister

import scala.collection.mutable

case class LinkState(
  startTick: Tick,
  reporterType: ReportTypeEnum = null,
  scheduleOnTimeManager: Boolean = true,
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
) extends BaseState(startTick = startTick, reporterType = reporterType, scheduleOnTimeManager = scheduleOnTimeManager)
