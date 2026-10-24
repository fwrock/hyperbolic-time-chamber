package org.interscity.htc
package model.hybrid.support.link

import core.util.JsonUtil
import model.hybrid.entity.state.LinkState
import model.hybrid.entity.state.enumeration.SimulationModeEnum
import model.hybrid.entity.state.model.*
import model.hybrid.entity.event.data.link.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

class LinkFollowingHandlerSpec extends AnyFlatSpec with Matchers {
  private def state = LinkState(0, from = 1, to = 2, length = 165.8, lanes = 1,
    speedLimit = 50, capacity = 100, freeSpeed = 50, simulationMode = SimulationModeEnum.MICRO,
    microTicksPerGlobalTick = 1, downstreamLookahead = true, downstreamConnectors = Map(9L -> 8L),
    vehiclesByLane = Map(0 -> mutable.Queue(VehicleInLane(13, "cars", 158.3, 0, 0, 4.5, nextLinkId = 9))))
  private def observation(tick: Long, seq: Long = 1, vehicle: Option[MicroBoundaryVehicle] = None,
                          link: Long = 8) = MicroBoundarySnapshot(link, tick, seq, 9.03, vehicle)

  "Boundary history" should "use the latest publication strictly before the integrating tick" in {
    val history = MicroBoundaryHistory()
    history.accept(observation(5, vehicle = Some(MicroBoundaryVehicle(12, 0.026, 0.26, 4.5))))
    history.accept(observation(6))
    history.before(6).get.tick shouldBe 5
    history.accept(observation(5, seq = 2))
    history.before(6).get.vehicle shouldBe None
    history.accept(observation(7))
    history.snapshots.map(_.tick) shouldBe List(7, 6)
    history.before(6) shouldBe None
  }

  "Cross-link following" should "subscribe once and publish immutable actual occupancy without scheduling or waiting" in {
    val s = state
    var sent = Vector.empty[(Long, AnyRef)]
    val handler = new LinkFollowingHandler(() => s, () => 7, () => 5,
      (id, _, data, _) => sent :+= id -> data)
    handler.observeNextRoad(9)
    handler.observeNextRoad(9)
    sent.map(_._1) shouldBe Vector(8, 9)
    sent.map(_._2) shouldBe Vector(SubscribeMicroBoundaryData(), SubscribeMicroBoundaryData())
    sent = Vector.empty
    handler.subscribe(6)
    val occupied = sent.head._2.asInstanceOf[MicroBoundaryUpdateData].snapshot
    occupied.vehicle.get shouldBe MicroBoundaryVehicle(13, 158.3, 0, 4.5)
    s.vehiclesByLane(0).clear()
    handler.publish()
    occupied.vehicle shouldBe defined
    sent.last._2.asInstanceOf[MicroBoundaryUpdateData].snapshot.vehicle shouldBe None
    sent.last._2.asInstanceOf[MicroBoundaryUpdateData].snapshot.sequence shouldBe 2
  }

  it should "follow a connector leader and look through an empty connector onto the next road" in {
    val s = state
    var sends = 0
    val handler = new LinkFollowingHandler(() => s, () => 7, () => 5, (_, _, _, _) => sends += 1)
    handler.observeNextRoad(9)
    handler.receive(8, observation(5, vehicle = Some(MicroBoundaryVehicle(12, 0.026, 0.26, 4.5))))
    handler.receive(9, observation(5, vehicle = Some(MicroBoundaryVehicle(11, 2, 1, 12)), link = 9))
    sends shouldBe 2 // Receiving publications never produces another publication cycle.
    handler.leaders(5) shouldBe empty
    handler.leaders(6)(13).actorId shouldBe 12
    handler.leaders(6)(13).position shouldBe 165.826 +- 1e-9
    handler.receive(8, observation(6))
    handler.leaders(7)(13).actorId shouldBe 11
    handler.leaders(7)(13).position shouldBe 176.83 +- 1e-9
    handler.leaders(7)(13).vehicleLength shouldBe 12
  }

  it should "restore subscriptions, sequence, observations and route IDs before deterministic replay" in {
    var s = state
    var sent = Vector.empty[(Long, AnyRef)]
    val handler = new LinkFollowingHandler(() => s, () => 7, () => 5,
      (id, _, data, _) => sent :+= id -> data)
    handler.observeNextRoad(9)
    handler.receive(8, observation(4, vehicle = Some(MicroBoundaryVehicle(12, 0.026, 0.26, 4.5))))
    handler.subscribe(6)
    val checkpoint = JsonUtil.toJson(s)
    handler.publish()
    val expected = sent.last
    s = JsonUtil.fromJson[LinkState](checkpoint)
    s.downstreamConnectors(9L) shouldBe 8L
    s.following.observations(8L).before(5).get.vehicle.get.id shouldBe 12
    s.vehiclesByLane(0).head.nextLinkId shouldBe 9
    handler.publish()
    sent.last shouldBe expected
    handler.observeNextRoad(9)
    s.following.requested shouldBe List(8, 9)
  }

  it should "reject an unsupported or unsolicited boundary instead of interpreting it as an empty road" in {
    val s = state
    val handler = new LinkFollowingHandler(() => s, () => 7, () => 5, (_, _, _, _) => ())
    intercept[IllegalArgumentException](handler.receive(8, observation(4)))
    handler.observeNextRoad(9)
    intercept[IllegalArgumentException](handler.receive(8, observation(4).copy(supported = false)))
    intercept[IllegalArgumentException](handler.receive(9, observation(4)))
  }
}
