package org.interscity.htc.model.hybrid.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HybridRuntimeCapabilitiesSpec extends AnyFlatSpec with Matchers {
  "the optimistic hybrid adapter" should "accept static routing and reject unaccounted shared feedback" in {
    Seq("disabled", "none", "OFF", "accounted", "ACCOUNTED").foreach(HybridRuntimeCapabilities.validateOptimisticRouting)
    Seq("inmemory", "kafka", "unknown").foreach { strategy =>
      intercept[IllegalArgumentException](HybridRuntimeCapabilities.validateOptimisticRouting(strategy))
        .getMessage should include ("htc.routing.cache-strategy=accounted")
    }
  }

}
