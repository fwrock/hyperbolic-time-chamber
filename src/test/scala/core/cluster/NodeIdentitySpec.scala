package org.interscity.htc
package core.cluster

import core.entity.event.{ ActorInteractionEvent, EntityEnvelopeEvent }
import core.serializer.{ ActorInteractionSerializer, EntityEnvelopeSerializer }
import model.hybrid.entity.event.data.link.LinkInfoData

import com.typesafe.config.ConfigFactory
import io.prometheus.client.CollectorRegistry
import org.apache.pekko.actor.{ ActorSystem, Address, ExtendedActorSystem }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters.*

class NodeIdentitySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("NodeIdentitySpec",
    ConfigFactory.parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off").withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }

  private def addr(host: String, port: Int) = Address("pekko", "hyperbolic-time-chamber", host, port)

  "node labels" should "come from a fixed list per strategy, with Pereiro and São Paulo among the cities" in {
    NodeIdentity.labelFor("rank", 3) shouldBe "node-3"
    NodeIdentity.labelFor("animals", 0) shouldBe "Onça-pintada"
    NodeIdentity.labelFor("cities", 0) shouldBe "Pereiro"
    NodeIdentity.labelFor("cities", 1) shouldBe "São Paulo"
    NodeIdentity.Cities should contain allOf ("Pereiro", "São Paulo")
    NodeIdentity.labelFor("unknown-strategy", 2) shouldBe "node-2"          // falls back to rank
    intercept[IllegalArgumentException](NodeIdentity.labelFor("rank", -1))
  }

  it should "stay unique past the end of a list" in {
    for (strategy <- Seq("rank", "animals", "cities")) {
      val labels = (0 until 200).map(NodeIdentity.labelFor(strategy, _))
      labels.distinct.size shouldBe labels.size
    }
    NodeIdentity.labelFor("cities", NodeIdentity.Cities.size) shouldBe "Pereiro-2"
    NodeIdentity.Animals.distinct.size shouldBe NodeIdentity.Animals.size
    NodeIdentity.Cities.distinct.size shouldBe NodeIdentity.Cities.size
  }

  "rank assignment" should "give every node the same table regardless of the order it learned about the members" in {
    val a = addr("10.0.0.2", 2551)
    val b = addr("10.0.0.10", 2551)       // sorted by host text: "10.0.0.10" < "10.0.0.2"
    val c = addr("10.0.0.2", 2600)
    val members = Seq(a, b, c)
    val views = members.permutations.toSeq
    for (self <- members) {
      val ranks = views.map(v => NodeIdentity.rankOf(self, v))
      ranks.distinct.size shouldBe 1                                   // independent of arrival order
    }
    members.flatMap(m => NodeIdentity.rankOf(m, members)).sorted shouldBe Seq(0, 1, 2)   // a permutation: no duplicates
    NodeIdentity.rankOf(b, members) shouldBe Some(0)
    NodeIdentity.rankOf(a, members) shouldBe Some(1)
    NodeIdentity.rankOf(c, members) shouldBe Some(2)
  }

  it should "include the node itself even if the member list is not yet aware of it" in {
    NodeIdentity.rankOf(addr("10.0.0.1", 1), Seq.empty) shouldBe Some(0)
  }

  "the wire stamp" should "be rank + 1 so that 0 means unknown, and map back to a label" in {
    NodeIdentity.rank shouldBe 0
    NodeIdentity.selfStamp shouldBe 1
    NodeIdentity.stamp shouldBe 1
    NodeIdentity.nameOfStamp(0) shouldBe "unknown"
    NodeIdentity.nameOfStamp(3) shouldBe "node-2"
    NodeIdentity.originLabel(0) shouldBe ""
    NodeIdentity.originLabel(2) shouldBe "node-1"
  }

  it should "survive both wire formats that carry an interaction" in {
    val event = ActorInteractionEvent(10L, 1L, 2L, "example.Link", "", "example.Link",
      data = LinkInfoData(100.0, 10.0, 3, 13.9, 2), resourceId = "run", senderNode = 3)
    val ext = system.asInstanceOf[ExtendedActorSystem]
    val standalone = new ActorInteractionSerializer(ext)
    standalone.fromBinary(standalone.toBinary(event), standalone.manifest(event)).asInstanceOf[ActorInteractionEvent].senderNode shouldBe 3
    val envelope = new EntityEnvelopeSerializer(ext)
    val wrapped = EntityEnvelopeEvent("htcaid_link_7", event)
    envelope.fromBinary(envelope.toBinary(wrapped), envelope.manifest(wrapped)).asInstanceOf[EntityEnvelopeEvent]
      .event.asInstanceOf[ActorInteractionEvent].senderNode shouldBe 3
    // an event created on this node is stamped by default; an unstamped one costs nothing on the wire
    ActorInteractionEvent(1L, 1L, 1L, "", "", "", data = LinkInfoData(1.0, 1.0, 1, 1.0, 1), resourceId = "").senderNode shouldBe 1
    val unstamped = event.copy(senderNode = 0)
    standalone.toBinary(unstamped).length should be < standalone.toBinary(event).length
  }

  "receive accounting" should "count local, remote and unknown senders by node pair and message type" in {
    def byNode(from: String, locality: String, eventType: String): Double =
      Option(CollectorRegistry.defaultRegistry.getSampleValue("htc_messages_by_node_total",
        Array("from_node", "to_node", "locality", "event_type"), Array(from, "node-0", locality, eventType))).map(_.doubleValue).getOrElse(0.0)
    def byClass(from: String, to: String, locality: String): Double =
      Option(CollectorRegistry.defaultRegistry.getSampleValue("htc_messages_by_class_total",
        Array("from_type", "to_type", "locality"), Array(from, to, locality))).map(_.doubleValue).getOrElse(0.0)
    val local0 = byNode("node-0", "local", "enter"); val remote0 = byNode("node-1", "remote", "leave")
    val unknown0 = byNode("unknown", "unknown", "enter"); val carLink0 = byClass("Car", "Link", "remote")
    NodeIdentity.recordReceive(1, "enter", "model.hybrid.actor.Car", "Link")     // sender stamp 1 = rank 0 = this node
    NodeIdentity.recordReceive(2, "leave", "model.hybrid.actor.Car", "Link")
    NodeIdentity.recordReceive(2, "leave", "model.hybrid.actor.Car", "Link")
    NodeIdentity.recordReceive(0, "enter", "model.hybrid.actor.Car", "Link")
    byNode("node-0", "local", "enter") - local0 shouldBe 1.0
    byNode("node-1", "remote", "leave") - remote0 shouldBe 2.0          // same pair, different type -> separate series
    byNode("node-1", "remote", "enter") shouldBe 0.0
    byNode("unknown", "unknown", "enter") - unknown0 shouldBe 1.0
    byClass("Car", "Link", "remote") - carLink0 shouldBe 2.0
  }

  it should "bound the cardinality of free-form event types" in {
    for (i <- 0 until 200) NodeIdentity.recordReceive(1, s"type-$i", "X", "Y")
    val series = CollectorRegistry.defaultRegistry.metricFamilySamples().asScala
      .flatMap(_.samples.asScala).filter(_.name == "htc_messages_by_node_total").map(_.labelValues.get(3)).toSet
    series.size should be <= 70                                              // 64 distinct + "other" + the earlier test's types
    series should contain ("other")
  }

  it should "label a cluster address with the node it belongs to, or host:port when unknown" in {
    NodeIdentity.labelOfAddress(addr("203.0.113.9", 2551)) shouldBe "203.0.113.9:2551"
  }
}
