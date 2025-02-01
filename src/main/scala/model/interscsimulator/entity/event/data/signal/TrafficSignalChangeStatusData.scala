package org.interscity.htc
package model.interscsimulator.entity.event.data.signal

import model.interscsimulator.entity.state.enumeration.TrafficSignalPhaseStateEnum

import org.interscity.htc.core.types.CoreTypes.Tick
import org.interscity.htc.core.entity.event.BaseEvent
import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.data.BaseEventData

case class TrafficSignalChangeStatusData(
  signalState: TrafficSignalPhaseStateEnum,
  nextTick: Tick,
  nodes: List[String]
) extends BaseEventData
