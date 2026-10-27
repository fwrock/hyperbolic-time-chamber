package org.interscity.htc
package core.actor.manager.loadbalance.allocation

import core.actor.manager.loadbalance.LoadBalanceTestIsolation
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ ActorRef, ActorSystem, Props }
import org.apache.pekko.cluster.{ Cluster, MemberStatus }
import org.apache.pekko.testkit.{ TestKit, TestProbe }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Await
import scala.concurrent.duration.*

class SpatialDirectorySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private val config = ConfigFactory.parseString("""
    pekko.actor.provider=cluster
    pekko.actor.fail-mixed-versions=off
    pekko.remote.artery.canonical.hostname="127.0.0.1"
    pekko.remote.artery.canonical.port=0
    pekko.cluster.seed-nodes=[]
    pekko.cluster.jmx.multi-mbeans-in-same-jvm=on
    htc.load-balance-manager.enabled=false
  """).withFallback(ConfigFactory.load())
  private implicit val system: ActorSystem = ActorSystem("SpatialDirectorySpec", config)
  private val peer = ActorSystem("SpatialDirectorySpec", config)
  private val here = Cluster(system).selfAddress.toString
  private val there = Cluster(peer).selfAddress.toString
  private val row = SpatialAssignment("801", "dynamic-qt-shard-0-0", "example.Car", 1, 2)

  override def beforeAll(): Unit = {
    LoadBalanceTestIsolation.acquire()
    Cluster(system).join(Cluster(system).selfAddress)
    TestProbe().awaitAssert(Cluster(system).selfMember.status shouldBe MemberStatus.Up, 10.seconds, 20.millis)
    Cluster(peer).join(Cluster(system).selfAddress)
    TestProbe().awaitAssert(Cluster(system).state.members.count(_.status == MemberStatus.Up) shouldBe 2, 15.seconds, 30.millis)
  }
  override def afterAll(): Unit = try {
    TestKit.shutdownActorSystem(peer, 15.seconds)
    TestKit.shutdownActorSystem(system, 15.seconds)
    SpatialShardIdRegistry.clear()
  } finally LoadBalanceTestIsolation.release()

  private class Session(registerPeer: Boolean = true) {
    val authority = system.actorOf(Props(classOf[SpatialDirectoryAuthority]))
    val remoteAuthority = Await.result(peer.actorSelection(authority.path.toStringWithAddress(Cluster(system).selfAddress))
      .resolveOne(5.seconds), 5.seconds)
    val left = TestProbe(); val right = TestProbe()(peer); val driver = TestProbe()
    left.send(authority, RegisterSpatialReplica("left", here, "left-client", left.ref))
    val initial = left.expectMsgType[InstallSpatialAssignments]
    val epoch = initial.epoch
    ackLeft(initial)
    left.expectMsgType[SpatialReplicaRegistered]
    if (registerPeer) enrollPeer()
    def enrollPeer(): Unit = {
      right.send(remoteAuthority, RegisterSpatialReplica("right", there, "right-client", right.ref))
      val initial = right.expectMsgType[InstallSpatialAssignments]
      ackRight(initial)
      right.expectMsgType[SpatialReplicaRegistered]
    }
    def ackLeft(e: InstallSpatialAssignments): Unit = left.send(authority,
      SpatialAssignmentsInstalled(e.requestId, e.epoch, e.revision, here, "left-client"))
    def ackRight(e: InstallSpatialAssignments): Unit = right.send(remoteAuthority,
      SpatialAssignmentsInstalled(e.requestId, e.epoch, e.revision, there, "right-client"))
    def request(id: String = "publish", rows: Vector[SpatialAssignment] = Vector(row)) =
      PublishSpatialAssignments(id, epoch, here, "left-client", rows)
    def publish(id: String = "publish", rows: Vector[SpatialAssignment] = Vector(row)): Unit =
      driver.send(authority, request(id, rows))
    def commit(id: String = "publish"): SpatialAssignmentsPublished = {
      val a = left.expectMsgType[InstallSpatialAssignments]
      val b = right.expectMsgType[InstallSpatialAssignments]
      a.requestId shouldBe id
      a shouldBe b
      ackLeft(a); ackRight(b)
      driver.expectMsgType[SpatialAssignmentsPublished]
    }
  }

  "spatial publication" should "wait for every replica and reject stale or foreign acknowledgements" in {
    val s = new Session
    s.publish()
    val a = s.left.expectMsgType[InstallSpatialAssignments]
    val b = s.right.expectMsgType[InstallSpatialAssignments]
    s.ackLeft(a)
    s.driver.expectNoMessage(100.millis)
    s.driver.send(s.authority, SpatialAssignmentsInstalled(b.requestId, b.epoch, b.revision, there, "right-client"))
    s.right.send(s.remoteAuthority, SpatialAssignmentsInstalled(b.requestId, "old", b.revision, there, "right-client"))
    s.right.send(s.remoteAuthority, SpatialAssignmentsInstalled(b.requestId, b.epoch, 0L, there, "right-client"))
    s.driver.expectNoMessage(100.millis)
    s.ackRight(b)
    s.driver.expectMsg(SpatialAssignmentsPublished("publish", s.epoch, 1L))
  }

  it should "wait for an Up member's replica to register and hydrate before installing a publication" in {
    val s = new Session(registerPeer = false)
    s.publish()
    s.left.expectNoMessage(100.millis)
    s.driver.expectNoMessage(100.millis)
    s.enrollPeer()
    s.commit().revision shouldBe 1L
  }

  it should "serialize publications and replay duplicate receipts without advancing revision" in {
    val s = new Session
    s.publish()
    s.publish("next", Vector(row.copy(entityId = "802")))
    s.publish("next", Vector(row.copy(entityId = "802")))
    s.commit().revision shouldBe 1L
    s.commit("next").revision shouldBe 2L
    s.driver.expectMsg(SpatialAssignmentsPublished("next", s.epoch, 2L))
    s.publish()
    s.driver.expectMsg(SpatialAssignmentsPublished("publish", s.epoch, 1L))
    s.left.expectNoMessage(100.millis)
  }

  it should "reject conflicting assignments without installing a partial batch" in {
    val s = new Session
    s.publish(); s.commit()
    s.publish("conflict", Vector(row.copy(entityId = "802"), row.copy(shardId = "changed")))
    s.driver.expectMsgType[SpatialDirectoryRejected].reason should include("changed")
    s.left.expectMsgType[SpatialDirectoryFailed]
    s.right.expectMsgType[SpatialDirectoryFailed]
  }

  it should "abort an unacknowledged publication when a replica is lost" in {
    val s = new Session
    s.publish()
    s.left.expectMsgType[InstallSpatialAssignments]
    val b = s.right.expectMsgType[InstallSpatialAssignments]
    s.ackRight(b)
    system.stop(s.left.ref)
    s.driver.expectMsgType[SpatialDirectoryRejected].reason should include("replica lost")
    s.right.expectMsgType[SpatialDirectoryFailed]
  }

  it should "fail closed on a replacement replica or stale publisher epoch" in {
    val s = new Session
    s.left.send(s.authority, RegisterSpatialReplica("replacement", here, "new-client", s.left.ref))
    s.left.expectMsgType[SpatialDirectoryRejected]
    s.left.expectMsgType[SpatialDirectoryFailed]
    val other = new Session
    other.driver.send(other.authority, other.request().copy(epoch = "old"))
    other.driver.expectMsgType[SpatialDirectoryRejected]
    other.left.expectMsgType[SpatialDirectoryFailed]
  }

  "spatial registry installation" should "apply a complete revision atomically and enforce type-aware routing" in {
    SpatialShardIdRegistry.clear()
    SpatialShardIdRegistry.installDirectory("epoch", 0L, Vector.empty, replace = true)
    SpatialShardIdRegistry.installDirectory("epoch", 1L, Vector(row), replace = false)
    SpatialShardIdRegistry.requirePublishedShardId(row.entityId, row.typeName) shouldBe row.shardId
    SpatialShardIdRegistry.confirmsPublication("epoch", 1L, Vector(row)) shouldBe true
    SpatialShardIdRegistry.confirmsPublication("old", 1L, Vector(row)) shouldBe false
    SpatialShardIdRegistry.confirmsPublication("epoch", 2L, Vector(row)) shouldBe false
    SpatialShardIdRegistry.getEntityClassName(row.entityId) shouldBe Some(row.typeName)
    SpatialShardIdRegistry.getEntitiesInShard(row.shardId) shouldBe Set(row.entityId)
    SpatialShardIdRegistry.getPosition(row.entityId) shouldBe Some(1.0 -> 2.0)
    intercept[IllegalArgumentException](SpatialShardIdRegistry.requirePublishedShardId(row.entityId, "other.Type"))
    intercept[IllegalStateException](SpatialShardIdRegistry.requirePublishedShardId("unknown", row.typeName))
    intercept[IllegalArgumentException](SpatialShardIdRegistry.installDirectory("epoch", 2L,
      Vector(row.copy(entityId = "new"), row.copy(shardId = "changed")), replace = false))
    SpatialShardIdRegistry.getShardId("new") shouldBe None
    SpatialShardIdRegistry.directoryRevision shouldBe 1L
  }

  it should "reject gaps changed epochs and conflicting repeated revisions" in {
    SpatialShardIdRegistry.clear()
    SpatialShardIdRegistry.installDirectory("epoch", 0L, Vector.empty, replace = true)
    intercept[IllegalArgumentException](SpatialShardIdRegistry.installDirectory("epoch", 2L, Vector(row), replace = false))
    intercept[IllegalArgumentException](SpatialShardIdRegistry.installDirectory("other", 1L, Vector(row), replace = false))
    SpatialShardIdRegistry.installDirectory("epoch", 1L, Vector(row), replace = false)
    SpatialShardIdRegistry.installDirectory("epoch", 1L, Vector(row), replace = false)
    intercept[IllegalArgumentException](SpatialShardIdRegistry.installDirectory("epoch", 1L,
      Vector(row.copy(shardId = "changed")), replace = false))
    intercept[IllegalArgumentException](SpatialShardIdRegistry.putShardId(row.entityId, "hash"))
    intercept[IllegalArgumentException](SpatialShardIdRegistry.putEntityClassName(row.entityId, "other.Type"))
    SpatialShardIdRegistry.requirePublishedShardId(row.entityId, row.typeName) shouldBe row.shardId
  }

  "spatial replica" should "hydrate before forwarding publication requests and reject authority replacement" in {
    SpatialShardIdRegistry.clear()
    val authority = TestProbe(); val driver = TestProbe(); val observer = TestProbe()
    val replica = system.actorOf(Props(classOf[SpatialDirectoryReplica], authority.ref, Cluster(system).selfAddress))
    val registration = authority.expectMsgType[RegisterSpatialReplica]
    driver.send(replica, ObserveSpatialDirectory(observer.ref))
    driver.send(replica, LocalPublishSpatial("publication", Vector(row)))
    authority.expectNoMessage(100.millis)
    authority.send(replica, InstallSpatialAssignments(registration.requestId, "epoch", 0L, Vector.empty, replace = true))
    val ack = authority.expectMsgType[SpatialAssignmentsInstalled]
    ack.incarnation shouldBe registration.incarnation
    authority.send(replica, SpatialReplicaRegistered(registration.requestId, "epoch", 0L))
    authority.expectMsgType[PublishSpatialAssignments].requestId shouldBe "publication"
    val replacement = TestProbe()
    replacement.send(replica, InstallSpatialAssignments("later", "other", 1L, Vector(row), replace = false))
    replacement.expectMsgType[SpatialInstallFailed]
    observer.expectMsgType[SpatialDirectoryFailed]
    driver.send(replica, LocalPublishSpatial("after-failure", Vector(row)))
    driver.expectMsgType[SpatialDirectoryRejected]
    system.stop(replica)
  }

  "disabled spatial runtime" should "remain passive" in {
    val runtime = SpatialDirectoryRuntime(system)
    runtime.enabled shouldBe false
    runtime.start()
    intercept[IllegalArgumentException](runtime.status())
    intercept[IllegalArgumentException](runtime.publish("id", Vector(row)))
  }
}
