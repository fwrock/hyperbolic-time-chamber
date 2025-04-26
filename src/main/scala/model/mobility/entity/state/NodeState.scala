package org.interscity.htc
package model.mobility.entity.state

import core.entity.state.BaseState

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.types.Tick
import org.interscity.htc.model.mobility.entity.state.model.SignalState

import scala.collection.mutable

case class NodeState(
  startTick: Tick,
  latitude: Double,
  longitude: Double,
  links: List[String],
  connections: mutable.Map[String, Identify] = mutable.Map.empty,
  signals: mutable.Map[String, SignalState] = mutable.Map.empty,
  busStops: mutable.Map[String, Identify] = mutable.Map.empty,
  subwayStations: mutable.Map[String, Identify] = mutable.Map.empty
) extends BaseState(startTick = startTick)
