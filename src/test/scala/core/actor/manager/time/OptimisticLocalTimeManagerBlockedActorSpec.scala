package org.interscity.htc
package core.actor.manager.time

import core.entity.event.FinishEvent
import core.entity.event.control.execution.LvtReportEvent
import core.types.Tick

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ ActorRef, ActorSystem }
import org.apache.pekko.testkit.{ TestActorRef, TestProbe }
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.control.execution.{ RegisterActorEvent, StartSimulationTimeEvent }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.compiletime.uninitialized
import scala.concurrent.duration.DurationInt

/** Gap T: termination must tell "finished" from "blocked on a reply that never comes".
  *
  * An actor that sends a request and resolves its spontaneous event with `None` (what `Movable`
  * does after `EnterLinkData`, commit c339162) leaves both `runningEvents` and the schedule. From
  * the LTM's point of view it is gone, so the LTM reports `isIdle = true`. If the reply is then lost
  * (see Gap A), the GTM's `TerminationPlateauDetector` sees all LTMs idle and a flat GVT and
  * declares a successful end while the actor is still waiting.
  *
  * Fixed by `FinishEvent.awaitingReply`: an actor that resolves with no next tick while its
  * awaited-reply registry is non-empty says so, and the LTM stays non-idle until a later finish
  * clears it.
  */
class OptimisticLocalTimeManagerBlockedActorSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var _system: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit =
    _system = ActorSystem(
      "OptimisticLocalTimeManagerBlockedActorSpec",
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
    def testFinish(finish: FinishEvent): Unit = finishEvent(finish)
    def testRunningEventIds: Set[Long] = runningEvents.map(_.id).toSet
    def testCompleteDestruct(identity: Identify): Unit = completeDestruct(identity)
    def testReport(): Unit = reportGlobalTimeManager()
  }

  private def identityFor(id: Long, probe: TestProbe): Identify =
    Identify(
      id = id,
      resourceId = "",
      classType = "test.Actor",
      actorRef = probe.ref.path.toString,
      actorType = "PoolDistributed"
    )

  private def finishWith(
    ltm: TestActorRef[TestOptimisticLocalTimeManager],
    identity: Identify,
    probe: TestProbe,
    awaitingReply: Boolean
  ): Unit =
    ltm.underlyingActor.testFinish(
      FinishEvent(
        actorRef = probe.ref,
        identify = identity,
        end = 0L,
        scheduleTick = None,
        timeManager = ltm,
        generation = 1L,
        awaitingReply = awaitingReply
      )
    )

  private def latestReport(parent: TestProbe): LvtReportEvent =
    parent.receiveWhile(1.second) { case m => m }.collect { case e: LvtReportEvent => e }.last

  private def startedLtm(parent: TestProbe, identity: Identify) = {
    val ltm = TestActorRef(new TestOptimisticLocalTimeManager(Some(parent.ref)))
    ltm.underlyingActor.testStart(0L)
    ltm.underlyingActor.testRegister(identity, 0L)
    ltm.underlyingActor.testRunningEventIds shouldBe Set(1L)
    ltm
  }

  "an actor that resolved its spontaneous event with None and awaits nothing" should
    "let the LTM report isIdle = true" in {
      val parentProbe = TestProbe()
      val actorProbe = TestProbe()
      val identity = identityFor(1L, actorProbe)
      val ltm = startedLtm(parentProbe, identity)

      finishWith(ltm, identity, actorProbe, awaitingReply = false)

      ltm.underlyingActor.testRunningEventIds shouldBe empty
      latestReport(parentProbe).isIdle shouldBe true
    }

  "an actor that resolved its spontaneous event with None while waiting for a reply" should
    "keep the LTM non-idle, and its LVT at the request tick, until the reply arrives (Gap T)" in {
      val parentProbe = TestProbe()
      val actorProbe = TestProbe()
      val identity = identityFor(1L, actorProbe)
      val ltm = startedLtm(parentProbe, identity)

      finishWith(ltm, identity, actorProbe, awaitingReply = true)

      // Neither running nor scheduled, but not finished either.
      ltm.underlyingActor.testRunningEventIds shouldBe empty
      val blocked = latestReport(parentProbe)
      blocked.isIdle shouldBe false
      blocked.blockedActors shouldBe 1
      blocked.lvt should be <= 0L

      // The reply arrives and the actor resolves its next dispatch with nothing left to wait for.
      finishWith(ltm, identity, actorProbe, awaitingReply = false)
      val released = latestReport(parentProbe)
      released.isIdle shouldBe true
      released.blockedActors shouldBe 0
    }

  it should "stop counting as blocked once the actor is destructed" in {
    val parentProbe = TestProbe()
    val actorProbe = TestProbe()
    val identity = identityFor(1L, actorProbe)
    val ltm = startedLtm(parentProbe, identity)
    finishWith(ltm, identity, actorProbe, awaitingReply = true)
    latestReport(parentProbe).isIdle shouldBe false

    ltm.underlyingActor.testCompleteDestruct(identity)
    ltm.underlyingActor.testReport()

    latestReport(parentProbe).blockedActors shouldBe 0
  }
}
