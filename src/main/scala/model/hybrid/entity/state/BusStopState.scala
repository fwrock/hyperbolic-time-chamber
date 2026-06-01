package org.interscity.htc
package model.hybrid.entity.state

import core.entity.state.BaseState

import org.htc.protobuf.core.entity.actor.Identify

import scala.collection.mutable

case class BusStopState(
  nodeId: String,
  label: String,
  people: mutable.Map[String, mutable.Seq[Identify]] = mutable.Map.empty,
  deadLines: mutable.Set[String] = mutable.Set.empty
) extends BaseState(scheduleOnTimeManager = false)
