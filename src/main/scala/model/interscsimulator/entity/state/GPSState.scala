package org.interscity.htc
package model.interscsimulator.entity.state

import core.entity.state.BaseState

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.types.Tick
import org.interscity.htc.model.interscsimulator.entity.event.data.RequestRoute
import org.interscity.htc.model.interscsimulator.types.CityMap

import scala.collection.mutable

case class GPSState(
  startTick: Tick,
  cityMapPath: String = null,
  var cityMap: CityMap = null,
  requests: mutable.Queue[(Identify, RequestRoute)] = mutable.Queue[(Identify, RequestRoute)]()
) extends BaseState(startTick = startTick)
