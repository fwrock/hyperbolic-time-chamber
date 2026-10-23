package org.interscity.htc
package core.actor

import core.entity.actor.properties.Properties
import core.entity.event.ActorInteractionEvent
import core.entity.event.control.load.InitializeEvent
import core.entity.event.control.report.ReportEvent
import core.entity.event.data.InitializeData
import core.entity.state.BaseState
import core.enumeration.{ CreationTypeEnum, ReportTypeEnum, TimeManagerTypeEnum }
import core.runtime.EventOrder
import core.runtime.accounting.*
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{ TestActorRef, TestProbe }
import org.htc.protobuf.core.entity.event.control.load.InitializeEntityAckEvent
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import scala.concurrent.duration.*

private case class RetirementState(startTick: Long = 0L, count: Int = 0)
  extends BaseState(startTick = startTick, scheduleOnTimeManager = false)

class SimulationBaseActorCommittedRetirementSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("SimulationBaseActorCommittedRetirementSpec",
    ConfigFactory.parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off").withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }
  private class LP(props: Properties, rejectCapability: Boolean = false, classifyReplyAsSuperseded: Boolean = false,
    awaitSpontaneous: Boolean = false) extends SimulationBaseActor[RetirementState](props) {
    def ready: Boolean = recoveryFinished
    def count: Int = state.count
    def terminal: Boolean = buildMigrationSnapshot().terminalIntent.nonEmpty
    override protected def validateRuntimeCapabilities(): Unit =
      require(!rejectCapability, "unsupported test effect")
    override protected def isKnownSupersededReply(event: ActorInteractionEvent): Boolean = classifyReplyAsSuperseded
    override def actSpontaneous(event: core.entity.event.SpontaneousEvent): Unit =
      if (awaitSpontaneous) {
        sendMessageTo(99L, "example.Peer", "request", actorType = CreationTypeEnum.PoolDistributed)
        registerAwaitedReply("peer", "request")
      }
    override def actInteractWith(event: ActorInteractionEvent): Unit = {
      if (awaitSpontaneous) consumeAwaitedReply("peer")
      state = state.copy(count = state.count + 1)
      report(event.tick, "completed")
      selfDestruct()
    }
  }

  private class SchedulingLP(props: Properties) extends SimulationBaseActor[RetirementState](props) {
    def ready: Boolean = recoveryFinished
    override def actSpontaneous(event: core.entity.event.SpontaneousEvent): Unit =
      onFinishSpontaneous(Some(event.tick + 10L))
    override def actInteractWith(event: ActorInteractionEvent): Unit = scheduleEvent(20L)
  }

  for (futureInputFirst <- Seq(false, true)) {
  "a queued dispatch invalidated by rollback" should s"release its slot before the restored wake (future input ahead: $futureInputFirst)" in {
    val coordinator = TestProbe()
    val manager = TestProbe()
    val creator = TestProbe()
    val lp = TestActorRef(new SchedulingLP(Properties(entityId = "104", actorType = CreationTypeEnum.PoolDistributed,
      runtimeCoordinator = Some(coordinator.ref), reporters = mutable.Map.empty,
      timeManagers = mutable.Map(TimeManagerTypeEnum.TIME_WARP -> manager.ref))))
    creator.awaitAssert(lp.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
    lp ! InitializeEvent("104", creator.ref, InitializeData(RetirementState(), "test", creator.ref,
      mutable.Map.empty, timeManagers = mutable.Map(TimeManagerTypeEnum.TIME_WARP -> manager.ref),
      runtimeCoordinator = Some(coordinator.ref)))
    coordinator.expectMsgType[JoinRuntime]
    coordinator.send(lp, RuntimeJoined("run", coordinator.ref, manager.ref))
    creator.expectMsgType[InitializeEntityAckEvent]
    val initial = EventOrder.root(0L, "test.LP", 104L)
    coordinator.send(lp, RuntimeDelivery("run", 1L,
      core.entity.event.SpontaneousEvent(0L, manager.ref, generation = 1L, order = Some(initial))))
    coordinator.expectMsg(DeliveryAccepted("run", 1L))
    val originalWake = coordinator.expectMsgType[RouteControl].event.asInstanceOf[core.entity.event.FinishEvent].nextOrder
    originalWake.get.tick shouldBe 10L
    val input = ActorInteractionEvent(5L, 0L, 99L, "example.Peer", "", "example.Peer",
      data = "reschedule", resourceId = "test", seq = 1L, order = Some(EventOrder(5L, 0L, "input")))
    coordinator.send(lp, RuntimeDelivery("run", 2L, input))
    coordinator.expectMsg(DeliveryAccepted("run", 2L))
    val later = coordinator.expectMsgType[RouteControl].event.asInstanceOf[org.htc.protobuf.core.entity.event.communication.ScheduleEvent]
    coordinator.send(lp, FreezeRuntime(1L))
    coordinator.expectMsg(RuntimeFrozen(1L))
    if (futureInputFirst) {
      coordinator.send(lp, RuntimeDelivery("run", 5L, input.copy(tick = 15L, seq = 2L,
        order = Some(EventOrder(15L, 0L, "future")))))
      coordinator.expectMsg(DeliveryAccepted("run", 5L))
    }
    val laterOrder = later.order.map(o => EventOrder(o.tick, o.microstep, o.key))
    coordinator.send(lp, RuntimeDelivery("run", 3L, core.entity.event.SpontaneousEvent(20L, manager.ref,
      generation = 2L, scheduleRevision = later.revision, order = laterOrder)))
    coordinator.expectMsg(DeliveryAccepted("run", 3L))
    coordinator.send(lp, RuntimeDelivery("run", 4L, input.copy(isAntiMessage = true)))
    coordinator.expectMsg(DeliveryAccepted("run", 4L))
    coordinator.send(lp, ResumeRuntime(1L))
    val restored = coordinator.expectMsgType[RouteControl].event.asInstanceOf[core.entity.event.control.execution.ReplaceScheduleEvent]
    restored.next shouldBe originalWake
    restored.completedGeneration shouldBe 1L
    val discarded = coordinator.expectMsgType[RouteControl].event.asInstanceOf[core.entity.event.FinishEvent]
    discarded.discardedDispatch shouldBe true
    discarded.generation shouldBe 2L
    coordinator.send(lp, CollectRuntimeSnapshot(2L))
    coordinator.expectMsg(RuntimeSnapshot(2L, Some(10L), 0, 0))
  }
  }

  for (waitForReply <- Seq(true, false)) {
    "accounted spontaneous completion" should
      (if (waitForReply) "retain a registered wait until its reply resolves the dispatch"
       else "fail a missing completion without inventing another wake") in {
      val coordinator = TestProbe()
      val manager = TestProbe()
      val creator = TestProbe()
      val lp = TestActorRef(new LP(Properties(entityId = "103", actorType = CreationTypeEnum.PoolDistributed,
        runtimeCoordinator = Some(coordinator.ref), reporters = mutable.Map.empty,
        timeManagers = mutable.Map(TimeManagerTypeEnum.TIME_WARP -> manager.ref)), awaitSpontaneous = waitForReply))
      creator.awaitAssert(lp.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
      lp ! InitializeEvent("103", creator.ref, InitializeData(RetirementState(), "test", creator.ref,
        mutable.Map.empty, timeManagers = mutable.Map(TimeManagerTypeEnum.TIME_WARP -> manager.ref),
        runtimeCoordinator = Some(coordinator.ref)))
      coordinator.expectMsgType[JoinRuntime]
      coordinator.send(lp, RuntimeJoined("run", coordinator.ref, manager.ref))
      creator.expectMsgType[InitializeEntityAckEvent]
      val order = EventOrder.root(10L, "test.LP", 103L)
      coordinator.send(lp, RuntimeDelivery("run", 1L,
        core.entity.event.SpontaneousEvent(10L, manager.ref, generation = 1L, order = Some(order))))
      coordinator.expectMsg(DeliveryAccepted("run", 1L))
      if (waitForReply) {
        coordinator.expectMsgType[RouteInteraction]
        coordinator.send(lp, CollectRuntimeSnapshot(1L))
        coordinator.expectMsg(RuntimeSnapshot(1L, Some(10L), 1, 0))
        coordinator.send(lp, RuntimeDelivery("run", 2L, ActorInteractionEvent(10L, 0L, 99L,
          "example.Peer", "", "example.Peer", data = "response", resourceId = "test", seq = 1L,
          order = Some(order.child(10L, 1L)))))
        coordinator.expectMsg(DeliveryAccepted("run", 2L))
        val completion = coordinator.expectMsgType[RouteControl].event.asInstanceOf[core.entity.event.FinishEvent]
        completion.generation shouldBe 1L
        completion.awaitingReply shouldBe false
      } else coordinator.expectMsgType[RuntimeFailure].reason should include ("missing spontaneous completion")
    }
  }

  "an accounted straggler" should "retain a reply classified against the speculative suffix" in {
    val coordinator = TestProbe()
    val manager = TestProbe()
    val creator = TestProbe()
    val lp = TestActorRef(new LP(Properties(entityId = "102", actorType = CreationTypeEnum.PoolDistributed,
      runtimeCoordinator = Some(coordinator.ref), reporters = mutable.Map.empty,
      timeManagers = mutable.Map(TimeManagerTypeEnum.TIME_WARP -> manager.ref)), classifyReplyAsSuperseded = true))
    creator.awaitAssert(lp.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
    lp ! InitializeEvent("102", creator.ref, InitializeData(RetirementState(), "test", creator.ref,
      mutable.Map.empty, timeManagers = mutable.Map(TimeManagerTypeEnum.TIME_WARP -> manager.ref),
      runtimeCoordinator = Some(coordinator.ref)))
    coordinator.expectMsgType[JoinRuntime]
    coordinator.send(lp, RuntimeJoined("run", coordinator.ref, manager.ref))
    creator.expectMsgType[InitializeEntityAckEvent]
    def input(tick: Long) = ActorInteractionEvent(tick, 0L, 99L, "example.Source", "", "example.Source",
      data = "reply", resourceId = "test", seq = tick, order = Some(EventOrder(tick, 0L, s"reply-$tick")))
    coordinator.send(lp, RuntimeDelivery("run", 1L, input(20L)))
    coordinator.awaitAssert(lp.underlyingActor.count shouldBe 1, 3.seconds, 10.millis)
    coordinator.send(lp, RuntimeDelivery("run", 2L, input(10L)))
    coordinator.awaitAssert(lp.underlyingActor.count shouldBe 2, 3.seconds, 10.millis)
  }

  "model capability validation" should "fail before registering or acknowledging an unsupported LP" in {
    val coordinator = TestProbe()
    val creator = TestProbe()
    val lp = TestActorRef(new LP(Properties(entityId = "101", runtimeCoordinator = Some(coordinator.ref)),
      rejectCapability = true))
    creator.awaitAssert(lp.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
    lp ! InitializeEvent("101", creator.ref, InitializeData(RetirementState(), "test", creator.ref,
      mutable.Map.empty, runtimeCoordinator = Some(coordinator.ref)))
    coordinator.expectMsgType[RuntimeFailure].reason should include ("unsupported test effect")
    creator.expectNoMessage(100.millis)
    coordinator.expectNoMessage(100.millis)
  }

  "a speculative terminal LP" should "survive cancellation at GVT and retire only after corrected output commits" in {
    val coordinator = TestProbe()
    val manager = TestProbe()
    val sink = TestProbe()
    val creator = TestProbe()
    val lp = TestActorRef(new LP(Properties(entityId = "100", actorType = CreationTypeEnum.PoolDistributed,
      runtimeCoordinator = Some(coordinator.ref), reporters = mutable.Map(ReportTypeEnum.parquet -> sink.ref),
      timeManagers = mutable.Map(TimeManagerTypeEnum.TIME_WARP -> manager.ref))))
    creator.awaitAssert(lp.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
    lp ! InitializeEvent("100", creator.ref, InitializeData(RetirementState(), "test", creator.ref,
      mutable.Map(ReportTypeEnum.parquet -> sink.ref), timeManagers = mutable.Map(TimeManagerTypeEnum.TIME_WARP -> manager.ref),
      runtimeCoordinator = Some(coordinator.ref)))
    coordinator.expectMsgType[JoinRuntime]
    creator.expectNoMessage(100.millis)
    coordinator.send(lp, RuntimeJoined("run", coordinator.ref, manager.ref))
    creator.expectMsgType[InitializeEntityAckEvent]
    val original = ActorInteractionEvent(10L, 0L, 99L, "example.Source", "", "example.Source",
      data = "complete", resourceId = "test", seq = 1L, order = Some(EventOrder(10L, 0L, "completion")))
    coordinator.send(lp, RuntimeDelivery("run", 1L, original))
    lp.underlyingActor.count shouldBe 1
    coordinator.awaitAssert(lp.underlyingActor.terminal shouldBe true, 3.seconds, 10.millis)
    sink.expectNoMessage(100.millis)
    coordinator.receiveWhile(idle = 50.millis) { case _ => () }
    coordinator.send(lp, FreezeRuntime(1L))
    coordinator.expectMsg(RuntimeFrozen(1L))
    coordinator.send(lp, CommitRuntime(1L, 10L))
    coordinator.expectMsg(RuntimeCommitted(1L, false))
    sink.expectNoMessage(100.millis)
    coordinator.send(lp, RuntimeDelivery("run", 2L, original.copy(isAntiMessage = true)))
    coordinator.expectMsg(DeliveryAccepted("run", 2L))
    lp.underlyingActor.count shouldBe 1
    coordinator.send(lp, ResumeRuntime(1L))
    coordinator.awaitAssert({
      lp.underlyingActor.count shouldBe 0
      lp.underlyingActor.terminal shouldBe false
    }, 3.seconds, 10.millis)
    val restoredSchedule = coordinator.expectMsgType[RouteControl]
    restoredSchedule.event.asInstanceOf[core.entity.event.control.execution.ReplaceScheduleEvent].next shouldBe None
    restoredSchedule.tick shouldBe 10L
    coordinator.send(lp, RuntimeDelivery("run", 3L, original.copy(tick = 12L, seq = 2L,
      order = Some(EventOrder(12L, 0L, "replacement")))))
    lp.underlyingActor.count shouldBe 1
    coordinator.receiveWhile(idle = 50.millis) { case _ => () }
    coordinator.send(lp, FreezeRuntime(2L))
    coordinator.expectMsg(RuntimeFrozen(2L))
    coordinator.send(lp, CommitRuntime(2L, 13L))
    val published = coordinator.expectMsgType[PublishCommittedReport]
    published.event.tick shouldBe 12L
    coordinator.expectMsg(RuntimeCommitted(2L, true))
    val watcher = TestProbe()
    watcher.watch(lp)
    coordinator.send(lp, RetireRuntimeProcess(2L))
    coordinator.expectMsg(RuntimeProcessRetired(2L))
    watcher.expectTerminated(lp)
    sink.expectNoMessage(100.millis)
  }
}
