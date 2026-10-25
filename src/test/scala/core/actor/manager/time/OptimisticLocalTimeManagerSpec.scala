package org.interscity.htc
package core.actor.manager.time

import core.entity.event.FinishEvent
import core.entity.event.control.execution.{ GvtUpdateEvent, LvtReportEvent, RegisterPassiveActorEvent }
import core.types.Tick

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ ActorRef, ActorSystem }
import org.apache.pekko.testkit.{ TestActorRef, TestProbe }
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.execution.{ DestructEvent, RegisterActorEvent, StartSimulationTimeEvent, StopSimulationEvent }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.compiletime.uninitialized
import scala.concurrent.duration.DurationInt

/** Regression coverage for `OptimisticLocalTimeManager`'s defining difference from
  * `ConservativeLocalTimeManager` (`docs/TIME_WARP_DESIGN.md` §2): it dispatches whatever's
  * scheduled immediately, with no `UpdateGlobalTimeEvent` permission needed from a
  * `GlobalTimeManager` first, and reports progress via a fire-and-forget `LvtReportEvent` instead
  * of the barrier's blocking `LocalTimeReportEvent` handshake. Mirrors
  * `LocalTimeManagerBatchStallSpec`'s harness (direct calls on `underlyingActor`, `TestProbe`s
  * standing in for actors, `pekko.actor.provider = local`).
  */
class OptimisticLocalTimeManagerSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var _system: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit =
    _system = ActorSystem(
      "OptimisticLocalTimeManagerSpec",
      ConfigFactory
        .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
        .withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit = {
    _system.terminate()
    ()
  }

  private class TestOptimisticLocalTimeManager(parentManager: Option[ActorRef])
      extends OptimisticLocalTimeManager(
        simulationDuration = 100_000L,
        simulationManager = ActorRef.noSender,
        parentManager = parentManager
      ) {
    def testRecoveryFinished: Boolean = recoveryFinished
    def testStart(startTick: Tick): Unit =
      startSimulation(StartSimulationTimeEvent(startTick = startTick, actorRef = "", data = None))
    def testRegister(identity: Identify, startTick: Tick): Unit =
      registerActor(RegisterActorEvent(startTick = startTick, actorId = identity.id, identify = Some(identity)))
    def testRegisterPassive(identity: Identify): Unit =
      registerPassiveActor(RegisterPassiveActorEvent(actorId = identity.id, identify = Some(identity)))
    def testSchedule(identity: Identify, tick: Tick): Unit =
      scheduleEvent(ScheduleEvent(tick = tick, actorRef = identity.id.toString, identify = Some(identity)))
    def testScheduleResyncAfterRollback(identity: Identify, tick: Tick): Unit =
      scheduleEvent(
        ScheduleEvent(tick = tick, actorRef = identity.id.toString, identify = Some(identity), resyncAfterRollback = true)
      )
    def testFinish(finish: FinishEvent): Unit = finishEvent(finish)
    def testRunningEventIds: Set[Long] = runningEvents.map(_.id).toSet
    def testScheduledTicks: Set[Tick] = scheduledActors.keySet.toSet
    def testHighestProcessedTick(actorId: Long): Tick = highestProcessedTickOf(actorId)
  }

  private def identityFor(id: Long, probe: TestProbe): Identify =
    Identify(
      id = id,
      resourceId = "",
      classType = "test.Actor",
      actorRef = probe.ref.path.toString,
      actorType = "PoolDistributed"
    )

  private def newManager(parentProbe: TestProbe): TestActorRef[TestOptimisticLocalTimeManager] = {
    val ref = TestActorRef(new TestOptimisticLocalTimeManager(Some(parentProbe.ref)))
    parentProbe.awaitAssert(ref.underlyingActor.testRecoveryFinished shouldBe true, 5.seconds, 10.millis)
    ref
  }

  "OptimisticLocalTimeManager" should "dispatch a registered actor immediately, with no UpdateGlobalTimeEvent permission needed" in {
    val parentProbe = TestProbe()
    val actorProbe = TestProbe()
    val ltm = newManager(parentProbe)
    val identity = identityFor(1L, actorProbe)

    ltm.underlyingActor.testStart(0L)
    ltm.underlyingActor.testRegister(identity, 0L)

    // No UpdateGlobalTimeEvent sent at all -- unlike ConservativeLocalTimeManager, dispatch must
    // have already happened purely from registerActor/scheduleEvent's own re-notify hook.
    ltm.underlyingActor.testRunningEventIds shouldBe Set(1L)
  }

  it should "replace an in-flight schedule atomically without changing the requested tick" in {
    val parentProbe = TestProbe()
    val actorProbe = TestProbe()
    val ltm = newManager(parentProbe)
    val identity = identityFor(44L, actorProbe)
    ltm.underlyingActor.testStart(0L)
    ltm.underlyingActor.testRegister(identity, 100L)
    actorProbe.expectMsgType[core.entity.event.SpontaneousEvent].tick shouldBe 100L
    ltm.underlyingActor.testSchedule(identity, 150L)
    ltm.underlyingActor.testSchedule(identity, 200L)
    val next = core.runtime.EventOrder(10L, 0L, "replacement")
    ltm ! core.entity.event.control.execution.ReplaceScheduleEvent(identity, 1L, Some(next), 1L, false)
    val dispatched = actorProbe.expectMsgType[core.entity.event.SpontaneousEvent](2.seconds)
    dispatched.tick shouldBe 10L
    dispatched.order shouldBe Some(next)
    dispatched.scheduleRevision shouldBe 1L
    ltm.underlyingActor.testScheduledTicks shouldBe empty
    parentProbe.fishForMessage(1.second) {
      case e: LvtReportEvent => e.lvt == 10L && !e.isIdle
      case _ => false
    }
  }

  it should "retain an unseen dispatch until its exact discard acknowledgement" in {
    val parent = TestProbe()
    val actor = TestProbe()
    val ltm = newManager(parent)
    val identity = identityFor(45L, actor)
    ltm.underlyingActor.testStart(0L)
    ltm.underlyingActor.testRegister(identity, 100L)
    val old = actor.expectMsgType[core.entity.event.SpontaneousEvent]
    val next = core.runtime.EventOrder(10L, 0L, "replacement")
    ltm ! core.entity.event.control.execution.ReplaceScheduleEvent(identity, 2L, Some(next), 0L, false)
    actor.expectNoMessage(100.millis)
    ltm.underlyingActor.testRunningEventIds shouldBe Set(identity.id)
    ltm.underlyingActor.testFinish(FinishEvent(actor.ref, identity, 100L, timeManager = ltm,
      generation = old.generation, discardedDispatch = true))
    val replacement = actor.expectMsgType[core.entity.event.SpontaneousEvent]
    replacement.tick shouldBe 10L
    replacement.scheduleRevision shouldBe 2L
    ltm.underlyingActor.testFinish(FinishEvent(actor.ref, identity, 100L, timeManager = ltm,
      generation = old.generation, discardedDispatch = true))
    ltm.underlyingActor.testRunningEventIds shouldBe Set(identity.id)
  }

  it should "ignore duplicated and superseded schedule revisions" in {
    val parent = TestProbe()
    val actor = TestProbe()
    val ltm = newManager(parent)
    val identity = identityFor(46L, actor)
    val next = core.runtime.EventOrder(10L, 0L, "replacement")
    ltm ! core.entity.event.control.execution.ReplaceScheduleEvent(identity, 2L, Some(next), 0L, false)
    ltm ! core.entity.event.control.execution.ReplaceScheduleEvent(identity, 2L, None, 0L, false)
    ltm ! core.entity.event.control.execution.ReplaceScheduleEvent(identity, 1L, None, 0L, false)
    actor.expectNoMessage(100.millis)
    ltm.underlyingActor.testStart(0L)
    actor.expectMsgType[core.entity.event.SpontaneousEvent].order shouldBe Some(next)
    actor.expectNoMessage(100.millis)
  }

  it should "dispatch only the earliest queued tick for each actor in one pass" in {
    val parentProbe = TestProbe()
    val actorProbe = TestProbe()
    val ltm = newManager(parentProbe)
    val identity = identityFor(42L, actorProbe)
    ltm.underlyingActor.testRegister(identity, 1L)
    ltm.underlyingActor.testSchedule(identity, 2L)
    ltm.underlyingActor.testStart(0L)

    actorProbe.expectMsgType[core.entity.event.SpontaneousEvent].tick shouldBe 1L
    actorProbe.expectNoMessage(200.millis)
    ltm.underlyingActor.testScheduledTicks shouldBe Set(2L)
    ltm.underlyingActor.testFinish(
      FinishEvent(actorRef = actorProbe.ref, identify = identity, end = 1L,
        scheduleTick = Some("2"), timeManager = ltm, generation = 1L)
    )
    actorProbe.expectMsgType[core.entity.event.SpontaneousEvent].tick shouldBe 2L
  }

  it should "report LVT via a fire-and-forget LvtReportEvent once the batch resolves, not a blocking LocalTimeReportEvent" in {
    val parentProbe = TestProbe()
    val actorProbe = TestProbe()
    val ltm = newManager(parentProbe)
    val identity = identityFor(1L, actorProbe)

    ltm.underlyingActor.testStart(0L)
    ltm.underlyingActor.testRegister(identity, 0L)
    ltm.underlyingActor.testRunningEventIds shouldBe Set(1L)

    ltm.underlyingActor.testFinish(
      FinishEvent(
        actorRef = actorProbe.ref,
        identify = identity,
        end = 0L,
        scheduleTick = Some("5"),
        timeManager = ltm,
        generation = 1L
      )
    )

    // The actor was immediately redispatched for tick 5 -- no waiting for anything from the parent.
    ltm.underlyingActor.testRunningEventIds shouldBe Set(1L)
    ltm.underlyingActor.testScheduledTicks shouldBe empty

    parentProbe.fishForMessage(200.millis) {
      case e: LvtReportEvent => e.lvt == 5L && !e.isIdle
      case _                 => false
    }
  }

  it should "report isIdle=true once nothing is running or scheduled" in {
    val parentProbe = TestProbe()
    val actorProbe = TestProbe()
    val ltm = newManager(parentProbe)
    val identity = identityFor(1L, actorProbe)

    ltm.underlyingActor.testStart(0L)
    ltm.underlyingActor.testRegister(identity, 0L)
    ltm.underlyingActor.testRunningEventIds shouldBe Set(1L)

    ltm.underlyingActor.testFinish(
      FinishEvent(
        actorRef = actorProbe.ref,
        identify = identity,
        end = 0L,
        scheduleTick = None,
        timeManager = ltm,
        generation = 1L
      )
    )

    ltm.underlyingActor.testRunningEventIds shouldBe empty
    ltm.underlyingActor.testScheduledTicks shouldBe empty
    parentProbe.fishForMessage(200.millis) {
      case e: LvtReportEvent => e.isIdle
      case _                 => false
    }
  }

  it should "report LVT as the actor's real high-water mark once idle, not collapse back to localTickOffset (regression for the GVT-plateaued-at-initialTick bug, docs/KNOWN_GAPS.md 2026-09-11)" in {
    val parentProbe = TestProbe()
    val actorProbe = TestProbe()
    val ltm = newManager(parentProbe)
    val identity = identityFor(1L, actorProbe)

    ltm.underlyingActor.testStart(0L)
    // Registers and immediately dispatches the actor straight to a tick far past initialTick/
    // localTickOffset (both still 0 at this point) -- mirrors an actor that has genuinely raced
    // ahead under Time Warp's no-barrier dispatch.
    ltm.underlyingActor.testRegister(identity, 500L)
    ltm.underlyingActor.testRunningEventIds shouldBe Set(1L)
    ltm.underlyingActor.testHighestProcessedTick(1L) shouldBe 500L

    // Finishes with nothing rescheduled: runningEvents empties and nextTick is None, the exact
    // "momentarily nothing in flight" gap that used to make localVirtualTime fall back to
    // localTickOffset (still 0) instead of the real progress already observed.
    ltm.underlyingActor.testFinish(
      FinishEvent(
        actorRef = actorProbe.ref,
        identify = identity,
        end = 500L,
        scheduleTick = None,
        timeManager = ltm,
        generation = 1L
      )
    )

    ltm.underlyingActor.testRunningEventIds shouldBe empty
    ltm.underlyingActor.testScheduledTicks shouldBe empty
    parentProbe.fishForMessage(200.millis) {
      case e: LvtReportEvent => e.isIdle && e.lvt == 500L
      case _                 => false
    }
  }

  it should "re-dispatch immediately when a fresh ScheduleEvent arrives for a previously-idle actor" in {
    val parentProbe = TestProbe()
    val actorProbe = TestProbe()
    val ltm = newManager(parentProbe)
    val identity = identityFor(1L, actorProbe)

    ltm.underlyingActor.testStart(0L)
    ltm.underlyingActor.testRegister(identity, 0L)
    ltm.underlyingActor.testFinish(
      FinishEvent(
        actorRef = actorProbe.ref,
        identify = identity,
        end = 0L,
        scheduleTick = None,
        timeManager = ltm,
        generation = 1L
      )
    )
    ltm.underlyingActor.testRunningEventIds shouldBe empty

    ltm.underlyingActor.testSchedule(identity, 42L)

    // No permission round-trip needed -- the re-notify hook dispatches straight away.
    ltm.underlyingActor.testRunningEventIds shouldBe Set(1L)
  }

  it should "report isIdle=true at startSimulation even when zero actors are ever registered on it" in {
    // Regression coverage for docs/TIME_WARP_DESIGN.md's real end-to-end-run finding: an LTM pool
    // routee that starts with nothing scheduled (plausible whenever the pool has more instances
    // than the scenario has actors, or the distribution across instances is uneven) must still
    // report once, or OptimisticGlobalTimeManager.recomputeGvtAndCheckTermination -- which requires
    // a report from EVERY registered LTM before it estimates anything -- waits forever for a report
    // that never comes, and the simulation can never terminate.
    val parentProbe = TestProbe()
    val ltm = newManager(parentProbe)

    ltm.underlyingActor.testStart(0L)

    parentProbe.fishForMessage(200.millis) {
      case e: LvtReportEvent => e.isIdle
      case _                 => false
    }
  }

  it should "report LVT as Long.MaxValue (no constraint) for a routee with zero actors ever assigned, instead of pinning global GVT near initialTick forever (regression for docs/KNOWN_GAPS.md 2026-09-11)" in {
    val parentProbe = TestProbe()
    val ltm = newManager(parentProbe)

    ltm.underlyingActor.testStart(0L)

    parentProbe.fishForMessage(200.millis) {
      case e: LvtReportEvent => e.isIdle && e.lvt == Long.MaxValue
      case _                 => false
    }
  }

  it should "not dispatch an EAGER actor registered before startSimulation has run, but pick it up once it does" in {
    // Regression coverage for docs/TIME_WARP_DESIGN.md's conservative-vs-optimistic comparison
    // finding: registerActor/scheduleEvent can arrive during the load/warm-up phase, well before
    // this LTM's own StartSimulationTimeEvent -- registerOnTimeManager is called from
    // proceedNormalInit/onInitialize, both of which run at actor creation, independent of whether
    // the simulation has formally started. Dispatching then (as onActorRescheduled used to,
    // unconditionally) handed EAGER actors like SubwayStation/BusStation their first
    // SpontaneousEvent before the cross-actor infrastructure their handling depends on was ready,
    // silently producing zero activity for the rest of the run.
    val parentProbe = TestProbe()
    val actorProbe = TestProbe()
    val ltm = newManager(parentProbe)
    val identity = identityFor(2L, actorProbe)

    // No testStart yet -- this LTM hasn't "started" in the hasStarted sense.
    ltm.underlyingActor.testRegister(identity, 0L)
    ltm.underlyingActor.testRunningEventIds shouldBe empty
    actorProbe.expectNoMessage(200.millis)

    ltm.underlyingActor.testStart(0L)

    actorProbe.expectMsgClass(3.seconds, classOf[core.entity.event.SpontaneousEvent])
    ltm.underlyingActor.testRunningEventIds shouldBe Set(2L)
  }

  "a passively-registered actor" should "never be dispatched a SpontaneousEvent, but still be destructed (and so flushed) at simulation stop" in {
    // Regression coverage for docs/TIME_WARP_DESIGN.md's conservative-vs-optimistic comparison
    // finding: an actor with scheduleOnTimeManager=false (Link/Node/RailLink/BusStop -- the common
    // case for purely-reactive infrastructure actors) never registered with any time manager at all
    // before RegisterPassiveActorEvent existed, so LocalTimeManagerBase.forceDestructActiveActors
    // (the only mechanism that flushes a Time-Warp actor's buffered report() calls via onDestruct)
    // could never reach it -- 282 of 287 report rows were silently dropped in a real run. Passive
    // registration must NOT cause any dispatch (an actor with no real actSpontaneous override, like
    // RailLink, would be caught by handleSpontaneous's auto-reschedule safety net and busy-loop
    // forever) but MUST still be reachable for a forced destruct once the simulation actually stops.
    val parentProbe = TestProbe()
    val actorProbe = TestProbe()
    val ltm = newManager(parentProbe)
    val identity = identityFor(3L, actorProbe)

    ltm.underlyingActor.testStart(0L)
    ltm.underlyingActor.testRegisterPassive(identity)

    // No dispatch at all -- neither running nor scheduled.
    ltm.underlyingActor.testRunningEventIds shouldBe empty
    ltm.underlyingActor.testScheduledTicks shouldBe empty
    actorProbe.expectNoMessage(200.millis)

    ltm ! StopSimulationEvent()

    actorProbe.expectMsgClass(3.seconds, classOf[DestructEvent])
  }

  "a destruct=true FinishEvent from live business logic" should "not immediately destruct the actor -- it must wait for GVT to confirm the decision is safe" in {
    // Regression coverage for the Time Warp destruct-safety bug: an actor's own "I'm done"
    // decision is speculative under optimistic execution and can be invalidated by a later
    // rollback, so destroying it (context.stop via DestructEvent) before GVT confirms finish.end
    // can never be rolled back past is unsafe -- see shouldDestructNow's doc.
    val parentProbe = TestProbe()
    val actorProbe = TestProbe()
    val ltm = newManager(parentProbe)
    val identity = identityFor(4L, actorProbe)

    ltm.underlyingActor.testStart(0L)
    ltm.underlyingActor.testRegister(identity, 0L)
    ltm.underlyingActor.testRunningEventIds shouldBe Set(4L)
    actorProbe.expectMsgClass(3.seconds, classOf[core.entity.event.SpontaneousEvent])

    ltm.underlyingActor.testFinish(
      FinishEvent(
        actorRef = actorProbe.ref,
        identify = identity,
        end = 10L,
        scheduleTick = None,
        timeManager = ltm,
        destruct = true,
        generation = 1L
      )
    )

    actorProbe.expectNoMessage(200.millis)
  }

  for (gvtKnownBeforeFinish <- Seq(false, true)) {
  it should s"destruct only after GVT passes finish.end (cached GVT: $gvtKnownBeforeFinish)" in {
    val parentProbe = TestProbe()
    val actorProbe = TestProbe()
    val ltm = newManager(parentProbe)
    val identity = identityFor(5L, actorProbe)

    ltm.underlyingActor.testStart(0L)
    ltm.underlyingActor.testRegister(identity, 0L)
    actorProbe.expectMsgClass(3.seconds, classOf[core.entity.event.SpontaneousEvent])
    if (gvtKnownBeforeFinish) ltm ! GvtUpdateEvent(gvt = 10L)
    ltm.underlyingActor.testFinish(
      FinishEvent(
        actorRef = actorProbe.ref,
        identify = identity,
        end = 10L,
        scheduleTick = None,
        timeManager = ltm,
        destruct = true,
        generation = 1L
      )
    )
    actorProbe.expectNoMessage(200.millis)

    ltm ! GvtUpdateEvent(gvt = 10L)
    actorProbe.expectNoMessage(200.millis)

    ltm ! GvtUpdateEvent(gvt = 11L)
    actorProbe.expectMsgClass(3.seconds, classOf[DestructEvent])
  }
  }

  it should "cancel the pending destruct when a ScheduleEvent reschedules the same actor before GVT confirms it, so a later GvtUpdateEvent must not destroy it" in {
    // Mirrors resyncScheduleAfterRollback's real effect: a rollback that replays this actor back to
    // life reschedules it via a normal ScheduleEvent, which must retract the earlier speculative
    // destruct decision rather than let a later GVT update destroy an actor that is actually alive.
    val parentProbe = TestProbe()
    val actorProbe = TestProbe()
    val ltm = newManager(parentProbe)
    val identity = identityFor(6L, actorProbe)

    ltm.underlyingActor.testStart(0L)
    ltm.underlyingActor.testRegister(identity, 0L)
    actorProbe.expectMsgClass(3.seconds, classOf[core.entity.event.SpontaneousEvent])
    ltm.underlyingActor.testFinish(
      FinishEvent(
        actorRef = actorProbe.ref,
        identify = identity,
        end = 10L,
        scheduleTick = None,
        timeManager = ltm,
        destruct = true,
        generation = 1L
      )
    )
    actorProbe.expectNoMessage(200.millis)

    ltm.underlyingActor.testSchedule(identity, 20L)
    ltm.underlyingActor.testRunningEventIds shouldBe Set(6L)
    // Drain the SpontaneousEvent the reschedule itself dispatched, so the assertion below is only
    // about whether a DestructEvent follows the GVT update, not about this unrelated redispatch.
    actorProbe.expectMsgClass(3.seconds, classOf[core.entity.event.SpontaneousEvent])

    ltm ! GvtUpdateEvent(gvt = 100L)
    actorProbe.expectNoMessage(200.millis)
  }

  "an actor orphaned in runningEvents (never sends a FinishEvent back)" should "not block a causally-unrelated actor scheduled on the same LTM instance from being dispatched" in {
    // Regression coverage for the whole-routee dispatch gate bug: one `OptimisticLocalTimeManager`
    // instance is a shared pool routee serving several actors' independent schedules (see this
    // class's own doc), so a single actor stuck forever in `runningEvents` (orphaned by a separate
    // rollback bug elsewhere) must not permanently stall every other actor ever scheduled on this
    // same routee -- confirmed live via a freeze where all pool routees were simultaneously
    // `isIdle=false` and stuck. `dispatchNextAvailable` must skip only the in-flight straggler, not
    // gate all dispatch on `runningEvents` being empty.
    val parentProbe = TestProbe()
    val strandedActorProbe = TestProbe()
    val liveActorProbe = TestProbe()
    val ltm = newManager(parentProbe)
    val strandedIdentity = identityFor(7L, strandedActorProbe)
    val liveIdentity = identityFor(8L, liveActorProbe)

    ltm.underlyingActor.testStart(0L)
    ltm.underlyingActor.testRegister(strandedIdentity, 0L)
    strandedActorProbe.expectMsgClass(3.seconds, classOf[core.entity.event.SpontaneousEvent])
    ltm.underlyingActor.testRunningEventIds shouldBe Set(7L)

    // The stranded actor never sends a FinishEvent back -- it's permanently "in flight" from this
    // LTM's point of view, simulating the orphaning bug this fix is scoped around.
    ltm.underlyingActor.testSchedule(liveIdentity, 5L)

    liveActorProbe.expectMsgClass(3.seconds, classOf[core.entity.event.SpontaneousEvent])
    ltm.underlyingActor.testRunningEventIds shouldBe Set(7L, 8L)
  }

  "a resyncAfterRollback ScheduleEvent" should "set highestProcessedTick to the reverted tick instead of bumping past it, fixing the Time Warp rollback dispatch bug" in {
    // Regression coverage for the live-debugged bug: a plain ScheduleEvent's stale-tick guard
    // compares against highestProcessedTick and bumps forward when at/behind it, which is exactly
    // wrong for a post-rollback resync -- resyncScheduleAfterRollback sends the actor's authoritative
    // reverted currentTick, and that must become the new watermark verbatim, not get redirected
    // past the actor's own stale, never-rolled-back-until-now watermark.
    val parentProbe = TestProbe()
    val actorProbe = TestProbe()
    val ltm = newManager(parentProbe)
    val identity = identityFor(9L, actorProbe)

    ltm.underlyingActor.testStart(0L)
    ltm.underlyingActor.testRegister(identity, 0L)
    actorProbe.expectMsgClass(3.seconds, classOf[core.entity.event.SpontaneousEvent])

    // Finish with no reschedule so the actor leaves runningEvents -- otherwise dispatchNextAvailable
    // (which is what actually bumps highestProcessedTick, via sendSpontaneousEvent) would skip it as
    // already in flight and neither testSchedule below would advance the watermark.
    ltm.underlyingActor.testFinish(
      FinishEvent(actorRef = actorProbe.ref, identify = identity, end = 0L, scheduleTick = None, timeManager = ltm, generation = 1L)
    )

    ltm.underlyingActor.testSchedule(identity, 500L)
    ltm.underlyingActor.testHighestProcessedTick(9L) shouldBe 500L
    actorProbe.expectMsgClass(3.seconds, classOf[core.entity.event.SpontaneousEvent])
    ltm.underlyingActor.testFinish(
      FinishEvent(actorRef = actorProbe.ref, identify = identity, end = 500L, scheduleTick = None, timeManager = ltm, generation = 2L)
    )

    ltm.underlyingActor.testScheduleResyncAfterRollback(identity, 200L)

    ltm.underlyingActor.testHighestProcessedTick(9L) shouldBe 200L
    // The resync is dispatched immediately (not left redirected to 501, the old bug's behavior).
    actorProbe.expectMsgType[core.entity.event.SpontaneousEvent].tick shouldBe 200L
  }

  it should "leave a plain, non-resync ScheduleEvent at/behind the watermark bumped forward exactly as before" in {
    val parentProbe = TestProbe()
    val actorProbe = TestProbe()
    val ltm = newManager(parentProbe)
    val identity = identityFor(10L, actorProbe)

    ltm.underlyingActor.testStart(0L)
    ltm.underlyingActor.testRegister(identity, 0L)
    actorProbe.expectMsgClass(3.seconds, classOf[core.entity.event.SpontaneousEvent])
    ltm.underlyingActor.testFinish(
      FinishEvent(actorRef = actorProbe.ref, identify = identity, end = 0L, scheduleTick = None, timeManager = ltm, generation = 1L)
    )

    ltm.underlyingActor.testSchedule(identity, 500L)
    ltm.underlyingActor.testHighestProcessedTick(10L) shouldBe 500L
    actorProbe.expectMsgClass(3.seconds, classOf[core.entity.event.SpontaneousEvent])
    ltm.underlyingActor.testFinish(
      FinishEvent(actorRef = actorProbe.ref, identify = identity, end = 500L, scheduleTick = None, timeManager = ltm, generation = 2L)
    )

    ltm.underlyingActor.testSchedule(identity, 100L)

    ltm.underlyingActor.testHighestProcessedTick(10L) shouldBe 501L
    actorProbe.expectMsgType[core.entity.event.SpontaneousEvent].tick shouldBe 501L
  }

  it should "leave the watermark at whichever resync was sent last, proving it sets rather than min/max-compares" in {
    val parentProbe = TestProbe()
    val actorProbe = TestProbe()
    val ltm = newManager(parentProbe)
    val identity = identityFor(11L, actorProbe)

    ltm.underlyingActor.testStart(0L)
    ltm.underlyingActor.testRegister(identity, 0L)
    actorProbe.expectMsgClass(3.seconds, classOf[core.entity.event.SpontaneousEvent])
    ltm.underlyingActor.testFinish(
      FinishEvent(actorRef = actorProbe.ref, identify = identity, end = 0L, scheduleTick = None, timeManager = ltm, generation = 1L)
    )

    ltm.underlyingActor.testScheduleResyncAfterRollback(identity, 300L)
    ltm.underlyingActor.testHighestProcessedTick(11L) shouldBe 300L
    actorProbe.expectMsgClass(3.seconds, classOf[core.entity.event.SpontaneousEvent])
    ltm.underlyingActor.testFinish(
      FinishEvent(actorRef = actorProbe.ref, identify = identity, end = 300L, scheduleTick = None, timeManager = ltm, generation = 2L)
    )

    ltm.underlyingActor.testScheduleResyncAfterRollback(identity, 150L)

    ltm.underlyingActor.testHighestProcessedTick(11L) shouldBe 150L
    actorProbe.expectMsgType[core.entity.event.SpontaneousEvent].tick shouldBe 150L
  }
}
