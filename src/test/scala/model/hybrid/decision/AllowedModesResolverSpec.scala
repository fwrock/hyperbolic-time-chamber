package org.interscity.htc
package model.hybrid.decision

import model.hybrid.entity.state.plan.ConcreteMode
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AllowedModesResolverSpec extends AnyFlatSpec with Matchers {

  private val scenarioDefault = Set(ConcreteMode.Walk, ConcreteMode.Bus)

  "resolveAllowedModes" should "use the per-person override when present" in {
    val personOverride = Set(ConcreteMode.Car)

    AllowedModesResolver.resolveAllowedModes(scenarioDefault, Some(personOverride)) shouldBe personOverride
  }

  it should "fall back to the scenario default when no override is given" in {
    AllowedModesResolver.resolveAllowedModes(scenarioDefault, None) shouldBe scenarioDefault
  }

  it should "honour an empty override rather than treating it as absent" in {
    AllowedModesResolver.resolveAllowedModes(scenarioDefault, Some(Set.empty)) shouldBe Set.empty
  }
}
