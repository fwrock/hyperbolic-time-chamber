package org.interscity.htc
package core.runtime.distributed

import core.actor.manager.time.{AccountedOptimisticGlobalTimeManager, AccountedOptimisticLocalTimeManager}
import core.entity.event.{ActorInteractionEvent, FinishEvent}
import core.entity.event.control.report.ReportEvent
import core.runtime.EventOrder
import core.runtime.accounting.*
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.apache.pekko.testkit.{TestActorRef, TestProbe}
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.control.execution.{StartSimulationTimeEvent, StopSimulationEvent}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

private case class HeldTransfer(target: ActorRef, source: ActorRef, transfer: OptimisticTransfer)

class DistributedRuntimeSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("DistributedRuntimeSpec",
    ConfigFactory.parseString("pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off\nhtc.time-warp.termination-heartbeat-interval-ms=60000")
      .withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }
  private class GTM(manager: ActorRef, sink: ActorRef) extends AccountedOptimisticGlobalTimeManager(100L, manager) {
    override def onStart(): Unit = ()
    override protected def expectedWorkers: Int = 2
    override protected lazy val reportManagerRef: ActorRef = sink
    def ready: Boolean = recoveryFinished
  }
  private class Worker(manager: ActorRef, global: ActorRef, gate: ActorRef)
    extends AccountedOptimisticLocalTimeManager(100L, manager, global) {
    def ready: Boolean = recoveryFinished
    override protected def transferTo(destination: ActorRef, event: OptimisticTransfer): Unit =
      gate ! HeldTransfer(destination, self, event)
  }
  private var sequence = 0
  private def run(anti: Boolean, failure: Option[String] = None): Unit = {
    sequence += 1
    val manager = TestProbe()
    val flush = TestProbe()
    val sink = TestProbe()
    val gate = TestProbe()
    val source = TestProbe()
    val destination = TestProbe()
    val gtm = TestActorRef(new GTM(manager.ref, flush.ref), s"gtm-$sequence")
    manager.awaitAssert(gtm.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
    val workers = Vector("a", "b").map(n => TestActorRef(new Worker(manager.ref, gtm, gate.ref), s"worker-$sequence-$n"))
    manager.awaitAssert(workers.forall(_.underlyingActor.ready) shouldBe true, 5.seconds, 10.millis)
    val model = "org.interscity.htc.model.example.Model"
    val ids = (1L to 100L).toVector
    val first = ids.find(id => Math.floorMod(LogicalProcessId(model, id).hashCode(), 2) == 0).get
    val second = ids.find(id => Math.floorMod(LogicalProcessId(model, id).hashCode(), 2) == 1).get
    def enroll(lp: TestProbe, id: Long): RuntimeJoined = {
      lp.send(gtm, JoinRuntime(Identify(id = id, classType = model), lp.ref, None, 0L, s"instance-$id"))
      val joined = lp.expectMsgType[RuntimeJoined]
      joined.coordinator shouldBe gtm
      joined.distributed shouldBe true
      joined
    }
    val a = enroll(source, first)
    val b = enroll(destination, second)
    a.owner should not be b.owner
    source.send(a.owner, RouteControl(a.epoch, a.owner, FinishEvent(source.ref,
      Identify(id = first, classType = "example.Model"), 0L, timeManager = a.owner), 0L))
    val event = ActorInteractionEvent(10L, 1L, first, model, "", model, data = "input", resourceId = "test",
      seq = 1L, isAntiMessage = anti, order = if (anti) None else Some(EventOrder.root(10L, model, first)),
      sourceEpoch = a.epoch, sourceIncarnation = s"instance-$first")
    source.send(a.owner, RouteInteraction(a.epoch, LogicalProcessId(model, second), event))
    val held = gate.expectMsgType[HeldTransfer]
    gtm ! StartSimulationTimeEvent(startTick = 0L)
    source.expectMsg(SetRuntimeHorizon(100L))
    source.expectMsg(ResumeRuntime(0L))
    destination.expectMsg(SetRuntimeHorizon(100L))
    destination.expectMsg(ResumeRuntime(0L))
    gtm ! RequestRuntimeRound()
    val round = source.expectMsgType[FreezeRuntime].round
    destination.expectMsg(FreezeRuntime(round))
    source.send(a.owner, RuntimeFrozen(round))
    destination.send(b.owner, RuntimeFrozen(round))
    source.expectNoMessage(100.millis)
    destination.expectNoMessage(100.millis)
    manager.expectNoMessage(100.millis)
    if (failure.contains("lp") || failure.contains("ltm")) {
      system.stop(if (failure.contains("lp")) destination.ref else b.owner)
      manager.expectMsgType[RuntimeFailure](5.seconds).reason should include (
        if (failure.contains("lp")) "LP lost" else "LTM lost")
      manager.expectNoMessage(100.millis)
      return
    }
    held.target.tell(held.transfer, held.source)
    val delivery = destination.expectMsgType[RuntimeDelivery]
    delivery.event shouldBe event
    source.expectNoMessage(100.millis)
    destination.send(b.owner, DeliveryAccepted(b.epoch, delivery.token))
    source.expectMsg(CollectRuntimeSnapshot(round))
    destination.expectMsg(CollectRuntimeSnapshot(round))
    source.send(a.owner, RuntimeSnapshot(round, None, 0, 0))
    destination.send(b.owner, RuntimeSnapshot(round, Some(10L), 0, 0))
    source.expectMsg(CommitRuntime(round, 10L))
    destination.expectMsg(CommitRuntime(round, 10L))
    source.send(a.owner, PublishCommittedReport(sink.ref, ReportEvent(first.toString, 9L, 0L, data = "committed")))
    source.send(a.owner, RuntimeCommitted(round, false))
    destination.send(b.owner, RuntimeCommitted(round, false))
    val report = sink.expectMsgType[CommittedReportDelivery]
    source.expectNoMessage(100.millis)
    sink.send(report.ackTo, DeliveryAccepted(report.epoch, report.token))
    source.expectMsg(ResumeRuntime(round))
    destination.expectMsg(ResumeRuntime(round))

    gtm ! RequestRuntimeRound()
    val last = source.expectMsgType[FreezeRuntime].round
    destination.expectMsg(FreezeRuntime(last))
    source.send(a.owner, RuntimeFrozen(last))
    destination.send(b.owner, RuntimeFrozen(last))
    source.expectMsg(CollectRuntimeSnapshot(last))
    destination.expectMsg(CollectRuntimeSnapshot(last))
    source.send(a.owner, RuntimeSnapshot(last, None, 0, 0))
    destination.send(b.owner, RuntimeSnapshot(last, None, 0, 0))
    source.expectMsg(CommitRuntime(last, Long.MaxValue))
    destination.expectMsg(CommitRuntime(last, Long.MaxValue))
    source.send(a.owner, RuntimeCommitted(last, true))
    destination.send(b.owner, RuntimeCommitted(last, true))
    source.expectMsg(RetireRuntimeProcess(last, true))
    destination.expectMsg(RetireRuntimeProcess(last, true))
    val drain = flush.expectMsgType[DrainRuntimeReports]
    drain.targets shouldBe Set(sink.ref)
    source.send(a.owner, RuntimeProcessRetired(last))
    destination.send(b.owner, RuntimeProcessRetired(last))
    manager.expectNoMessage(100.millis)
    flush.send(gtm, RuntimeReportsFlushed(last, if (failure.contains("sink")) Some("sink write failed") else None))
    if (failure.contains("sink")) {
      manager.expectMsgType[RuntimeFailure].reason should include ("sink write failed")
      manager.expectNoMessage(100.millis)
    } else manager.expectMsg(StopSimulationEvent())
  }

  "distributed optimistic accounting" should "keep a delayed positive in the source ledger until destination acceptance, then commit the fresh minimum" in {
    run(false)
  }
  it should "hold GVT for a delayed anti-message and wait for report receipt, retirement and sink flush" in {
    run(true)
  }
  it should "fail explicitly when an LP dies with a delivery still in transit" in {
    run(false, Some("lp"))
  }
  it should "fail explicitly when an LTM is lost instead of shrinking the frozen membership" in {
    run(false, Some("ltm"))
  }
  it should "propagate final sink flush failure without reporting successful completion" in {
    run(false, Some("sink"))
  }
}
