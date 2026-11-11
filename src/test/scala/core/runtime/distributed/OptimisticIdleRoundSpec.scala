package org.interscity.htc
package core.runtime.distributed

import core.actor.manager.time.{AccountedOptimisticGlobalTimeManager, AccountedOptimisticLocalTimeManager}
import core.entity.event.ActorInteractionEvent
import core.runtime.EventOrder
import core.runtime.accounting.*
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.apache.pekko.testkit.{TestActorRef, TestProbe}
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.control.execution.StartSimulationTimeEvent
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

class OptimisticIdleRoundSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("OptimisticIdleRoundSpec",
    ConfigFactory.parseString("pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off\nhtc.time-warp.termination-heartbeat-interval-ms=60000")
      .withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }

  private class GTM(manager: ActorRef) extends AccountedOptimisticGlobalTimeManager(100L, manager) {
    override def onStart(): Unit = ()
    override protected def expectedWorkers: Int = 2
    def ready: Boolean = recoveryFinished
  }
  private class Worker(manager: ActorRef, global: ActorRef)
    extends AccountedOptimisticLocalTimeManager(100L, manager, global) {
    def ready: Boolean = recoveryFinished
  }

  "optimistic worker activity" should "ignore an old idle hint after a newer delivery has been accepted" in {
    val global = TestProbe()
    global.ignoreMsg { case _: core.entity.event.control.execution.TimeManagerRegisterEvent => true; case _ => false }
    val lp = TestProbe()
    val worker = TestActorRef(new Worker(global.ref, global.ref))
    global.awaitAssert(worker.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
    global.expectMsg(JoinRuntimeManager(worker))
    global.send(worker, RuntimeMembership("run", Vector(worker)))
    val id = LogicalProcessId("example.Model", 1L)
    global.send(worker, RegisterLocalProcess("run",
      JoinRuntime(Identify(id = id.id, classType = id.model), lp.ref, None, incarnation = "instance")))
    global.expectMsg(LocalProcessRegistered(id))
    lp.expectMsgType[RuntimeJoined]
    global.send(worker, StartLocalRuntime(0L, 100L))
    lp.expectMsg(SetRuntimeHorizon(100L))
    lp.expectMsgType[SetOptimisticExecutionLimits]
    lp.expectMsg(ResumeRuntime(0L))
    global.expectMsg(OptimisticWorkerActivity(0L, false))
    lp.send(worker, OptimisticProcessIdle("run", 0L))
    global.expectMsg(OptimisticWorkerActivity(0L, true))

    val event = ActorInteractionEvent(10L, 1L, id.id, id.model, "", id.model, data = "input", resourceId = "run",
      seq = 1L, order = Some(EventOrder.root(10L, id.model, id.id)),
      sourceEpoch = "run", sourceIncarnation = "instance")
    lp.send(worker, RouteInteraction("run", id, event))
    global.expectMsg(OptimisticWorkerActivity(0L, false))
    val delivery = lp.expectMsgType[RuntimeDelivery]
    lp.send(worker, OptimisticProcessIdle("run", 0L))
    lp.send(worker, DeliveryAccepted("run", delivery.token))
    global.expectNoMessage(200.millis)
    lp.send(worker, OptimisticProcessIdle("run", 0L, delivery.token))
    global.expectMsg(OptimisticWorkerActivity(0L, true))
  }

  "optimistic idle rounds" should "coalesce notices, ignore stale rounds and fall back after an unchanged cut" in {
    val manager = TestProbe()
    val global = TestActorRef(new GTM(manager.ref))
    manager.awaitAssert(global.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
    val workers = Vector.fill(2)(TestProbe())
    workers.foreach(w => w.send(global, JoinRuntimeManager(w.ref)))
    workers.foreach(_.expectMsgType[RuntimeMembership])
    global ! StartSimulationTimeEvent(startTick = 0L)
    workers.foreach(_.expectMsgType[StartLocalRuntime])

    workers.head.send(global, OptimisticWorkerActivity(0L, true))
    workers.head.send(global, OptimisticWorkerActivity(0L, true))
    workers.foreach(_.expectNoMessage(100.millis))
    workers.head.send(global, OptimisticWorkerActivity(0L, false))
    workers.last.send(global, OptimisticWorkerActivity(0L, true))
    workers.foreach(_.expectNoMessage(100.millis))
    workers.head.send(global, OptimisticWorkerActivity(0L, true))

    def finishRound(round: Long): Unit = {
      workers.foreach(_.expectMsg(FreezeRuntime(round)))
      // Hints cannot advance GVT without the frozen, drained snapshots.
      workers.foreach(_.expectNoMessage(100.millis))
      workers.foreach(w => w.send(global, RuntimeFrozen(round)))
      workers.foreach(_.expectMsg(CollectRuntimeSnapshot(round)))
      workers.foreach(w => w.send(global, RuntimeSnapshot(round, Some(10L), 0, 0)))
      workers.foreach(_.expectMsg(CommitRuntime(round, 10L)))
      workers.foreach(w => w.send(global, LocalRuntimeCommitted(round, Set.empty)))
      workers.foreach(_.expectMsg(RetireLocalProcesses(round, false)))
      workers.foreach(w => w.send(global, LocalProcessesRetired(round)))
      workers.foreach(_.expectMsg(ResumeRuntime(round)))
    }

    finishRound(1L)
    workers.foreach(w => w.send(global, OptimisticWorkerActivity(0L, true)))
    workers.foreach(_.expectNoMessage(100.millis))
    workers.foreach(w => w.send(global, OptimisticWorkerActivity(1L, true)))
    finishRound(2L)
    workers.foreach(w => w.send(global, OptimisticWorkerActivity(2L, true)))
    workers.foreach(_.expectNoMessage(200.millis))
    manager.expectNoMessage(100.millis)

    global ! RequestRuntimeRound()
    workers.foreach(_.expectMsg(FreezeRuntime(3L)))
  }
}
