package org.interscity.htc
package model.hybrid.validation

import core.util.JsonUtil
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MicroFieldReplaySpec extends AnyFlatSpec with Matchers {
  private def episode = FieldReplayEpisode("test", List(FieldReplayVehicle(11, 100, 10, 4.5)),
    List(FieldReplayLeader(0, 120, 10, 4.5), FieldReplayLeader(1, 121, 10, 4.5),
      FieldReplayLeader(2, 122, 10, 4.5)))

  "Field replay" should "use only the previous observed boundary and preserve every output tick" in {
    val original = MicroFieldReplay.replay(episode)
    val stopped = MicroFieldReplay.replay(episode.copy(leader = episode.leader.updated(1,
      FieldReplayLeader(1, 120, 0, 4.5))))
    original.map(_.tick) shouldBe Vector(0L, 1L, 2L)
    original.take(2) shouldBe stopped.take(2)
    stopped.last.position should be < original.last.position
    original(1).velocity shouldBe 10.26 +- 1e-9
  }

  it should "use measured leader lengths and stop before the rear bumper" in {
    val input = episode.copy(followers = List(FieldReplayVehicle(11, 100, 0, 4.5)),
      leader = List(FieldReplayLeader(0, 110, 0, 8), FieldReplayLeader(1, 110, 0, 8)))
    MicroFieldReplay.replay(input).last.position shouldBe 100.0
    MicroFieldReplay.replay(input).last.velocity shouldBe 0.0
  }

  it should "round-trip typed input and deterministically replay a platoon" in {
    val input = FieldReplayInput(List(episode.copy(followers = episode.followers :+
      FieldReplayVehicle(12, 80, 5, 5))))
    val restored = JsonUtil.fromJson[FieldReplayInput](JsonUtil.toJson(input))
    restored shouldBe input
    val samples = MicroFieldReplay.replay(restored.episodes.head)
    samples shouldBe MicroFieldReplay.replay(input.episodes.head)
    samples.groupBy(_.vehicle).values.foreach(_.map(_.tick) shouldBe Vector(0L, 1L, 2L))
  }

  it should "reject missing leader ticks and invalid initial states" in {
    intercept[IllegalArgumentException](MicroFieldReplay.replay(episode.copy(leader = episode.leader.drop(1))))
    intercept[IllegalArgumentException](MicroFieldReplay.replay(episode.copy(
      followers = List(FieldReplayVehicle(11, 100, Double.NaN, 4.5)))))
  }

  it should "accept an explicit documented speed cap without changing legacy input defaults" in {
    val explicit = episode.copy(speedLimitKmh = 36)
    MicroFieldReplay.replay(explicit).last.velocity shouldBe 10.0
    MicroFieldReplay.replay(episode).last.velocity should be > 10.0
    val legacy = JsonUtil.fromJson[FieldReplayEpisode](
      JsonUtil.toJson(episode).replace(",\"speedLimitKmh\":50.0", ""))
    MicroFieldReplay.replay(legacy) shouldBe MicroFieldReplay.replay(episode)
    intercept[IllegalArgumentException](MicroFieldReplay.replay(episode.copy(speedLimitKmh = Double.NaN)))
  }
}
