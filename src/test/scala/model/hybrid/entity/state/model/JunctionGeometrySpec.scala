package org.interscity.htc
package model.hybrid.entity.state.model

import core.util.JsonUtil
import model.hybrid.entity.state.{ CarState, LinkState, NodeState }
import model.hybrid.entity.state.enumeration.ActorTypeEnum
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

class JunctionGeometrySpec extends AnyFlatSpec with Matchers {
  private val geometry = JunctionGeometry(2L, 4L, 6L, 7.0, 5.0,
    List(MetricPoint(0, 0), MetricPoint(3, 0), MetricPoint(3, 4)))

  "A metric junction path" should "measure its polyline and interpolate distance around the bend" in {
    geometry.shapeLengthMeters shouldBe 7.0
    geometry.positionAt(0) shouldBe MetricPoint(0, 0)
    geometry.positionAt(5) shouldBe MetricPoint(3, 2)
    geometry.positionAt(100) shouldBe MetricPoint(3, 4)
    geometry.positionAt(-1) shouldBe MetricPoint(0, 0)
    geometry.copy(lengthMeters = 7.01).positionAt(7.01) shouldBe MetricPoint(3, 4)
  }

  it should "reject nonphysical geometry and ambiguous movement selection" in {
    intercept[IllegalArgumentException](geometry.copy(lengthMeters = 0))
    intercept[IllegalArgumentException](geometry.copy(speedLimitMps = Double.NaN))
    intercept[IllegalArgumentException](MetricPoint(Double.PositiveInfinity, 0))
    intercept[IllegalArgumentException](geometry.copy(shape = List(MetricPoint(0, 0), MetricPoint(0, 0))))
    intercept[IllegalArgumentException](NodeState(0, latitude = 0, longitude = 0, links = Nil,
      junctionMovements = List(JunctionMovement(4, 6, 9), JunctionMovement(4, 6, 10))))
  }

  "Junction checkpoint state" should "preserve typed geometry, target speed limits and route insertion" in {
    val link = LinkState(0, from = 2, to = 2, length = 7, lanes = 1, speedLimit = 18,
      capacity = 2, freeSpeed = 18, junctionGeometry = Some(geometry), exitSpeedLimitsMps = Map(6L -> 5.0))
    val restored = JsonUtil.fromJson[LinkState](JsonUtil.toJson(link))
    restored.junctionGeometry shouldBe Some(geometry)
    restored.exitSpeedLimitsMps(6L) shouldBe 5.0
    val car = CarState(0, origin = 1, destination = 3, actorType = ActorTypeEnum.Car, size = 4.5)
    car.movableBestRoute = Some(mutable.Queue(6L -> 3L))
    car.movablePendingJunctionPath = Some(PrecomputedRouteItem(9, 2))
    car.movableCurrentPathIsJunction = true
    val restoredCar = JsonUtil.fromJson[CarState](JsonUtil.toJson(car))
    restoredCar.movableCurrentPathIsJunction shouldBe true
    restoredCar.takePendingJunctionPath() shouldBe Some(9L -> 2L)
    restoredCar.takePendingJunctionPath() shouldBe None
    restoredCar.movableBestRoute.get.toList shouldBe List(6L -> 3L)
  }
}
