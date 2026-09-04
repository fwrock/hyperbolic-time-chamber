package org.interscity.htc
package core.actor.manager.time

import core.actor.manager.time.gvt.MarginBasedGVTEstimation
import core.entity.event.control.execution.{ LvtReportEvent, TimeManagerRegisterEvent }
import core.entity.event.control.load.{ RegisterProgressiveLoadManagerEvent, TickWindowReady, TickWindowRequest }

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ ActorRef, ActorSystem }
import org.apache.pekko.routing.Broadcast
import org.apache.pekko.testkit.{ TestActorRef, TestProbe }
import org.htc.protobuf.core.entity.event.control.execution.StartSimulationTimeEvent
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.compiletime.uninitialized
import scala.concurrent.duration.DurationInt

/** Regression coverage for `docs/TIME_WARP_DESIGN.md`'s progressive-loading gap: `SimulationManager`
  * sends `RegisterProgressiveLoadManagerEvent` unconditionally whenever a scenario has progressive
  * sources, regardless of which GTM is active — without handling it, a Time Warp run would hang at
  * startup waiting for an ack that never arrives, and without gating termination on it, an
  * all-idle GVT plateau caused by nothing having loaded yet would be indistinguishable from real
  * completion.
  *
  * `onStart` is overridden to skip `createTimeManagersPool()` (needs a real cluster) — every
  * other handler is exercised directly via its real mailbox, `TestProbe`s standing in for
  * registered `OptimisticLocalTimeManager`s and the `ProgressiveLoadDataManager`.
  */
class OptimisticGlobalTimeManagerSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var _system: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit =
    _system = ActorSystem(
      "OptimisticGlobalTimeManagerSpec",
      ConfigFactory
        .parseString(
          "pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off\n" +
            "htc.time-warp.termination-heartbeat-interval-ms = 50"
        )
        .withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit = {
    _system.terminate()
    ()
  }

  private class TestOptimisticGlobalTimeManager
      extends OptimisticGlobalTimeManager(
        simulationDuration = 100_000L,
        simulationManager = ActorRef.noSender,
        gvtEstimationStrategy = new MarginBasedGVTEstimation(margin = 0L),
        plateauRoundsRequired = 1
      ) {
    override def onStart(): Unit = ()
    def testSetPool(ref: ActorRef): Unit = timeManagersPool = ref
  }

  private def newGtm(): TestActorRef[TestOptimisticGlobalTimeManager] = {
    val ref = TestActorRef(new TestOptimisticGlobalTimeManager)
    Thread.sleep(1000)
    ref
  }

  private def registerLtm(gtm: TestActorRef[TestOptimisticGlobalTimeManager], probe: TestProbe): Unit = {
    gtm.underlyingActor.testSetPool(probe.ref)
    gtm ! TimeManagerRegisterEvent(actorRef = probe.ref)
  }

  "a Time Warp run with progressive sources" should "ack RegisterProgressiveLoadManagerEvent instead of hanging" in {
    val gtm = newGtm()
    val ackProbe = TestProbe()

    gtm ! RegisterProgressiveLoadManagerEvent(
      progressiveLoadManager = TestProbe().ref,
      lookAheadTicks = 5000L,
      ackTo = Some(ackProbe.ref)
    )

    ackProbe.expectMsgClass(3.seconds, classOf[org.interscity.htc.core.entity.event.control.load.ProgressiveLoadManagerRegisteredEvent])
      .lookAheadTicks shouldBe 5000L
  }

  it should "hold simulation start until the initial progressive window is ready, then release it" in {
    val gtm = newGtm()
    val plmProbe = TestProbe()
    val ltmProbe = TestProbe()

    gtm ! RegisterProgressiveLoadManagerEvent(progressiveLoadManager = plmProbe.ref, lookAheadTicks = 5000L, ackTo = None)
    registerLtm(gtm, ltmProbe)

    gtm ! StartSimulationTimeEvent(startTick = 0L, actorRef = "", data = None)

    plmProbe.expectMsgClass(3.seconds, classOf[TickWindowRequest]).currentTick shouldBe 0L
    ltmProbe.expectNoMessage(300.millis) // must NOT have been told to start yet

    gtm ! TickWindowReady(readyUpToTick = 5000L, actorsCreated = 42L)

    ltmProbe.expectMsgClass(3.seconds, classOf[Broadcast]).message shouldBe a[StartSimulationTimeEvent]
  }

  it should "request the next window instead of terminating when idle before loading is complete" in {
    val gtm = newGtm()
    val plmProbe = TestProbe()
    val ltmProbe = TestProbe()

    gtm ! RegisterProgressiveLoadManagerEvent(progressiveLoadManager = plmProbe.ref, lookAheadTicks = 1000L, ackTo = None)
    registerLtm(gtm, ltmProbe)
    gtm ! StartSimulationTimeEvent(startTick = 0L, actorRef = "", data = None)
    plmProbe.expectMsgClass(3.seconds, classOf[TickWindowRequest])
    gtm ! TickWindowReady(readyUpToTick = 1000L, actorsCreated = 10L)
    ltmProbe.expectMsgClass(3.seconds, classOf[Broadcast])

    gtm.tell(LvtReportEvent(lvt = 1000L, isIdle = true), ltmProbe.ref)

    plmProbe.expectMsgClass(3.seconds, classOf[TickWindowRequest]).currentTick shouldBe 1001L
  }

  it should "terminate on a real idle plateau once progressive loading is complete" in {
    val gtm = newGtm()
    val plmProbe = TestProbe()
    val ltmProbe = TestProbe()
    val simulationManagerProbe = TestProbe()

    gtm ! RegisterProgressiveLoadManagerEvent(progressiveLoadManager = plmProbe.ref, lookAheadTicks = 1000L, ackTo = None)
    registerLtm(gtm, ltmProbe)
    gtm ! StartSimulationTimeEvent(startTick = 0L, actorRef = "", data = None)
    plmProbe.expectMsgClass(3.seconds, classOf[TickWindowRequest])
    gtm ! TickWindowReady(readyUpToTick = Long.MaxValue, actorsCreated = 10L)
    ltmProbe.expectMsgClass(3.seconds, classOf[Broadcast])

    gtm.tell(LvtReportEvent(lvt = 5L, isIdle = true), ltmProbe.ref)

    // The GVT estimate advances on this same round (docs/TIME_WARP_DESIGN.md §4's report-buffer
    // flush signal), broadcast before termination's own StopSimulationEvent broadcast.
    ltmProbe.expectMsgClass(3.seconds, classOf[Broadcast]).message shouldBe a[core.entity.event.control.execution.GvtUpdateEvent]
    ltmProbe.expectMsgClass(3.seconds, classOf[Broadcast]).message shouldBe a[org.htc.protobuf.core.entity.event.control.execution.StopSimulationEvent]
  }

  "the termination heartbeat" should "eventually terminate a plateaued-but-not-yet-declared simulation with no further LvtReportEvent ever arriving" in {
    // Regression coverage for docs/TIME_WARP_DESIGN.md's real end-to-end-run finding:
    // recomputeGvtAndCheckTermination only used to run reactively off LvtReportEvent/TickWindowReady/
    // Terminated. Once an LTM's LAST real report already leaves it idle, nothing else ever arrives to
    // trigger a second recompute -- TerminationPlateauDetector's required consecutive rounds (2 here)
    // could never accumulate past the 1 the last report produced, and the simulation would sit idle
    // forever. This test sends exactly ONE LvtReportEvent, then sends nothing else at all -- only the
    // self-scheduled heartbeat can produce the second round.
    class TestOptimisticGlobalTimeManagerTwoRounds
        extends OptimisticGlobalTimeManager(
          simulationDuration = 100_000L,
          simulationManager = ActorRef.noSender,
          gvtEstimationStrategy = new MarginBasedGVTEstimation(margin = 0L),
          plateauRoundsRequired = 2
        ) {
      override def onStart(): Unit = ()
      def testSetPool(ref: ActorRef): Unit = timeManagersPool = ref
    }

    val gtm = TestActorRef(new TestOptimisticGlobalTimeManagerTwoRounds)
    Thread.sleep(1000)
    val ltmProbe = TestProbe()
    gtm.underlyingActor.testSetPool(ltmProbe.ref)
    gtm ! TimeManagerRegisterEvent(actorRef = ltmProbe.ref)

    gtm ! StartSimulationTimeEvent(startTick = 0L, actorRef = "", data = None)
    ltmProbe.expectMsgClass(3.seconds, classOf[Broadcast]).message shouldBe a[StartSimulationTimeEvent]

    gtm.tell(LvtReportEvent(lvt = 5L, isIdle = true), ltmProbe.ref)

    // GVT advances on this same round, broadcast before termination's own StopSimulationEvent (see
    // the earlier "terminate on a real idle plateau" test's identical ordering). Round 1 of the
    // plateau (plateauRoundsRequired = 2) is produced by this report, not yet enough to terminate.
    ltmProbe.expectMsgClass(3.seconds, classOf[Broadcast]).message shouldBe a[core.entity.event.control.execution.GvtUpdateEvent]

    // Nothing else is ever sent from here on -- no second LvtReportEvent, no further messages at
    // all. Only the 50ms self-scheduled heartbeat can produce round 2 and terminate.
    ltmProbe.expectMsgClass(3.seconds, classOf[Broadcast]).message shouldBe a[org.htc.protobuf.core.entity.event.control.execution.StopSimulationEvent]
  }
}
