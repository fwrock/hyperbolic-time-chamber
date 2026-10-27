package org.interscity.htc
package model.hybrid.validation

import core.util.JsonUtil
import model.hybrid.entity.state.model.VehicleInLane
import model.hybrid.micro.strategy.DefaultMicroSimulationStrategy

import java.nio.file.{Files, Path}
import scala.collection.mutable

case class FieldReplayVehicle(id: Long, position: Double, velocity: Double, length: Double)
case class FieldReplayLeader(tick: Long, position: Double, velocity: Double, length: Double)
case class FieldReplayEpisode(id: String, followers: List[FieldReplayVehicle], leader: List[FieldReplayLeader],
                              speedLimitKmh: Double = 50.0)
case class FieldReplayInput(episodes: List[FieldReplayEpisode])
case class FieldReplaySample(episode: String, vehicle: Long, tick: Long,
                             position: Double, velocity: Double, acceleration: Double)

object MicroFieldReplay {
  def replay(episode: FieldReplayEpisode): Vector[FieldReplaySample] = {
    require(episode.followers.nonEmpty && episode.leader.size >= 2, "empty field replay")
    require(episode.speedLimitKmh.isFinite && episode.speedLimitKmh > 0, "invalid field speed cap")
    require(episode.followers.map(_.id).distinct.size == episode.followers.size, "duplicate follower")
    require(episode.leader.map(_.tick) == episode.leader.indices.map(_.toLong).toList,
      "field leader must have contiguous ticks starting at zero")
    require(episode.followers.forall(v => v.id >= 0 && v.position.isFinite && v.position >= 0 &&
      v.velocity.isFinite && v.velocity >= 0 && v.velocity <= episode.speedLimitKmh / 3.6 &&
      v.length.isFinite && v.length > 0), "invalid initial field follower")
    require(episode.leader.forall(v => v.position.isFinite && v.position >= 0 &&
      v.velocity.isFinite && v.velocity >= 0 && v.length.isFinite && v.length > 0), "invalid field leader")
    val lane = mutable.Queue.from(episode.followers.map(v =>
      VehicleInLane(v.id, "field-replay", v.position, v.velocity, 0, v.length, entryTick = -1)))
    VehicleInLane.sortFrontToBack(lane)
    val strategy = DefaultMicroSimulationStrategy()
    val waiting = mutable.Map.empty[Long, Double]
    val samples = Vector.newBuilder[FieldReplaySample]
    lane.foreach(v => samples += FieldReplaySample(episode.id, v.actorId, 0, v.position, v.velocity, 0))
    for (tick <- 1 until episode.leader.size) {
      // The observed boundary at t must not enter the physics interval ending at t.
      val observed = episode.leader(tick - 1)
      val boundary = VehicleInLane(-2, "field-boundary", observed.position, observed.velocity, 0, observed.length)
      val front = lane.head.actorId
      val updates = strategy.executeSubTick(mutable.Map(0 -> lane), 0, tick.toLong,
        100000, episode.speedLimitKmh, 0.1, 1, waiting,
        downstreamLeaders = Map(front -> boundary), retainExits = true)
      require(updates.size == episode.followers.size && !updates.exists(_.reachedEnd), "field replay left its domain")
      updates.foreach(v => samples += FieldReplaySample(episode.id, v.vehicleId, tick.toLong,
        v.position, v.velocity, v.acceleration))
    }
    samples.result()
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 2, "usage: MicroFieldReplay input.json output.json")
    val input = JsonUtil.fromJson[FieldReplayInput](Files.readString(Path.of(args(0))))
    require(input.episodes.map(_.id).distinct.size == input.episodes.size, "duplicate episode")
    val samples = input.episodes.flatMap(replay)
    Files.writeString(Path.of(args(1)), JsonUtil.toJson(samples))
    ()
  }
}
