package org.interscity.htc
package model.test

import core.actor.SimulationBaseActor
import core.actor.manager.loadbalance.LoadBalanceTestIsolation
import core.actor.manager.loadbalance.allocation.SpatialShardIdRegistry
import core.actor.manager.loadbalance.migration.MigrationSnapshot
import core.actor.manager.time.{ ChannelClosedGlobalTimeManager, DirectConservativeLocalTimeManager }
import core.entity.actor.properties.Properties
import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import core.entity.event.control.load.InitializeEvent
import core.entity.event.control.loadbalance.*
import core.entity.event.control.migration.*
import core.entity.event.data.InitializeData
import core.entity.state.BaseState
import core.enumeration.{ CreationTypeEnum, ReportTypeEnum, TimeManagerTypeEnum }
import core.metrics.core.SimulationMetricSet
import core.runtime.accounting.*
import core.runtime.conservative.*
import com.typesafe.config.ConfigFactory
import io.prometheus.client.CollectorRegistry
import org.apache.pekko.actor.{ ActorRef, ActorSystem, Props }
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.testkit.{ TestKit, TestProbe }
import org.htc.protobuf.core.entity.event.control.execution.{ StartSimulationTimeEvent, StopSimulationEvent }
import org.htc.protobuf.core.entity.event.control.load.InitializeEntityAckEvent
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import scala.concurrent.duration.*

private case class DirectMigrationState(startTick: Long = 0L, value: Int = 0)
    extends BaseState(startTick = startTick, scheduleOnTimeManager = true)
private case object CaptureDirectCheckpoint
private case object StopDirectSource
private case class DirectMigrationObserved(
  tick: Long,
  value: Int,
  kind: String,
  sequence: Long = 0L
)

class DirectConservativeMigrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem(
    "DirectConservativeMigrationSpec",
    ConfigFactory
      .parseString("""
      pekko.actor.provider=cluster
      pekko.actor.fail-mixed-versions=off
      pekko.remote.artery.canonical.hostname="127.0.0.1"
      pekko.remote.artery.canonical.port=0
      pekko.cluster.seed-nodes=[]
    """)
      .withFallback(ConfigFactory.load())
  )
  override def beforeAll(): Unit = LoadBalanceTestIsolation.acquire()
  override def afterAll(): Unit = try {
    TestKit.shutdownActorSystem(system, 15.seconds)
    SpatialShardIdRegistry.clear()
  } finally LoadBalanceTestIsolation.release()

  private class Global(manager: ActorRef, reporter: ActorRef)
      extends ChannelClosedGlobalTimeManager(100L, false, manager) {
    override protected lazy val directInteractions: Boolean = true
    override protected def expectedWorkers: Int = 1
    override protected lazy val simulationMetrics: SimulationMetricSet = new SimulationMetricSet(
      new CollectorRegistry()
    )
    override protected lazy val reportManagerRef: ActorRef = reporter
    override def onStart(): Unit = ()
  }

  private class LP(props: Properties, observer: ActorRef)
      extends SimulationBaseActor[DirectMigrationState](props) {
    override protected def internStateStrings(value: DirectMigrationState): DirectMigrationState =
      value
    override protected def onMigrationRestore(): Unit = ()
    override protected def handleEvent: Receive = {
      case CaptureDirectCheckpoint => sender() ! buildMigrationSnapshot()
      case StopDirectSource        => stopAfterMigrationSnapshot()
    }
    override protected def deliverDirectInteraction(
      receiver: LogicalProcessId,
      kind: CreationTypeEnum,
      delivery: DirectExecute
    ): Unit = {
      observer ! delivery
      super.deliverDirectInteraction(receiver, kind, delivery)
    }
    override protected def actSpontaneous(event: SpontaneousEvent): Unit = {
      sendMessageTo(
        getEntityId.toLong,
        getClass.getName,
        "increment",
        "increment",
        CreationTypeEnum.PoolDistributed
      )
      observer ! DirectMigrationObserved(currentTick, state.value, "wake")
      if (currentTick == 0L) {
        report(Map("value" -> state.value), "before_migration")
        onFinishSpontaneous(Some(10L))
      } else onFinishSpontaneous(None)
    }
    override def actInteractWith(event: ActorInteractionEvent): Unit = {
      state = state.copy(value = state.value + 1)
      observer ! DirectMigrationObserved(currentTick, state.value, "input", event.seq)
    }
  }

  "direct conservative migration" should "restore identity counters and reject closed-tick duplicates after physical replacement" in {
    val manager = TestProbe(); val reporter = TestProbe(); val sink = TestProbe();
    val observer = TestProbe()
    val creator = TestProbe(); val control = TestProbe(); val store = TestProbe();
    val watcher = TestProbe()
    val global =
      system.actorOf(Props(new Global(manager.ref, reporter.ref)), "direct-migration-global")
    val owner = system.actorOf(
      DirectConservativeLocalTimeManager.props(100L, manager.ref, global),
      "direct-migration-owner"
    )
    val id = "98301"
    val props = Properties(
      entityId = id,
      resourceId = "direct-migration",
      actorType = CreationTypeEnum.PoolDistributed,
      runtimeCoordinator = Some(global),
      reporters = mutable.Map(ReportTypeEnum.parquet -> sink.ref),
      timeManagers = mutable.Map(TimeManagerTypeEnum.DISCRETE_EVENT -> owner)
    )
    SpatialShardIdRegistry.putShardId(id, "direct-live-shard")
    SpatialShardIdRegistry.putEntityClassName(id, classOf[LP].getName)
    val source = system.actorOf(Props(new LP(props, observer.ref)), id)
    source ! InitializeEvent(
      id,
      creator.ref,
      InitializeData(
        DirectMigrationState(),
        "direct-migration",
        creator.ref,
        props.reporters,
        timeManagers = props.timeManagers,
        runtimeCoordinator = Some(global)
      )
    )
    creator.expectMsgType[InitializeEntityAckEvent](10.seconds)
    val placement = sink.expectMsgType[CommittedReportDelivery]
    sink.send(placement.ackTo, DeliveryAccepted(placement.epoch, placement.token))
    global ! StartSimulationTimeEvent(startTick = 0L)
    val oldDelivery = observer.expectMsgType[DirectExecute]
    observer.expectMsg(DirectMigrationObserved(0L, 0, "wake"))
    val firstInput = observer.expectMsgType[DirectMigrationObserved]
    firstInput.value shouldBe 1
    val output = sink.expectMsgType[CommittedReportDelivery]
    control.send(
      global,
      RequestMigrationPauseEvent(
        Set("direct-live-shard"),
        control.ref,
        Map("direct-live-shard" -> Cluster(system).selfAddress.toString),
        "direct-cut"
      )
    )
    sink.send(output.ackTo, DeliveryAccepted(output.epoch, output.token))
    control.expectMsgType[MigrationSafeEvent](5.seconds).requestId shouldBe "direct-cut"
    control.send(source, CaptureDirectCheckpoint)
    val checkpoint = control.expectMsgType[MigrationSnapshot]
    checkpoint.conservativeRuntime.get.directConservative shouldBe true
    checkpoint.conservativeRuntime.get.directSequence should be > 0L
    checkpoint.conservativeRuntime.get.directClosedThrough shouldBe Some(0L)
    checkpoint.conservativeRuntime.get.inputs shouldBe empty
    watcher.watch(source)
    control.send(source, StopDirectSource)
    watcher.expectTerminated(source)
    val restored =
      system.actorOf(Props(new LP(props.copy(runtimeCoordinator = None), observer.ref)), id)
    control.send(
      restored,
      MigrationContextEvent(
        props.timeManagers,
        props.reporters,
        checkpoint,
        "direct-batch",
        control.ref,
        store.ref,
        "direct-offer",
        1L
      )
    )
    store.expectMsg(MigrationRestoredAckEvent(id, "direct-batch", "direct-offer", 1L))
    control.send(restored, oldDelivery)
    observer.expectNoMessage(150.millis)
    control.send(global, MigrationCompleteNotifyEvent("direct-cut"))
    val restoredPlacement = sink.expectMsgType[CommittedReportDelivery]
    restoredPlacement.event.label shouldBe "actor_placement"
    sink.send(
      restoredPlacement.ackTo,
      DeliveryAccepted(restoredPlacement.epoch, restoredPlacement.token)
    )
    observer.expectMsgType[DirectExecute]
    observer.expectMsg(DirectMigrationObserved(10L, 1, "wake"))
    val nextInput = observer.expectMsgType[DirectMigrationObserved]
    nextInput.value shouldBe 2
    nextInput.sequence should be > firstInput.sequence
    val drain = reporter.expectMsgType[DrainRuntimeReports]
    reporter.send(global, RuntimeReportsFlushed(drain.round))
    manager.expectMsgType[StopSimulationEvent]
    system.stop(global); system.stop(owner)
  }
}
