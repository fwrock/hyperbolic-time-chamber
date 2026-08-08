package org.interscity.htc
package core.actor.manager.time

import core.actor.manager.time.{ ConservativeLocalTimeManager, LocalDiscreteEventTimeManager }
import core.types.Tick
import core.util.ManagerConstantsUtil.LOCAL_TIME_MANAGER_ACTOR_NAME

import org.apache.pekko.actor.{ ActorRef, Props }
import org.htc.protobuf.core.entity.actor.Identify

import scala.collection.mutable
import scala.concurrent.duration._

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
) extends ConservativeLocalTimeManager(
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

  // Stall detector: wall-clock time of the last tick advance. Used only for logging.
  private var lastTickAdvanceWall: Long = System.currentTimeMillis()
  private var lastAdvancedAtTick: Tick = -1L

  /** Periodic stall-check message. Sent by the scheduler every 30s. */
  private case object StallCheck

  override def preStart(): Unit = {
    super.preStart()
    context.system.scheduler.scheduleWithFixedDelay(
      30.seconds, 30.seconds, self, StallCheck
    )(context.dispatcher)
  }

  /** Extend the parent handler with our StallCheck case. */
  override def handleEvent: Receive = {
    val stallHandler: PartialFunction[Any, Unit] = { case StallCheck => logStallIfNeeded() }
    stallHandler.orElse(super.handleEvent)
  }

  /** Log a stall report if we have been stuck in the same tick for > 25 s.
    * Skipped during the loading/warmup phase (before StartSimulationTimeEvent). */
  private def logStallIfNeeded(): Unit = {
    if (startTime == 0L) return // simulation not started yet — loading phase, not a stall
    val elapsed = System.currentTimeMillis() - lastTickAdvanceWall
    if (elapsed > 25_000) {
      if (runningEvents.nonEmpty) {
        val sample = runningEvents.take(30)
          .map(e => s"${e.classType.split('.').last}/${e.id}")
          .mkString(", ")
        val more = if (runningEvents.size > 30) s" ... +${runningEvents.size - 30} more" else ""
        logWarn(
          s"[STALL] tick=$localTickOffset | elapsed=${elapsed / 1000}s | " +
          s"runningEvents=${runningEvents.size} | pendingBatch=${pendingTickActors.size}\n" +
          s"  stuck actors: $sample$more"
        )
      } else if (pendingTickActors.nonEmpty) {
        logWarn(
          s"[STALL] tick=$localTickOffset | elapsed=${elapsed / 1000}s | " +
          s"runningEvents=0 | pendingBatch=${pendingTickActors.size} (batch not firing?)"
        )
      } else {
        val nextScheduled = scheduledActors.keys.toSeq.sorted.headOption
        logWarn(
          s"[STALL?] tick=$localTickOffset | elapsed=${elapsed / 1000}s | " +
          s"runningEvents=0 | pendingBatch=0 | nextScheduledTick=$nextScheduled (GTM not advancing?)"
        )
      }
    }
  }

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

    val effectiveTick = scheduledActors.keys.filter(_ <= tick).minOption.getOrElse(tick)

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

    // Reset stall timer each time we actually fire actors for a new tick.
    if (effectiveTick != lastAdvancedAtTick) {
      lastAdvancedAtTick = effectiveTick
      lastTickAdvanceWall = System.currentTimeMillis()
    }

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
