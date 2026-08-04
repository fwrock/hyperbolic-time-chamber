package org.interscity.htc
package model.hybrid.decision

import model.hybrid.entity.state.plan.{ ConcreteMode, ModeDecisionRequest, PendingDecision }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ScenarioLoadValidatorSpec extends AnyFlatSpec with Matchers {

  private def pendingWith(strategyId: String): PendingDecision =
    PendingDecision(ModeDecisionRequest(allowedModes = Set(ConcreteMode.Walk), strategyId = strategyId))

  private val availableCtx = ScenarioValidationContext(
    transitRouteDataAvailable = true,
    transitMapDataAvailable   = true
  )
  private val noTransitRoutesCtx = ScenarioValidationContext(
    transitRouteDataAvailable = false,
    transitMapDataAvailable   = true
  )

  "validateModeDecisionEngines" should "succeed when every referenced strategyId is registered and available" in {
    val decisions = List(pendingWith("nearest-stop-utility"), pendingWith("travel-time"))

    ScenarioLoadValidator.validateModeDecisionEngines(decisions, availableCtx) shouldBe Right(())
  }

  it should "fail when a strategyId has no registered engine" in {
    val decisions = List(pendingWith("nearest-stop-utility"), pendingWith("no-such-strategy"))

    ScenarioLoadValidator.validateModeDecisionEngines(decisions, availableCtx) shouldBe a[Left[_, _]]
  }

  it should "fail when 'raptor' is referenced but transit route data is unavailable" in {
    val decisions = List(pendingWith("raptor"))

    ScenarioLoadValidator.validateModeDecisionEngines(decisions, noTransitRoutesCtx) shouldBe a[Left[_, _]]
  }

  it should "succeed for an empty set of pending decisions (nothing to validate)" in {
    ScenarioLoadValidator.validateModeDecisionEngines(Nil, noTransitRoutesCtx) shouldBe Right(())
  }
}
