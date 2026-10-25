package org.interscity.htc
package model.test

import core.actor.manager.SnapshotManager
import core.actor.manager.loadbalance.LoadBalanceManager
import core.actor.manager.loadbalance.allocation.*
import core.actor.manager.loadbalance.migration.{ MigrationStateStoreRegistry, MigrationWindowSubscriber }
import core.actor.manager.loadbalance.strategy.StrategyConfig
import core.entity.control.loadbalance.{ MigrationPlan, MigrationReason, SpatialBounds }
import core.entity.event.EntityEnvelopeEvent
import core.entity.event.control.loadbalance.*
import core.enumeration.{ LoadBalanceStrategyEnum, ReportTypeEnum }
import core.util.{ ActorCreatorUtil, JsonUtil }
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ Actor, ActorRef, ActorSystem, AddressFromURIString, Props }
import org.apache.pekko.cluster.{ Cluster, MemberStatus }
import org.apache.pekko.cluster.pubsub.DistributedPubSub
import org.apache.pekko.cluster.pubsub.DistributedPubSubMediator.{ Publish, SubscribeAck }
import org.apache.pekko.cluster.sharding.ShardRegion
import org.apache.pekko.cluster.singleton.{ ClusterSingletonManager, ClusterSingletonManagerSettings }
import org.apache.pekko.testkit.TestProbe
import org.htc.protobuf.core.entity.event.control.execution.StopSimulationEvent
import org.scalatest.matchers.should.Matchers
import java.nio.file.{ Files, Path }
import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.*

sealed trait AllocationValidationWire
case class WindowTransportPing(replyTo: ActorRef) extends AllocationValidationWire
case class WindowTransportReady(address: String) extends AllocationValidationWire
case object ReadAllocationWorkerStatus extends AllocationValidationWire
case class AllocationWorkerStatus(pid: Long, localAllocatorAbsent: Boolean, spatialEntries: Int,
  migrationActive: Boolean) extends AllocationValidationWire

class AllocationProofSubscriber(observer: ActorRef) extends MigrationWindowSubscriber {
  override def receive: Receive = {
    case e: SubscribeAck => super.receive(e); observer ! "subscribed"
    case e: WindowTransportPing => e.replyTo ! WindowTransportReady(Cluster(context.system).selfAddress.toString)
    case e => super.receive(e)
  }
}
class AllocationProofManager(tm: ActorRef, simulation: ActorRef) extends LoadBalanceManager(
  tm, simulation, LoadBalanceStrategyEnum.Disabled, SpatialBounds(-1, -1, 1, 1), StrategyConfig())

class AllocationProofWorkerStatus extends Actor {
  override def receive: Receive = {
    case ReadAllocationWorkerStatus => sender() ! AllocationWorkerStatus(ProcessHandle.current().pid(),
      ShardAllocatorRegistry.get.isEmpty, SpatialShardIdRegistry.size, MigrationStateStoreRegistry.isMigrationActive.get())
  }
}

