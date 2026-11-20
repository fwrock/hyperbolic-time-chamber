package org.interscity.htc
package core.util

import com.typesafe.config.ConfigFactory
import core.entity.event.EntityEnvelopeEvent
import io.prometheus.client.CollectorRegistry
import org.apache.pekko.actor.{Actor, ActorSystem, Props}
import org.apache.pekko.cluster.sharding.ShardRegion
import org.apache.pekko.testkit.{ImplicitSender, TestKit}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

class LocalEntityRouterSpec extends TestKit(ActorSystem("LocalEntityRouterSpec",
  ConfigFactory.parseString(
    """pekko.actor.provider=local
      |pekko.actor.fail-mixed-versions=off
      |htc.runtime.mode=single-node""".stripMargin).withFallback(ConfigFactory.load())))
  with ImplicitSender with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val echo = Props(new Actor {
    override def receive: Receive = {
      case _: ShardRegion.StartEntity =>
      case payload                    => sender() ! payload
    }
  })

  private def publish(region: org.apache.pekko.actor.ActorRef, id: String): Unit = {
    region ! ShardRegion.StartEntity(id)
    expectMsgType[ShardRegion.StartEntityAck](3.seconds)
  }

  private def count(region: String, path: String): Double =
    Option(CollectorRegistry.defaultRegistry.getSampleValue(
      "htc_single_node_deliveries_total", Array("region", "path"), Array(region, path))).fold(0.0)(_.doubleValue)

  "A local entity router" should "keep a pair on the region even after the entity is published" in {
    val region = LocalRegions.getOrCreate(system, "router.Pinned", echo)
    val router = new LocalEntityRouter(system)
    router.tryDirect("router.Pinned", "e", "first", testActor) shouldBe false
    region.tell(EntityEnvelopeEvent("e", "first"), testActor)
    expectMsg(3.seconds, "first")
    awaitAssert(LocalRegions.entityIndex(system, "router.Pinned").get("e") should not be null, 3.seconds, 20.millis)
    router.tryDirect("router.Pinned", "e", "later", testActor) shouldBe false
  }

  it should "not pin other senders: a router that never routed through the region goes direct" in {
    val region = LocalRegions.getOrCreate(system, "router.Senders", echo)
    val pinnedSender = new LocalEntityRouter(system)
    pinnedSender.tryDirect("router.Senders", "e", "x", testActor) shouldBe false
    region.tell(EntityEnvelopeEvent("e", "x"), testActor)
    expectMsg(3.seconds, "x")
    awaitAssert(LocalRegions.entityIndex(system, "router.Senders").get("e") should not be null, 3.seconds, 20.millis)

    new LocalEntityRouter(system).tryDirect("router.Senders", "e", "y", testActor) shouldBe true
    expectMsg(3.seconds, "y")
    pinnedSender.tryDirect("router.Senders", "e", "z", testActor) shouldBe false
  }

  it should "pin per (region, entity) pair, leaving other entities and other regions direct" in {
    val regionA = LocalRegions.getOrCreate(system, "router.PairA", echo)
    val regionB = LocalRegions.getOrCreate(system, "router.PairB", echo)
    publish(regionA, "other")
    publish(regionB, "e")
    val router = new LocalEntityRouter(system)

    router.tryDirect("router.PairA", "e", "born", testActor) shouldBe false
    router.tryDirect("router.PairA", "other", "o", testActor) shouldBe true
    router.tryDirect("router.PairB", "e", "same-id-other-region", testActor) shouldBe true
    receiveN(2, 3.seconds).toSet shouldBe Set[Any]("o", "same-id-other-region")
  }

  it should "fall back to the region, and pin, when the region was never started" in {
    val router = new LocalEntityRouter(system)
    router.tryDirect("router.NotYet", "e", "x", testActor) shouldBe false

    val region = LocalRegions.getOrCreate(system, "router.NotYet", echo)
    publish(region, "e")
    router.tryDirect("router.NotYet", "e", "y", testActor) shouldBe false
    router.tryDirect("router.NotYet", "fresh", "z", testActor) shouldBe false
    publish(region, "other")
    router.tryDirect("router.NotYet", "other", "w", testActor) shouldBe true
    expectMsg(3.seconds, "w")
  }

  it should "count each delivery under its path" in {
    val region = LocalRegions.getOrCreate(system, "router.Counted", echo)
    publish(region, "live")
    val router = new LocalEntityRouter(system)
    val (direct0, newer0, pinned0) =
      (count("router.Counted", "direct"), count("router.Counted", "region_new"), count("router.Counted", "region_pinned"))

    router.tryDirect("router.Counted", "live", "a", testActor) shouldBe true
    router.tryDirect("router.Counted", "live", "b", testActor) shouldBe true
    router.tryDirect("router.Counted", "unborn", "c", testActor) shouldBe false
    router.tryDirect("router.Counted", "unborn", "d", testActor) shouldBe false
    router.tryDirect("router.Counted", "unborn", "e", testActor) shouldBe false

    count("router.Counted", "direct") - direct0 shouldBe 2.0
    count("router.Counted", "region_new") - newer0 shouldBe 1.0
    count("router.Counted", "region_pinned") - pinned0 shouldBe 2.0
  }
}
