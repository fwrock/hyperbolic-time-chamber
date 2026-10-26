package org.interscity.htc
package model.test

import core.actor.SimulationBaseActor
import core.actor.manager.loadbalance.LoadBalanceTestIsolation
import core.actor.manager.loadbalance.allocation.SpatialShardIdRegistry
import core.actor.manager.loadbalance.migration.MigrationSnapshot
import core.actor.manager.time.{ ChannelAwareLocalTimeManager, ChannelClosedGlobalTimeManager }
import core.entity.actor.properties.Properties
import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import core.entity.event.control.load.InitializeEvent
import core.entity.event.control.loadbalance.*
import core.entity.event.control.migration.*
import core.entity.event.data.InitializeData
import core.entity.state.BaseState
import core.enumeration.{ CreationTypeEnum, ReportTypeEnum, TimeManagerTypeEnum }
import core.runtime.accounting.*
import core.runtime.conservative.*
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ ActorRef, ActorSystem }
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.testkit.{ TestActorRef, TestKit, TestProbe }
import org.htc.protobuf.core.entity.event.control.execution.{ StartSimulationTimeEvent, StopSimulationEvent }
import org.htc.protobuf.core.entity.event.control.load.InitializeEntityAckEvent
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import scala.concurrent.duration.*

private case class MigrationCounterState(startTick: Long = 0L, value: Int = 0)
    extends BaseState(startTick = startTick, scheduleOnTimeManager = true)
private case object CaptureConservativeCheckpoint
private case object StopFrozenSource

class ConservativeMigrationRuntimeSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("ConservativeMigrationRuntimeSpec",
    ConfigFactory.parseString("""
      pekko.actor.provider=cluster
      pekko.actor.fail-mixed-versions=off
      pekko.remote.artery.canonical.hostname="127.0.0.1"
      pekko.remote.artery.canonical.port=0
      pekko.cluster.seed-nodes=[]
      htc.report-manager.default-strategy=json
    """).withFallback(ConfigFactory.load()))
  override def beforeAll(): Unit = LoadBalanceTestIsolation.acquire()
  override def afterAll(): Unit = try {
    TestKit.shutdownActorSystem(system, 15.seconds)
    SpatialShardIdRegistry.clear()
  } finally LoadBalanceTestIsolation.release()

  private class GTM(manager: ActorRef, reports: ActorRef, count: Int = 1) extends ChannelClosedGlobalTimeManager(100L, false, manager) {
    override def onStart(): Unit = ()
    override protected def expectedWorkers: Int = count
    override protected lazy val reportManagerRef: ActorRef = reports
    def ready: Boolean = recoveryFinished
  }
  private class Worker(gtm: ActorRef, manager: ActorRef) extends ChannelAwareLocalTimeManager(100L, manager, gtm) {
    def ready: Boolean = recoveryFinished
  }
  private class LP(props: Properties, observer: ActorRef) extends SimulationBaseActor[MigrationCounterState](props) {
    def ready: Boolean = recoveryFinished
    override protected def internStateStrings(s: MigrationCounterState): MigrationCounterState = s
    override protected def onMigrationRestore(): Unit = ()
    override def handleEvent: Receive = {
      case CaptureConservativeCheckpoint => sender() ! buildMigrationSnapshot()
      case StopFrozenSource => stopAfterMigrationSnapshot()
      case event => super.handleEvent(event)
    }
    override def actSpontaneous(event: SpontaneousEvent): Unit = {
      if (currentTick == 0L) {
        registerAwaitedReply("proof", "proof")
        sendMessageTo(getEntityId.toLong, getClass.getName, "reply", actorType = CreationTypeEnum.PoolDistributed)
        report(Map("value" -> state.value), "before_migration")
        observer ! ("waiting", state.value)
      } else {
        observer ! ("finished", currentTick, state.value)
        onFinishSpontaneous(None)
      }
    }
    override def actInteractWith(event: ActorInteractionEvent): Unit = {
      require(awaitedReplySendSeq("proof").nonEmpty, "migration lost awaited reply identity")
      consumeAwaitedReply("proof")
      state = state.copy(value = state.value + 1)
      observer ! ("reply", currentTick, state.value)
      onFinishSpontaneous(Some(10L))
    }
  }

  "production conservative migration" should "preserve an accepted queued reply and the unfinished dispatch across physical actor replacement" in {
    val manager = TestProbe(); val reports = TestProbe(); val sink = TestProbe(); val observer = TestProbe()
    val creator = TestProbe(); val control = TestProbe(); val store = TestProbe(); val watcher = TestProbe()
    val gtm = TestActorRef(new GTM(manager.ref, reports.ref), "migration-gtm")
    control.awaitAssert(gtm.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
    val worker = TestActorRef(new Worker(gtm, manager.ref), "migration-worker")
    control.awaitAssert(worker.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
    val id = "801"
    val props = Properties(entityId = id, resourceId = "migration", actorType = CreationTypeEnum.PoolDistributed,
      runtimeCoordinator = Some(gtm), reporters = mutable.Map(ReportTypeEnum.parquet -> sink.ref),
      timeManagers = mutable.Map(TimeManagerTypeEnum.DISCRETE_EVENT -> worker))
    val source = TestActorRef(new LP(props, observer.ref), "original-801")
    control.awaitAssert(source.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
    SpatialShardIdRegistry.putShardId(id, "live-shard")
    SpatialShardIdRegistry.putEntityClassName(id, classOf[LP].getName)
    source ! InitializeEvent(id, creator.ref, InitializeData(MigrationCounterState(), "migration", creator.ref,
      mutable.Map(ReportTypeEnum.parquet -> sink.ref), timeManagers = mutable.Map(TimeManagerTypeEnum.DISCRETE_EVENT -> worker),
      runtimeCoordinator = Some(gtm)))
    creator.expectMsgType[InitializeEntityAckEvent]
    gtm ! StartSimulationTimeEvent(startTick = 0L)
    observer.expectMsg(("waiting", 0))
    val report = sink.expectMsgType[CommittedReportDelivery]
    control.send(gtm, RequestMigrationPauseEvent(Set("live-shard"), control.ref,
      Map("live-shard" -> Cluster(system).selfAddress.toString), "freeze-one"))
    sink.send(report.ackTo, DeliveryAccepted(report.epoch, report.token))
    val safe = control.expectMsgType[MigrationSafeEvent](5.seconds)
    safe.requestId shouldBe "freeze-one"
    safe.liveMembers.get.map(_.id) shouldBe Vector(LogicalProcessId(classOf[LP].getName, 801L))
    observer.expectNoMessage(100.millis)
    control.send(source, CaptureConservativeCheckpoint)
    val checkpoint = control.expectMsgType[MigrationSnapshot]
    checkpoint.awaitedReplies.keySet shouldBe Set("proof")
    checkpoint.conservativeRuntime.get.inputs.size shouldBe 1
    checkpoint.conservativeRuntime.get.generation shouldBe 1L
    watcher.watch(source)
    control.send(source, StopFrozenSource)
    watcher.expectTerminated(source)
    val target = TestActorRef(new LP(props.copy(runtimeCoordinator = None), observer.ref), id)
    control.awaitAssert(target.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
    control.send(target, MigrationContextEvent(mutable.Map(TimeManagerTypeEnum.DISCRETE_EVENT -> worker),
      mutable.Map(ReportTypeEnum.parquet -> sink.ref), checkpoint, "batch", control.ref, store.ref, "offer", 1L))
    store.expectMsg(MigrationRestoredAckEvent(id, "batch", "offer", 1L))
    observer.expectNoMessage(100.millis)
    control.send(gtm, MigrationCompleteNotifyEvent("stale"))
    observer.expectNoMessage(100.millis)
    control.send(gtm, MigrationCompleteNotifyEvent("freeze-one"))
    observer.expectMsg(("reply", 0L, 1))
    observer.expectMsg(("finished", 10L, 1))
    val drain = reports.expectMsgType[DrainRuntimeReports](5.seconds)
    reports.send(gtm, RuntimeReportsFlushed(drain.round))
    manager.expectMsg(StopSimulationEvent())
  }

  it should "require every freeze and resume acknowledgement across queued waves while preserving a manual pause" in {
    for (deferRequest <- Seq(false, true)) {
      val driver = TestProbe(); val simulation = TestProbe(); val left = TestProbe(); val right = TestProbe()
      val gtm = TestActorRef(new GTM(simulation.ref, TestProbe().ref, 2), s"migration-barriers-$deferRequest")
      driver.awaitAssert(gtm.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
      left.send(gtm, JoinRuntimeManager(left.ref))
      right.send(gtm, JoinRuntimeManager(right.ref))
      val epoch = left.expectMsgType[ConservativeMembership].epoch
      right.expectMsgType[ConservativeMembership]
      gtm ! StartSimulationTimeEvent(startTick = 0L)
      val initial = left.expectMsgType[CollectConservativeReady]
      right.expectMsg(initial)
      driver.send(gtm, RequestMigrationPauseEvent(Set("one"), driver.ref, Map("one" -> "target"), "first"))
      left.send(gtm, ConservativeReady(initial.round, None, 0))
      right.send(gtm, ConservativeReady(initial.round, None, 0))
      val freeze = left.expectMsgType[FreezeConservativeMigration]
      right.expectMsg(freeze)
      left.send(gtm, ConservativeMigrationFrozen(epoch, "first", Vector.empty))
      right.send(gtm, ConservativeMigrationFrozen(epoch, "stale", Vector.empty))
      driver.expectNoMessage(100.millis)
      right.send(gtm, ConservativeMigrationFrozen(epoch, "first", Vector.empty))
      driver.expectMsgType[MigrationSafeEvent].requestId shouldBe "first"
      if (deferRequest) driver.send(gtm, MigrationCompleteNotifyEvent("first"))
      else driver.send(gtm, RequestMigrationPauseEvent(Set("two"), driver.ref, Map("two" -> "target"), "second"))
      left.expectMsg(ResumeConservativeMigration(epoch, "first"))
      right.expectMsg(ResumeConservativeMigration(epoch, "first"))
      left.send(gtm, ConservativeMigrationResumed(epoch, "first"))
      right.expectNoMessage(100.millis)
      if (deferRequest) driver.send(gtm, RequestMigrationPauseEvent(Set("two"), driver.ref, Map("two" -> "target"), "second"))
      right.send(gtm, ConservativeMigrationResumed(epoch, "first"))
      left.expectMsgType[FreezeConservativeMigration].requestId shouldBe "second"
      right.expectMsgType[FreezeConservativeMigration].requestId shouldBe "second"
      left.send(gtm, ConservativeMigrationFrozen(epoch, "second", Vector.empty))
      right.send(gtm, ConservativeMigrationFrozen(epoch, "second", Vector.empty))
      driver.expectMsgType[MigrationSafeEvent].liveMembers shouldBe Some(Vector.empty)
      driver.send(gtm, org.htc.protobuf.core.entity.event.control.execution.PauseSimulationEvent())
      driver.send(gtm, MigrationCompleteNotifyEvent("first"))
      left.expectNoMessage(100.millis)
      driver.send(gtm, MigrationCompleteNotifyEvent("second"))
      left.expectMsg(ResumeConservativeMigration(epoch, "second"))
      right.expectMsg(ResumeConservativeMigration(epoch, "second"))
      left.send(gtm, ConservativeMigrationResumed(epoch, "second"))
      right.send(gtm, ConservativeMigrationResumed(epoch, "second"))
      left.expectNoMessage(100.millis)
      driver.send(gtm, org.htc.protobuf.core.entity.event.control.execution.ResumeSimulationEvent())
      left.expectMsgType[CollectConservativeReady]
      right.expectMsgType[CollectConservativeReady]
      system.stop(gtm)
    }
  }

  it should "defer a correlated startup migration until the first progressive input window is accepted" in {
    import core.entity.event.control.load.{ RegisterProgressiveLoadManagerEvent, TickWindowReady, TickWindowRequest }
    val simulation = TestProbe(); val driver = TestProbe(); val worker = TestProbe(); val producer = TestProbe()
    val gtm = TestActorRef(new GTM(simulation.ref, TestProbe().ref), "migration-initial-input")
    driver.awaitAssert(gtm.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
    worker.send(gtm, JoinRuntimeManager(worker.ref))
    val epoch = worker.expectMsgType[ConservativeMembership].epoch
    driver.send(gtm, RegisterProgressiveLoadManagerEvent(producer.ref, 100L))
    driver.send(gtm, StartSimulationTimeEvent(startTick = 0L))
    producer.expectMsgType[TickWindowRequest]
    driver.send(gtm, RequestMigrationPauseEvent(Set("cell"), driver.ref, Map("cell" -> "target"), "initial-cut"))
    worker.expectNoMessage(100.millis)
    simulation.expectNoMessage(100.millis)
    producer.send(gtm, TickWindowReady(100L, 1L))
    val ready = worker.expectMsgType[CollectConservativeReady]
    worker.send(gtm, ConservativeReady(ready.round, None, 0))
    worker.expectMsgType[FreezeConservativeMigration].requestId shouldBe "initial-cut"
    worker.send(gtm, ConservativeMigrationFrozen(epoch, "initial-cut", Vector.empty))
    driver.expectMsgType[MigrationSafeEvent].requestId shouldBe "initial-cut"
    system.stop(gtm)
  }

  it should "decline a late migration after the terminal cut without failing a completed run" in {
    val simulation = TestProbe(); val driver = TestProbe(); val worker = TestProbe()
    val gtm = TestActorRef(new GTM(simulation.ref, TestProbe().ref), "migration-terminal-cut")
    driver.awaitAssert(gtm.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
    worker.send(gtm, JoinRuntimeManager(worker.ref))
    worker.expectMsgType[ConservativeMembership]
    driver.send(gtm, StartSimulationTimeEvent(startTick = 0L))
    val ready = worker.expectMsgType[CollectConservativeReady]
    worker.send(gtm, ConservativeReady(ready.round, None, 0))
    worker.expectMsgType[StopConservativeRuntime]
    driver.send(gtm, RequestMigrationPauseEvent(Set("cell"), driver.ref, Map("cell" -> "target"), "late-cut"))
    driver.expectMsg(ConservativeMigrationDeclined("late-cut"))
    worker.expectNoMessage(100.millis)
    simulation.expectNoMessage(100.millis)
    system.stop(gtm)
  }

  it should "fail an unexpected source loss after freezing instead of mistaking it for authorized handoff" in {
    val gtm = TestProbe(); val simulation = TestProbe(); val source = TestProbe()
    val worker = TestActorRef(new Worker(gtm.ref, simulation.ref), "migration-loss-worker")
    gtm.awaitAssert(worker.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
    gtm.receiveWhile(idle = 30.millis) { case _ => () }
    gtm.send(worker, ConservativeMembership("loss", Vector(worker)))
    SpatialShardIdRegistry.putShardId("902", "loss-shard")
    SpatialShardIdRegistry.putEntityClassName("902", "example.Loss")
    gtm.send(worker, RegisterConservativeProcess("loss", gtm.ref, JoinRuntime(
      org.htc.protobuf.core.entity.actor.Identify(id = 902L, classType = "example.Loss"), source.ref, None,
      incarnation = "source")))
    source.expectMsgType[RuntimeJoined]
    gtm.send(worker, FreezeConservativeMigration("loss", "cut", Map("loss-shard" -> "target"), 1L))
    source.expectMsg(FreezeConservativeProcess("loss", "cut"))
    source.send(worker, ConservativeProcessFrozen("loss", "cut"))
    gtm.expectMsgType[ConservativeMigrationFrozen]
    system.stop(source.ref)
    gtm.expectMsgType[RuntimeFailure].reason should include("LP lost")
    system.stop(worker)
  }
}
