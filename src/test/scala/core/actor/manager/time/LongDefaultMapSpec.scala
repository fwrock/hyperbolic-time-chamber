package org.interscity.htc
package core.actor.manager.time

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LongDefaultMapSpec extends AnyFlatSpec with Matchers {
  "LongDefaultMap" should "answer the default for an absent key without storing it" in {
    val map = new LongDefaultMap(7L)
    map(1L) shouldBe 7L
    map.size shouldBe 0
  }

  it should "return updated values, including negative keys and values" in {
    val map = new LongDefaultMap(0L)
    map(-5L) = -9L
    map(Long.MaxValue) = 3L
    map(-5L) shouldBe -9L
    map(Long.MaxValue) shouldBe 3L
    map.size shouldBe 2
  }

  it should "fall back to the default after removal" in {
    val map = new LongDefaultMap(0L)
    map(4L) = 11L
    map.remove(4L)
    map(4L) shouldBe 0L
    map.remove(4L)
    map.size shouldBe 0
  }
}
