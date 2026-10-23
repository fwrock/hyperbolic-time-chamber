package org.interscity.htc.model.hybrid.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HybridRuntimeCapabilitiesSpec extends AnyFlatSpec with Matchers {
  "the optimistic hybrid adapter" should "accept static routing and reject unaccounted shared feedback" in {
    Seq("disabled", "none", "OFF").foreach(HybridRuntimeCapabilities.validateOptimisticRouting)
    Seq("inmemory", "kafka", "unknown").foreach { strategy =>
      intercept[IllegalArgumentException](HybridRuntimeCapabilities.validateOptimisticRouting(strategy))
        .getMessage should include ("timestamped feedback adapter")
    }
  }

  it should "reject irreversible live transit creation" in {
    intercept[UnsupportedOperationException](HybridRuntimeCapabilities.requireCancellableTransitBirth())
      .getMessage should include ("cancellable birth adapter")
  }
}
