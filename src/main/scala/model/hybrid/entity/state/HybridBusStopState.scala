package org.interscity.htc
package model.hybrid.entity.state

import core.entity.state.BaseState

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.Identify

import scala.collection.mutable

case class HybridBusStopState(
  nodeId: String,
  label: String,
  people: mutable.Map[String, mutable.Seq[Identify]] = mutable.Map.empty
) extends BaseState
