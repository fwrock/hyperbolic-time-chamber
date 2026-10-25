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
import org.interscity.htc.core.entity.event.control.rollback.AntiMessagePayload
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

private case class BirthTransferHeld(target: ActorRef, source: ActorRef, event: OptimisticTransfer)
private case class BirthInitializationHeld(owner: ActorRef, target: LogicalProcessId, origin: RuntimeBirthOrigin)

class DistributedBirthEnrollmentSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("DistributedBirthEnrollmentSpec",
    ConfigFactory.parseString("pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off\nhtc.time-warp.termination-heartbeat-interval-ms=60000")
      .withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }
  private class GTM(manager: ActorRef) extends AccountedOptimisticGlobalTimeManager(100L, manager) {
    override def onStart(): Unit = ()
    override protected def expectedWorkers: Int = 2
    def ready: Boolean = recoveryFinished
  }
  private class Worker(manager: ActorRef, global: ActorRef, gate: ActorRef, factory: ActorRef)
    extends AccountedOptimisticLocalTimeManager(100L, manager, global) {
    def ready: Boolean = recoveryFinished
    override protected def transferTo(target: ActorRef, event: OptimisticTransfer): Unit =
      gate ! BirthTransferHeld(target, self, event)
    override protected def createRuntimeBirthInitializer(target: LogicalProcessId, origin: RuntimeBirthOrigin, data: RuntimeBirthData): ActorRef = {
      gate ! BirthInitializationHeld(self, target, origin)
      factory
    }
  }
  private def run(loseInitializer: Boolean = false, antiFirst: Boolean = false): Unit = {
    val manager = TestProbe(); val gate = TestProbe(); val factory = TestProbe()
    val source = TestProbe(); val other = TestProbe(); val child = TestProbe()
    val global = TestActorRef(new GTM(manager.ref))
    manager.awaitAssert(global.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
    val workers = Vector.fill(2)(TestActorRef(new Worker(manager.ref, global, gate.ref, factory.ref)))
    manager.awaitAssert(workers.forall(_.underlyingActor.ready) shouldBe true, 5.seconds, 10.millis)
    val model = "example.Parent"
    val sourceId = 1L
    val sourceOwner = Math.floorMod(LogicalProcessId(model, sourceId).hashCode(), 2)
    val otherId = (2L to 100L).find(id => Math.floorMod(LogicalProcessId(model, id).hashCode(), 2) != sourceOwner).get
    val target = (1L to 100L).map(LogicalProcessId("example.Child", _)).find(id => Math.floorMod(id.hashCode(), 2) != sourceOwner).get
    def enroll(lp: TestProbe, id: Long): RuntimeJoined = {
      lp.send(global, JoinRuntime(Identify(id = id, classType = model), lp.ref, None, 0L, s"instance-$id"))
      lp.expectMsgType[RuntimeJoined]
    }
    val a = enroll(source, sourceId); val b = enroll(other, otherId)
    global ! StartSimulationTimeEvent(startTick = 0L)
    for (lp <- Vector(source, other)) {
      lp.expectMsg(SetRuntimeHorizon(100L)); lp.expectMsgType[SetOptimisticExecutionLimits]; lp.expectMsg(ResumeRuntime(0L))
    }
    val positive = ActorInteractionEvent(10L, 1L, sourceId, model, "", model,
      data = RuntimeBirthData("{}", Map.empty, "run", Map.empty, Map.empty), resourceId = "run", seq = 1L,
      order = Some(EventOrder(10L, 1L, "birth-slot")), sourceEpoch = a.epoch, sourceIncarnation = s"instance-$sourceId")
    source.send(a.owner, RouteInteraction(a.epoch, target, positive))
    val held = gate.expectMsgType[BirthTransferHeld]
    val heldAnti = if (antiFirst) {
      source.send(a.owner, RouteInteraction(a.epoch, target, positive.copy(isAntiMessage = true,
        order = None, data = AntiMessagePayload)))
      Some(gate.expectMsgType[BirthTransferHeld])
    } else None
    global ! RequestRuntimeRound()
    val round = source.expectMsgType[FreezeRuntime].round
    other.expectMsg(FreezeRuntime(round))
    other.send(b.owner, RuntimeFrozen(round)); source.send(a.owner, RuntimeFrozen(round))
    heldAnti.foreach { anti =>
      anti.target.tell(anti.event, anti.source)
      gate.expectNoMessage(50.millis)
    }
    held.target.tell(held.event, held.source)
    val initialization = gate.expectMsgType[BirthInitializationHeld]
    source.expectNoMessage(100.millis); other.expectNoMessage(100.millis)
    if (loseInitializer) {
      system.stop(factory.ref)
      manager.expectMsgType[RuntimeFailure].reason should include ("birth initializer lost")
      return
    }
    child.send(global, JoinRuntime(Identify(id = target.id, classType = target.model), child.ref, None, 10L,
      "child-instance", Some(initialization.origin)))
    val joined = child.expectMsgType[RuntimeJoined]
    joined.owner shouldBe b.owner
    child.expectMsg(SetRuntimeHorizon(100L)); child.expectMsgType[SetOptimisticExecutionLimits]
    child.expectMsg(FreezeRuntime(round))
    child.send(joined.owner, RuntimeFrozen(round))
    factory.send(initialization.owner, RuntimeBirthInitialized(target, child.ref))
    factory.expectMsg(StopRuntimeBirthInitializer)
    if (antiFirst) {
      val anti = child.expectMsgType[RuntimeDelivery]
      anti.event.asInstanceOf[ActorInteractionEvent].isAntiMessage shouldBe true
      child.send(joined.owner, DeliveryAccepted(a.epoch, anti.token))
    }
    val delivery = child.expectMsgType[RuntimeDelivery]
    delivery.event shouldBe positive
    source.expectNoMessage(100.millis); other.expectNoMessage(100.millis)
    child.send(joined.owner, DeliveryAccepted(a.epoch, delivery.token))
    for (lp <- Vector(source, other, child)) lp.expectMsg(CollectRuntimeSnapshot(round))
    source.send(a.owner, RuntimeSnapshot(round, None, 0, 0))
    other.send(b.owner, RuntimeSnapshot(round, None, 0, 0))
    child.send(joined.owner, RuntimeSnapshot(round, Some(10L), 0, 0))
    for (lp <- Vector(source, other, child)) {
      lp.expectMsgType[SetOptimisticExecutionLimits]
      lp.expectMsg(CommitRuntime(round, 10L))
    }
    manager.expectNoMessage(100.millis)
  }
  "optimistic birth enrollment" should "reopen an already frozen owner and include its initialized, frozen child before commitment" in { run() }
  it should "retain an anti-message that reaches an unknown child before its birth positive" in { run(antiFirst = true) }
  it should "fail initializer loss while birth acceptance remains outstanding" in { run(loseInitializer = true) }
}
