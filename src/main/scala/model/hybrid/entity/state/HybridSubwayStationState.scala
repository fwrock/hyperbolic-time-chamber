package org.interscity.htc
package model.hybrid.entity.state

import core.types.Tick

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.state.BaseState
import org.interscity.htc.model.hybrid.entity.state.enumeration.SubwayStationStateEnum
import org.interscity.htc.model.hybrid.entity.state.enumeration.SubwayStationStateEnum.Start
import org.interscity.htc.model.hybrid.entity.state.model.{ SubwayInformation, SubwayLineInformation, SubwayStationNode }

import scala.collection.mutable

case class HybridSubwayStationState(
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
