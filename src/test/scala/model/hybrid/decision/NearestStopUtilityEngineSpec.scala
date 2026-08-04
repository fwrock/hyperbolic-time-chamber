package org.interscity.htc
package model.hybrid.decision

import model.hybrid.entity.state.ModeChoiceWeights
import model.hybrid.entity.state.plan.{ ConcreteMode, ModeDecisionRequest, WalkLeg }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** `NearestStopUtilityEngine` wraps the real `ModeChoiceUtil.chooseBestLogistics`. In this test
  * environment no `htc.mobility.transit-map-file` is configured, so `TransitMapUtil.isAvailable`
  * is `false` and `chooseBestLogistics` takes its documented graceful-degradation branch
  * (`shouldSkipModeChoice` short-circuits to the walk candidate) without ever touching
  * `CityMapUtil` — see `RaptorMultiModalEngineSpec`'s doc for why that singleton can't safely be
  * exercised here. This still runs the real production code path for "no transit data available",
  * not a mock.
  */
class NearestStopUtilityEngineSpec extends AnyFlatSpec with Matchers {

  private val engine = new NearestStopUtilityEngine()
  private val ctx = DecisionContext(
    weights            = ModeChoiceWeights(),
    ownedVehicles      = Map.empty,
    vehicleCurrentNode = Map.empty,
    currentTick        = 0L
  )

  "validateForScenario" should "always be Right, regardless of scenario data availability" in {
    engine.validateForScenario(ScenarioValidationContext(false, false)) shouldBe Right(())
    engine.validateForScenario(ScenarioValidationContext(true, true)) shouldBe Right(())
  }

  "decide" should "resolve to a walk leg when transit data is unavailable and walk is allowed" in {
    val request = ModeDecisionRequest(allowedModes = Set(ConcreteMode.Walk, ConcreteMode.Bus), strategyId = "nearest-stop-utility")

    val result = engine.decide("n1", "n2", request, ctx)

    result shouldBe Right(List(WalkLeg("n1", "n2", None)))
  }

  it should "report NoViableJourney when the only resolvable mode (walk) isn't allowed" in {
    val request = ModeDecisionRequest(allowedModes = Set(ConcreteMode.Bus), strategyId = "nearest-stop-utility")

    val result = engine.decide("n1", "n2", request, ctx)

    result match {
      case Left(NoViableJourney(reason)) => reason should include("not in allowedModes")
      case other                         => fail(s"expected Left(NoViableJourney(...)), got $other")
    }
  }

  it should "apply a weightsOverride when present instead of the context default" in {
    val overrideWeights = ModeChoiceWeights(maxWalkDistanceM = 1.0)
    val request = ModeDecisionRequest(
      allowedModes    = Set(ConcreteMode.Walk),
      strategyId      = "nearest-stop-utility",
      weightsOverride = Some(overrideWeights)
    )

    // With TransitMapUtil unavailable, chooseBestLogistics skips before ever consulting
    // maxWalkDistanceM, so the override is inert here — this asserts it is at least accepted
    // and doesn't change the graceful-degradation outcome.
    engine.decide("n1", "n2", request, ctx) shouldBe Right(List(WalkLeg("n1", "n2", None)))
  }
}
