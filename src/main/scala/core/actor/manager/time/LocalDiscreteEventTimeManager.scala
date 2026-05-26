package org.interscity.htc
package core.actor.manager.time

import core.actor.manager.time.{ LocalDiscreteEventTimeManager, LocalTimeManagerBase }
import core.types.Tick
import core.util.ManagerConstantsUtil.LOCAL_TIME_MANAGER_ACTOR_NAME

import org.apache.pekko.actor.{ ActorRef, Props }
import org.htc.protobuf.core.entity.actor.Identify

import scala.collection.mutable

/** Local Time Manager for Discrete Event Simulation. This manager processes events in chronological
  * order, advancing time only when all events at the current time have been processed. This is the
  * traditional approach used by the original TimeManager.
  *
  * @param simulationDuration
  *   The total duration of the simulation in ticks
  * @param simulationManager
  *   Reference to the simulation manager
  * @param parentManager
  *   Reference to the global time manager
  */
class LocalDiscreteEventTimeManager(
  simulationDuration: Tick,
  simulationManager: ActorRef,
  parentManager: Option[ActorRef]
) extends LocalTimeManagerBase(
      simulationDuration = simulationDuration,
      simulationManager = simulationManager,
      parentManager = parentManager,
      actorId =
        if (parentManager.isEmpty)
          s"$LOCAL_TIME_MANAGER_ACTOR_NAME-${System.nanoTime()}"
        else
          LOCAL_TIME_MANAGER_ACTOR_NAME
    ) {

  /** Batch size for firing spontaneous events. When a tick has more actors than this threshold,
    * events are fired in batches to prevent overwhelming the messaging system (e.g. 750K+ actors at
    * tick 0).
    */
  private val TICK_BATCH_SIZE = 500
  private val pendingTickActors = mutable.Queue[Identify]()
  private var currentBatchTick: Tick = -1
  private var currentBatchSequence: Long = 0L

  private lazy val tickProgressLogInterval: Long =
    scala.util.Try(
      context.system.settings.config.getLong("htc.time-manager.local-tick-progress-log-interval")
    ).toOption.filter(_ > 0).getOrElse(5000L)

  private lazy val batchDebugLogEvery: Long =
    scala.util.Try(
      context.system.settings.config.getLong("htc.time-manager.local-batch-debug-log-every")
    ).toOption.filter(_ > 0).getOrElse(10L)

  /** Processes a tick in discrete event manner. Events are processed one at a time in chronological
    * order. Time only advances when all events at the current time have been processed.
    *
    * For large ticks (actors > TICK_BATCH_SIZE), events are fired in batches. Each batch waits for
    * all FinishEvents before firing the next batch.
    *
    * @param tick
    *   The tick to process
    */
  protected def processTick(tick: Tick): Unit = {
    // Process ALL events scheduled at ticks <= tick, not just exactly at tick.
    // This prevents "orphaned" actors: when the GTM's selective barrier sends
    // UpdateGlobalTimeEvent(T_big) to an LTM that has a car scheduled at T_small < T_big
    // (because a Car's ScheduleEvent arrived in the LTM mailbox just before the UGT),
    // the car would be filtered out by nextTick (which requires tick >= localTickOffset).
    // By identifying and processing skipped ticks, we ensure those in-flight registrations
    // are executed rather than orphaned and force-destroyed.
    //
    // IMPORTANT: We process exactly one tick's actors at a time (the earliest available
    // tick <= globalTick). When those actors finish, advanceToNextTick → processTick is
    // called again and will find the next skipped tick. This preserves the discrete-event
    // ordering invariant: no actors at tick T+1 fire before all actors at tick T finish.
    //
    // We also update localTickOffset to effectiveTick so that subsequent ScheduleEvents
    // sent by the actor (e.g. car schedules at effectiveTick+linkTime) are NOT bumped to
    // localTickOffset+1 (which would be T_big+1 if we left localTickOffset as T_big).
    val effectiveTick = scheduledActors.keys.filter(_ <= tick).minOption.getOrElse(tick)

    // Reset localTickOffset to effectiveTick so that ScheduleEvents from this actor's
    // actSpontaneous (e.g. Car entering a link at effectiveTick+linkTraversalTime) are
    // scheduled at the correct future tick rather than being bumped to T_big+1.
    if (effectiveTick < localTickOffset) {
      logInfo(
        s"[LocalDiscreteEvent] Rewinding localTickOffset from $localTickOffset to $effectiveTick to process skipped tick"
      )
      localTickOffset = effectiveTick
      tickOffset = effectiveTick - initialTick
    }

    scheduledActors.get(effectiveTick).foreach {
      actorsSet =>
        val actorTypes = actorsSet.groupBy(_.classType).view.mapValues(_.size).toMap
        val actorSummary = actorTypes.map {
          case (classType, count) =>
            s"${classType.split('.').lastOption.getOrElse(classType)}=$count"
        }.mkString(", ")

        if (effectiveTick != tick) {
          logInfo(
            s"[LocalDiscreteEvent] Processing skipped tick $effectiveTick (globalTick=$tick) with ${actorsSet.size} actors ($actorSummary)"
          )
        } else if (effectiveTick % tickProgressLogInterval == 0 || actorsSet.size > TICK_BATCH_SIZE) {
          logInfo(
            s"[LocalDiscreteEvent] Processing tick $effectiveTick with ${actorsSet.size} scheduled actors ($actorSummary)"
          )
        }

        if (actorsSet.size <= TICK_BATCH_SIZE) {
          // Small set — fire all at once (original fast path)
          sendSpontaneousEvent(effectiveTick, actorsSet)
        } else {
          // Large set — queue and fire in batches to prevent system overload
          currentBatchTick = effectiveTick
          currentBatchSequence = 0L
          pendingTickActors.enqueueAll(actorsSet)
          fireNextBatch()
        }
    }
    scheduledActors.remove(effectiveTick)
    scheduledTicksOnFinish.remove(effectiveTick)

    // Only advance if nothing running and nothing pending
    if (runningEvents.isEmpty && pendingTickActors.isEmpty) {
      super.advanceToNextTick()
    }
  }

  /** Fire the next batch of actors from the pending queue. Resets the watchdog timestamp when
    * starting a new batch.
    */
  private def fireNextBatch(): Unit = {
    var count = 0
    currentBatchSequence += 1
    while (pendingTickActors.nonEmpty && count < TICK_BATCH_SIZE) {
      val identity = pendingTickActors.dequeue()
      runningEvents.add(identity)
      sendSpontaneousEvent(currentBatchTick, identity)
      count += 1
    }
    if (
      count > 0 &&
      (pendingTickActors.isEmpty || currentBatchSequence % batchDebugLogEvery == 0)
    ) {
      logDebug(
        s"[LocalTM] Fired batch #$currentBatchSequence of $count actors at tick $currentBatchTick, " +
          s"${pendingTickActors.size} remaining, ${runningEvents.size} running"
      )
    }
  }

  /** Override to handle pending batch actors. When a batch completes (runningEvents empty), fire
    * the next batch before advancing.
    */
  override protected def advanceToNextTick(): Unit =
    if (runningEvents.isEmpty && pendingTickActors.nonEmpty) {
      fireNextBatch()
    } else {
      super.advanceToNextTick()
    }
}

object LocalDiscreteEventTimeManager {
  def props(
    simulationDuration: Tick,
    simulationManager: ActorRef,
    parentManager: Option[ActorRef]
  ): Props =
    Props(
      classOf[LocalDiscreteEventTimeManager],
      simulationDuration,
      simulationManager,
      parentManager
    )
}
