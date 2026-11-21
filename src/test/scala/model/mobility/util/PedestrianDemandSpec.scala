package org.interscity.htc
package model.mobility.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PedestrianDemandSpec extends AnyFlatSpec with Matchers {
  "PedestrianDemand" should "see a Person under relative and fully qualified class names" in {
    PedestrianDemand.mayWalk(Seq("mobility.actor.Link", "mobility.actor.Person")) shouldBe true
    PedestrianDemand.mayWalk(Seq("org.interscity.htc.model.mobility.actor.Person")) shouldBe true
  }

  it should "not treat a car-only scenario as walking" in {
    PedestrianDemand.mayWalk(Seq("mobility.actor.Link", "mobility.actor.Node", "mobility.actor.Car")) shouldBe false
  }

  it should "not match a class that merely contains Person in its name" in {
    PedestrianDemand.mayWalk(Seq("mobility.actor.PersonLike", "mobility.actor.NotAPerson")) shouldBe false
  }

  it should "tolerate null entries and an empty scenario" in {
    PedestrianDemand.mayWalk(Seq(null, "mobility.actor.Car")) shouldBe false
    PedestrianDemand.mayWalk(Nil) shouldBe false
  }
}
