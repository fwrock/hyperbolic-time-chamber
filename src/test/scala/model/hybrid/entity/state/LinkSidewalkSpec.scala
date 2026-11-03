package org.interscity.htc
package model.hybrid.entity.state

import core.util.JsonUtil
import org.interscity.htc.model.hybrid.entity.state.model.SidewalkConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LinkSidewalkSpec extends AnyFlatSpec with Matchers {
  private val legacy = """{"startTick":0,"from":1,"to":2,"length":100,"lanes":2,"speedLimit":30,"capacity":20,"freeSpeed":30}"""

  "Link sidewalks" should "default old scenario data to separate enabled pedestrian space" in {
    val link = JsonUtil.fromJson[LinkState](legacy)
    link.sidewalk shouldBe SidewalkConfig()
    val micro = LinkState.createMicro(0, 1, 2, 100, 2, 30, 20, 30)
    micro.sidewalk shouldBe SidewalkConfig()
    micro.vehiclesByLane.keySet shouldBe Set(0, 1)
    micro.laneConfigurations.size shouldBe 2
    micro.totalVehicles shouldBe 0
  }

  it should "preserve custom sidewalk dimensions through state copies and checkpoint JSON" in {
    val sidewalk = SidewalkConfig(enabled = false, width = 2.4, gap = 0.5)
    val link = LinkState.createMicro(0, 1, 2, 100, 2, 30, 20, 30, sidewalk = sidewalk)
    val restored = JsonUtil.fromJson[LinkState](JsonUtil.toJson(link))
    restored.sidewalk shouldBe sidewalk
    restored.copy(currentSpeed = 10).sidewalk shouldBe sidewalk
    LinkState.createMeso(0, 1, 2, 100, 2, 30, 20, 30, sidewalk).sidewalk shouldBe sidewalk
  }

  it should "reject nonphysical sidewalk dimensions and explicit null configuration" in {
    Seq(0.0, -1.0, Double.NaN, Double.PositiveInfinity).foreach { width =>
      intercept[IllegalArgumentException](SidewalkConfig(width = width))
    }
    Seq(-0.1, Double.NaN, Double.PositiveInfinity).foreach { gap =>
      intercept[IllegalArgumentException](SidewalkConfig(gap = gap))
    }
    intercept[IllegalArgumentException](JsonUtil.fromJson[LinkState](legacy).copy(sidewalk = null))
  }
}
