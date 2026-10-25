package org.interscity.htc
package model.hybrid.support.link

import model.hybrid.entity.state.LinkState
import model.hybrid.entity.state.enumeration.SimulationModeEnum
import model.hybrid.entity.state.model.{ DynamicLinkCost, VehicleInLane }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.util.Success

class LinkRoutingCostSpec extends AnyFlatSpec with Matchers {
  private def cost(state: LinkState): DynamicLinkCost = {
    var published: Option[DynamicLinkCost] = None
    val reporter = new LinkMetricsReporter(
      reportFn = (_, _) => (), entityIdFn = () => "7", currentTickFn = () => 10L,
      cacheTtl = 60, costPublishInterval = 0, getLinkStateFn = () => state,
      getVehicleEntryTickFn = _ => None, getVehicleWaitingSecondsFn = _ => 0.0,
      getRegisteredVehicleIdsFn = () => Nil, logWarnFn = _ => (),
      publishCostFn = (value, _) => { published = Some(value); Success(()) })
    reporter.publishDynamicCost()
    published.get
  }

  private def link = LinkState(startTick = 0L, from = 1L, to = 2L, length = 100.0,
    lanes = 1, speedLimit = 36.0, capacity = 20.0, freeSpeed = 36.0)

  "routing cost publication" should "restore free-flow cost when a MICRO link becomes empty" in {
    val published = cost(link.copy(simulationMode = SimulationModeEnum.MICRO, currentSpeed = 0.0))
    published.currentSpeed shouldBe 10.0
    published.freeFlowSpeed shouldBe 10.0
    published.totalCost shouldBe 100.0
  }

  it should "give the same penalty to the same physical speed in MESO and MICRO" in {
    val vehicle = VehicleInLane(9L, "hybrid.actor.Car", 10.0, 5.0, 0.0, 4.5)
    val micro = link.copy(simulationMode = SimulationModeEnum.MICRO, currentSpeed = 5.0,
      vehiclesByLane = Map(0 -> mutable.Queue(vehicle)))
    cost(micro).totalCost shouldBe 200.0
    cost(link.copy(currentSpeed = 18.0)).totalCost shouldBe cost(micro).totalCost
  }

  it should "retain the stopped-traffic penalty while an actual MICRO vehicle remains" in {
    val vehicle = VehicleInLane(9L, "hybrid.actor.Car", 10.0, 0.0, 0.0, 4.5)
    val stopped = link.copy(simulationMode = SimulationModeEnum.MICRO, currentSpeed = 0.0,
      vehiclesByLane = Map(0 -> mutable.Queue(vehicle)))
    cost(stopped).totalCost shouldBe 1000.0
  }
}
