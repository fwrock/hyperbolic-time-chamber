package org.interscity.htc
package core.actor.manager.time

import core.actor.manager.time.gvt.MarginBasedGVTEstimation
import core.entity.event.control.execution.LvtReportEvent
import core.entity.event.control.load.*
import core.runtime.accounting.*
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ ActorRef, ActorSystem }
import org.apache.pekko.testkit.{ TestActorRef, TestProbe }
import org.htc.protobuf.core.entity.event.control.execution.{ StartSimulationTimeEvent, StopSimulationEvent }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

class OptimisticGlobalTimeManagerSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("OptimisticGlobalTimeManagerSpec",
    ConfigFactory.parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off\nhtc.time-warp.termination-heartbeat-interval-ms = 50")
      .withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }
  private class GTM(coordinator: ActorRef, manager: ActorRef)
    extends OptimisticGlobalTimeManager(1000L, manager, new MarginBasedGVTEstimation(0L), 1) {
    override def onStart(): Unit = accountedCoordinator = coordinator
    def ready: Boolean = recoveryFinished
  }
  private def rig(): (TestActorRef[GTM], TestProbe, TestProbe) = {
    val runtime = TestProbe()
    val manager = TestProbe()
    val ref = TestActorRef(new GTM(runtime.ref, manager.ref))
    manager.awaitAssert(ref.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
    (ref, runtime, manager)
  }

  "optimistic startup" should "acknowledge the input producer and register all windows before execution" in {
    val (gtm, runtime, _) = rig()
    val producer = TestProbe()
    val ack = TestProbe()
    gtm ! RegisterProgressiveLoadManagerEvent(producer.ref, 100L, Some(ack.ref))
    ack.expectMsgType[ProgressiveLoadManagerRegisteredEvent].lookAheadTicks shouldBe 100L
    gtm ! StartSimulationTimeEvent(startTick = 0L)
    producer.expectMsgType[TickWindowRequest].horizonTick shouldBe Long.MaxValue
    gtm ! TickWindowReady(100L, 2L)
    producer.expectMsgType[TickWindowRequest].currentTick shouldBe 101L
    runtime.expectNoMessage(100.millis)
    gtm ! TickWindowReady(Long.MaxValue, 0L)
    runtime.expectMsg(StartRuntime(0L))
  }

  "optimistic termination" should "never accept repeated idle LVT reports as a completion proof" in {
    val (gtm, runtime, manager) = rig()
    gtm ! StartSimulationTimeEvent(startTick = 0L)
    runtime.expectMsg(StartRuntime(0L))
    (1 to 5).foreach(_ => gtm ! LvtReportEvent(1000L, true))
    manager.expectNoMessage(200.millis)
    runtime.fishForMessage(1.second) { case RequestRuntimeRound() => true; case _ => false }
  }

  it should "stop only after the accounted coordinator confirms final retirement and sink drain" in {
    val (gtm, runtime, manager) = rig()
    gtm ! StartSimulationTimeEvent(startTick = 0L)
    runtime.expectMsg(StartRuntime(0L))
    TestProbe().send(gtm, RuntimeFinished(Long.MaxValue, false))
    manager.expectNoMessage(100.millis)
    runtime.send(gtm, RuntimeFinished(Long.MaxValue, false))
    manager.expectMsg(StopSimulationEvent())
  }

  it should "propagate a runtime failure without claiming successful completion" in {
    val (gtm, runtime, manager) = rig()
    runtime.send(gtm, RuntimeFailure("lost owner"))
    manager.expectMsg(RuntimeFailure("lost owner"))
    manager.expectNoMessage(100.millis)
  }
}
