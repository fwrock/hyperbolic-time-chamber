package org.interscity.htc
package model.interscsimulator.entity.state

import core.types.CoreTypes.Tick

import org.interscity.htc.core.entity.actor.Identify
import org.interscity.htc.core.entity.state.BaseState
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.SubwayStationStateEnum
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.SubwayStationStateEnum.Start
import org.interscity.htc.model.interscsimulator.entity.state.model.{ SubwayInformation, SubwayLineInformation, SubwayStationNode }

import scala.collection.mutable

case class SubwayStationState(
  startTick: Tick,
  name: String,
  nodeId: String,
  terminal: Boolean,
  garage: Boolean,
  lines: mutable.Map[String, SubwayLineInformation],
  subways: mutable.Map[String, mutable.Queue[SubwayInformation]],
  linesRoute: mutable.Map[String, mutable.Queue[(SubwayStationNode, String)]],
  people: mutable.Map[String, mutable.Seq[Identify]] = mutable.Map.empty,
  var status: SubwayStationStateEnum = Start
) extends BaseState(startTick = startTick)
