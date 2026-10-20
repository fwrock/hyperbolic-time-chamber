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

/** W0 of the Time Warp correctness plan, Gap T: termination cannot tell "finished" from "blocked on
  * a reply that never comes".
  *
  * An actor that sends a request and resolves its spontaneous event with `None` (what `Movable`
  * does after `EnterLinkData`, commit c339162) leaves both `runningEvents` and the schedule. From
  * the LTM's point of view it is gone, so the LTM reports `isIdle = true`. If the reply is then lost
  * (see Gap A), the GTM's `TerminationPlateauDetector` sees all LTMs idle and a flat GVT and
  * declares a successful end while the actor is still waiting.
  *
  * The characterization test pins today's behavior. The pending test states the contract W1 must
  * establish; it needs a way for the actor to tell the LTM it is waiting, which does not exist yet,
  * so it is a placeholder.
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
  }

  private def identityFor(id: Long, probe: TestProbe): Identify =
    Identify(
      id = id,
      resourceId = "",
      classType = "test.Actor",
      actorRef = probe.ref.path.toString,
      actorType = "PoolDistributed"
    )

  "an actor that resolved its spontaneous event with None while waiting for a reply" should
    "currently make the LTM report isIdle = true (Gap T, characterization)" in {
      val parentProbe = TestProbe()
      val actorProbe = TestProbe()
      val ltm = TestActorRef(new TestOptimisticLocalTimeManager(Some(parentProbe.ref)))
      val identity = identityFor(1L, actorProbe)

      ltm.underlyingActor.testStart(0L)
      ltm.underlyingActor.testRegister(identity, 0L)
      ltm.underlyingActor.testRunningEventIds shouldBe Set(1L)

      // The actor sent a request at tick 0 and now waits for the reply: no next tick to schedule.
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
      parentProbe.fishForMessage(1.second) {
        case e: LvtReportEvent => e.isIdle
        case _                 => false
      }
    }

  it should "keep the LTM non-idle, and its LVT at the request tick, until the reply arrives (Gap T, W1 contract)" in {
    pending
  }
}
