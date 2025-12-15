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
  * @param lookaheadWindow
  *   number of ticks actors can advance speculatively
  */
case class TimeManagerState(
  var startTime: Long = 0,
  var localTickOffset: Tick = 0,
  var tickOffset: Tick = 0,
  var initialTick: Tick = 0,
  var isPaused: Boolean = false,
  var isStopped: Boolean = false,
  var lookaheadWindow: Tick = 1,
  // Window-based execution
  var windowSize: Tick = 1,
  var currentWindowStart: Tick = 0,
  var currentWindowEnd: Tick = 0,
  var windowExecutionEnabled: Boolean = false,
  // Throughput metrics
  var simulationStartWallTime: Long = 0,  // Wall clock when simulation started
  var lastMetricsTick: Tick = 0,
  var lastMetricsTime: Long = 0,
  var ticksProcessedSinceLastMetric: Long = 0,
  // Diagnostic counters
  var totalSpontaneousEventsSent: Long = 0,
  var totalActorWakeups: Long = 0,
  var idleTicksCount: Long = 0,  // Ticks with no scheduled actors
  registeredActors: mutable.Set[String] = mutable.Set[String](),
  scheduledActors: mutable.Map[Tick, ScheduledActors] = mutable.Map[Tick, ScheduledActors](),
  scheduledTicksOnFinish: mutable.Set[Tick] = mutable.Set[Tick](),
  runningEvents: mutable.Set[Identify] = mutable.Set[Identify](),
  var timeManagersPool: ActorRef = null,
  var countScheduled: Long = 0,
  var countDestruction: Long = 0,
  localTimeManagers: mutable.Map[ActorRef, LocalTimeManagerTickInfo] = mutable.Map()
) extends BaseState(startTick = 0)
