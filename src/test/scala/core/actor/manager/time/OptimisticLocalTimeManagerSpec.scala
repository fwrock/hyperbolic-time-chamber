package org.interscity.htc
package core.actor.manager.time

import core.entity.event.FinishEvent
import core.entity.event.control.execution.LvtReportEvent
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

  private def newManager(parentProbe: TestProbe): TestActorRef[TestOptimisticLocalTimeManager] =
    TestActorRef(new TestOptimisticLocalTimeManager(Some(parentProbe.ref)))

  "OptimisticLocalTimeManager" should "dispatch a registered actor immediately, with no UpdateGlobalTimeEvent permission needed" in {
    val parentProbe = TestProbe()
    val actorProbe = TestProbe()
    val ltm = newManager(parentProbe)
    val identity = identityFor("actor-a", actorProbe)

    ltm.underlyingActor.testStart(0L)
    ltm.underlyingActor.testRegister(identity, 0L)

    // No UpdateGlobalTimeEvent sent at all -- unlike ConservativeLocalTimeManager, dispatch must
    // have already happened purely from registerActor/scheduleEvent's own re-notify hook.
    ltm.underlyingActor.testRunningEventIds shouldBe Set("actor-a")
  }

  it should "report LVT via a fire-and-forget LvtReportEvent once the batch resolves, not a blocking LocalTimeReportEvent" in {
    val parentProbe = TestProbe()
    val actorProbe = TestProbe()
    val ltm = newManager(parentProbe)
    val identity = identityFor("actor-a", actorProbe)

    ltm.underlyingActor.testStart(0L)
    ltm.underlyingActor.testRegister(identity, 0L)
    ltm.underlyingActor.testRunningEventIds shouldBe Set("actor-a")

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
    ltm.underlyingActor.testRunningEventIds shouldBe Set("actor-a")
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
    val identity = identityFor("actor-a", actorProbe)

    ltm.underlyingActor.testStart(0L)
    ltm.underlyingActor.testRegister(identity, 0L)
    ltm.underlyingActor.testRunningEventIds shouldBe Set("actor-a")

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

  it should "re-dispatch immediately when a fresh ScheduleEvent arrives for a previously-idle actor" in {
    val parentProbe = TestProbe()
    val actorProbe = TestProbe()
    val ltm = newManager(parentProbe)
    val identity = identityFor("actor-a", actorProbe)

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
    ltm.underlyingActor.testRunningEventIds shouldBe Set("actor-a")
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
}
