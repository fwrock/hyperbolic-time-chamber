package org.interscity.htc
package model.hybrid.support

import core.types.Tick
import core.util.SimulationClock
import model.hybrid.entity.event.data.MicroUpdateData
import model.hybrid.micro.strategy.DefaultMicroSimulationStrategy

object MicroObservationFields {
  def update(data: MicroUpdateData, tick: Tick, reportEvery: Int): Map[String, Any] = {
    val clock = SimulationClock.current
    val leader = data.leaderVehicle.filterNot(_ == DefaultMicroSimulationStrategy.SignalBlockerId)
    Map(
      "observation_schema" -> "htc.micro_observation.v1",
      "time_seconds" -> clock.seconds(tick),
      // A decimated endpoint does not observe the skipped intervals.
      "sample_duration_seconds" -> clock.secondsPerTick,
      "report_every_updates" -> reportEvery,
      "sampling" -> "interval_endpoint",
      "acceleration" -> data.acceleration,
      "leader_id" -> leader.map(_.toString).orNull,
      "leader_velocity" -> data.leaderVelocity,
      "gap" -> leader.map(_ => Double.box(data.gapToLeader)).orNull,
      "gap_valid" -> leader.isDefined
    )
  }

  def event(tick: Tick): Map[String, Any] =
    Map("observation_schema" -> "htc.micro_observation.v1", "time_seconds" -> SimulationClock.current.seconds(tick))
}
