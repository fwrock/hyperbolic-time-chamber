package org.interscity.htc
package model.hybrid.entity.state

import core.types.Tick

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import org.interscity.htc.core.entity.state.BaseState
import org.interscity.htc.model.hybrid.entity.state.model.{ Phase, SignalState }

import scala.collection.mutable

/** `signalStates` carries `@JsonDeserialize(keyAs = classOf[java.lang.Long])`: on Scala 3,
  * jackson-module-scala only preserves a case class's full Map key/value generics for the
  * *first* constructor parameter, so any later `Map[Long, _]` field silently deserializes its
  * keys as `String` without this annotation (see `BusStationState` for the full explanation).
  */
case class TrafficSignalState(
  startTick: Tick,
  cycleDuration: Tick,
  offset: Tick,
  nodes: List[Long],
  phases: List[Phase],
  @JsonDeserialize(keyAs = classOf[java.lang.Long])
  signalStates: mutable.Map[Long, SignalState]
) extends BaseState(startTick = startTick) {
  override def getStartTick: Tick = startTick + offset
}
