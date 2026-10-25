package org.interscity.htc
package core.actor.manager.loadbalance

import core.actor.manager.loadbalance.allocation.{ ShardAllocatorRegistry, SpatialShardIdRegistry }
import core.actor.manager.loadbalance.migration.{ MigrationStateStoreRegistry, MigrationWindowSubscriber }
import core.actor.manager.loadbalance.strategy.StrategyConfig
import core.entity.control.loadbalance.{ MigrationPlan, MigrationReason, SpatialBounds, SpatialEntity }
import core.entity.event.control.loadbalance.*
import core.entity.event.control.migration.*
import core.enumeration.LoadBalanceStrategyEnum
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ Actor, ActorRef, ActorSystem, Props }
import org.apache.pekko.cluster.sharding.ShardRegion
import org.apache.pekko.cluster.{ Cluster, MemberStatus }
import org.apache.pekko.cluster.pubsub.DistributedPubSub
import org.apache.pekko.cluster.pubsub.DistributedPubSubMediator.{ Subscribe, SubscribeAck }
import org.apache.pekko.testkit.TestProbe
import org.htc.protobuf.core.entity.event.control.execution.StopSimulationEvent
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

class LoadBalanceManagerMigrationQueueSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem(
    "LoadBalanceManagerMigrationQueueSpec",
    ConfigFactory.parseString("""
      pekko.actor.provider = cluster
      pekko.actor.fail-mixed-versions = off
      pekko.remote.artery.canonical.hostname = "127.0.0.1"
      pekko.remote.artery.canonical.port = 0
      pekko.cluster.seed-nodes = []
      pekko.cluster.min-nr-of-members = 1
    """).withFallback(ConfigFactory.load())
  )

  override def beforeAll(): Unit = {
    LoadBalanceTestIsolation.acquire()
    Cluster(system).join(Cluster(system).selfAddress)
    TestProbe().awaitAssert(
      Cluster(system).selfMember.status shouldBe MemberStatus.Up,
      10.seconds, 20.millis
    )
  }

  override def afterAll(): Unit = {
    try {
      MigrationStateStoreRegistry.clear()
      SpatialShardIdRegistry.clear()
      ShardAllocatorRegistry.clear()
      system.terminate()
      ()
    } finally LoadBalanceTestIsolation.release()
  }

  private case class AddObservedShard(id: String)
  private case class ObservedAttempt(shardId: String, attemptId: String)
  private class PlacementRegion(destination: Boolean) extends Actor {
    private var shards = Set.empty[String]
    override def receive: Receive = {
      case AddObservedShard(id) => if (destination) shards += id
      case ShardRegion.GetShardRegionState => sender() ! ShardRegion.CurrentShardRegionState(
        shards.map(id => ShardRegion.ShardState(id, Set.empty)))
    }
  }

  private class Manager(tm: ActorRef, simulation: ActorRef, limit: Int = 1,
    handoffs: ActorRef = system.deadLetters, notifySources: Boolean = false,
    regions: Option[(ActorRef, ActorRef)] = None, attempts: Option[ActorRef] = None,
    extraRegions: Seq[LoadBalanceManager.ResolvedMigrationTarget] = Seq.empty,
    selectedStrategy: LoadBalanceStrategyEnum = LoadBalanceStrategyEnum.Disabled) extends LoadBalanceManager(
    tm, simulation, selectedStrategy,
    SpatialBounds(-1, -1, 1, 1), StrategyConfig(maxConcurrentMigrations = limit)
  ) {
    override protected def createSingletonProxy(name: String): ActorRef = self
    private lazy val placementRegions = regions.getOrElse(
      context.actorOf(Props(new PlacementRegion(false))) -> context.actorOf(Props(new PlacementRegion(true))))
    override protected def triggerPekkoHandoff(plan: MigrationPlan): Unit = {
      handoffs ! plan
      if (regions.isEmpty) placementRegions._2 ! AddObservedShard(plan.shardId)
      val id = startMigrationPlacementMonitoring(plan,
        Seq(LoadBalanceManager.ResolvedMigrationTarget("test", placementRegions._2, placementRegions._1)) ++ extraRegions)
      attempts.foreach(_ ! ObservedAttempt(plan.shardId, id))
    }
    override protected def resolveMigrationDestinations(plan: MigrationPlan): Either[String, Seq[LoadBalanceManager.ResolvedMigrationTarget]] =
      Right(Seq.empty)
    override protected def notifyEntitiesForMigrationWithBatch(shardId: String, targetNode: String, batchId: String): Unit =
      if (notifySources) super.notifyEntitiesForMigrationWithBatch(shardId, targetNode, batchId)
  }

  private def windowProbe(): TestProbe = {
    val windows = TestProbe()
    windows.send(DistributedPubSub(system).mediator, Subscribe(MigrationWindowSubscriber.TOPIC, windows.ref))
    windows.expectMsgType[SubscribeAck]
    windows
  }

  private def readyForHandoff(manager: ActorRef, batch: RegisterMigrationBatchEvent, snapshots: TestProbe,
    control: TestProbe, windows: TestProbe): Unit = {
    snapshots.send(manager, MigrationBatchRegisteredEvent(batch.batchId))
    snapshots.send(manager, MigrationSnapshotsReadyEvent(batch.batchId, batch.entityIds))
    windows.expectMsgType[MigrationWindowOpenEvent].batchId shouldBe batch.batchId
    control.send(manager, MigrationWindowAckEvent(batch.batchId, MigrationWindowSubscriber.PHASE_OPEN,
      Cluster(system).selfAddress.toString))
  }

  "migration queue promotion" should "drain waiting waves once without replacing an open wave or resuming early" in {
    val tm = TestProbe()
    val simulation = TestProbe()
    val snapshots = TestProbe()
    val control = TestProbe()
    val windows = windowProbe()
    MigrationStateStoreRegistry.registerSnapshotManager(snapshots.ref)
    val address = Cluster(system).selfAddress
    val plans = (1 to 3).map(i => MigrationPlan(s"queue-shard-$i", address, address, MigrationReason.Rebalance))
    plans.zipWithIndex.foreach { case (p, i) =>
      SpatialShardIdRegistry.putShardId((101 + i).toString, p.shardId)
    }
    val manager = system.actorOf(Props(new Manager(tm.ref, simulation.ref)))
    control.watch(manager)
    simulation.expectMsgType[LoadBalanceReadyEvent]

    try {
      // Only admission/phase transitions are under test; probes provide handoff/restore results.
      plans.foreach(p => control.send(manager, RequestMigrationEvent(p)))
      control.send(manager, RequestMigrationEvent(plans.last))
      tm.expectMsgType[RequestMigrationPauseEvent].shardIds shouldBe Set(plans.head.shardId)
      tm.send(manager, MigrationSafeEvent(0L))
      var batch = snapshots.expectMsgType[RegisterMigrationBatchEvent]
      batch.entityIds shouldBe Set("101")
      readyForHandoff(manager, batch, snapshots, control, windows)

      plans.zipWithIndex.foreach { case (p, i) =>
        control.send(manager, MigrationCompleteEvent("unknown"))
        control.send(manager, MigrationCompleteEvent(p.shardId))
        control.send(manager, MigrationCompleteEvent(p.shardId))
        tm.expectNoMessage(100.millis)
        control.send(manager, MigrationSafeEvent(999L))
        control.send(manager, MigrationRestoreAcceptedEvent((101 + i).toString, batch.batchId))
        windows.expectMsgType[MigrationWindowCloseEvent].batchId shouldBe batch.batchId
        tm.expectNoMessage(100.millis)
        control.send(manager, MigrationWindowAckEvent(batch.batchId, MigrationWindowSubscriber.PHASE_CLOSE, address.toString))
        if (i < plans.size - 1) {
          tm.expectMsgType[RequestMigrationPauseEvent].shardIds shouldBe Set(plans(i + 1).shardId)
          tm.send(manager, MigrationSafeEvent(i + 1L))
          val next = snapshots.expectMsgType[RegisterMigrationBatchEvent]
          next.batchId should not be batch.batchId
          next.entityIds shouldBe Set((102 + i).toString)
          batch = next
          readyForHandoff(manager, batch, snapshots, control, windows)
        } else {
          tm.expectMsgType[MigrationCompleteNotifyEvent]
        }
      }
      tm.expectNoMessage(100.millis)
      control.send(manager, MigrationCompleteEvent(plans.last.shardId))
      tm.expectNoMessage(100.millis)
    } finally {
      system.stop(manager)
      control.expectTerminated(manager)
      system.stop(windows.ref)
    }
  }

  it should "defer newly admitted work and keep the window open until placement and restore both complete" in {
    val tm = TestProbe(); val simulation = TestProbe(); val snapshots = TestProbe(); val control = TestProbe()
    val source = TestProbe(); val target = TestProbe(); val windows = windowProbe()
    MigrationStateStoreRegistry.registerSnapshotManager(snapshots.ref)
    val address = Cluster(system).selfAddress
    val first = MigrationPlan("late-first", address, address, MigrationReason.Rebalance)
    val second = first.copy(shardId = "late-second")
    SpatialShardIdRegistry.putShardId("201", first.shardId)
    SpatialShardIdRegistry.putShardId("202", second.shardId)
    val manager = system.actorOf(Props(new Manager(tm.ref, simulation.ref, limit = 2,
      regions = Some(source.ref -> target.ref))))
    control.watch(manager)
    simulation.expectMsgType[LoadBalanceReadyEvent]
    try {
      control.send(manager, RequestMigrationEvent(first))
      tm.expectMsgType[RequestMigrationPauseEvent]
      tm.send(manager, MigrationSafeEvent(0L))
      val firstBatch = snapshots.expectMsgType[RegisterMigrationBatchEvent]
      readyForHandoff(manager, firstBatch, snapshots, control, windows)
      control.send(manager, RequestMigrationEvent(second))
      control.send(manager, MigrationRestoreAcceptedEvent("201", firstBatch.batchId))
      control.send(manager, MigrationCompleteEvent(first.shardId))
      windows.expectNoMessage(100.millis)
      tm.expectNoMessage(100.millis)
      confirmPlacement(source, target, first.shardId)
      windows.expectMsgType[MigrationWindowCloseEvent]
      control.send(manager, MigrationWindowAckEvent(firstBatch.batchId, MigrationWindowSubscriber.PHASE_CLOSE, address.toString))
      tm.expectMsgType[RequestMigrationPauseEvent].shardIds shouldBe Set(second.shardId)
      tm.send(manager, MigrationSafeEvent(1L))
      val secondBatch = snapshots.expectMsgType[RegisterMigrationBatchEvent]
      readyForHandoff(manager, secondBatch, snapshots, control, windows)
      confirmPlacement(source, target, second.shardId)
      windows.expectNoMessage(100.millis)
      tm.expectNoMessage(100.millis)
      control.send(manager, MigrationCompleteEvent(first.shardId))
      control.send(manager, MigrationRestoreAcceptedEvent("202", secondBatch.batchId))
      windows.expectMsgType[MigrationWindowCloseEvent]
      control.send(manager, MigrationWindowAckEvent(secondBatch.batchId, MigrationWindowSubscriber.PHASE_CLOSE, address.toString))
      tm.expectMsgType[MigrationCompleteNotifyEvent]
      tm.expectNoMessage(100.millis)
    } finally {
      system.stop(manager)
      control.expectTerminated(manager)
      system.stop(windows.ref)
    }
  }

  private def confirmPlacement(source: TestProbe, target: TestProbe, shardId: String): Unit = {
    source.expectMsg(ShardRegion.GetShardRegionState)
    target.expectMsg(ShardRegion.GetShardRegionState)
    source.reply(ShardRegion.CurrentShardRegionState(Set.empty))
    target.reply(ShardRegion.CurrentShardRegionState(Set(ShardRegion.ShardState(shardId, Set.empty))))
  }

  private class PlacementRig(extra: Seq[LoadBalanceManager.ResolvedMigrationTarget] = Seq.empty) {
    val tm = TestProbe(); val simulation = TestProbe(); val snapshots = TestProbe(); val control = TestProbe()
    val source = TestProbe(); val target = TestProbe(); val attempts = TestProbe(); val windows = windowProbe()
    MigrationStateStoreRegistry.registerSnapshotManager(snapshots.ref)
    val address = Cluster(system).selfAddress
    val manager = system.actorOf(Props(new Manager(tm.ref, simulation.ref,
      regions = Some(source.ref -> target.ref), attempts = Some(attempts.ref), extraRegions = extra)))
    control.watch(manager)
    simulation.expectMsgType[LoadBalanceReadyEvent]

    def begin(shardId: String, entityId: String): (RegisterMigrationBatchEvent, ObservedAttempt) = {
      SpatialShardIdRegistry.putShardId(entityId, shardId)
      control.send(manager, RequestMigrationEvent(MigrationPlan(shardId, address, address, MigrationReason.Rebalance)))
      tm.expectMsgType[RequestMigrationPauseEvent]
      tm.send(manager, MigrationSafeEvent(0L))
      val batch = snapshots.expectMsgType[RegisterMigrationBatchEvent]
      readyForHandoff(manager, batch, snapshots, control, windows)
      batch -> attempts.expectMsgType[ObservedAttempt]
    }

    def restore(batch: RegisterMigrationBatchEvent): Unit =
      batch.entityIds.foreach(id => control.send(manager, MigrationRestoreAcceptedEvent(id, batch.batchId)))

    def finish(batch: RegisterMigrationBatchEvent): Unit = {
      windows.expectMsgType[MigrationWindowCloseEvent].batchId shouldBe batch.batchId
      control.send(manager, MigrationWindowAckEvent(batch.batchId, MigrationWindowSubscriber.PHASE_CLOSE, address.toString))
      tm.expectMsgType[MigrationCompleteNotifyEvent]
    }

    def close(): Unit = {
      system.stop(manager)
      control.expectTerminated(manager)
      system.stop(windows.ref)
    }
  }

  "placement evidence" should "reject incomplete or failed observations and unsolicited success until source absence and target presence agree" in {
    val rig = new PlacementRig()
    val shard = "evidence-shard"
    try {
      val (batch, attempt) = rig.begin(shard, "601")
      rig.restore(batch)
      rig.control.send(rig.manager, MigrationCompleteEvent(shard, batchId = batch.batchId, attemptId = attempt.attemptId))
      rig.source.expectMsg(ShardRegion.GetShardRegionState)
      rig.target.expectMsg(ShardRegion.GetShardRegionState)
      rig.source.reply(ShardRegion.CurrentShardRegionState(Set.empty, Set(shard)))
      rig.target.reply(ShardRegion.CurrentShardRegionState(Set(ShardRegion.ShardState(shard, Set.empty))))
      rig.windows.expectNoMessage(100.millis)
      rig.source.expectMsg(ShardRegion.GetShardRegionState)
      rig.target.expectMsg(ShardRegion.GetShardRegionState)
      rig.source.reply(ShardRegion.CurrentShardRegionState(Set(ShardRegion.ShardState(shard, Set.empty))))
      rig.target.reply(ShardRegion.CurrentShardRegionState(Set(ShardRegion.ShardState(shard, Set.empty))))
      rig.windows.expectNoMessage(100.millis)
      rig.source.expectMsg(ShardRegion.GetShardRegionState)
      rig.target.expectMsg(ShardRegion.GetShardRegionState)
      rig.source.reply(org.apache.pekko.actor.Status.Failure(new IllegalStateException("unavailable observation")))
      rig.source.expectNoMessage(600.millis)
      rig.target.reply(ShardRegion.CurrentShardRegionState(Set.empty))
      rig.windows.expectNoMessage(100.millis)
      rig.tm.expectNoMessage(100.millis)
      confirmPlacement(rig.source, rig.target, shard)
      rig.finish(batch)
      rig.tm.expectNoMessage(100.millis)
    } finally rig.close()
  }

  it should "ignore old attempt completions and observation rounds when the same shard migrates again" in {
    val rig = new PlacementRig()
    val shard = "repeat-shard"
    try {
      val (first, oldAttempt) = rig.begin(shard, "602")
      confirmPlacement(rig.source, rig.target, shard)
      rig.restore(first)
      rig.finish(first)
      val (second, current) = rig.begin(shard, "602")
      current.attemptId should not be oldAttempt.attemptId
      second.batchId should not be first.batchId
      rig.restore(second)
      rig.control.send(rig.manager, LoadBalanceManager.PlacementObserved(shard, first.batchId, oldAttempt.attemptId, 1L, true))
      rig.control.send(rig.manager, LoadBalanceManager.PlacementObserved(shard, second.batchId, oldAttempt.attemptId, 1L, true))
      rig.control.send(rig.manager, LoadBalanceManager.PlacementObserved(shard, second.batchId, current.attemptId, 99L, true))
      rig.control.send(rig.manager, MigrationCompleteEvent(shard, batchId = first.batchId, attemptId = oldAttempt.attemptId))
      rig.control.send(rig.manager, MigrationCompleteEvent(shard, batchId = second.batchId, attemptId = current.attemptId))
      rig.windows.expectNoMessage(100.millis)
      rig.tm.expectNoMessage(100.millis)
      confirmPlacement(rig.source, rig.target, shard)
      rig.finish(second)
      rig.control.send(rig.manager, MigrationCompleteEvent(shard))
      rig.tm.expectNoMessage(100.millis)
    } finally rig.close()
  }

  it should "require every physical type in one observation round without mixing evidence across rounds" in {
    val otherSource = TestProbe(); val otherTarget = TestProbe()
    val rig = new PlacementRig(Seq(LoadBalanceManager.ResolvedMigrationTarget("other", otherTarget.ref, otherSource.ref)))
    val shard = "multi-type-evidence"
    val present = ShardRegion.CurrentShardRegionState(Set(ShardRegion.ShardState(shard, Set.empty)))
    val absent = ShardRegion.CurrentShardRegionState(Set.empty)
    try {
      val (batch, _) = rig.begin(shard, "603")
      rig.restore(batch)
      confirmPlacement(rig.source, rig.target, shard)
      otherSource.expectMsg(ShardRegion.GetShardRegionState); otherTarget.expectMsg(ShardRegion.GetShardRegionState)
      otherSource.reply(absent); otherTarget.reply(absent)
      rig.windows.expectNoMessage(100.millis)
      rig.source.expectMsg(ShardRegion.GetShardRegionState); rig.target.expectMsg(ShardRegion.GetShardRegionState)
      rig.source.reply(absent); rig.target.reply(absent)
      otherSource.expectMsg(ShardRegion.GetShardRegionState); otherTarget.expectMsg(ShardRegion.GetShardRegionState)
      otherSource.reply(absent); otherTarget.reply(present)
      rig.windows.expectNoMessage(100.millis)
      confirmPlacement(rig.source, rig.target, shard)
      confirmPlacement(otherSource, otherTarget, shard)
      rig.finish(batch)
    } finally rig.close()
  }

  it should "discard in-flight observations after preparation fails without resuming" in {
    val rig = new PlacementRig()
    val shard = "failed-evidence"
    try {
      val (batch, _) = rig.begin(shard, "604")
      rig.source.expectMsg(ShardRegion.GetShardRegionState)
      rig.target.expectMsg(ShardRegion.GetShardRegionState)
      rig.snapshots.send(rig.manager, MigrationPreparationFailedEvent(batch.batchId, "604", "restore failed"))
      rig.tm.expectMsg(StopSimulationEvent())
      rig.simulation.expectMsg(StopSimulationEvent())
      rig.source.reply(ShardRegion.CurrentShardRegionState(Set.empty))
      rig.target.reply(ShardRegion.CurrentShardRegionState(Set(ShardRegion.ShardState(shard, Set.empty))))
      rig.control.send(rig.manager, MigrationRestoreAcceptedEvent("604", batch.batchId))
      rig.tm.expectNoMessage(200.millis)
      rig.windows.expectNoMessage(100.millis)
    } finally rig.close()
  }

  it should "stop without publishing migration success when shutdown aborts an unverified attempt" in {
    val rig = new PlacementRig()
    try {
      rig.begin("stopped-evidence", "605")
      rig.source.expectMsg(ShardRegion.GetShardRegionState)
      rig.target.expectMsg(ShardRegion.GetShardRegionState)
      rig.control.send(rig.manager, StopSimulationEvent())
      rig.control.expectTerminated(rig.manager)
      rig.tm.expectNoMessage(100.millis)
      rig.windows.expectNoMessage(100.millis)
    } finally {
      system.stop(rig.manager)
      system.stop(rig.windows.ref)
    }
  }

  "snapshot preparation" should "require registration, the complete save barrier, and valid open acknowledgements in order" in {
    val tm = TestProbe(); val simulation = TestProbe(); val snapshots = TestProbe()
    val control = TestProbe(); val handoffs = TestProbe(); val windows = TestProbe()
    windows.send(DistributedPubSub(system).mediator, Subscribe(MigrationWindowSubscriber.TOPIC, windows.ref))
    windows.expectMsgType[SubscribeAck]
    MigrationStateStoreRegistry.registerSnapshotManager(snapshots.ref)
    val address = Cluster(system).selfAddress
    val plan = MigrationPlan("barrier-shard", address, address, MigrationReason.Rebalance)
    SpatialShardIdRegistry.putShardId("301", plan.shardId)
    val manager = system.actorOf(Props(new Manager(tm.ref, simulation.ref, handoffs = handoffs.ref)))
    control.watch(manager)
    simulation.expectMsgType[LoadBalanceReadyEvent]
    try {
      control.send(manager, RequestMigrationEvent(plan))
      tm.expectMsgType[RequestMigrationPauseEvent]
      tm.send(manager, MigrationSafeEvent(0L))
      val batch = snapshots.expectMsgType[RegisterMigrationBatchEvent]
      control.send(manager, MigrationWindowAckEvent(batch.batchId, MigrationWindowSubscriber.PHASE_OPEN, address.toString))
      snapshots.send(manager, MigrationSnapshotsReadyEvent(batch.batchId, batch.entityIds))
      windows.expectNoMessage(100.millis)
      handoffs.expectNoMessage(100.millis)
      snapshots.send(manager, MigrationBatchRegisteredEvent(batch.batchId))
      snapshots.send(manager, MigrationSnapshotsReadyEvent("old-batch", batch.entityIds))
      windows.expectNoMessage(100.millis)
      snapshots.send(manager, MigrationSnapshotsReadyEvent(batch.batchId, batch.entityIds))
      windows.expectMsgType[MigrationWindowOpenEvent].batchId shouldBe batch.batchId
      handoffs.expectNoMessage(100.millis)
      control.send(manager, MigrationWindowAckEvent(batch.batchId, MigrationWindowSubscriber.PHASE_OPEN, "unknown-node"))
      handoffs.expectNoMessage(100.millis)
      control.send(manager, MigrationWindowAckEvent(batch.batchId, MigrationWindowSubscriber.PHASE_OPEN, address.toString))
      handoffs.expectMsg(plan)
      snapshots.send(manager, MigrationSnapshotsReadyEvent(batch.batchId, batch.entityIds))
      control.send(manager, MigrationWindowAckEvent(batch.batchId, MigrationWindowSubscriber.PHASE_OPEN, address.toString))
      handoffs.expectNoMessage(100.millis)
      control.send(manager, MigrationCompleteEvent(plan.shardId))
      control.send(manager, MigrationRestoredAckEvent("301", batch.batchId, "unconfirmed-target", 1L))
      control.send(manager, ShardRegion.StartEntityAck("301", plan.shardId))
      windows.expectNoMessage(100.millis)
      tm.expectNoMessage(100.millis)
      control.send(manager, MigrationRestoreAcceptedEvent("301", batch.batchId))
      windows.expectMsgType[MigrationWindowCloseEvent].batchId shouldBe batch.batchId
      control.send(manager, MigrationRestoreAcceptedEvent("301", batch.batchId))
      windows.expectNoMessage(100.millis)
      control.send(manager, MigrationWindowAckEvent(batch.batchId, MigrationWindowSubscriber.PHASE_CLOSE, address.toString))
      tm.expectMsgType[MigrationCompleteNotifyEvent]
    } finally {
      system.stop(manager)
      control.expectTerminated(manager)
      system.stop(windows.ref)
    }
  }

  it should "stop the run without resuming when snapshot preparation fails" in {
    val tm = TestProbe(); val simulation = TestProbe(); val snapshots = TestProbe(); val control = TestProbe()
    MigrationStateStoreRegistry.registerSnapshotManager(snapshots.ref)
    val address = Cluster(system).selfAddress
    val plan = MigrationPlan("failed-snapshot", address, address, MigrationReason.Rebalance)
    SpatialShardIdRegistry.putShardId("401", plan.shardId)
    val manager = system.actorOf(Props(new Manager(tm.ref, simulation.ref)))
    control.watch(manager)
    simulation.expectMsgType[LoadBalanceReadyEvent]
    try {
      control.send(manager, RequestMigrationEvent(plan))
      tm.expectMsgType[RequestMigrationPauseEvent]
      tm.send(manager, MigrationSafeEvent(0L))
      val batch = snapshots.expectMsgType[RegisterMigrationBatchEvent]
      snapshots.send(manager, MigrationPreparationFailedEvent(batch.batchId, "401", "capture failed"))
      tm.expectMsg(StopSimulationEvent())
      simulation.expectMsg(StopSimulationEvent())
      snapshots.send(manager, MigrationSnapshotsReadyEvent(batch.batchId, batch.entityIds))
      control.send(manager, RequestMigrationEvent(plan))
      tm.expectNoMessage(100.millis)
    } finally {
      system.stop(manager)
      control.expectTerminated(manager)
    }
  }

  it should "stop when a registered source has no actor-class route instead of waiting for its save" in {
    val tm = TestProbe(); val simulation = TestProbe(); val snapshots = TestProbe(); val control = TestProbe()
    MigrationStateStoreRegistry.registerSnapshotManager(snapshots.ref)
    val address = Cluster(system).selfAddress
    val plan = MigrationPlan("unroutable-source", address, address, MigrationReason.Rebalance)
    SpatialShardIdRegistry.putShardId("501", plan.shardId)
    val manager = system.actorOf(Props(new Manager(tm.ref, simulation.ref, notifySources = true)))
    control.watch(manager)
    simulation.expectMsgType[LoadBalanceReadyEvent]
    try {
      control.send(manager, RequestMigrationEvent(plan))
      tm.expectMsgType[RequestMigrationPauseEvent]
      tm.send(manager, MigrationSafeEvent(0L))
      val batch = snapshots.expectMsgType[RegisterMigrationBatchEvent]
      snapshots.send(manager, MigrationBatchRegisteredEvent(batch.batchId))
      tm.expectMsg(StopSimulationEvent())
      simulation.expectMsg(StopSimulationEvent())
      tm.expectNoMessage(100.millis)
    } finally {
      system.stop(manager)
      control.expectTerminated(manager)
    }
  }

  "static placement" should "skip pinned infrastructure without querying placement or preparing snapshots" in {
    val tm = TestProbe(); val simulation = TestProbe(); val snapshots = TestProbe(); val control = TestProbe(); val handoffs = TestProbe()
    MigrationStateStoreRegistry.registerSnapshotManager(snapshots.ref)
    val manager = system.actorOf(Props(new Manager(tm.ref, simulation.ref, handoffs = handoffs.ref,
      selectedStrategy = LoadBalanceStrategyEnum.Default)))
    control.watch(manager)
    simulation.expectMsgType[LoadBalanceReadyEvent]
    try {
      control.send(manager, RegisterSpatialEntityEvent(new SpatialEntity {
        override val spatialEntityId = "901"
        override val entityClassType = "hybrid.actor.Link"
        override val position = (0.0, 0.0)
      }))
      val assigned = control.expectMsgType[ShardAssignmentResponse]
      val address = Cluster(system).selfAddress
      control.send(manager, RequestMigrationEvent(MigrationPlan(assigned.shardId, address, address, MigrationReason.Rebalance)))
      tm.expectMsgType[RequestMigrationPauseEvent]
      tm.send(manager, MigrationSafeEvent(0L))
      tm.expectMsgType[MigrationCompleteNotifyEvent]
      snapshots.expectNoMessage(100.millis)
      handoffs.expectNoMessage(100.millis)
      control.send(manager, MigrationCompleteEvent(assigned.shardId))
      tm.expectNoMessage(100.millis)
    } finally {
      system.stop(manager)
      control.expectTerminated(manager)
    }
  }
}
