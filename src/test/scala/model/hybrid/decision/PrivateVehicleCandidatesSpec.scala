package org.interscity.htc
package model.hybrid.decision

import org.htc.protobuf.core.entity.actor.Identify
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PrivateVehicleCandidatesSpec extends AnyFlatSpec with Matchers {

  private val car = Identify(id = "car-1")
  private val bicycle = Identify(id = "bicycle-1")

  "available" should "include a vehicle parked at the origin node" in {
    val result = PrivateVehicleCandidates.available(
      originNodeId       = "n1",
      ownedVehicles      = Map("car" -> car),
      vehicleCurrentNode = Map("car" -> "n1")
    )

    result shouldBe Map("car" -> car)
  }

  it should "exclude a vehicle parked at a different node" in {
    val result = PrivateVehicleCandidates.available(
      originNodeId       = "n1",
      ownedVehicles      = Map("car" -> car),
      vehicleCurrentNode = Map("car" -> "n2")
    )

    result shouldBe empty
  }

  it should "treat a vehicle with no vehicleCurrentNode entry as available (never moved from start)" in {
    val result = PrivateVehicleCandidates.available(
      originNodeId       = "n1",
      ownedVehicles      = Map("car" -> car),
      vehicleCurrentNode = Map.empty
    )

    result shouldBe Map("car" -> car)
  }

  it should "evaluate each owned vehicle independently" in {
    val result = PrivateVehicleCandidates.available(
      originNodeId       = "n1",
      ownedVehicles      = Map("car" -> car, "bicycle" -> bicycle),
      vehicleCurrentNode = Map("car" -> "n2") // bicycle has no entry -> available
    )

    result shouldBe Map("bicycle" -> bicycle)
  }
}
