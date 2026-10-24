package org.interscity.htc
package core.actor.manager.warmup

import core.entity.event.control.warmup.*
import core.runtime.accounting.RuntimeFailure
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props}
import org.apache.pekko.testkit.{TestActorRef, TestProbe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

class WarmUpHandshakeSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("WarmUpHandshakeSpec",
    ConfigFactory.parseString("pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off")
      .withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }
  private class Manager(simulation: ActorRef) extends WarmUpManager(simulation, 2) {
    override def onStart(): Unit = ()
    def ready: Boolean = recoveryFinished
  }
  private def manager(simulation: TestProbe): ActorRef = {
    val actor = TestActorRef(new Manager(simulation.ref))
    simulation.awaitAssert(actor.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
    actor
  }

  "warm-up startup" should "send work to a late remote worker after the first worker has already finished" in {
    val simulation = TestProbe(); val a = TestProbe(); val b = TestProbe()
    val actor = manager(simulation)
    simulation.send(actor, StartWarmUpWorkersEvent(List("resource#warmUp")))
    a.send(actor, WarmUpWorkerReadyEvent("node-a"))
    a.expectMsg(StartWarmUpWorkersEvent(List("resource#warmUp")))
    a.send(actor, WarmUpWorkerDoneEvent("node-a"))
    simulation.expectNoMessage(100.millis)
    b.send(actor, WarmUpWorkerReadyEvent("node-b"))
    b.expectMsg(StartWarmUpWorkersEvent(List("resource#warmUp")))
    b.send(actor, WarmUpWorkerDoneEvent("node-b"))
    simulation.expectMsg(WarmUpAllDoneEvent())
    a.send(actor, WarmUpWorkerReadyEvent("node-a"))
    a.send(actor, WarmUpWorkerDoneEvent("node-a"))
    simulation.expectNoMessage(100.millis)
    a.expectNoMessage(100.millis)
  }

  it should "wait for targets and ignore duplicate ready, start and completion acknowledgements" in {
    val simulation = TestProbe(); val a = TestProbe(); val b = TestProbe()
    val actor = manager(simulation)
    a.send(actor, WarmUpWorkerReadyEvent("node-a"))
    b.send(actor, WarmUpWorkerReadyEvent("node-b"))
    a.expectNoMessage(100.millis)
    simulation.send(actor, StartWarmUpWorkersEvent(Nil))
    a.expectMsg(StartWarmUpWorkersEvent(Nil)); b.expectMsg(StartWarmUpWorkersEvent(Nil))
    simulation.send(actor, StartWarmUpWorkersEvent(Nil))
    a.send(actor, WarmUpWorkerReadyEvent("node-a"))
    a.send(actor, WarmUpWorkerDoneEvent("node-a"))
    a.send(actor, WarmUpWorkerDoneEvent("node-a"))
    simulation.expectNoMessage(100.millis)
    a.expectNoMessage(100.millis)
    b.send(actor, WarmUpWorkerDoneEvent("node-b"))
    simulation.expectMsg(WarmUpAllDoneEvent())
  }

  it should "fail explicitly when a registered worker disappears before global warm-up completion" in {
    val simulation = TestProbe(); val a = TestProbe()
    val actor = manager(simulation)
    simulation.send(actor, StartWarmUpWorkersEvent(Nil))
    a.send(actor, WarmUpWorkerReadyEvent("node-a"))
    a.expectMsg(StartWarmUpWorkersEvent(Nil))
    system.stop(a.ref)
    simulation.expectMsgType[RuntimeFailure].reason should include ("worker lost")
  }

  it should "reject two different workers claiming the same node" in {
    val simulation = TestProbe(); val a = TestProbe(); val b = TestProbe()
    val actor = manager(simulation)
    a.send(actor, WarmUpWorkerReadyEvent("node-a"))
    b.send(actor, WarmUpWorkerReadyEvent("node-a"))
    simulation.expectMsgType[RuntimeFailure].reason should include ("duplicate")
  }

  it should "propagate target failure instead of acknowledging successful warm-up" in {
    val manager = TestProbe()
    val worker = system.actorOf(Props(new WarmUpWorker(manager.ref) {
      override protected def nodeAddress: String = "node-test"
    }))
    manager.expectMsg(WarmUpWorkerReadyEvent("node-test"))
    manager.send(worker, StartWarmUpWorkersEvent(List("missing.WarmUpTarget#run")))
    manager.expectMsgType[RuntimeFailure].reason should include ("target failed")
    manager.expectNoMessage(100.millis)
  }
}
