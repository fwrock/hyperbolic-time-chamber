package org.interscity.htc
package core.entity.state

import core.entity.control.{ LocalTimeManagerTickInfo, ScheduledActors }

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.Identify

import scala.collection.mutable
import core.types.Tick

/** Default state. This class can be used as a default state, without necessary to create a new
  * class.
  *
  * @param startTick
  *   the tick when the state started
  */
case class TimeManagerState(
  var startTime: Long = 0,
  var localTickOffset: Tick = 0,
  var tickOffset: Tick = 0,
  var initialTick: Tick = 0,
  var isPaused: Boolean = false,
  var isStopped: Boolean = false,
  registeredActors: mutable.Set[String] = mutable.Set[String](),
  scheduledActors: mutable.Map[Tick, ScheduledActors] = mutable.Map[Tick, ScheduledActors](),
  scheduledTicksOnFinish: mutable.Set[Tick] = mutable.Set[Tick](),
  runningEvents: mutable.Set[Identify] = mutable.Set[Identify](),
  var timeManagersPool: ActorRef = null,
  var countScheduled: Long = 0,
  var countDestruction: Long = 0,
  localTimeManagers: mutable.Map[ActorRef, LocalTimeManagerTickInfo] = mutable.Map()
) extends BaseState(startTick = 0)
