package org.interscity.htc
package model.hybrid.entity.state

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.state.BaseState
import org.interscity.htc.core.types.Tick
import org.interscity.htc.model.hybrid.entity.state.enumeration.BusStationStateEnum
import org.interscity.htc.model.hybrid.entity.state.enumeration.BusStationStateEnum.Start
import org.interscity.htc.model.hybrid.entity.state.model.{ BusInformation, SubRoutePair }

import scala.collection.mutable

/** `busStops` (and every other non-first-position `Map[Long, _]` constructor parameter across
  * this package's state classes) needs `@JsonDeserialize(keyAs = classOf[java.lang.Long])`:
  * jackson-module-scala on Scala 3 resolves a case class field's Map key/value types from the
  * generic constructor signature, but that signature only survives intact for the *first*
  * constructor parameter — Scala 3 erases later parameters' Map type args to Object, so without
  * this annotation the key deserializer silently falls back to String and every lookup by a real
  * Long key throws `ClassCastException` (only surfaced once something unboxes the key, e.g.
  * `Ordering.Long.compare` during a `.sorted`).
  */
case class BusStationState(
  startTick: Long,
  name: String,
  origin: Long,
  destination: Long = 0L,
  @JsonDeserialize(keyAs = classOf[java.lang.Long])
  busStops: mutable.LinkedHashMap[Long, Long],
  interval: Tick,
  buses: mutable.Queue[BusInformation],
  goingRoute: Option[mutable.Map[SubRoutePair, mutable.Queue[(Identify, Identify)]]] = Some(
    mutable.Map.empty
  ),
  goingBestCost: Double = Double.MaxValue,
  returningRoute: Option[mutable.Map[SubRoutePair, mutable.Queue[(Identify, Identify)]]] = Some(
    mutable.Map.empty
  ),
  returningBestCost: Double = Double.MaxValue,
  var status: BusStationStateEnum = Start
) extends BaseState(startTick = startTick)
