package org.interscity.htc
package model.hybrid.decision

import model.hybrid.entity.state.ModeChoiceWeights
import model.hybrid.entity.state.plan.{ ConcreteMode, ModeDecisionRequest, PrivateVehicleLeg, WalkLeg }
import org.htc.protobuf.core.entity.actor.Identify
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** `TravelTimeEngine` wraps the real `TravelTimeModeChoiceStrategy`. Its walk/car A* candidates go
  * through `GPSUtil.calcRouteCompact(Walking)`, which — like `RaptorMultiModalEngine`'s dependency
  * on `CityMapUtil` — would otherwise require the eager, file-backed `CityMapUtil` singleton. All
  * tests here use `originNodeId == destinationNodeId`, which both `GPSUtil` route functions
  * short-circuit on (`if (originId == destinationId) return Some((0.0, mutable.Queue.empty))`)
  * *before* touching `CityMapUtil` — so these exercise the real strategy/engine code for a
  * (degenerate but real) zero-distance trip, without the singleton risk documented on
  * `RaptorMultiModalEngineSpec`.
  */
class TravelTimeEngineSpec extends AnyFlatSpec with Matchers {

  private val engine = new TravelTimeEngine()
  private val sameNode = "n1"

  private def ctx(ownedVehicles: Map[String, Identify] = Map.empty, vehicleCurrentNode: Map[String, String] = Map.empty) =
    DecisionContext(
      weights            = ModeChoiceWeights(),
      ownedVehicles      = ownedVehicles,
      vehicleCurrentNode = vehicleCurrentNode,
      currentTick        = 0L,
      entityId           = "test-person"
    )

  "validateForScenario" should "always be Right" in {
    engine.validateForScenario(ScenarioValidationContext(false, false)) shouldBe Right(())
  }

  "decide" should "resolve to a walk leg for a zero-distance trip with no owned vehicles" in {
    val request = ModeDecisionRequest(allowedModes = Set(ConcreteMode.Walk), strategyId = "travel-time")

    val result = engine.decide(sameNode, sameNode, request, ctx())

    result shouldBe Right(List(WalkLeg(sameNode, sameNode, Some(Nil))))
  }

  it should "report NoViableJourney when the only resolvable mode (walk) isn't allowed" in {
    val request = ModeDecisionRequest(allowedModes = Set(ConcreteMode.Bus), strategyId = "travel-time")

    val result = engine.decide(sameNode, sameNode, request, ctx())

    result match {
      case Left(NoViableJourney(reason)) => reason should include("not in allowedModes")
      case other                         => fail(s"expected Left(NoViableJourney(...)), got $other")
    }
  }

  it should "prefer a car parked at the origin over walking (higher modePref, same zero travel time)" in {
    val car = Identify(id = "car-1")
    val request = ModeDecisionRequest(allowedModes = Set(ConcreteMode.Car, ConcreteMode.Walk), strategyId = "travel-time")
    val context = ctx(ownedVehicles = Map("car" -> car), vehicleCurrentNode = Map("car" -> sameNode))

    val result = engine.decide(sameNode, sameNode, request, context)

    result shouldBe Right(List(PrivateVehicleLeg(ConcreteMode.Car, car)))
  }

  it should "fall back to walking when the owned car is parked away from the origin" in {
    val car = Identify(id = "car-1")
    val request = ModeDecisionRequest(allowedModes = Set(ConcreteMode.Car, ConcreteMode.Walk), strategyId = "travel-time")
    val context = ctx(ownedVehicles = Map("car" -> car), vehicleCurrentNode = Map("car" -> "n-elsewhere"))

    val result = engine.decide(sameNode, sameNode, request, context)

    result shouldBe Right(List(WalkLeg(sameNode, sameNode, Some(Nil))))
  }
}

/** Coverage for `TravelTimeEngine.raptorIncludedStopTypes` — the pure narrowing logic that decides
  * which PT stop types are worth a real `RaptorRouter.route` call when the cheap approximation's
  * winning candidate is bus/subway (see `TravelTimeEngine`'s class doc, "Lazy RAPTOR validation").
  * Deliberately pure — no `CityMapUtil`/`TransitMapUtil`/`RaptorRouter` singleton access — so the
  * mode-narrowing decision itself is testable independent of the eager-singleton constraints
  * documented on `RaptorMultiModalEngineSpec`.
  */
class TravelTimeEngineRaptorIncludedStopTypesSpec extends AnyFlatSpec with Matchers {

  private def weightsWith(includedModes: Set[String]): ModeChoiceWeights =
    ModeChoiceWeights(includedModes = includedModes)

  "raptorIncludedStopTypes" should "include both bus and subway when both are in includedModes and allowedModes" in {
    val weights = weightsWith(Set("bus", "subway", "walk"))
    val allowed = Set(ConcreteMode.Bus, ConcreteMode.Subway, ConcreteMode.Walk)

    TravelTimeEngine.raptorIncludedStopTypes(weights, allowed) shouldBe Set("bus", "subway")
  }

  it should "exclude subway when allowedModes forbids it even though includedModes allows it" in {
    val weights = weightsWith(Set("bus", "subway"))
    val allowed = Set(ConcreteMode.Bus)

    TravelTimeEngine.raptorIncludedStopTypes(weights, allowed) shouldBe Set("bus")
  }

  it should "exclude bus when includedModes doesn't name it even though allowedModes permits it" in {
    val weights = weightsWith(Set("subway", "walk"))
    val allowed = Set(ConcreteMode.Bus, ConcreteMode.Subway)

    TravelTimeEngine.raptorIncludedStopTypes(weights, allowed) shouldBe Set("subway")
  }

  it should "return an empty set when neither bus nor subway survives both filters" in {
    val weights = weightsWith(Set("walk", "car"))
    val allowed = Set(ConcreteMode.Walk, ConcreteMode.Car)

    TravelTimeEngine.raptorIncludedStopTypes(weights, allowed) shouldBe empty
  }

  it should "be case-insensitive on includedModes, matching TravelTimeModeChoiceStrategy's own lowercasing" in {
    val weights = weightsWith(Set("Bus", "SUBWAY"))
    val allowed = Set(ConcreteMode.Bus, ConcreteMode.Subway)

    TravelTimeEngine.raptorIncludedStopTypes(weights, allowed) shouldBe Set("bus", "subway")
  }
}
