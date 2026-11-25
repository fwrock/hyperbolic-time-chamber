package org.interscity.htc
package model.mobility.support.link

import com.typesafe.config.ConfigFactory
import core.util.JsonUtil
import model.mobility.entity.event.data.link.MicroBoundaryUpdateData
import model.mobility.entity.state.LinkState
import model.mobility.entity.state.enumeration.SimulationModeEnum
import model.mobility.entity.state.model.*
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.serialization.{ SerializationExtension, Serializers }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

class MicroLaneBoundaryPublicationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private val config = ConfigFactory
    .parseString("pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off")
    .withFallback(ConfigFactory.load())
  private val system = ActorSystem("LaneBoundaryWriter", config)
  private val peer = ActorSystem("LaneBoundaryReader", config)

  override def afterAll(): Unit = { system.terminate(); peer.terminate(); () }

  private def state = LinkState(
    0L,
    from = 1,
    to = 2,
    length = 100,
    lanes = 3,
    speedLimit = 50,
    capacity = 100,
    freeSpeed = 50,
    simulationMode = SimulationModeEnum.MICRO,
    microTicksPerGlobalTick = 1,
    vehiclesByLane = Map(
      0 -> mutable.Queue(
        VehicleInLane(1, "cars", 10, 2, 0, 4, nextLinkId = 12),
        VehicleInLane(2, "cars", 30, 4, 0, 5, nextLinkId = 13)
      ),
      2 -> mutable.Queue(
        VehicleInLane(3, "cars", 2, 0, 0, 8, nextLinkId = 14, pendingExitTick = Some(7L))
      )
    )
  )

  "Lane boundary publication" should "retain each lane's rear, front and explicit empty occupancy" in {
    val s = state
    var sent = Vector.empty[MicroBoundarySnapshot]
    val handler = new LinkFollowingHandler(
      () => s,
      () => 11,
      () => 7,
      (_, _, data, _) => sent :+= data.asInstanceOf[MicroBoundaryUpdateData].snapshot
    )
    handler.subscribe(20, "mobility.actor.Node")
    val snapshot = sent.head
    snapshot.supported shouldBe true // G02: multi-lane is consumable only through per-lane occupancy (see LinkFollowingHandler.receive).
    snapshot.laneBoundaries should have size 3
    snapshot.laneCount shouldBe 3
    snapshot.vehicle.get.id shouldBe 3
    snapshot.laneBoundaries.map(_.laneId) shouldBe List(0, 1, 2)
    snapshot.laneBoundaries(0).rearmost.get.id shouldBe 1
    snapshot.laneBoundaries(0).frontmost.get.id shouldBe 2
    snapshot.laneBoundaries(0).frontmost.get.nextLinkId shouldBe 13
    snapshot.laneBoundaries(1).rearmost shouldBe None
    snapshot.laneBoundaries(1).frontmost shouldBe None
    snapshot.laneBoundaries(2).frontmost.get.pendingExit shouldBe true
    s.vehiclesByLane(0).clear()
    handler.publish()
    snapshot.laneBoundaries(0).frontmost.get.id shouldBe 2
    sent.last.laneBoundaries(0).frontmost shouldBe None
  }

  it should "keep equal numeric subscriber IDs distinct across Node and Link shards" in {
    val s = state
    var sent = Vector.empty[(Long, String, MicroBoundarySnapshot)]
    val handler = new LinkFollowingHandler(
      () => s,
      () => 11,
      () => 7,
      (id, shard, data, _) =>
        sent :+= ((id, shard, data.asInstanceOf[MicroBoundaryUpdateData].snapshot))
    )
    handler.subscribe(20, "org.interscity.htc.model.mobility.actor.Link")
    handler.subscribe(20, "org.interscity.htc.model.mobility.actor.Node")
    handler.subscribe(20, "mobility.actor.Node")
    sent = Vector.empty
    handler.publish()
    sent.map(
      s => (s._1, s._2)
    ) shouldBe Vector((20L, "mobility.actor.Link"), (20L, "mobility.actor.Node"))
    sent.map(_._3).distinct.size shouldBe 1
    s.following.subscribers shouldBe List(20L)
    s.following.otherSubscribers shouldBe List(MicroBoundarySubscriber(20, "mobility.actor.Node"))
    intercept[IllegalArgumentException](handler.subscribe(20, "cars"))
  }

  it should "restore lane observations, subscriptions and publication identity with cold codecs" in {
    var s = state.copy(microEntry = MicroEntryConfig(enabled = true, initialSnapshotTick = Some(0L)))
    var sent = Vector.empty[MicroBoundarySnapshot]
    val handler = new LinkFollowingHandler(
      () => s,
      () => 11,
      () => 7,
      (_, _, data, _) => sent :+= data.asInstanceOf[MicroBoundaryUpdateData].snapshot
    )
    handler.subscribe(20, "mobility.actor.Node")
    s.following.observations = Map(11L -> MicroBoundaryHistory(List(sent.head)))
    val checkpoint = JsonUtil.toJson(s)
    handler.publish()
    val expected = sent.last
    s = JsonUtil.fromJson[LinkState](checkpoint)
    s.following.observations(11L).before(8).get.laneBoundaries(0).frontmost.get.id shouldBe 2
    handler.publish()
    sent.last shouldBe expected
    val from = SerializationExtension(system)
    val serializer = from.findSerializerFor(s)
    val restored = SerializationExtension(peer)
      .deserialize(
        from.serialize(s).get,
        serializer.identifier,
        Serializers.manifestFor(serializer, s)
      )
      .get
      .asInstanceOf[LinkState]
    restored.following.otherSubscribers shouldBe s.following.otherSubscribers
    restored.following.observations shouldBe s.following.observations
    restored.following.sequence shouldBe s.following.sequence
    restored.microEntry.initialSnapshotTick shouldBe Some(0L)
    s.microEntry.initialSnapshotTick shouldBe Some(0L)
  }

  it should "retain legacy defaults and refuse incomplete lane publications" in {
    val snapshot = JsonUtil.fromJson[MicroBoundarySnapshot](
      """{"linkId":11,"tick":7,"sequence":1,"length":100,"vehicle":null}"""
    )
    snapshot.laneCount shouldBe 1
    snapshot.laneBoundaries shouldBe Nil
    JsonUtil.fromJson[MicroFollowingState]("{}").otherSubscribers shouldBe Nil
    intercept[IllegalArgumentException](
      snapshot.copy(laneCount = 2, laneBoundaries = List(MicroLaneBoundary(0, None, None)))
    )
    intercept[IllegalArgumentException](
      snapshot.copy(
        laneCount = 2,
        laneBoundaries = List(MicroLaneBoundary(0, None, None), MicroLaneBoundary(0, None, None))
      )
    )
  }
}
