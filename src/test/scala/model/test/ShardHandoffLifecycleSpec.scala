package org.interscity.htc
package model.test

import core.actor.manager.loadbalance.LoadBalanceTestIsolation
import core.actor.SimulationBaseActor
import core.actor.manager.SnapshotManager
import core.actor.manager.loadbalance.allocation.{ LoadBalanceShardAllocator, ShardAllocatorRegistry }
import core.actor.manager.loadbalance.migration.{ MigrationSnapshot, MigrationStateStoreRegistry }
import core.entity.actor.properties.Properties
import core.entity.event.EntityEnvelopeEvent
import core.entity.event.control.migration.*
import core.entity.event.control.loadbalance.PrepareForMigrationEvent
import core.entity.state.DefaultState
import core.enumeration.{ CreationTypeEnum, ReportTypeEnum }
import core.types.Tick
import core.util.ActorCreatorUtil
import core.metrics.core.ActorMetrics
import model.hybrid.actor.{ Car, Person }
import model.hybrid.entity.event.data.bus.BusUnloadPassengerData
import model.hybrid.entity.event.data.person.TripCompletedData
import model.hybrid.entity.state.{ CarState, PersonState }
import model.hybrid.entity.state.enumeration.MovableStatusEnum.Moving
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ ActorRef, ActorSystem }
import org.apache.pekko.cluster.{ Cluster, MemberStatus }
import org.apache.pekko.testkit.TestProbe
import org.htc.protobuf.core.entity.event.control.execution.DestructEvent
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.concurrent.duration.*

private case class SeedHandoffActor(effects: ActorRef)
private case class ObserveHandoffActor(effects: ActorRef)
private case object ReadHandoffSnapshot
private case class QueryHandoffRestore(store: ActorRef)
private case class HandoffSnapshot(ref: ActorRef, snapshot: MigrationSnapshot)
private case object ModelDestructed
private case class ModelReport(label: String)
private case object IncrementHandoffCounter
private case object ReadHandoffCounter
private case object FailHandoffCapture
private case class HandoffCounter(ref: ActorRef, value: Long)

class HandoffCounterActor(properties: Properties) extends SimulationBaseActor[DefaultState](properties) {
  private var failCapture = false
  override protected def onStart(): Unit = state = DefaultState(0L)
  override protected def onMigrationRestore(): Unit = ()
  override protected def shouldRegisterOnTimeManagerAfterMigration(): Boolean = false
  override protected def internStateStrings(s: DefaultState): DefaultState = s
  override protected def buildMigrationSnapshot(): MigrationSnapshot = {
    require(!failCapture, "controlled capture failure")
    super.buildMigrationSnapshot()
  }
  override def handleEvent: Receive = {
    case IncrementHandoffCounter =>
      state.eventsAmount += 1L
      sender() ! HandoffCounter(self, state.eventsAmount)
    case ReadHandoffCounter => sender() ! HandoffCounter(self, state.eventsAmount)
    case FailHandoffCapture =>
      failCapture = true
      sender() ! HandoffCounter(self, state.eventsAmount)
    case event => super.handleEvent(event)
  }
}

class HandoffLifecycleCar(properties: Properties) extends Car(properties) {
  private var effects: ActorRef = context.system.deadLetters
  override protected def registerOnTimeManager(tick: Tick): Unit = ()
  override protected def scheduleNextTick(tick: Option[Tick]): Unit = ()
  override protected def shouldRegisterOnTimeManagerAfterMigration(): Boolean = false
  override protected def report(data: Any, label: String): Unit = effects ! ModelReport(label)
  override protected def sendVehicleMessage(
    entityId: Long, shardId: String, data: AnyRef, eventType: String, actorType: CreationTypeEnum
  ): Unit = effects ! data
  override def onDestruct(event: DestructEvent): Unit = {
    effects ! ModelDestructed
    super.onDestruct(event)
  }
  override def handleEvent: Receive = {
    case SeedHandoffActor(observer) =>
      effects = observer
      state = CarState(startTick = 0L, origin = 1001L, destination = 1002L,
        actorType = model.hybrid.entity.state.enumeration.ActorTypeEnum.Car, size = 4.5)
      state.status = Moving
      state.journey.sumoDepartTick = Some(3L)
      applyMigrationSnapshot(buildMigrationSnapshot().copy(
        currentTick = 10L, ownerPersonRefId = 71L, ownerPersonRefClassType = "hybrid.actor.Person",
        personCentric = true, tripOrigin = 1001L, tripDestination = 1002L, tripStartTick = 3L
      ))
      sender() ! HandoffSnapshot(self, buildMigrationSnapshot())
    case ObserveHandoffActor(observer) => effects = observer
    case QueryHandoffRestore(store) => store ! QueryMigrationEvent(getEntityId, self)
    case ReadHandoffSnapshot => sender() ! HandoffSnapshot(self, buildMigrationSnapshot())
    case event => super.handleEvent(event)
  }
}

