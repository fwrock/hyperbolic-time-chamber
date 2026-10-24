package org.interscity.htc
package core.runtime.distributed

import core.actor.manager.time.{AccountedOptimisticGlobalTimeManager, AccountedOptimisticLocalTimeManager}
import core.entity.event.control.load.*
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

class ProgressiveOptimisticRuntimeSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("ProgressiveOptimisticRuntimeSpec",
    ConfigFactory.parseString("pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off\nhtc.time-warp.termination-heartbeat-interval-ms=60000")
      .withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }
  private class GTM(manager: ActorRef, sink: ActorRef) extends AccountedOptimisticGlobalTimeManager(100L, manager) {
    override def onStart(): Unit = ()
    override protected def expectedWorkers: Int = 2
    override protected lazy val reportManagerRef: ActorRef = sink
    def ready: Boolean = recoveryFinished
  }
  private class Worker(manager: ActorRef, global: ActorRef) extends AccountedOptimisticLocalTimeManager(100L, manager, global) {
    def ready: Boolean = recoveryFinished
  }
  private class Fixture {
    val manager = TestProbe()
    val sink = TestProbe()
    val producer = TestProbe()
    val first = TestProbe()
    val gtm = TestActorRef(new GTM(manager.ref, sink.ref))
    manager.awaitAssert(gtm.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
    val workers = Vector.fill(2)(TestActorRef(new Worker(manager.ref, gtm)))
    manager.awaitAssert(workers.forall(_.underlyingActor.ready) shouldBe true, 5.seconds, 10.millis)
    def enroll(lp: TestProbe, id: Long, start: Long): RuntimeJoined = {
      lp.send(gtm, JoinRuntime(Identify(id = id, classType = "example.Model"), lp.ref, None, start, s"instance-$id"))
      lp.expectMsgType[RuntimeJoined]
    }
    val joined = enroll(first, 1L, 0L)
    producer.send(gtm, RegisterProgressiveLoadManagerEvent(producer.ref, 10L, Some(producer.ref)))
    producer.expectMsg(ProgressiveLoadManagerRegisteredEvent(10L))
    gtm ! StartSimulationTimeEvent(startTick = 0L)
    val initial = producer.expectMsgType[LoadOptimisticInputWindow]
    initial.fromTick shouldBe 0L
    initial.throughTick shouldBe 10L
    producer.send(gtm, OptimisticInputWindowOpened(initial.epoch, initial.window))
    producer.send(gtm, OptimisticInputWindowReady(initial.epoch, initial.window, 10L, false, 1L))
    first.expectMsg(SetRuntimeHorizon(100L))
    first.expectMsgType[SetOptimisticExecutionLimits].ceiling shouldBe 10L
    first.expectMsg(ResumeRuntime(0L))

    def reachInputBoundary(minimum: Option[Long] = Some(20L)): LoadOptimisticInputWindow = {
      gtm ! RequestRuntimeRound()
      val round = first.expectMsgType[FreezeRuntime].round
      first.send(joined.owner, RuntimeFrozen(round))
      first.expectMsg(CollectRuntimeSnapshot(round))
      // Pending model work is later than the unloaded cohort. GVT must stop at 11.
      first.send(joined.owner, RuntimeSnapshot(round, minimum, 0, 0))
      first.expectMsgType[SetOptimisticExecutionLimits].ceiling shouldBe 10L
      first.expectMsg(CommitRuntime(round, 11L))
      first.send(joined.owner, RuntimeCommitted(round, false))
      val next = producer.expectMsgType[LoadOptimisticInputWindow]
      next.fromTick shouldBe 11L
      next.throughTick shouldBe 21L
      first.expectNoMessage(100.millis)
      manager.expectNoMessage(100.millis)
      next
    }
  }

  "progressive optimistic input" should "enroll a cohort at GVT while paused and include it in the next fresh cut" in {
    val f = new Fixture
    val request = f.reachInputBoundary()
    val second = TestProbe()
    val joined = f.enroll(second, 2L, 11L)
    second.expectMsg(SetRuntimeHorizon(100L))
    second.expectNoMessage(100.millis)
    // Legacy EOF from SimulationManager must not overtake the producer's final ready reply.
    f.gtm ! TickWindowReady(Long.MaxValue, 0L)
    f.first.expectNoMessage(100.millis)
    f.producer.send(f.gtm, OptimisticInputWindowReady(request.epoch, request.window, 21L, true, 1L))
    f.first.expectMsgType[SetOptimisticExecutionLimits].ceiling shouldBe 61L
    second.expectMsgType[SetOptimisticExecutionLimits].ceiling shouldBe 61L
    f.first.expectMsg(ResumeRuntime(1L))
    second.expectMsg(ResumeRuntime(1L))
    f.gtm ! RequestRuntimeRound()
    val round = f.first.expectMsgType[FreezeRuntime].round
    second.expectMsg(FreezeRuntime(round))
    f.first.send(f.joined.owner, RuntimeFrozen(round))
    second.send(joined.owner, RuntimeFrozen(round))
    f.first.expectMsg(CollectRuntimeSnapshot(round))
    second.expectMsg(CollectRuntimeSnapshot(round))
    f.first.send(f.joined.owner, RuntimeSnapshot(round, Some(20L), 0, 0))
    second.send(joined.owner, RuntimeSnapshot(round, Some(11L), 0, 0))
    f.first.expectMsgType[SetOptimisticExecutionLimits]
    f.first.expectMsg(CommitRuntime(round, 11L))
    second.expectMsgType[SetOptimisticExecutionLimits]
    second.expectMsg(CommitRuntime(round, 11L))
    f.manager.expectNoMessage(100.millis)
  }

  it should "keep empty model queues alive until the producer closes and the final drain completes" in {
    val f = new Fixture
    val request = f.reachInputBoundary(None)
    f.producer.send(f.gtm, OptimisticInputWindowReady(request.epoch, request.window, 21L, true, 0L))
    f.first.expectMsgType[SetOptimisticExecutionLimits]
    f.first.expectMsg(ResumeRuntime(1L))
    f.gtm ! RequestRuntimeRound()
    val round = f.first.expectMsgType[FreezeRuntime].round
    f.first.send(f.joined.owner, RuntimeFrozen(round))
    f.first.expectMsg(CollectRuntimeSnapshot(round))
    f.first.send(f.joined.owner, RuntimeSnapshot(round, None, 0, 0))
    f.first.expectMsgType[SetOptimisticExecutionLimits]
    f.first.expectMsg(CommitRuntime(round, Long.MaxValue))
    f.first.send(f.joined.owner, RuntimeCommitted(round, true))
    f.first.expectMsg(RetireRuntimeProcess(round, true))
    f.first.send(f.joined.owner, RuntimeProcessRetired(round))
    val drain = f.sink.expectMsgType[DrainRuntimeReports]
    f.manager.expectNoMessage(100.millis)
    f.sink.send(f.gtm, RuntimeReportsFlushed(drain.round))
    f.manager.expectMsg(StopSimulationEvent())
  }

  it should "reject enrollment below GVT" in {
    val f = new Fixture
    f.reachInputBoundary()
    val late = TestProbe()
    late.send(f.gtm, JoinRuntime(Identify(id = 2L, classType = "example.Model"), late.ref, None, 10L, "late"))
    f.manager.expectMsgType[RuntimeFailure].reason should include ("below GVT")
  }
  it should "reject spontaneous model birth while the input window is executing" in {
    val f = new Fixture
    val late = TestProbe()
    late.send(f.gtm, JoinRuntime(Identify(id = 2L, classType = "example.Model"), late.ref, None, 20L, "late"))
    f.manager.expectMsgType[RuntimeFailure].reason should include ("outside input enrollment")
  }
  it should "reject an obsolete producer acknowledgement" in {
    val f = new Fixture
    f.reachInputBoundary()
    f.producer.send(f.gtm, OptimisticInputWindowReady(f.initial.epoch, f.initial.window, 10L, true, 0L))
    f.manager.expectMsgType[RuntimeFailure].reason should include ("stale/duplicate")
  }
  it should "fail when the established producer dies before explicit closure" in {
    val f = new Fixture
    system.stop(f.producer.ref)
    f.manager.expectMsgType[RuntimeFailure].reason should include ("input producer lost")
  }
  it should "propagate a source failure without declaring successful completion" in {
    val f = new Fixture
    f.reachInputBoundary()
    f.producer.send(f.gtm, RuntimeFailure("source file unreadable"))
    f.manager.expectMsgType[RuntimeFailure].reason should include ("source file unreadable")
    f.manager.expectNoMessage(100.millis)
  }
}
