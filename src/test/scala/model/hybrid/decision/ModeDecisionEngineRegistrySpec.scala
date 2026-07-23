package org.interscity.htc
package model.hybrid.decision

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ModeDecisionEngineRegistrySpec extends AnyFlatSpec with Matchers {

  "get" should "return the raptor engine for a known id" in {
    ModeDecisionEngineRegistry.get("raptor").map(_.id) shouldBe Some("raptor")
  }

  it should "return the nearest-stop-utility engine for a known id" in {
    ModeDecisionEngineRegistry.get("nearest-stop-utility").map(_.id) shouldBe Some("nearest-stop-utility")
  }

  it should "return the travel-time engine for a known id" in {
    ModeDecisionEngineRegistry.get("travel-time").map(_.id) shouldBe Some("travel-time")
  }

  it should "return None for an unknown id, with no silent fallback" in {
    ModeDecisionEngineRegistry.get("does-not-exist") shouldBe None
  }

  "register" should "make a custom engine retrievable under its own id" in {
    val custom = new ModeDecisionEngine {
      override val id: String = "custom-test-engine"
      override def validateForScenario(ctx: ScenarioValidationContext): Either[EngineUnavailable, Unit] = Right(())
      override def decide(
        originNodeId: String,
        destinationNodeId: String,
        request: model.hybrid.entity.state.plan.ModeDecisionRequest,
        ctx: DecisionContext
      ): Either[NoViableJourney, List[model.hybrid.entity.state.plan.AtomicLeg]] = Left(NoViableJourney("unused"))
    }

    ModeDecisionEngineRegistry.register("custom-test-engine", custom)

    ModeDecisionEngineRegistry.get("custom-test-engine") shouldBe Some(custom)
  }
}