class HandoffLifecyclePerson(properties: Properties) extends Person(properties) {
  private var effects: ActorRef = context.system.deadLetters
  override protected def shouldRegisterOnTimeManagerAfterMigration(): Boolean = false
  override protected def report(data: Any, label: String): Unit = effects ! ModelReport(label)
  override protected def sendMessageTo(
    entityId: Long, shardId: String, data: AnyRef, eventType: String, actorType: CreationTypeEnum
  ): Unit = effects ! data
  override def onDestruct(event: DestructEvent): Unit = {
    effects ! ModelDestructed
    super.onDestruct(event)
  }
  override def handleEvent: Receive = {
    case SeedHandoffActor(observer) =>
      effects = observer
      state = PersonState()
      applyMigrationSnapshot(buildMigrationSnapshot().copy(
        currentTick = 10L, currentPTVehicleRefId = 81L, currentPTVehicleRefClassType = "hybrid.actor.Bus"
      ))
      sender() ! HandoffSnapshot(self, buildMigrationSnapshot())
    case ObserveHandoffActor(observer) => effects = observer
    case QueryHandoffRestore(store) => store ! QueryMigrationEvent(getEntityId, self)
    case ReadHandoffSnapshot => sender() ! HandoffSnapshot(self, buildMigrationSnapshot())
    case event => super.handleEvent(event)
  }
}

class ShardHandoffLifecycleSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("ShardHandoffLifecycleSpec",
    ConfigFactory.parseString("""
      pekko.actor.provider = cluster
      pekko.actor.fail-mixed-versions = off
      pekko.remote.artery.canonical.hostname = "127.0.0.1"
      pekko.remote.artery.canonical.port = 0
      pekko.cluster.seed-nodes = []
      pekko.cluster.min-nr-of-members = 1
      pekko.cluster.sharding.rebalance-interval = 100 ms
    """).withFallback(ConfigFactory.load()))

  override def beforeAll(): Unit = {
    LoadBalanceTestIsolation.acquire()
    Cluster(system).join(Cluster(system).selfAddress)
    TestProbe().awaitAssert(Cluster(system).selfMember.status shouldBe MemberStatus.Up, 10.seconds, 20.millis)
  }

  override def afterAll(): Unit = {
    try {
      ShardAllocatorRegistry.clear()
      MigrationStateStoreRegistry.clear()
      system.terminate()
      ()
    } finally LoadBalanceTestIsolation.release()
  }

  private def runHandoff(actorClass: Class[?], id: String): Unit = {
    val control = TestProbe()
    val effects = TestProbe()
    val watcher = TestProbe()
    val allocator = LoadBalanceShardAllocator()
    ShardAllocatorRegistry.register(allocator)
    val region = ActorCreatorUtil.createShardRegion(
      system, actorClass.getName, id, "handoff", mutable.Map.empty,
      control.ref, mutable.Map.empty[ReportTypeEnum, ActorRef]
    )
    control.send(region, EntityEnvelopeEvent(id, SeedHandoffActor(effects.ref)))
    val original = control.expectMsgType[HandoffSnapshot](10.seconds)
    val store = system.actorOf(SnapshotManager.props(control.ref))
    val batchId = s"handoff-$id"
    control.send(store, RegisterMigrationBatchEvent(batchId, Set(id), control.ref))
    control.expectMsg(MigrationBatchRegisteredEvent(batchId))
    control.send(store, SaveMigrationSnapshotEvent(id, batchId, original.snapshot))
    control.expectMsg(MigrationSnapshotsReadyEvent(batchId, Set(id)))
    control.send(store, SaveFinalMigrationSnapshotEvent(id, batchId, original.snapshot, 1L))
    control.expectMsg(FinalMigrationSnapshotSavedEvent(id, batchId, 1L))
    val destroyedBefore = ActorMetrics.actorsDestroyed.labels(actorClass.getSimpleName).get()
    watcher.watch(original.ref)
    val shardId = (id.hashCode % 1000).toString
    allocator.forType(actorClass.getName).requestRebalance(shardId, region)
    watcher.expectTerminated(original.ref, 10.seconds)
    effects.expectNoMessage(100.millis)
    ActorMetrics.actorsDestroyed.labels(actorClass.getSimpleName).get() shouldBe destroyedBefore

    // Keep the JVM flag off for unrelated suites; explicitly query the real store after reallocation.
    control.send(region, EntityEnvelopeEvent(id, ObserveHandoffActor(effects.ref)))
    control.send(region, EntityEnvelopeEvent(id, QueryHandoffRestore(store)))
    control.expectMsg(MigrationRestoreAcceptedEvent(id, batchId))
    control.send(region, EntityEnvelopeEvent(id, QueryHandoffRestore(store)))
    control.expectMsg(MigrationRestoreAcceptedEvent(id, batchId))
    control.send(region, EntityEnvelopeEvent(id, ReadHandoffSnapshot))
    val restored = control.expectMsgType[HandoffSnapshot]
    restored.ref should not be original.ref
    // The existing restore hook merges the Lamport clock; all other captured fields must survive.
    restored.snapshot.copy(lamportClock = original.snapshot.lamportClock) shouldBe original.snapshot
    effects.expectNoMessage(100.millis)

    watcher.watch(restored.ref)
    control.send(region, EntityEnvelopeEvent(id, DestructEvent(actorRef = "")))
    watcher.expectTerminated(restored.ref, 5.seconds)
    ActorMetrics.actorsDestroyed.labels(actorClass.getSimpleName).get() shouldBe destroyedBefore + 1.0
    val cleanup = effects.receiveWhile(1.second, 100.millis) { case message => message }
    cleanup should contain(ModelDestructed)
    if (actorClass == classOf[HandoffLifecycleCar]) {
      val completions = cleanup.collect { case event: TripCompletedData => event }
      completions.size shouldBe 1
      completions.head.personId shouldBe 71L
    } else {
      cleanup.collect { case event: BusUnloadPassengerData => event.isArrival } shouldBe Seq(false)
    }
  }

  "a Pekko shard handoff" should "stop an active Car without finalizing its journey or completing its owner's trip" in {
    runHandoff(classOf[HandoffLifecycleCar], "601")
  }

  it should "stop a boarded Person without answering the vehicle's unload barrier" in {
    runHandoff(classOf[HandoffLifecyclePerson], "602")
  }

  private def counterRegion(id: String, control: TestProbe): ActorRef =
    ActorCreatorUtil.createShardRegion(system, classOf[HandoffCounterActor].getName, id, "handoff",
      mutable.Map.empty, control.ref, mutable.Map.empty[ReportTypeEnum, ActorRef])

  it should "drain frozen inputs with their original sender and save the final cut before stopping" in {
    val control = TestProbe(); val saves = TestProbe(); val watcher = TestProbe()
    val allocator = LoadBalanceShardAllocator()
    ShardAllocatorRegistry.register(allocator)
    MigrationStateStoreRegistry.registerSnapshotManager(saves.ref)
    val id = "603"
    val region = counterRegion(id, control)
    control.send(region, EntityEnvelopeEvent(id, ReadHandoffCounter))
    val original = control.expectMsgType[HandoffCounter](10.seconds)
    val preparation = PrepareForMigrationEvent((id.hashCode % 1000).toString, "target", "frozen", control.ref)
    control.send(region, EntityEnvelopeEvent(id, preparation))
    val captured = saves.expectMsgType[SaveMigrationSnapshotEvent]
    control.send(region, EntityEnvelopeEvent(id, IncrementHandoffCounter))
    control.expectNoMessage(100.millis)
    control.send(region, EntityEnvelopeEvent(id, preparation))
    saves.expectMsg(captured)
    watcher.watch(original.ref)
    allocator.forType(classOf[HandoffCounterActor].getName).requestRebalance(preparation.shardId, region)
    control.expectMsg(HandoffCounter(original.ref, 1L))
    val finalSave = saves.expectMsgType[SaveFinalMigrationSnapshotEvent](10.seconds)
    core.util.JsonUtil.fromJson[DefaultState](finalSave.snapshot.stateJson).eventsAmount shouldBe 1L
    watcher.expectNoMessage(100.millis)
    saves.reply(FinalMigrationSnapshotSavedEvent(id, "old-batch", finalSave.revision))
    watcher.expectNoMessage(100.millis)
    // Inject a late direct input while the final save is outstanding; normal modeled sends use the region.
    control.send(original.ref, IncrementHandoffCounter)
    control.expectNoMessage(100.millis)
    saves.reply(FinalMigrationSnapshotSavedEvent(id, finalSave.batchId, finalSave.revision))
    saves.reply(FinalMigrationSnapshotSavedEvent(id, finalSave.batchId, finalSave.revision))
    control.expectMsg(HandoffCounter(original.ref, 2L))
    val nextFinal = saves.expectMsgType[SaveFinalMigrationSnapshotEvent]
    nextFinal.revision shouldBe 2L
    core.util.JsonUtil.fromJson[DefaultState](nextFinal.snapshot.stateJson).eventsAmount shouldBe 2L
    saves.reply(FinalMigrationSnapshotSavedEvent(id, finalSave.batchId, finalSave.revision))
    watcher.expectNoMessage(100.millis)
    saves.reply(FinalMigrationSnapshotSavedEvent(id, nextFinal.batchId, nextFinal.revision))
    watcher.expectTerminated(original.ref, 10.seconds)
    // This fixture keeps the JVM migration flag off and explicitly drives the normal restore hook.
    control.send(region, EntityEnvelopeEvent(id, MigrationContextEvent(
      mutable.Map.empty, mutable.Map.empty, nextFinal.snapshot, nextFinal.batchId, control.ref,
      saves.ref, "counter-restore", nextFinal.revision)))
    saves.expectMsg(MigrationRestoredAckEvent(id, nextFinal.batchId, "counter-restore", nextFinal.revision))
    control.send(region, EntityEnvelopeEvent(id, ReadHandoffCounter))
    val restored = control.expectMsgType[HandoffCounter]
    restored.ref should not be original.ref
    restored.value shouldBe 2L
    control.expectNoMessage(100.millis)
  }

  it should "release retained inputs only for the matching preparation abort" in {
    val control = TestProbe(); val saves = TestProbe()
    MigrationStateStoreRegistry.registerSnapshotManager(saves.ref)
    val id = "604"
    val region = counterRegion(id, control)
    control.send(region, EntityEnvelopeEvent(id, ReadHandoffCounter))
    val original = control.expectMsgType[HandoffCounter](10.seconds)
    control.send(region, EntityEnvelopeEvent(id, PrepareForMigrationEvent("source", "target", "abort", control.ref)))
    saves.expectMsgType[SaveMigrationSnapshotEvent]
    control.send(region, EntityEnvelopeEvent(id, IncrementHandoffCounter))
    control.send(region, EntityEnvelopeEvent(id, AbortMigrationPreparationEvent("stale")))
    control.expectNoMessage(100.millis)
    control.send(region, EntityEnvelopeEvent(id, AbortMigrationPreparationEvent("abort")))
    control.expectMsg(HandoffCounter(original.ref, 1L))
    control.send(region, EntityEnvelopeEvent(id, ReadHandoffCounter))
    control.expectMsg(HandoffCounter(original.ref, 1L))
  }

  it should "reject missing storage and capture failures without freezing the source" in {
    val control = TestProbe(); val saves = TestProbe()
    MigrationStateStoreRegistry.clear()
    val id = "605"
    val region = counterRegion(id, control)
    control.send(region, EntityEnvelopeEvent(id, ReadHandoffCounter))
    val original = control.expectMsgType[HandoffCounter](10.seconds)
    val preparation = PrepareForMigrationEvent("source", "target", "failed", control.ref)
    control.send(region, EntityEnvelopeEvent(id, preparation))
    control.expectMsgType[MigrationPreparationFailedEvent].reason should include("not registered")
    control.send(region, EntityEnvelopeEvent(id, IncrementHandoffCounter))
    control.expectMsg(HandoffCounter(original.ref, 1L))
    MigrationStateStoreRegistry.registerSnapshotManager(saves.ref)
    control.send(region, EntityEnvelopeEvent(id, FailHandoffCapture))
    control.expectMsg(HandoffCounter(original.ref, 1L))
    control.send(region, EntityEnvelopeEvent(id, preparation))
    control.expectMsgType[MigrationPreparationFailedEvent].reason should include("controlled capture failure")
    saves.expectNoMessage(100.millis)
    control.send(region, EntityEnvelopeEvent(id, IncrementHandoffCounter))
    control.expectMsg(HandoffCounter(original.ref, 2L))
  }

  it should "report a final capture failure and stop directly while the region buffers the shard" in {
    val control = TestProbe(); val saves = TestProbe(); val watcher = TestProbe()
    MigrationStateStoreRegistry.registerSnapshotManager(saves.ref)
    val id = "606"
    val region = counterRegion(id, control)
    control.send(region, EntityEnvelopeEvent(id, ReadHandoffCounter))
    val original = control.expectMsgType[HandoffCounter](10.seconds)
    val preparation = PrepareForMigrationEvent((id.hashCode % 1000).toString, "target", "final-failure", control.ref)
    control.send(region, EntityEnvelopeEvent(id, preparation))
    saves.expectMsgType[SaveMigrationSnapshotEvent]
    control.send(region, EntityEnvelopeEvent(id, FailHandoffCapture))
    control.expectNoMessage(100.millis)
    watcher.watch(original.ref)
    ShardAllocatorRegistry.get.get.forType(classOf[HandoffCounterActor].getName).requestRebalance(preparation.shardId, region)
    control.expectMsg(HandoffCounter(original.ref, 0L))
    control.expectMsgType[MigrationPreparationFailedEvent].reason should include("controlled capture failure")
    watcher.expectTerminated(original.ref, 10.seconds)
    saves.expectNoMessage(100.millis)
  }
}
