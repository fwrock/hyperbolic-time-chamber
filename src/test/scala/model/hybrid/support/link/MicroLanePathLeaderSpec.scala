package org.interscity.htc
package model.hybrid.support.link

import model.hybrid.entity.state.LinkState
import model.hybrid.entity.state.enumeration.SimulationModeEnum
import model.hybrid.entity.state.model.*
import model.hybrid.micro.strategy.{Lc2013LaneChangeStrategy, MobilLaneChangeStrategy}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

/** G02: leaders follow the downstream lane path (origin lane -> connector lane -> exit lane). */
class MicroLanePathLeaderSpec extends AnyFlatSpec with Matchers {
  private val approach = 7L
  private val road = 9L
  private val connector = 8L

  private def state(connections: List[LaneConnection], withConnector: Boolean = true) =
    LinkState(0, from = 1, to = 2, length = 100, lanes = 2, speedLimit = 50, capacity = 100, freeSpeed = 50,
      simulationMode = SimulationModeEnum.MICRO, microTicksPerGlobalTick = 1, downstreamLookahead = true,
      downstreamConnectors = if (withConnector) Map(road -> connector) else Map.empty,
      laneConnections = connections,
      vehiclesByLane = Map(0 -> mutable.Queue(VehicleInLane(1, "cars", 90, 5, 0, 4.5, nextLinkId = road)),
        1 -> mutable.Queue(VehicleInLane(2, "cars", 80, 5, 0, 4.5, nextLinkId = road))))

  private def boundary(id: Long, tick: Long, laneCount: Int, occupied: Map[Int, MicroBoundaryVehicle]) =
    MicroBoundarySnapshot(id, tick, 1, 10, occupied.values.minByOption(_.position), laneCount = laneCount,
      laneBoundaries = (0 until laneCount).toList.map(l => MicroLaneBoundary(l, occupied.get(l), occupied.get(l))))

  private def handler(s: LinkState) =
    new LinkFollowingHandler(() => s, () => approach, () => 5, (_, _, _, _) => ())

  "Lane path leaders" should "see only the lane the path enters, so occupancy elsewhere never blocks" in {
    val s = state(List(LaneConnection(road, 0, 0), LaneConnection(road, 1, 1)))
    val h = handler(s)
    h.observeNextRoad(road)
    h.receive(connector, boundary(connector, 5, 2, Map(1 -> MicroBoundaryVehicle(50, 2, 0, 4.5))))
    h.receive(road, boundary(road, 5, 2, Map.empty))
    h.laneLeader(6, road, 0) shouldBe None
    h.laneLeader(6, road, 1).map(_.actorId) shouldBe Some(50L)
    h.leaders(6).keySet shouldBe Set(2L)
    h.laneLeader(5, road, 1) shouldBe None // observations must be strictly earlier than the integrating tick
  }

  it should "look through an empty connector lane onto the mapped exit-road lane" in {
    val s = state(List(LaneConnection(road, 0, 0, Some(1)), LaneConnection(road, 1, 1)))
    val h = handler(s)
    h.observeNextRoad(road)
    h.receive(connector, boundary(connector, 5, 2, Map.empty))
    h.receive(road, boundary(road, 5, 2, Map(1 -> MicroBoundaryVehicle(60, 3, 2, 4.5))))
    h.laneLeader(6, road, 0).map(_.actorId) shouldBe Some(60L)
    h.laneLeader(6, road, 0).get.position shouldBe (113.0 +- 1e-9)
  }

  it should "treat a missing connection or a missing observation as unknown, never as a leader" in {
    val s = state(List(LaneConnection(road, 1, 1)))
    val h = handler(s)
    h.observeNextRoad(road)
    h.receive(connector, boundary(connector, 5, 2, Map(0 -> MicroBoundaryVehicle(50, 2, 0, 4.5))))
    h.laneLeader(6, road, 0) shouldBe None
    h.laneLeader(6, road, 1) shouldBe None
  }

  it should "reject multi-lane downstream data without lane detail and unconnected multi-lane lookahead" in {
    val h = handler(state(List(LaneConnection(road, 0, 0))))
    h.observeNextRoad(road)
    an[IllegalArgumentException] should be thrownBy h.receive(connector,
      MicroBoundarySnapshot(connector, 5, 1, 10, None, laneCount = 2))
    an[IllegalArgumentException] should be thrownBy state(Nil)
  }

  "Lane-change safety" should "include the leader on the candidate lane's downstream path (MOBIL and LC2013)" in {
    val config = LaneChangingConfig(model = LaneChangingModel.Mobil, biasToRight = 0, cooldownSeconds = 2)
    val connections = List(LaneConnection(road, 1, 1))
    def lanes() = mutable.Map(0 -> mutable.Queue(VehicleInLane(1, "cars", 90, 5, 0, 4.5, nextLinkId = road)),
      1 -> mutable.Queue.empty[VehicleInLane])
    val blocker = VehicleInLane(50, "hybrid.actor.Link", 103, 0, 0, 5)
    def run(leader: (Long, Int, Long) => Option[VehicleInLane], lc2013: Boolean) = {
      val l = lanes()
      val moves = mutable.Buffer.empty[(Long, Int, Int, String)]
      val record = (v: Long, f: Int, t: Int, r: String) => { moves += ((v, f, t, r)); () }
      if (lc2013) Lc2013LaneChangeStrategy.execute(l, config.copy(model = LaneChangingModel.Lc2013), connections,
        Map.empty, 100, 50 / 3.6, .1, 0, 1, record, leader)
      else MobilLaneChangeStrategy.execute(l, config, connections, Map.empty, 100, 50 / 3.6, .1, 0, 1, record, leader)
      moves.toList
    }
    for (lc2013 <- List(false, true)) {
      run((_, _, _) => None, lc2013).map(m => (m._2, m._3)) shouldBe List((0, 1)) // control: the move is legal and safe
      run((_, lane, _) => Option.when(lane == 1)(blocker), lc2013) shouldBe empty
    }
  }
}
