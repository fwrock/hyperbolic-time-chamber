package org.interscity.htc
package core.actor

import core.entity.actor.properties.Properties
import core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import core.entity.event.control.load.InitializeEvent
import core.entity.event.data.InitializeData
import core.entity.state.BaseState
import core.enumeration.{CreationTypeEnum, ReportTypeEnum, TimeManagerTypeEnum}
import core.runtime.{EventOrder, OptimisticExecutionLimits}
import core.runtime.accounting.*
import core.util.JsonUtil
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{TestActorRef, TestProbe}
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.load.InitializeEntityAckEvent
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import scala.concurrent.duration.*

private case class BirthState(startTick: Long = 10L, count: Int = 0, schedule: Boolean = true)
  extends BaseState(startTick = startTick, scheduleOnTimeManager = schedule)

class SimulationBaseActorBirthSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("SimulationBaseActorBirthSpec",
    ConfigFactory.parseString("pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off").withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }
  private class Child(props: Properties) extends SimulationBaseActor[BirthState](props) {
    def ready: Boolean = recoveryFinished
    def born: Boolean = buildMigrationSnapshot().runtimeBorn
    def count: Int = state.count
    override protected def internStateStrings(s: BirthState): BirthState = s
    override def actSpontaneous(event: SpontaneousEvent): Unit = {
      state = state.copy(count = state.count + 1)
      sendMessageTo(99L, "example.Descendant", "effect", actorType = CreationTypeEnum.PoolDistributed)
      report(Map("count" -> state.count), "child-result")
      selfDestruct()
    }
    override def actInteractWith(event: ActorInteractionEvent): Unit = state = state.copy(count = state.count + 10)
  }
  private class Fixture {
    val global = TestProbe()
    val owner = TestProbe()
    val creator = TestProbe()
    val sink = TestProbe()
    val origin = RuntimeBirthOrigin(LogicalProcessId("example.Parent", 1L), "birth", 10L, 1L)
    val managers = mutable.Map(TimeManagerTypeEnum.TIME_WARP -> owner.ref)
    val reporters = mutable.Map(ReportTypeEnum.parquet -> sink.ref)
    val child = TestActorRef(new Child(Properties(entityId = "2", actorType = CreationTypeEnum.PoolDistributed,
      runtimeCoordinator = Some(global.ref), timeManagers = managers, reporters = reporters)))
    creator.awaitAssert(child.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
    child ! InitializeEvent("2", creator.ref, InitializeData(BirthState(), "birth-test", creator.ref, reporters,
      timeManagers = managers, runtimeCoordinator = Some(global.ref), runtimeBirth = Some(origin)))
    val join = global.expectMsgType[JoinRuntime]
    join.initialWake shouldBe None
    join.birth shouldBe Some(origin)
    owner.send(child, RuntimeJoined("run", global.ref, owner.ref, true))
    creator.expectMsgType[InitializeEntityAckEvent]
    owner.send(child, SetRuntimeHorizon(100L))
    owner.send(child, SetOptimisticExecutionLimits(0L, 100L, OptimisticExecutionLimits(100L)))
    owner.send(child, ResumeRuntime(0L))
    def birth(seq: Long = 1L, count: Int = 0, scheduled: Boolean = true): ActorInteractionEvent = ActorInteractionEvent(10L, 0L, 1L,
      "example.Parent", "", "example.Parent", data = RuntimeBirthData(JsonUtil.toJson(BirthState(
        startTick = if (scheduled) 10L else 0L, count = count, schedule = scheduled)),
        Map.empty, "birth-test", managers.toMap, reporters.toMap), resourceId = "birth-test", seq = seq,
      order = Some(EventOrder(10L, 1L, "birth")), sourceEpoch = "run", sourceIncarnation = "parent")
    def deliver(event: AnyRef, token: Long): Unit = {
      owner.send(child, RuntimeDelivery("run", token, event))
      owner.expectMsg(DeliveryAccepted("run", token))
    }
    def outputs(): Vector[Any] = owner.receiveWhile(1.second, 30.millis) { case event => event }.toVector
    def activate(): ScheduleEvent = {
      deliver(birth(), 1L)
      val output = outputs()
      output.collect { case r: RouteInteraction => r.event.data }.count(_.isInstanceOf[RuntimeBirthAccepted]) shouldBe 1
      output.collectFirst { case r: RouteControl if r.event.isInstanceOf[ScheduleEvent] => r.event.asInstanceOf[ScheduleEvent] }.get
    }
    def tick(schedule: ScheduleEvent): Unit = {
      val o = schedule.order.get
      deliver(SpontaneousEvent(10L, owner.ref, generation = 1L, scheduleRevision = schedule.revision,
        order = Some(EventOrder(o.tick, o.microstep, o.key))), 2L)
    }
    def anti(seq: Long = 1L): ActorInteractionEvent = birth(seq).copy(data = core.actor.rollback.AntiMessagePayload,
      isAntiMessage = true, order = None)
    def commit(frontier: Long, round: Long = 1L): Vector[Any] = {
      owner.send(child, FreezeRuntime(round)); owner.expectMsg(RuntimeFrozen(round))
      owner.send(child, CommitRuntime(round, frontier))
      outputs()
    }
  }

  "an accounted child birth" should "initialize inertly and execute only after its ordered birth input" in {
    val f = new Fixture
    f.child.underlyingActor.born shouldBe false
    f.owner.expectNoMessage(100.millis)
    val wake = f.activate()
    f.child.underlyingActor.born shouldBe true
    f.tick(wake)
    f.child.underlyingActor.count shouldBe 1
    f.outputs().collect { case r: RouteInteraction if !r.event.isAntiMessage => r }.size shouldBe 1
    f.commit(11L).collect { case r: PublishCommittedReport => r.event.label } shouldBe Vector("child-result")
  }
  it should "activate a passive child at creation time without scheduling its inert state's old start tick" in {
    val f = new Fixture
    f.deliver(f.birth(scheduled = false), 1L)
    f.child.underlyingActor.born shouldBe true
    val output = f.outputs()
    output.collect { case r: RouteControl => r } shouldBe empty
    output.collect { case r: RouteInteraction => r.event.data }.count(_.isInstanceOf[RuntimeBirthAccepted]) shouldBe 1
  }
  for (tick <- Seq(9L, 10L)) {
    it should s"reject input ordered before birth at tick $tick instead of blocking the dormant queue" in {
      val f = new Fixture
      val input = ActorInteractionEvent(tick, 0L, 99L, "example.Peer", "", "example.Peer", data = "too early",
        resourceId = "birth-test", seq = 1L, order = Some(EventOrder.root(tick, "example.Peer", 99L)),
        sourceEpoch = "run", sourceIncarnation = "peer")
      f.owner.send(f.child, RuntimeDelivery("run", 1L, input))
      f.global.expectMsgType[RuntimeFailure].reason should include ("input precedes")
      f.owner.expectNoMessage(100.millis)
    }
  }
  it should "undo the child and retract its descendant effects and reports when birth is cancelled" in {
    val f = new Fixture
    f.tick(f.activate())
    val effects = f.outputs().collect { case r: RouteInteraction if !r.event.isAntiMessage => r.event.messageId }
    effects.size shouldBe 1
    f.deliver(f.anti(), 3L)
    f.child.underlyingActor.born shouldBe false
    f.child.underlyingActor.count shouldBe 0
    val cancelled = f.outputs().collect { case r: RouteInteraction if r.event.isAntiMessage => r.event.messageId }
    effects.foreach(id => cancelled should contain (id))
    val committed = f.commit(11L)
    committed.collect { case r: PublishCommittedReport => r } shouldBe empty
    committed.collect { case r: RuntimeCommitted => r.mayRetire } shouldBe Vector(true)
  }
  it should "annihilate a cancellation arriving before the birth positive without executing the child" in {
    val f = new Fixture
    f.deliver(f.anti(), 1L)
    f.outputs()
    f.deliver(f.birth(), 2L)
    f.child.underlyingActor.born shouldBe false
    f.outputs() shouldBe empty
    f.commit(10L).collect { case r: RuntimeCommitted => r.mayRetire } shouldBe Vector(false)
    f.commit(11L, 2L).collect { case r: RuntimeCommitted => r.mayRetire } shouldBe Vector(true)
  }
  it should "hold a replacement positive until the old birth is cancelled and activate its new initial state" in {
    val f = new Fixture
    f.activate()
    f.deliver(f.birth(2L, 20), 2L)
    f.outputs() shouldBe empty
    f.deliver(f.anti(), 3L)
    val output = f.outputs()
    f.child.underlyingActor.born shouldBe true
    f.child.underlyingActor.count shouldBe 20
    output.collect { case r: RouteInteraction if !r.event.isAntiMessage => r.event.data }
      .collect { case ack: RuntimeBirthAccepted => ack.requestSeq } shouldBe Vector(2L)
  }
  it should "discard a duplicate positive without waiting for a nonexistent cancellation" in {
    val f = new Fixture
    f.activate()
    f.deliver(f.birth(), 2L)
    f.outputs() shouldBe empty
    f.child.underlyingActor.born shouldBe true
  }
  it should "rebuild surviving activation during coast-forward without resending its acknowledgement" in {
    val f = new Fixture
    f.tick(f.activate())
    f.outputs()
    def input(tick: Long, seq: Long): ActorInteractionEvent = ActorInteractionEvent(tick, 0L, 99L,
      "example.Peer", "", "example.Peer", data = "update", resourceId = "birth-test", seq = seq,
      order = Some(EventOrder.root(tick, "example.Peer", 99L)), sourceEpoch = "run", sourceIncarnation = "peer")
    f.deliver(input(12L, 1L), 3L)
    f.outputs()
    f.deliver(input(11L, 2L), 4L)
    f.child.underlyingActor.born shouldBe true
    f.child.underlyingActor.count shouldBe 21
    f.outputs().collect { case r: RouteInteraction if r.event.data.isInstanceOf[RuntimeBirthAccepted] => r } shouldBe empty
  }
  it should "retain a dormant child through its latest accepted tick after later input is rolled back and cancelled" in {
    val f = new Fixture
    f.tick(f.activate())
    f.outputs()
    val reply = ActorInteractionEvent(20L, 0L, 99L, "example.Peer", "", "example.Peer", data = "reply",
      resourceId = "birth-test", seq = 1L, order = Some(EventOrder.root(20L, "example.Peer", 99L)),
      sourceEpoch = "run", sourceIncarnation = "peer")
    f.deliver(reply, 3L)
    f.outputs()
    f.deliver(f.anti(), 4L)
    f.outputs()
    f.deliver(reply.copy(data = core.actor.rollback.AntiMessagePayload, isAntiMessage = true, order = None), 5L)
    f.outputs()
    f.child.underlyingActor.born shouldBe false
    f.commit(20L).collect { case r: RuntimeCommitted => r.mayRetire } shouldBe Vector(false)
    f.commit(21L, 2L).collect { case r: RuntimeCommitted => r.mayRetire } shouldBe Vector(true)
  }
}
