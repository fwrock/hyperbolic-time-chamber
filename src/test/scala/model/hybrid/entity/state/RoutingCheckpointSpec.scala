package org.interscity.htc
package model.hybrid.entity.state

import core.runtime.EventOrder
import core.util.JsonUtil
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RoutingCheckpointSpec extends AnyFlatSpec with Matchers {
  "RoutingServiceState" should "round-trip numeric link ids and their causal versions" in {
    val order = EventOrder.root(12L, "hybrid.actor.Link", 77L)
    val original = RoutingServiceState(costs = Map(77L -> RoutingCostVersion(123.5, order)))
    val restored = JsonUtil.fromJson[RoutingServiceState](JsonUtil.toJson(original))

    restored shouldBe original
    restored.costs.keySet shouldBe Set(77L)
  }

  "routing request state" should "retain a pending vehicle route and person decision through JSON restore" in {
    val car = CarState(startTick = 0L, origin = 1L, destination = 4L,
      actorType = enumeration.ActorTypeEnum.Car, size = 4.5)
    car.movablePendingRouteQuery = Some(PendingRouteQuery(9001L, 1L, 4L))
    JsonUtil.fromJson[CarState](JsonUtil.toJson(car)).movablePendingRouteQuery shouldBe car.movablePendingRouteQuery

    val person = PersonState(pendingRoutingDecision = Some(PendingPersonRoutingDecision(9001L, "PendingPlan")))
    JsonUtil.fromJson[PersonState](JsonUtil.toJson(person)).pendingRoutingDecision shouldBe person.pendingRoutingDecision
  }
}
