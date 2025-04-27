package org.interscity.htc
package model.mobility.entity.state

import core.entity.state.BaseState

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.enumeration.ReportTypeEnum
import org.interscity.htc.core.types.Tick
import org.interscity.htc.model.mobility.entity.event.data.RequestRoute
import org.interscity.htc.model.mobility.types.CityMap

import scala.collection.mutable

case class GPSState(
  startTick: Tick,
  reporterType: ReportTypeEnum = null,
  scheduleOnTimeManager: Boolean = false,
  cityMapPath: String = null,
  requests: mutable.Queue[(Identify, RequestRoute)] = mutable.Queue[(Identify, RequestRoute)]()
) extends BaseState(startTick = startTick, reporterType = reporterType, scheduleOnTimeManager = scheduleOnTimeManager)
