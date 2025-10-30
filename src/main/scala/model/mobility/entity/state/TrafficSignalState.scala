package org.interscity.htc
package model.mobility.entity.state

import core.types.Tick

import org.interscity.htc.core.entity.state.{BaseState, SimulationBaseState}
import org.interscity.htc.model.mobility.entity.state.model.{Phase, SignalState}

import scala.collection.mutable

case class TrafficSignalState(
  startTick: Tick,
  cycleDuration: Tick,
  offset: Tick,
  nodes: List[String],
  phases: List[Phase],
  signalStates: mutable.Map[String, SignalState]
) extends SimulationBaseState(startTick = startTick)
