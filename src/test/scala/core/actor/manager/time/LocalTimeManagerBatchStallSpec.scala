package org.interscity.htc
package core.actor.manager.time

import core.entity.event.{ FinishEvent, SpontaneousEvent }
import core.types.Tick

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ ActorRef, ActorSystem }
import org.apache.pekko.testkit.{ TestActorRef, TestProbe }
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.execution.{ RegisterActorEvent, StartSimulationTimeEvent }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.compiletime.uninitialized
import scala.concurrent.duration.DurationInt

/** Regression coverage for docs/KNOWN_GAPS.md's "`deferFinishSpontaneous()` Holds an Entire LTM
  * Batch Open for the Duration of an Unbounded Async Wait" gap, and the specific 0-tick
  * termination race the first (reverted) full-disengage fix attempt hit — flagged there as
  * "still open: no test currently exercises `runningEvents`/batch-stall behavior directly".
  *
  * Exercises `LocalDiscreteEventTimeManager`'s real bookkeeping (`runningEvents`,
  * `scheduledActors`, `advanceToNextTick`/`reportGlobalTimeManager`) directly on
  * `underlyingActor`, the same pattern `PrivateVehicleMigrationSnapshotSpec` uses for a
  * PersistentActor under test: calling protected methods (`registerActor`/`finishEvent`/etc.)
  * directly bypasses the mailbox/dispatcher entirely, avoiding message-ordering races while still
  * exercising the exact same code paths `handleEvent` routes into. `TestProbe`s stand in for the
  * "actors" a real Node/Car/BusStation would be, addressed by `PoolDistributed`/`actorSelection`
  * (matching `sendSpontaneousEventPool`) so no cluster sharding is needed —
  * `pekko.actor.provider = local` throughout, same as `LoadDataManagerScenarioPreflightSpec`/
  * `PrivateVehicleMigrationSnapshotSpec`.
  */
class LocalTimeManagerBatchStallSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  // Created in beforeAll (not a field initializer) for the same reason
  // PrivateVehicleMigrationSnapshotSpec does: SBT/ScalaTest's throwaway discovery instantiation
  // would otherwise double-create the ActorSystem.
  private var _system: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit =
    _system = ActorSystem(
      "LocalTimeManagerBatchStallSpec",
      ConfigFactory
        .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
        .withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit = {
    _system.terminate()
    ()
  }

  /** Exposes `LocalTimeManagerBase`/`LocalDiscreteEventTimeManager`'s protected surface for
    * direct calls, mirroring `PrivateVehicleMigrationSnapshotSpec`'s `TestCar` pattern.
    */
  private class TestLocalTimeManager(parentManager: Option[ActorRef])
      extends LocalDiscreteEventTimeManager(
        simulationDuration = 100_000L,
        simulationManager = ActorRef.noSender,
        parentManager = parentManager
      ) {
    def testStart(startTick: Tick): Unit =
      startSimulation(StartSimulationTimeEvent(startTick = startTick, actorRef = "", data = None))
    def testRegister(identity: Identify, startTick: Tick): Unit =
      registerActor(RegisterActorEvent(startTick = startTick, actorId = identity.id, identify = Some(identity)))
    def testSchedule(identity: Identify, tick: Tick): Unit =
      scheduleEvent(ScheduleEvent(tick = tick, actorRef = identity.id, identify = Some(identity)))
    def testFinish(finish: FinishEvent): Unit = finishEvent(finish)
    def testRunningEventIds: Set[String] = runningEvents.map(_.id).toSet
    def testScheduledTicks: Set[Tick] = scheduledActors.keySet.toSet
  }

  private def identityFor(id: String, probe: TestProbe): Identify =
    Identify(
      id = id,
      resourceId = "",
      classType = "test.Actor",
      actorRef = probe.ref.path.toString,
      actorType = "PoolDistributed"
    )

  private def newManager(parentProbe: TestProbe): TestActorRef[TestLocalTimeManager] =
    TestActorRef(new TestLocalTimeManager(Some(parentProbe.ref)))

  "LocalDiscreteEventTimeManager" should "not hold the batch open once a genuinely-disengaged actor sends its FinishEvent -- runningEvents clears and the LTM advances to a sibling's next tick" in {
    val parentProbe = TestProbe()
    val actorAProbe = TestProbe()
    val actorBProbe = TestProbe()
    val ltm = newManager(parentProbe)

    val identityA = identityFor("actor-a", actorAProbe)
    val identityB = identityFor("actor-b", actorBProbe)

    ltm.underlyingActor.testStart(0L)
    ltm.underlyingActor.testRegister(identityA, 0L)
    ltm.underlyingActor.testRegister(identityB, 0L)
    // registerActor -> scheduleEvent only NOTIFIES the parent that work exists (re-notify on
    // wasIdle) -- a real GlobalTimeManager would compute the next global tick and broadcast
    // UpdateGlobalTimeEvent back down to trigger the actual dispatch. Simulate that broadcast
    // directly (through the real mailbox, not underlyingActor, so handleEvent's own routing is
    // exercised) since parentProbe isn't a real GlobalTimeManager.
    parentProbe.receiveWhile(idle = 200.millis) { case _ => () }
    ltm ! org.htc.protobuf.core.entity.event.control.execution.UpdateGlobalTimeEvent(tick = 0L)

    // Both actors are dispatched together at tick 0 (LocalDiscreteEventTimeManager's own
    // processTick, triggered by the UpdateGlobalTimeEvent above, matching a real simulation's
    // first tick). Both should be in runningEvents.
    ltm.underlyingActor.testRunningEventIds shouldBe Set("actor-a", "actor-b")

    // actor-b resolves immediately, asking to run again at tick 5 (an ordinary
    // onFinishSpontaneous(Some(5))).
    ltm.underlyingActor.testFinish(
      FinishEvent(
        actorRef = actorBProbe.ref,
        identify = identityB,
        end = 0L,
        scheduleTick = Some("5"),
        timeManager = ltm,
        generation = 1L
      )
    )
    // actor-a is still "running" (an unbounded async wait, e.g. a dynamic-actor-spawn ack) --
    // but critically, using the FIXED pattern (a genuine, immediate disengage FinishEvent with no
    // scheduleTick, NOT deferFinishSpontaneous()'s "send nothing at all").
    ltm.underlyingActor.testRunningEventIds shouldBe Set("actor-a")

    ltm.underlyingActor.testFinish(
      FinishEvent(
        actorRef = actorAProbe.ref,
        identify = identityA,
        end = 0L,
        scheduleTick = None,
        timeManager = ltm,
        generation = 1L
      )
    )

    // The batch is no longer held open: runningEvents is empty, and actor-b's tick 5 is visible
    // as the LTM's next scheduled work -- this is the exact invariant deferFinishSpontaneous()
    // broke (it never sends a FinishEvent at all, so runningEvents never clears and this next
    // tick is never reachable until the deferred actor's async wait resolves).
    ltm.underlyingActor.testRunningEventIds shouldBe empty
    ltm.underlyingActor.testScheduledTicks shouldBe Set(5L)

    // advanceToNextTick's reportGlobalTimeManager(hasScheduled = true) must have reached the
    // parent -- the LTM is not sitting silently idle with real future work unreported.
    parentProbe.fishForMessage(200.millis) {
      case e: org.htc.protobuf.core.entity.event.control.execution.LocalTimeReportEvent =>
        e.hasScheduled && e.tick == 5L
      case _ => false
    }
  }

  it should "let a fully-disengaged actor (no FinishEvent scheduleTick at all) be re-woken later via a fresh ScheduleEvent, re-notifying an idle parent -- the exact protocol BusStation/SubwayStation depend on after a dynamic-actor-spawn ack" in {
    val parentProbe = TestProbe()
    val actorProbe = TestProbe()
    val ltm = newManager(parentProbe)
    val identity = identityFor("actor-a", actorProbe)

    ltm.underlyingActor.testStart(0L)
    ltm.underlyingActor.testRegister(identity, 0L)
    parentProbe.receiveWhile(idle = 200.millis) { case _ => () }
    ltm ! org.htc.protobuf.core.entity.event.control.execution.UpdateGlobalTimeEvent(tick = 0L)

    ltm.underlyingActor.testRunningEventIds shouldBe Set("actor-a")

    // Genuine disengage: onFinishSpontaneous(None), no scheduleTick -- the LTM is now fully idle
    // (no running events, nothing scheduled), matching what a real actor does right before
    // awaiting an external async event (e.g. a dynamic-actor-spawn ack) it has no bounded ETA for.
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
    // The idle report reaching the parent here is exactly the moment the original bug's race
    // window opens: a real GlobalTimeManager sees "no scheduled events" and starts its
    // termination grace period. Drain it so the assertion below is about the RE-notification,
    // not this expected idle report.
    parentProbe.fishForMessage(200.millis) {
      case e: org.htc.protobuf.core.entity.event.control.execution.LocalTimeReportEvent =>
        !e.hasScheduled
      case _ => false
    }

    // The async event finally arrives (e.g. onDynamicActorInitialized) and the actor
    // re-registers itself via a fresh ScheduleEvent -- SimulationBaseActor.scheduleEvent(tick),
    // not onFinishSpontaneous, is required here precisely because there's no in-flight
    // FinishEvent to attach a scheduleTick to (see CarSignalHandler.requestSignalState's own
    // comment on this same requirement).
    ltm.underlyingActor.testSchedule(identity, 42L)

    // wasIdle re-notification (LocalTimeManagerBase.scheduleEvent) must fire: this is the exact
    // mechanism that prevents the GlobalTimeManager from having already terminated the
    // simulation by the time this actor finally has real work again.
    parentProbe.fishForMessage(200.millis) {
      case e: org.htc.protobuf.core.entity.event.control.execution.LocalTimeReportEvent =>
        e.hasScheduled && e.tick == 42L
      case _ => false
    }
    ltm.underlyingActor.testScheduledTicks shouldBe Set(42L)
  }
}
