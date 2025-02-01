package org.interscity.htc
package model.interscsimulator.entity.state

import core.types.CoreTypes.Tick

import org.interscity.htc.core.entity.state.BaseState
import org.interscity.htc.model.interscsimulator.entity.state.model.{ Phase, SignalState }

import scala.collection.mutable

case class TrafficSignalState(
  startTick: Tick,
  cycleDuration: Tick,
  offset: Tick,
  nodes: List[String],
  phases: List[Phase],
  signalStates: mutable.Map[String, SignalState]
) extends BaseState(startTick = startTick)
