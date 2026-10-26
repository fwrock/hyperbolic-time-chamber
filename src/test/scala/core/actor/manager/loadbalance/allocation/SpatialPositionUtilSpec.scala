package org.interscity.htc
package core.actor.manager.loadbalance.allocation

import core.actor.manager.loadbalance.LoadBalanceTestIsolation
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SpatialPositionUtilSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  override def beforeAll(): Unit = LoadBalanceTestIsolation.acquire()
  override def afterAll(): Unit = {
    SpatialShardIdRegistry.clear()
    LoadBalanceTestIsolation.release()
  }
  "spatial position resolution" should "read numeric node references and a person's first activity from wire JSON" in {
    SpatialShardIdRegistry.putPosition("42", 1.5 -> 2.5)
    SpatialPositionUtil.extract("""{"origin":42}""") shouldBe Some(1.5 -> 2.5)
    SpatialPositionUtil.extract("""{"from":"42"}""") shouldBe Some(1.5 -> 2.5)
    SpatialPositionUtil.extract("""{"originalPlan":[{"kind":"Activity","nodeId":42}]}""") shouldBe Some(1.5 -> 2.5)
    SpatialPositionUtil.extract(Map("longitude" -> 3.5, "latitude" -> 4.5)) shouldBe Some(3.5 -> 4.5)
  }
  it should "retain published node coordinates after the temporary creation cache is cleared" in {
    SpatialShardIdRegistry.clear()
    SpatialShardIdRegistry.installDirectory("position", 0L, Vector(SpatialAssignment("42", "cell", "example.Node", 1.5, 2.5)), true)
    SpatialShardIdRegistry.clearPositions()
    SpatialPositionUtil.extract("""{"nodeId":42}""") shouldBe Some(1.5 -> 2.5)
  }
  it should "place entities without a physical coordinate inside the configured world instead of at an out-of-bounds zero" in {
    val config = ConfigFactory.parseString("""
      htc.load-balance-manager.world-bounds { min-x=10, max-x=20, min-y=30, max-y=40 }
    """)
    SpatialPositionUtil.resolve("{}", config) shouldBe (15.0 -> 35.0)
  }
}
