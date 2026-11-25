package org.interscity.htc
package core.modelbinding

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ModelBindingsSpec extends AnyFlatSpec with Matchers {

  private val hybrid = ModelBindings.forNamespace("hybrid")
  private val mobility = ModelBindings.forNamespace("mobility")

  "ModelBindings.forNamespace" should "resolve each model's own bindings" in {
    hybrid.namespace shouldBe "hybrid"
    mobility.namespace shouldBe "mobility"
    hybrid.accountedRoutingActorClassType shouldBe "hybrid.actor.RoutingService"
    mobility.accountedRoutingActorClassType shouldBe "mobility.actor.RoutingService"
  }

  it should "reject an unknown namespace" in {
    an[IllegalArgumentException] should be thrownBy ModelBindings.forNamespace("nope")
  }

  "globalTick" should "be the model's own message type, never the other model's" in {
    hybrid.globalTick(7L).getClass.getName should startWith("org.interscity.htc.model.hybrid.")
    mobility.globalTick(7L).getClass.getName should startWith("org.interscity.htc.model.mobility.")
  }

  "linkFlow" should "recognize only its own model's payload" in {
    val own = model.mobility.entity.event.data.VehicleLinkFlowData("1", "enter", "9", "Car", "LoadBalancedDistributed", 3)
    mobility.linkFlow(own) shouldBe Some(LinkFlowRecord("1", "enter", "9", "Car", "LoadBalancedDistributed", 3))
    hybrid.linkFlow(own) shouldBe None
    mobility.linkFlow("not a flow") shouldBe None
  }

  "requireScenarioMatches" should "accept actors of the active namespace and namespace-free ones" in {
    noException should be thrownBy ModelBindings.requireScenarioMatches(
      "mobility", List("mobility.actor.Car", "org.interscity.htc.model.mobility.actor.Link", "core.actor.Thing"))
  }

  it should "reject actors that belong to another model" in {
    val error = the[IllegalArgumentException] thrownBy ModelBindings.requireScenarioMatches(
      "hybrid", List("hybrid.actor.Car", "mobility.actor.Link"))
    error.getMessage should include("mobility.actor.Link")
    error.getMessage should include("HTC_MODEL_NAMESPACE")
  }
}
