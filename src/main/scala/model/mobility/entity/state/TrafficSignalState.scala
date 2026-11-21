package org.interscity.htc
package model.mobility.entity.state

import core.types.Tick

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import org.interscity.htc.core.entity.state.BaseState
import org.interscity.htc.model.mobility.entity.state.model.{ Phase, SignalState }

import scala.collection.mutable

/** `signalStates` carries `@JsonDeserialize(keyAs = classOf[java.lang.Long])`: on Scala 3,
  * jackson-module-scala only preserves a case class's full Map key/value generics for the
  * *first* constructor parameter, so any later `Map[Long, _]` field silently deserializes its
  * keys as `String` without this annotation (see `BusStationState` for the full explanation).
  */
case class TrafficSignalState(
  startTick: Tick,
  cycleDuration: Tick = 1L,
  offset: Tick = 0L,
  nodes: List[Long] = Nil,
  phases: List[Phase] = Nil,
  @JsonDeserialize(keyAs = classOf[java.lang.Long])
  signalStates: mutable.Map[Long, SignalState] = mutable.Map.empty,
  program: Option[model.SignalProgram] = None,
  var programRuntime: model.SignalProgramRuntime = model.SignalProgramRuntime()
) extends BaseState(startTick = startTick) {
  override def getStartTick: Tick = if (program.nonEmpty) startTick else startTick + offset
}
