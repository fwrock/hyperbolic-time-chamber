package org.interscity.htc
package core.actor.manager.loadbalance.allocation

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ ActorRef, ActorSystem, Props }
import org.apache.pekko.cluster.{ Cluster, MemberStatus }
import org.apache.pekko.testkit.{ TestKit, TestProbe }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

class AllocationAuthoritySpec extends AnyFlatSpec with Matchers with ScalaFutures with BeforeAndAfterAll {
  private val config = ConfigFactory.parseString("""
    pekko.actor.provider=cluster
    pekko.actor.fail-mixed-versions=off
    pekko.remote.artery.canonical.hostname="127.0.0.1"
    pekko.remote.artery.canonical.port=0
    pekko.cluster.seed-nodes=[]
    pekko.cluster.jmx.multi-mbeans-in-same-jvm=on
    htc.load-balance-manager.enabled=false
  """).withFallback(ConfigFactory.load())
  private implicit val system: ActorSystem = ActorSystem("AllocationAuthoritySpec", config)
  private val otherSystem = ActorSystem("AllocationAuthoritySpec", config)
  private val here = Cluster(system).selfAddress.toString
  private val there = Cluster(otherSystem).selfAddress.toString
  override def beforeAll(): Unit = {
    Cluster(system).join(Cluster(system).selfAddress)
    TestProbe().awaitAssert(Cluster(system).selfMember.status shouldBe MemberStatus.Up, 10.seconds, 30.millis)
    Cluster(otherSystem).join(Cluster(system).selfAddress)
    TestProbe().awaitAssert(Cluster(system).state.members.count(_.status == MemberStatus.Up) shouldBe 2, 15.seconds, 30.millis)
  }
  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(otherSystem, 15.seconds)
    TestKit.shutdownActorSystem(system, 15.seconds)
  }

  private class Session {
    val wire = TestProbe(); val controller = TestProbe(); val source = TestProbe(); val target = TestProbe()(otherSystem)
    val authority = system.actorOf(AllocationAuthority.props())
    wire.send(authority, BindAllocationController("bind", "", controller.ref, "controller"))
    val epoch = wire.expectMsgType[AllocationControllerBound].epoch
    val rows = Vector(AllocationRegion(source.ref, here, Vector("move")), AllocationRegion(target.ref, there, Vector.empty))
    wire.send(authority, RegisterAllocationRegion("source", epoch, "Car", here, "source-client", source.ref))
    wire.expectMsgType[AllocationRegionRegistered]
    wire.send(authority, RegisterAllocationRegion("target", epoch, "Car", there, "target-client", target.ref))
    wire.expectMsgType[AllocationRegionRegistered]
    def rebalance(sequence: Long, state: Vector[AllocationRegion] = rows, inProgress: Set[String] = Set.empty): Set[String] = {
      wire.send(authority, RebalanceShardsRequest(s"rebalance-$sequence", epoch, "Car", here, "source-client", sequence, state, inProgress))
      wire.expectMsgType[RebalanceShardsReply].shards
    }
    rebalance(1L) shouldBe empty
    wire.send(authority, ResolveAllocationPlacement("resolve", epoch, "controller", "move", here, there, Vector("Car")))
    val targets = wire.expectMsgType[AllocationPlacementResolved].targets
    val install = InstallAllocationIntent("install", epoch, "controller", "batch", "attempt", "move", here, there, targets)
    def installNow(): Unit = { wire.send(authority, install); wire.expectMsgType[AllocationIntentInstalled] }
  }

  "allocation authority" should "retain intents across repeated callbacks and exclude arrived or in-progress shards" in {
    val s = new Session
    s.installNow()
    s.wire.send(s.authority, s.install.copy(requestId = "duplicate-install"))
    s.wire.expectMsgType[AllocationIntentInstalled]
    s.rebalance(2L) shouldBe Set("move")
    s.rebalance(3L) shouldBe Set("move")
    s.rebalance(4L, inProgress = Set("move")) shouldBe empty
    val arrived = Vector(AllocationRegion(s.source.ref, here, Vector.empty), AllocationRegion(s.target.ref, there, Vector("move")))
    s.rebalance(5L, arrived) shouldBe empty
    s.rebalance(2L) shouldBe empty
    s.wire.send(s.authority, AllocateShardRequest("allocate", s.epoch, "Car", here, "source-client", 6L,
      s.source.ref, "move", s.rows))
    s.wire.expectMsgType[AllocateShardReply].region shouldBe s.target.ref
  }

  it should "retire idempotently without letting an old retirement clear a later reverse move" in {
    val s = new Session
    s.installNow()
    val retire = RetireAllocationIntent("retire", s.epoch, "controller", "batch", "attempt", "move")
    s.wire.send(s.authority, retire); s.wire.expectMsgType[AllocationIntentRetired]
    s.wire.send(s.authority, retire.copy(requestId = "repeat")); s.wire.expectMsgType[AllocationIntentRetired]
    val arrived = Vector(AllocationRegion(s.source.ref, here, Vector.empty), AllocationRegion(s.target.ref, there, Vector("move")))
    s.rebalance(2L, arrived) shouldBe empty
    s.wire.send(s.authority, ResolveAllocationPlacement("reverse", s.epoch, "controller", "move", there, here, Vector("Car")))
    val targets = s.wire.expectMsgType[AllocationPlacementResolved].targets
    s.wire.send(s.authority, InstallAllocationIntent("next", s.epoch, "controller", "next-batch", "next-attempt", "move", there, here, targets))
    s.wire.expectMsgType[AllocationIntentInstalled]
    s.wire.send(s.authority, retire)
    s.wire.expectMsgType[AllocationRejected]
    s.rebalance(3L, arrived) shouldBe Set("move")
    s.wire.send(s.authority, AllocateShardRequest("next-target", s.epoch, "Car", here, "source-client", 4L,
      s.target.ref, "move", arrived))
    s.wire.expectMsgType[AllocateShardReply].region shouldBe s.source.ref
  }

  it should "fail closed if the callback owner or authority epoch changes" in {
    val s = new Session
    s.wire.send(s.authority, RebalanceShardsRequest("wrong-owner", s.epoch, "Car", there, "target-client", 1L, s.rows, Set.empty))
    s.wire.expectMsgType[AllocationRejected].reason should include("owner changed")
    s.controller.expectMsgType[AllocationAuthorityFailure]
    s.wire.send(s.authority, AllocateShardRequest("later", s.epoch, "Car", here, "source-client", 2L,
      s.source.ref, "fresh", s.rows))
    s.wire.expectMsgType[AllocationRejected]
    val other = new Session
    other.wire.send(other.authority, AllocateShardRequest("wrong-epoch", "old", "Car", here, "source-client", 2L,
      other.source.ref, "fresh", other.rows))
    other.wire.expectMsgType[AllocationRejected]
    other.controller.expectMsgType[AllocationAuthorityFailure]
  }

  it should "reject conflicting registrations and controller replacement" in {
    val s = new Session
    s.wire.send(s.authority, RegisterAllocationRegion("replacement", s.epoch, "Car", here, "new-client", s.source.ref))
    s.wire.expectMsgType[AllocationRejected]
    s.controller.expectMsgType[AllocationAuthorityFailure]
    val other = new Session
    other.wire.send(other.authority, BindAllocationController("replacement", other.epoch, other.controller.ref, "new-controller"))
    other.wire.expectMsgType[AllocationRejected]
    other.controller.expectMsgType[AllocationAuthorityFailure]
  }

  it should "install all physical types atomically without leaving a partial placement on rejection" in {
    val s = new Session
    s.wire.send(s.authority, s.install.copy(targets = s.targets :+ AllocationTarget("Bus", s.source.ref, s.target.ref)))
    s.wire.expectMsgType[AllocationRejected]
    s.rebalance(2L) shouldBe empty
    val preferSource = Vector(AllocationRegion(s.source.ref, here, Vector("move")),
      AllocationRegion(s.target.ref, there, Vector("a", "b", "c")))
    s.wire.send(s.authority, AllocateShardRequest("no-partial-target", s.epoch, "Car", here, "source-client", 3L,
      s.source.ref, "move", preferSource))
    s.wire.expectMsgType[AllocateShardReply].region shouldBe s.source.ref
    s.controller.expectNoMessage(100.millis)
  }

  it should "reject an unavailable explicit target without falling back or dropping its intent" in {
    val s = new Session
    s.installNow()
    s.wire.send(s.authority, AllocateShardRequest("missing-target", s.epoch, "Car", here, "source-client", 2L,
      s.source.ref, "move", Vector(s.rows.head)))
    s.wire.expectMsgType[AllocationRejected].reason should include("unavailable")
    s.rebalance(3L) shouldBe Set("move")
    s.controller.expectNoMessage(100.millis)
  }

  "node registration" should "gate callbacks until ACK and pin the authority epoch" in {
    val authority = TestProbe(); val driver = TestProbe(); val region = TestProbe()
    val client = system.actorOf(Props(classOf[AllocationNodeClient], authority.ref, Cluster(system).selfAddress))
    driver.send(client, LocalAllocationRegion("Car", region.ref))
    val registered = authority.expectMsgType[RegisterAllocationRegion]
    val callback = LocalAllocate("allocate", "Car", 1L, region.ref, "move", Vector(AllocationRegion(region.ref, here, Vector.empty)))
    driver.send(client, callback)
    driver.expectMsgType[AllocationRejected]
    authority.send(client, AllocationRegionRegistered(registered.requestId, "epoch", "Car"))
    driver.send(client, callback)
    val forwarded = authority.expectMsgType[AllocateShardRequest]
    forwarded.epoch shouldBe "epoch"
    forwarded.incarnation shouldBe registered.incarnation
    authority.reply(AllocateShardReply(forwarded.requestId, "epoch", region.ref))
    driver.expectMsgType[AllocateShardReply]
    authority.send(client, AllocationRegionRegistered(registered.requestId, "replacement-epoch", "Car"))
    driver.send(client, callback.copy(requestId = "later"))
    driver.expectMsgType[AllocationRejected].reason should include("epoch changed")
  }

  "remote strategy adapter" should "correlate callback replies and preserve callback sequence without choosing a local fallback" in {
    val client = TestProbe(); val region = TestProbe()
    val adapter = new RemoteShardAllocator(client.ref, "Car", Cluster(system).selfAddress)
    val result = adapter.allocateShard(region.ref, "move", Map(region.ref -> Vector.empty))
    val request = client.expectMsgType[LocalAllocate]
    request.sequence shouldBe 1L
    client.reply(AllocateShardReply("wrong-request", "epoch", region.ref))
    result.failed.futureValue shouldBe a[IllegalStateException]
    val next = adapter.rebalance(Map(region.ref -> Vector.empty), Set.empty)
    val second = client.expectMsgType[LocalRebalance]
    second.sequence shouldBe 2L
    client.reply(RebalanceShardsReply(second.requestId, "epoch", Set("move")))
    next.futureValue shouldBe Set("move")
  }

  "disabled custom allocation" should "keep its extension passive and reject authority requests" in {
    val runtime = AllocationRuntime(system)
    runtime.enabled shouldBe false
    runtime.start()
    intercept[IllegalArgumentException](runtime.status()).getMessage should include("disabled")
    intercept[IllegalArgumentException](runtime.request(GetAllocationAuthorityStatus("id", "epoch")))
  }
}
