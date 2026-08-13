package org.interscity.htc
package model.hybrid.entity.state

import core.types.Tick

import org.interscity.htc.core.entity.state.BaseState
import org.interscity.htc.model.hybrid.entity.state.model.{ Phase, SignalState }

import scala.collection.mutable

case class TrafficSignalState(
  startTick: Tick,
  cycleDuration: Tick,
  offset: Tick,
  nodes: List[Long],
  phases: List[Phase],
  signalStates: mutable.Map[Long, SignalState]
) extends BaseState(startTick = startTick) {
  override def getStartTick: Tick = startTick + offset
}
