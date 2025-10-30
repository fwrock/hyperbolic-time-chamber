package org.interscity.htc
package model.mobility.entity.state

import core.types.Tick

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.state.{BaseState, SimulationBaseState}
import org.interscity.htc.model.mobility.entity.state.enumeration.SubwayStationStateEnum
import org.interscity.htc.model.mobility.entity.state.enumeration.SubwayStationStateEnum.Start
import org.interscity.htc.model.mobility.entity.state.model.{SubwayInformation, SubwayLineInformation, SubwayStationNode}

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
) extends SimulationBaseState(startTick = startTick)
