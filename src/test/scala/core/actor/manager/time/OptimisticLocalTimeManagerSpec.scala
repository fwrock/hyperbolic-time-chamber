package org.interscity.htc
package core.actor.manager.time

import core.entity.event.FinishEvent
import core.entity.event.control.execution.{ LvtReportEvent, RegisterPassiveActorEvent }
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
    def testStart(startTick: Tick): Unit =
      startSimulation(StartSimulationTimeEvent(startTick = startTick, actorRef = "", data = None))
    def testRegister(identity: Identify, startTick: Tick): Unit =
      registerActor(RegisterActorEvent(startTick = startTick, actorId = identity.id, identify = Some(identity)))
    def testRegisterPassive(identity: Identify): Unit =
      registerPassiveActor(RegisterPassiveActorEvent(actorId = identity.id, identify = Some(identity)))
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
    val identity = identityFor("subwaystation-a", actorProbe)

    // No testStart yet -- this LTM hasn't "started" in the hasStarted sense.
    ltm.underlyingActor.testRegister(identity, 0L)
    ltm.underlyingActor.testRunningEventIds shouldBe empty
    actorProbe.expectNoMessage(200.millis)

    ltm.underlyingActor.testStart(0L)

    actorProbe.expectMsgClass(3.seconds, classOf[core.entity.event.SpontaneousEvent])
    ltm.underlyingActor.testRunningEventIds shouldBe Set("subwaystation-a")
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
    val identity = identityFor("raillink-a", actorProbe)

    ltm.underlyingActor.testStart(0L)
    ltm.underlyingActor.testRegisterPassive(identity)

    // No dispatch at all -- neither running nor scheduled.
    ltm.underlyingActor.testRunningEventIds shouldBe empty
    ltm.underlyingActor.testScheduledTicks shouldBe empty
    actorProbe.expectNoMessage(200.millis)

    ltm ! StopSimulationEvent()

    actorProbe.expectMsgClass(3.seconds, classOf[DestructEvent])
  }
}
