package org.interscity.htc
package model.hybrid.decision

import model.hybrid.entity.state.ArrivalLogistics
import model.hybrid.entity.state.plan.{ ConcreteMode, StopRef }
import org.htc.protobuf.core.entity.actor.Identify
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ArrivalLogisticsTranslationSpec extends AnyFlatSpec with Matchers {

  "modeOf" should "resolve every concrete mode string" in {
    ArrivalLogisticsTranslation.modeOf("walk") shouldBe Some(ConcreteMode.Walk)
    ArrivalLogisticsTranslation.modeOf("car") shouldBe Some(ConcreteMode.Car)
    ArrivalLogisticsTranslation.modeOf("bicycle") shouldBe Some(ConcreteMode.Bicycle)
    ArrivalLogisticsTranslation.modeOf("motorcycle") shouldBe Some(ConcreteMode.Motorcycle)
    ArrivalLogisticsTranslation.modeOf("bus") shouldBe Some(ConcreteMode.Bus)
    ArrivalLogisticsTranslation.modeOf("subway") shouldBe Some(ConcreteMode.Subway)
  }

  it should "return None for the unresolved placeholder mode 'auto'" in {
    ArrivalLogisticsTranslation.modeOf("auto") shouldBe None
  }

  "buildWalkLeg" should "carry the origin, destination and precomputed route through unchanged" in {
    val logistics = ArrivalLogistics(mode = "walk", precomputedRoute = Some(List(("l1", "n2"))))

    val leg = ArrivalLogisticsTranslation.buildWalkLeg("n1", "n3", logistics)

    leg.originNodeId shouldBe "n1"
    leg.destinationNodeId shouldBe "n3"
    leg.precomputedRoute shouldBe Some(List(("l1", "n2")))
  }

  "buildPrivateVehicleLeg" should "build a leg when a vehicle is present" in {
    val vehicle = Identify(id = "car-1")
    val logistics = ArrivalLogistics(mode = "car", vehicle = Some(vehicle))

    val leg = ArrivalLogisticsTranslation.buildPrivateVehicleLeg(ConcreteMode.Car, logistics)

    leg.map(_.vehicle) shouldBe Some(vehicle)
    leg.map(_.mode) shouldBe Some(ConcreteMode.Car)
  }

  it should "return None when the logistics carries no vehicle" in {
    val logistics = ArrivalLogistics(mode = "car", vehicle = None)

    ArrivalLogisticsTranslation.buildPrivateVehicleLeg(ConcreteMode.Car, logistics) shouldBe None
  }

  "buildTransitLeg" should "assemble a TransitLeg from already-resolved stop refs" in {
    val boarding = StopRef("busstop-1", "hybrid.actor.BusStop", "n2")
    val alighting = StopRef("busstop-2", "hybrid.actor.BusStop", "n4")

    val leg = ArrivalLogisticsTranslation.buildTransitLeg(ConcreteMode.Bus, "L1", boarding, alighting)

    leg.mode shouldBe ConcreteMode.Bus
    leg.line shouldBe "L1"
    leg.boardingStop shouldBe boarding
    leg.alightingStop shouldBe alighting
  }

  "resolveTransitLeg" should "return None when TransitMapUtil has no stops loaded (default test environment)" in {
    val logistics = ArrivalLogistics(
      mode                  = "bus",
      line                  = Some("L1"),
      boardingStopId        = Some("busstop-1"),
      boardingStopClassType = Some("hybrid.actor.BusStop"),
      alightingNodeId       = Some("n4")
    )

    ArrivalLogisticsTranslation.resolveTransitLeg(ConcreteMode.Bus, logistics) shouldBe None
  }

  "translate" should "produce a WalkLeg for mode walk" in {
    val logistics = ArrivalLogistics(mode = "walk")

    ArrivalLogisticsTranslation.translate("n1", "n2", logistics).map(_.mode) shouldBe Some(ConcreteMode.Walk)
  }

  it should "produce a PrivateVehicleLeg for a private mode with a vehicle" in {
    val vehicle = Identify(id = "bike-1")
    val logistics = ArrivalLogistics(mode = "bicycle", vehicle = Some(vehicle))

    ArrivalLogisticsTranslation.translate("n1", "n2", logistics).map(_.mode) shouldBe Some(ConcreteMode.Bicycle)
  }

  it should "return None for the unresolved 'auto' mode" in {
    val logistics = ArrivalLogistics(mode = "auto")

    ArrivalLogisticsTranslation.translate("n1", "n2", logistics) shouldBe None
  }

  it should "return None for a transit mode when the stop cannot be resolved" in {
    val logistics = ArrivalLogistics(mode = "bus", line = Some("L1"), boardingStopId = Some("busstop-1"), alightingNodeId = Some("n4"))

    ArrivalLogisticsTranslation.translate("n1", "n2", logistics) shouldBe None
  }
}