object AllocationAuthorityJvmProbe extends Matchers {
  private def writeJson(path: Path, value: Any): Unit = {
    val temporary = path.resolveSibling(path.getFileName.toString + ".tmp")
    Files.writeString(temporary, JsonUtil.toJson(value))
    Files.move(temporary, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
  }
  def main(args: Array[String]): Unit = {
    try run(args)
    catch {
      case scala.util.control.NonFatal(error) =>
        writeJson(Path.of(args(1)).resolve(args(0) + "-failure.json"),
          Map("passed" -> false, "reason" -> Option(error.getMessage).getOrElse(error.getClass.getName)))
        error.printStackTrace()
        System.exit(1)
    }
  }

  private def run(args: Array[String]): Unit = {
    val role = args(0)
    val output = Path.of(args(1))
    val config = ConfigFactory.parseString("""
      pekko.actor.provider=cluster
      pekko.actor.fail-mixed-versions=off
      pekko.remote.artery.canonical.hostname="127.0.0.1"
      pekko.remote.artery.canonical.port=0
      pekko.cluster.seed-nodes=[]
      pekko.cluster.sharding.rebalance-interval=100 ms
      pekko.cluster.singleton-proxy.singleton-identification-interval=100 ms
      htc.load-balance-manager.enabled=true
      pekko.actor.serialization-bindings {
        "org.interscity.htc.model.test.DestinationProbeCommand"=jackson-cbor
        "org.interscity.htc.model.test.AllocationValidationWire"=jackson-cbor
      }
    """).withFallback(ConfigFactory.load())
    implicit val system: ActorSystem = ActorSystem("AllocationAuthorityJvmProbe", config)
    val cluster = Cluster(system)
    val runtime = AllocationRuntime(system)
    runtime.start()
    val probe = TestProbe()
    if (role == "worker") cluster.join(cluster.selfAddress)
    else cluster.join(AddressFromURIString(args(2)))
    probe.awaitAssert(cluster.selfMember.status shouldBe MemberStatus.Up, 30.seconds, 50.millis)
    if (role != "worker") probe.awaitAssert(cluster.state.members.count(_.status == MemberStatus.Up) shouldBe 2, 30.seconds, 50.millis)
    system.actorOf(ClusterSingletonManager.props(SnapshotManager.props(system.deadLetters), StopSimulationEvent(),
      ClusterSingletonManagerSettings(system)), "snapshot-manager")
    val subscribed = TestProbe()
    system.actorOf(Props(classOf[AllocationProofSubscriber], subscribed.ref), "migration-window-subscriber")
    subscribed.expectMsg(20.seconds, "subscribed")
    system.actorOf(Props(classOf[AllocationProofWorkerStatus]), "allocation-proof-worker")
    val id = "801"
    val shardId = (id.hashCode % 1000).toString
    val name = classOf[DestinationCounterActor].getName
    val region = ActorCreatorUtil.createShardRegion(system, name, id, "authority-proof", mutable.Map.empty,
      probe.ref, mutable.Map.empty[ReportTypeEnum, ActorRef])
    probe.awaitAssert({
      val state = Await.result(runtime.status(), 5.seconds)
      state.failure shouldBe None
      state.registeredTypes should contain(name)
    }, 30.seconds, 50.millis)
    val client = Await.result(runtime.status(), 5.seconds)
    ShardAllocatorRegistry.get shouldBe None
    if (role == "worker") {
      writeJson(output.resolve("worker-ready.json"), Map(
        "pid" -> ProcessHandle.current().pid(), "address" -> cluster.selfAddress.toString,
        "epoch" -> client.epoch, "incarnation" -> client.incarnation, "spatial_entries" -> SpatialShardIdRegistry.size))
      Await.result(system.whenTerminated, Duration.Inf)
    } else {
      val worker = AddressFromURIString(args(2))
      val transport = TestProbe()
      var seen = Set.empty[String]
      val deadline = 20.seconds.fromNow
      while (seen.size < 2 && deadline.hasTimeLeft()) {
        DistributedPubSub(system).mediator ! Publish(MigrationWindowSubscriber.TOPIC, WindowTransportPing(transport.ref))
        seen ++= transport.receiveWhile(500.millis, 100.millis) { case WindowTransportReady(address) => address }
      }
      seen shouldBe Set(cluster.selfAddress.toString, worker.toString)
      SpatialShardIdRegistry.putShardId(id, shardId)
      SpatialShardIdRegistry.putEntityClassName(id, name)
      probe.awaitAssert({
        val state = Await.result(runtime.request(GetAllocationAuthorityStatus("regions-ready", client.epoch)), 10.seconds)
          .asInstanceOf[AllocationAuthorityStatus]
        state.allocations(name).map(_.address).toSet shouldBe Set(cluster.selfAddress.toString, worker.toString)
      }, 20.seconds, 50.millis)
      probe.send(region, EntityEnvelopeEvent(id, SetDestinationCounter(23L)))
      val original = probe.expectMsgType[DestinationCounterValue](20.seconds)
      probe.awaitAssert({
        val state = Await.result(runtime.request(GetAllocationAuthorityStatus("preflight", client.epoch)), 10.seconds)
          .asInstanceOf[AllocationAuthorityStatus]
        state.allocations(name).find(_.address == cluster.selfAddress.toString).get.shards should contain(shardId)
      }, 20.seconds, 50.millis)
      val tm = TestProbe(); val simulation = TestProbe(); val watcher = TestProbe()
      val manager = system.actorOf(Props(classOf[AllocationProofManager], tm.ref, simulation.ref), "proof-controller")
      simulation.expectMsgType[LoadBalanceReadyEvent](20.seconds)
      watcher.watch(original.owner)
      probe.send(manager, RequestMigrationEvent(MigrationPlan(shardId, cluster.selfAddress, worker, MigrationReason.Rebalance)))
      tm.expectMsgType[RequestMigrationPauseEvent].shardIds shouldBe Set(shardId)
      tm.send(manager, MigrationSafeEvent(0L))
      watcher.expectTerminated(original.owner, 30.seconds)
      tm.expectMsgType[MigrationCompleteNotifyEvent](30.seconds)
      probe.send(region, EntityEnvelopeEvent(id, ReadDestinationCounter))
      val restored = probe.expectMsgType[DestinationCounterValue](10.seconds)
      restored.value shouldBe 23L
      restored.owner.path.address shouldBe worker
      probe.send(region, ShardRegion.GetShardRegionState)
      probe.expectMsgType[ShardRegion.CurrentShardRegionState].shards.map(_.shardId) should not contain shardId
      system.actorSelection(s"$worker/system/sharding/$name").tell(ShardRegion.GetShardRegionState, probe.ref)
      probe.expectMsgType[ShardRegion.CurrentShardRegionState](10.seconds).shards.map(_.shardId) should contain(shardId)
      system.actorSelection(s"$worker/user/allocation-proof-worker").tell(ReadAllocationWorkerStatus, probe.ref)
      val peer = probe.expectMsgType[AllocationWorkerStatus]
      peer.localAllocatorAbsent shouldBe true
      peer.spatialEntries shouldBe 0
      peer.migrationActive shouldBe false
      MigrationStateStoreRegistry.isMigrationActive.get() shouldBe false
      val authority = Await.result(runtime.request(GetAllocationAuthorityStatus("proof", client.epoch)), 10.seconds)
        .asInstanceOf[AllocationAuthorityStatus]
      authority.callbackOwners(name) shouldBe worker.toString
      authority.controllerAddress shouldBe Some(cluster.selfAddress.toString)
      authority.registered(name) shouldBe Set(worker.toString, cluster.selfAddress.toString)
      authority.failure shouldBe None
      tm.expectNoMessage(200.millis)
      writeJson(output.resolve("result.json"), Map(
        "passed" -> true, "controller_pid" -> ProcessHandle.current().pid(), "worker_pid" -> peer.pid,
        "controller_address" -> cluster.selfAddress.toString, "callback_owner" -> authority.callbackOwners(name),
        "restored_owner" -> restored.owner.path.address.toString, "restored_value" -> restored.value,
        "epoch" -> client.epoch, "controller_incarnation" -> client.incarnation,
        "worker_spatial_entries" -> peer.spatialEntries, "local_allocator_absent" -> ShardAllocatorRegistry.get.isEmpty,
        "worker_allocator_absent" -> peer.localAllocatorAbsent, "window_closed_on_both_nodes" -> true,
        "routing_scope" -> "hash shard IDs; controller-only membership fixture", "time_manager" -> "explicit conservative pause probe"))
      // The launcher captures completion evidence before tearing down its own child processes.
      Await.result(system.whenTerminated, Duration.Inf)
    }
  }
}
