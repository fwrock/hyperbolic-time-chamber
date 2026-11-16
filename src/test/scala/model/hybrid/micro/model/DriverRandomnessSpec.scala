package org.interscity.htc
package model.hybrid.micro.model

import model.hybrid.entity.state.model.VehicleInLane
import model.hybrid.micro.strategy.DefaultMicroSimulationStrategy
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

class DriverRandomnessSpec extends AnyFlatSpec with Matchers {
  private def car(seed: Long=42,sigma: Double=.5) =
    VehicleInLane(7,"cars",0,10,0,5,sigma=sigma,randomSeed=seed)

  "Driver imperfection" should "preserve the exact deterministic candidate at sigma zero" in {
    for (step <- 0L to 100L)
      DriverRandomness.velocity(car(sigma=0),10.26,step,.1) shouldBe 10.26
  }

  it should "give reproducible vehicle-specific and seed-specific draws in [0,1)" in {
    val draws=(0L until 1000L).map(t => DriverRandomness.uniform(42,7,t))
    draws shouldBe (0L until 1000L).reverse.map(t => DriverRandomness.uniform(42,7,t)).reverse
    all(draws) should be >= 0.0
    all(draws) should be < 1.0
    draws.distinct.size shouldBe 1000
    draws should not be (0L until 1000L).map(t => DriverRandomness.uniform(43,7,t))
    draws should not be (0L until 1000L).map(t => DriverRandomness.uniform(42,8,t))
  }

  it should "apply bounded acceleration-scaled dawdling with the expected uniform mean" in {
    val v=car()
    val losses=(0L until 4096L).map(t => 10.26-DriverRandomness.velocity(v,10.26,t,.1))
    all(losses) should be >= 0.0
    all(losses) should be <= .13
    losses.sum/losses.size shouldBe .065 +- .003
    DriverRandomness.velocity(v,0,1,.1) shouldBe 0.0
    DriverRandomness.velocity(v.copy(velocity=0),1,1,.1) should be < 1.0
    DriverRandomness.velocity(v.copy(velocity=0),1,1,.1) should be >= .95
    DriverRandomness.velocity(v,9.55,1,.1) shouldBe 9.55
    DriverRandomness.velocity(v,2,1,.1) shouldBe 2.0
  }

  private def trajectory(seed: Long,sigma: Double,restoreAt: Option[Int]=None): Vector[(Double,Double)] = {
    var lane=mutable.Queue(car(seed,sigma).copy(velocity=0))
    val strategy=DefaultMicroSimulationStrategy()
    (1 to 100).map { tick =>
      if (restoreAt.contains(tick)) {
        val restored=core.util.JsonUtil.fromJson[VehicleInLane](core.util.JsonUtil.toJson(lane.head))
        lane=mutable.Queue(restored)
      }
      strategy.executeSubTick(mutable.Map(0->lane),0,tick,1000,50,.1,1,mutable.Map.empty)
      (lane.head.position,lane.head.velocity)
    }.toVector
  }

  it should "replay actual Link trajectories exactly through an independent checkpoint" in {
    trajectory(42,.5,Some(41)) shouldBe trajectory(42,.5)
    trajectory(42,.5) should not be trajectory(43,.5)
    trajectory(42,.5).last._1 should be < trajectory(42,0).last._1
  }

  it should "produce the same physics when substeps are grouped into runtime ticks" in {
    def run(group: Int) = {
      val lane=mutable.Queue(car().copy(velocity=0))
      for (tick <- 1L to 100L/group)
        DefaultMicroSimulationStrategy().executeSubTick(mutable.Map(0->lane),0,tick,1000,50,.1,group,mutable.Map.empty)
      lane.head
    }
    run(1).position shouldBe run(10).position
    run(1).velocity shouldBe run(10).velocity
  }

  it should "keep independent vehicles invariant to lane/map iteration order" in {
    def run(reverse: Boolean) = {
      val entries=Vector(0->mutable.Queue(car()),1->mutable.Queue(car().copy(actorId=8)))
      val lanes=mutable.Map.from(if (reverse) entries.reverse else entries)
      DefaultMicroSimulationStrategy().executeSubTick(lanes,0,1,1000,50,.1,1,mutable.Map.empty)
      lanes.values.flatMap(_.toList).map(v => v.actorId->v.velocity).toMap
    }
    run(false) shouldBe run(true)
  }

  it should "hold a stochastic queue under red and release it after green" in {
    import model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.{Red,Green}
    val lane=mutable.Queue(car(sigma=1).copy(position=100,velocity=0),
      car(sigma=1).copy(actorId=8,position=93,velocity=0))
    def step(tick: Long, green: Boolean) =
      DefaultMicroSimulationStrategy().executeSubTick(mutable.Map(0->lane),0,tick,100,50,.1,1,
        mutable.Map.empty,Some(if (green) Green else Red))
    for (tick <- 1L to 50L) step(tick,false)
    lane.map(_.position).toVector shouldBe Vector(100.0,93.0)
    lane.map(_.velocity).toVector shouldBe Vector(0.0,0.0)
    step(51,true).exists(_.reachedEnd) shouldBe true
    step(52,true)
    lane.head.actorId shouldBe 8L
    lane.head.velocity should be > 0.0
  }

  it should "preserve the physical stop line when sigma is positive" in {
    import model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red
    val lane=mutable.Queue(car().copy(position=65))
    for (tick <- 1L to 400L) {
      DefaultMicroSimulationStrategy().executeSubTick(mutable.Map(0->lane),0,tick,100,50,.1,1,
        mutable.Map.empty,Some(Red))
      lane.head.position should be <= 100.0
      lane.head.velocity should be >= 0.0
    }
    lane.head.position shouldBe 100.0 +- .01
    lane.head.velocity shouldBe 0.0 +- .01
  }
}
