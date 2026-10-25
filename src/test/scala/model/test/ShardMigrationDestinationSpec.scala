package org.interscity.htc
package model.test

import core.actor.SimulationBaseActor
import core.actor.manager.SnapshotManager
import core.actor.manager.loadbalance.{ LoadBalanceManager, LoadBalanceTestIsolation }
import core.actor.manager.loadbalance.allocation.{ ShardAllocatorRegistry, SpatialShardIdRegistry }
import core.actor.manager.loadbalance.migration.{ MigrationStateStoreRegistry, MigrationWindowSubscriber }
import core.actor.manager.loadbalance.strategy.StrategyConfig
import core.entity.actor.properties.Properties
import core.entity.control.loadbalance.{ MigrationPlan, MigrationReason, SpatialBounds }
import core.entity.event.EntityEnvelopeEvent
import core.entity.event.control.loadbalance.*
import core.entity.event.control.migration.*
import core.entity.state.DefaultState
import core.enumeration.{ LoadBalanceStrategyEnum, ReportTypeEnum }
import core.util.ActorCreatorUtil
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ ActorRef, ActorSystem, Props }
import org.apache.pekko.cluster.{ Cluster, MemberStatus }
import org.apache.pekko.cluster.pubsub.DistributedPubSub
import org.apache.pekko.cluster.pubsub.DistributedPubSubMediator.{ Subscribe, SubscribeAck }
import org.apache.pekko.cluster.sharding.ShardRegion
import org.apache.pekko.testkit.{ TestKit, TestProbe }
import org.htc.protobuf.core.entity.event.control.execution.StopSimulationEvent
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import scala.concurrent.duration.*

sealed trait DestinationProbeCommand
case class SetDestinationCounter(value: Long) extends DestinationProbeCommand
case object ReadDestinationCounter extends DestinationProbeCommand
case class QueryDestinationRestore(store: ActorRef) extends DestinationProbeCommand
case class DestinationCounterValue(owner: ActorRef, value: Long) extends DestinationProbeCommand

class DestinationCounterActor(properties: Properties) extends SimulationBaseActor[DefaultState](properties) {
  override protected def onStart(): Unit = state = DefaultState(0L)
  override protected def internStateStrings(s: DefaultState): DefaultState = s
  override protected def onMigrationRestore(): Unit = ()
  override protected def shouldRegisterOnTimeManagerAfterMigration(): Boolean = false
  override def handleEvent: Receive = {
    case SetDestinationCounter(value) =>
      state.eventsAmount = value
      sender() ! DestinationCounterValue(self, state.eventsAmount)
    case ReadDestinationCounter => sender() ! DestinationCounterValue(self, state.eventsAmount)
    case QueryDestinationRestore(store) => store ! QueryMigrationEvent(getEntityId, self)
    case event => super.handleEvent(event)
  }
}

class ShardMigrationDestinationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private val config = ConfigFactory.parseString("""
    pekko.actor.provider = cluster
    pekko.actor.fail-mixed-versions = off
    pekko.remote.artery.canonical.hostname = "127.0.0.1"
    pekko.remote.artery.canonical.port = 0
    pekko.cluster.seed-nodes = []
    pekko.cluster.min-nr-of-members = 1
    pekko.cluster.jmx.multi-mbeans-in-same-jvm = on
    pekko.cluster.sharding.rebalance-interval = 100 ms
    pekko.actor.serialization-bindings {
      "org.interscity.htc.model.test.DestinationProbeCommand" = jackson-cbor
    }
  """).withFallback(ConfigFactory.load())
  private implicit val sourceSystem: ActorSystem = ActorSystem("ShardMigrationDestinationSpec", config)
  private val targetSystem: ActorSystem = ActorSystem("ShardMigrationDestinationSpec", config)
  private val sourceAddress = Cluster(sourceSystem).selfAddress
  private val targetAddress = Cluster(targetSystem).selfAddress
  private val typeName = classOf[DestinationCounterActor].getName

  override def beforeAll(): Unit = {
    LoadBalanceTestIsolation.acquire()
    Cluster(sourceSystem).join(sourceAddress)
    TestProbe().awaitAssert(Cluster(sourceSystem).selfMember.status shouldBe MemberStatus.Up, 15.seconds, 30.millis)
    Cluster(targetSystem).join(sourceAddress)
    TestProbe().awaitAssert(Cluster(sourceSystem).state.members.count(_.status == MemberStatus.Up) shouldBe 2, 20.seconds, 30.millis)
  }

  override def afterAll(): Unit = {
    try {
      ShardAllocatorRegistry.get.foreach(_.clearAll())
      ShardAllocatorRegistry.clear()
      SpatialShardIdRegistry.clear()
      MigrationStateStoreRegistry.clear()
      TestKit.shutdownActorSystem(targetSystem, 20.seconds)
      TestKit.shutdownActorSystem(sourceSystem, 20.seconds)
    } finally LoadBalanceTestIsolation.release()
  }

  private case class CheckDestination(plan: MigrationPlan)
  private class Manager(tm: ActorRef, simulation: ActorRef) extends LoadBalanceManager(
    tm, simulation, LoadBalanceStrategyEnum.Disabled, SpatialBounds(-1, -1, 1, 1), StrategyConfig()) {
    override protected def createSingletonProxy(name: String): ActorRef = self
    override def handleEvent: Receive = {
      case CheckDestination(plan) => sender() ! resolveMigrationDestinations(plan)
      case event => super.handleEvent(event)
    }
  }

  private def region(system: ActorSystem, id: String, control: TestProbe): ActorRef =
    ActorCreatorUtil.createShardRegion(system, typeName, id, "destination", mutable.Map.empty,
      control.ref, mutable.Map.empty[ReportTypeEnum, ActorRef])

  "planned shard placement" should "move a live shard to the requested idle member and restore its state through the real store" in {
    val control = TestProbe(); val tm = TestProbe(); val simulation = TestProbe(); val windows = TestProbe(); val watcher = TestProbe()
    windows.send(DistributedPubSub(sourceSystem).mediator, Subscribe(MigrationWindowSubscriber.TOPIC, windows.ref))
    windows.expectMsgType[SubscribeAck]
    val manager = sourceSystem.actorOf(Props(new Manager(tm.ref, simulation.ref)))
    simulation.expectMsgType[LoadBalanceReadyEvent]
    val root = ShardAllocatorRegistry.get.get
    val allocator = root.forType(typeName)
    val id = "801"; val shardId = "destination-801"
    SpatialShardIdRegistry.putShardId(id, shardId)
    SpatialShardIdRegistry.putEntityClassName(id, typeName)
    val sourceRegion = region(sourceSystem, id, control)
    val targetRegion = region(targetSystem, id, control)
    control.send(sourceRegion, EntityEnvelopeEvent(id, SetDestinationCounter(23L)))
    val original = control.expectMsgType[DestinationCounterValue](15.seconds)
    control.awaitAssert(allocator.resolveDestination(shardId, sourceAddress, targetAddress, sourceAddress).isRight shouldBe true,
      15.seconds, 30.millis)
    allocator.getRegionIndex.exists { case (ref, shards) =>
      ref.path.address == targetAddress && shards.isEmpty
    } shouldBe true
    val store = sourceSystem.actorOf(SnapshotManager.props(tm.ref))
    MigrationStateStoreRegistry.registerSnapshotManager(store)
    val plan = MigrationPlan(shardId, sourceAddress, targetAddress, MigrationReason.Rebalance)
    watcher.watch(original.owner)
    try {
      control.send(manager, RequestMigrationEvent(plan))
      tm.expectMsgType[RequestMigrationPauseEvent]
      tm.send(manager, MigrationSafeEvent(0L))
      val opened = windows.expectMsgType[MigrationWindowOpenEvent](10.seconds)
      opened.entityIds shouldBe Set(id)
      // The JVM-wide flag remains off for other suites; drive both node ACKs and the target query explicitly.
      control.send(manager, MigrationWindowAckEvent(opened.batchId, MigrationWindowSubscriber.PHASE_OPEN, sourceAddress.toString))
      control.send(manager, MigrationWindowAckEvent(opened.batchId, MigrationWindowSubscriber.PHASE_OPEN, sourceAddress.toString))
      watcher.expectNoMessage(100.millis)
      windows.expectNoMessage(100.millis)
      control.send(manager, MigrationWindowAckEvent(opened.batchId, MigrationWindowSubscriber.PHASE_OPEN, targetAddress.toString))
      watcher.expectTerminated(original.owner, 15.seconds)
      control.send(sourceRegion, EntityEnvelopeEvent(id, QueryDestinationRestore(store)))
      val closed = windows.expectMsgType[MigrationWindowCloseEvent](15.seconds)
      closed.batchId shouldBe opened.batchId
      control.send(sourceRegion, EntityEnvelopeEvent(id, ReadDestinationCounter))
      val restored = control.expectMsgType[DestinationCounterValue]
      restored.owner.path.address shouldBe targetAddress
      restored.owner should not be original.owner
      restored.value shouldBe 23L
      control.send(sourceRegion, ShardRegion.GetShardRegionState)
      control.expectMsgType[ShardRegion.CurrentShardRegionState].shards.map(_.shardId) should not contain shardId
      control.send(targetRegion, ShardRegion.GetShardRegionState)
      control.expectMsgType[ShardRegion.CurrentShardRegionState].shards.map(_.shardId) should contain(shardId)
      tm.expectNoMessage(100.millis)
      control.send(manager, MigrationWindowAckEvent(opened.batchId, MigrationWindowSubscriber.PHASE_CLOSE, sourceAddress.toString))
      control.send(manager, MigrationWindowAckEvent(opened.batchId, MigrationWindowSubscriber.PHASE_CLOSE, sourceAddress.toString))
      tm.expectNoMessage(100.millis)
      control.send(manager, MigrationWindowAckEvent(opened.batchId, MigrationWindowSubscriber.PHASE_CLOSE, targetAddress.toString))
      tm.expectMsgType[MigrationCompleteNotifyEvent](5.seconds)
      tm.expectNoMessage(100.millis)
    } finally {
      sourceSystem.stop(manager)
      sourceSystem.stop(windows.ref)
      root.clearAll()
    }
  }

  it should "stop before preparing sources when the requested member has no observed region for their type" in {
    val control = TestProbe(); val tm = TestProbe(); val simulation = TestProbe(); val snapshots = TestProbe(); val fakeRegion = TestProbe()
    val manager = sourceSystem.actorOf(Props(new Manager(tm.ref, simulation.ref)))
    simulation.expectMsgType[LoadBalanceReadyEvent]
    val allocator = ShardAllocatorRegistry.get.get.forType(typeName)
    val id = "802"; val shardId = "unavailable-destination"
    SpatialShardIdRegistry.putShardId(id, shardId)
    SpatialShardIdRegistry.putEntityClassName(id, typeName)
    allocator.registerLocalRegion(fakeRegion.ref, sourceAddress)
    allocator.rebalance(Map(fakeRegion.ref -> Vector(shardId)), Set.empty)
    MigrationStateStoreRegistry.registerSnapshotManager(snapshots.ref)
    try {
      control.send(manager, RequestMigrationEvent(MigrationPlan(shardId, sourceAddress, targetAddress, MigrationReason.Rebalance)))
      tm.expectMsgType[RequestMigrationPauseEvent]
      tm.send(manager, MigrationSafeEvent(0L))
      tm.expectMsg(StopSimulationEvent())
      simulation.expectMsg(StopSimulationEvent())
      snapshots.expectNoMessage(100.millis)
      allocator.getPendingRebalances shouldBe empty
    } finally sourceSystem.stop(manager)
  }

  it should "resolve every dynamic physical type and reject a logical shard containing pinned infrastructure" in {
    val control = TestProbe(); val tm = TestProbe(); val simulation = TestProbe()
    val source = TestProbe(); val target = TestProbe()
    val manager = sourceSystem.actorOf(Props(new Manager(tm.ref, simulation.ref)))
    simulation.expectMsgType[LoadBalanceReadyEvent]
    val root = ShardAllocatorRegistry.get.get
    val otherType = "org.interscity.htc.model.test.OtherVehicle"
    val shardId = "multi-type-destination"
    Seq(typeName, otherType).zipWithIndex.foreach { case (name, i) =>
      SpatialShardIdRegistry.putShardId((811 + i).toString, shardId)
      SpatialShardIdRegistry.putEntityClassName((811 + i).toString, name)
      val allocator = root.forType(name)
      allocator.registerLocalRegion(source.ref, sourceAddress)
      allocator.registerLocalRegion(target.ref, targetAddress)
      allocator.rebalance(Map(source.ref -> Vector(shardId), target.ref -> Vector.empty), Set.empty)
    }
    val plan = MigrationPlan(shardId, sourceAddress, targetAddress, MigrationReason.Rebalance)
    try {
      control.send(manager, CheckDestination(plan))
      val resolved = control.expectMsgType[Either[String, Seq[LoadBalanceManager.ResolvedMigrationTarget]]]
      resolved.map(_.map(_.typeName).toSet) shouldBe Right(Set(typeName, otherType))
      SpatialShardIdRegistry.putShardId("813", shardId)
      SpatialShardIdRegistry.putEntityClassName("813", "hybrid.actor.Link")
      control.send(manager, CheckDestination(plan))
      control.expectMsgType[Either[String, Seq[LoadBalanceManager.ResolvedMigrationTarget]]].left.toOption.get should include("pinned infrastructure")
    } finally sourceSystem.stop(manager)
  }
}
