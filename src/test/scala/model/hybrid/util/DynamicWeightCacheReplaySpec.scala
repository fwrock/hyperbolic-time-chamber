package org.interscity.htc
package model.hybrid.util

import model.hybrid.entity.state.model.DynamicLinkCost

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** Regression coverage for `docs/TIME_WARP_DESIGN.md`'s model-level audit, third finding:
  * `DynamicWeightCache.getWeight` used to read directly from a strategy backed by an out-of-band,
  * real-time pub-sub channel (Kafka/Distributed Data), with no way for a later replay of the same
  * route computation to see the exact values the original live read saw. `withRecording`/
  * `withOverride` (ThreadLocal-scoped, so concurrently-running actors on different threads never
  * interfere with each other) close that gap without `CompactGraph`/`GPSUtil`/any model actor
  * needing to know Time Warp exists -- `SimulationBaseActor` wraps a live dispatch in
  * `withRecording` and a replay in `withOverride`.
  */
class DynamicWeightCacheReplaySpec extends AnyFlatSpec with Matchers {

  private def costFor(linkId: String, congestionFactor: Double): DynamicLinkCost =
    DynamicLinkCost(
      linkId = linkId,
      baseCost = 100.0,
      congestionFactor = congestionFactor,
      currentSpeed = 5.0,
      freeFlowSpeed = 10.0,
      vehicleCount = 1,
      capacity = 10,
      lastUpdateTick = 0L
    )

  "withOverride" should "reproduce the exact value withRecording captured, even after the live cache changes underneath it" in {
    val linkId = s"link-${java.util.UUID.randomUUID()}"
    DynamicWeightCache.publishCost(costFor(linkId, congestionFactor = 1.0), ttlSeconds = 60)

    // Live dispatch: record whatever getWeight actually returns right now.
    val recorded = mutable.Map.empty[String, Double]
    val liveWeight = DynamicWeightCache.withRecording(recorded) {
      DynamicWeightCache.getWeight(linkId, staticWeight = 999.0)
    }
    recorded(linkId) shouldBe liveWeight

    // Time passes; a real Kafka update (or this test standing in for one) changes the live cache.
    DynamicWeightCache.publishCost(costFor(linkId, congestionFactor = 3.0), ttlSeconds = 60)
    val changedLiveWeight = DynamicWeightCache.getWeight(linkId, staticWeight = 999.0)
    changedLiveWeight should not be liveWeight // sanity: the live cache genuinely changed

    // Replay of the original event must see the ORIGINAL value, not whatever the live cache holds now.
    val replayedWeight = DynamicWeightCache.withOverride(recorded.toMap) {
      DynamicWeightCache.getWeight(linkId, staticWeight = 999.0)
    }
    replayedWeight shouldBe liveWeight
  }

  it should "fall back to the edge's static weight, not the live cache, for a link the recording never saw" in {
    val linkId = s"link-${java.util.UUID.randomUUID()}"
    DynamicWeightCache.publishCost(costFor(linkId, congestionFactor = 5.0), ttlSeconds = 60)

    // Override map from a DIFFERENT event that never touched this link at all.
    val replayedWeight = DynamicWeightCache.withOverride(Map.empty) {
      DynamicWeightCache.getWeight(linkId, staticWeight = 42.0)
    }
    replayedWeight shouldBe 42.0 // static fallback, ignoring the live (dynamic) cost entirely
  }

  it should "not leak into a plain call made after the wrapped block ends" in {
    val linkId = s"link-${java.util.UUID.randomUUID()}"
    DynamicWeightCache.publishCost(costFor(linkId, congestionFactor = 2.0), ttlSeconds = 60)

    val liveWeight = DynamicWeightCache.getWeight(linkId, staticWeight = 999.0)

    DynamicWeightCache.withOverride(Map(linkId -> 12345.0)) {
      DynamicWeightCache.getWeight(linkId, staticWeight = 999.0) shouldBe 12345.0
    }

    // Outside the block, the override must no longer apply -- the real live cache value again.
    DynamicWeightCache.getWeight(linkId, staticWeight = 999.0) shouldBe liveWeight
  }
}
