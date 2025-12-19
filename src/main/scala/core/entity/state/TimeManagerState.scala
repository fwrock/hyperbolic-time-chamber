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
  var startTime: Long = -1,
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
  localTimeManagers: mutable.Map[ActorRef, LocalTimeManagerTickInfo] = mutable.Map(),
  var actorsAmount: Long = 0,
  var totalActorsAmount: Long = 0,
  // Progress-based stall detection (not time-based)
  var lastGlobalTickBroadcast: Long = 0,  // Wall time of last broadcast (for logging only)
  var lastLocalReportReceived: Long = 0,  // Wall time of last report (for logging only)
  var stallDetectionEnabled: Boolean = true,
  var cyclesWithoutProgress: Int = 0,  // Count sync cycles with no tick advancement
  var lastCompletedTick: Tick = 0,  // Last tick that all TMs completed
  var eventsProcessedLastCycle: Long = 0,  // Events in last sync cycle
  var totalEventsLastCheck: Long = 0  // Total events at last stall check
) extends BaseState(startTick = 0)
