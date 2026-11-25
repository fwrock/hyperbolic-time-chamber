package org.interscity.htc
package model.mobility.micro.strategy

import model.mobility.entity.state.model.*
import model.mobility.entity.state.enumeration.TrafficSignalPhaseStateEnum.{Green,Red}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

class MobilLaneChangeStrategySpec extends AnyFlatSpec with Matchers {
  private def car(id: Long,pos: Double,velocity: Double=10) = VehicleInLane(id,"cars",pos,velocity,0,5,nextLinkId=9)
  private val active = LaneChangingConfig(model=LaneChangingModel.Mobil,biasToRight=0,cooldownSeconds=2)
  private def step(lanes: mutable.Map[Int,mutable.Queue[VehicleInLane]], config: LaneChangingConfig=active,
                   connections: List[LaneConnection]=Nil, tick: Long=1) = {
    val events = mutable.Buffer.empty[(Long,Int,Int,String)]
    val updates = DefaultMicroSimulationStrategy().executeSubTick(lanes,0,tick,200,72,.1,1,mutable.Map.empty,
      laneChanging=config,laneConnections=connections,laneChangedFn=(v,a,b,r) => events += ((v,a,b,r)))
    (updates,events.toList)
  }
  "Configured lane changing" should "stay disabled for old inputs" in {
    val lanes = mutable.Map(0 -> mutable.Queue(car(1,30),car(2,45,0)),1 -> mutable.Queue.empty[VehicleInLane])
    step(lanes,LaneChangingConfig())._2 shouldBe empty
    lanes(0).map(_.actorId).toSet shouldBe Set(1L,2L)
  }
  it should "overtake a slower leader through a safe adjacent lane" in {
    val lanes = mutable.Map(0 -> mutable.Queue(car(1,30),car(2,45,0)),1 -> mutable.Queue.empty[VehicleInLane])
    step(lanes)._2.exists(e => e._1 == 1 && e._2 == 0 && e._3 == 1) shouldBe true
    lanes.values.flatMap(_.map(_.actorId)).toList.sorted shouldBe List(1L,2L)
  }
  it should "move toward a required turn lane and preserve cooldown state" in {
    val lanes = mutable.Map(0 -> mutable.Queue(car(1,100)),1 -> mutable.Queue.empty[VehicleInLane])
    step(lanes,connections=List(LaneConnection(9,1,0)))._2 shouldBe List((1L,0,1,"strategic"))
    lanes(1).head.lastLaneChangeSeconds shouldBe Some(0.0)
    step(lanes,connections=List(LaneConnection(9,0,0)),tick=2)._2 shouldBe empty
  }
  it should "reject overlapping targets and unsafe fast rear followers" in {
    for (target <- List(car(3,100),car(3,94,20))) {
      val lanes = mutable.Map(0 -> mutable.Queue(car(1,100)),1 -> mutable.Queue(target.copy(lastLaneChangeSeconds=Some(0))))
      step(lanes,connections=List(LaneConnection(9,1,0)))._2 shouldBe empty
    }
  }
  it should "respect the changing driver's reaction time and the target follower's minimum gap" in {
    def changes(tau: Double) = {
      val lanes=mutable.Map(0 -> mutable.Queue(car(1,100,3).copy(reactionTime=tau)),
        1 -> mutable.Queue(car(2,112,0).copy(lastLaneChangeSeconds=Some(0))))
      step(lanes,connections=List(LaneConnection(9,1,0)))._2
    }
    changes(1).map(_._1) shouldBe List(1L)
    changes(2) shouldBe empty
    def rearChanges(gap: Double) = {
      val lanes=mutable.Map(0 -> mutable.Queue(car(1,100,0)),
        1 -> mutable.Queue(car(2,91,0).copy(minGap=gap,lastLaneChangeSeconds=Some(0))))
      step(lanes,connections=List(LaneConnection(9,1,0)))._2
    }
    rearChanges(2).map(_._1) shouldBe List(1L)
    rearChanges(6) shouldBe empty
  }
  it should "reject a target front gap that needs more braking than the changing car can supply" in {
    val lanes = mutable.Map(0 -> mutable.Queue(car(1,100)),
      1 -> mutable.Queue(car(2,107.1,0).copy(lastLaneChangeSeconds=Some(0))))
    step(lanes,connections=List(LaneConnection(9,1,0)))._2 shouldBe empty
    lanes(0).exists(_.actorId == 1) shouldBe true
  }
  it should "arbitrate competing changes deterministically by vehicle identity" in {
    def run(reverse: Boolean) = {
      val entries = List(0 -> mutable.Queue(car(1,100)),1 -> mutable.Queue.empty[VehicleInLane],2 -> mutable.Queue(car(2,100)))
      val lanes = mutable.Map.from(if (reverse) entries.reverse else entries)
      step(lanes,connections=List(LaneConnection(9,1,0)))._2
    }
    run(false) shouldBe run(true)
    run(false) shouldBe List((1L,0,1,"strategic"))
  }
  it should "keep an illegal exit lane stopped until a change becomes possible" in {
    val lanes = mutable.Map(0 -> mutable.Queue(car(1,200)),1 -> mutable.Queue(car(2,200).copy(lastLaneChangeSeconds=Some(0))))
    val (updates,_) = step(lanes,connections=List(LaneConnection(9,1,0)))
    updates.find(_.vehicleId == 1).get.reachedEnd shouldBe false
    lanes(0).head.position shouldBe 200.0
  }
  "Movement signals" should "stop the red turn while allowing a different green turn on the same road" in {
    val lanes = mutable.Map(0 -> mutable.Queue(car(1,199)),1 -> mutable.Queue(car(2,199).copy(nextLinkId=10)))
    val updates = DefaultMicroSimulationStrategy().executeSubTick(lanes,0,1,200,72,.1,1,mutable.Map.empty,
      movementSignals=Map((9L,0)->Red,(10L,1)->Green))
    updates.find(_.vehicleId == 1).get.reachedEnd shouldBe false
    updates.find(_.vehicleId == 2).get.reachedEnd shouldBe true
  }

  "Strategic preparation" should "cross several adjacent lanes before a short high-speed road ends" in {
    val lanes=mutable.Map.from((0 until 7).map(l => l -> mutable.Queue.empty[VehicleInLane]))
    lanes(4).enqueue(car(1,0,22))
    val changed=mutable.Buffer.empty[(Int,Int)]
    var departed=false
    for (tick <- 1L to 12L if !departed) {
      val updates=DefaultMicroSimulationStrategy().executeSubTick(lanes,0,tick,94.65,80,.1,10,mutable.Map.empty,
        laneChanging=active,laneConnections=List(LaneConnection(9,0,0),LaneConnection(9,1,1)),
        laneChangedFn=(_,from,to,_) => changed += ((from,to)))
      departed=updates.exists(_.reachedEnd)
    }
    departed shouldBe true
    changed.take(3).toList shouldBe List((4,3),(3,2),(2,1))
  }
  it should "create a bounded-braking gap for side-by-side cars requiring opposite lanes" in {
    val lanes=mutable.Map(0 -> mutable.Queue(car(1,0,8.33)),
      1 -> mutable.Queue(car(2,0,8.33).copy(nextLinkId=10)))
    MobilLaneChangeStrategy.execute(lanes,active,List(LaneConnection(9,1,0),LaneConnection(10,0,0)),Map.empty,
      71.45,30/3.6,.1,0,1,(_,_,_,_) => ()).keySet shouldBe Set(2L)
    val trace=mutable.Buffer.empty[Any]
    val exits=mutable.Set.empty[Long]
    val velocities=mutable.Map(1L -> 8.33,2L -> 8.33)
    var yielded=false
    for (tick <- 1L to 40L if exits.size < 2) {
      val updates=DefaultMicroSimulationStrategy().executeSubTick(lanes,0,tick,71.45,30,.1,1,mutable.Map.empty,
        laneChanging=active,laneConnections=List(LaneConnection(9,1,0),LaneConnection(10,0,0)))
      if (tick <= 30) trace += ((tick,lanes.toVector.map((l,q) => (l,q.map(v => (v.actorId,v.position,v.velocity)).toList))))
      updates.foreach { update =>
        update.velocity should be >= velocities(update.vehicleId)-4.5*.1-1e-8
        if (update.vehicleId==2 && update.velocity < velocities(2L)) yielded=true
        velocities(update.vehicleId)=update.velocity
        if (update.reachedEnd) exits += update.vehicleId
      }
    }
    // Continue long enough to traverse the road after forming the merge gap.
    for (tick <- 41L to 200L if exits.size < 2) {
      DefaultMicroSimulationStrategy().executeSubTick(lanes,0,tick,71.45,30,.1,1,mutable.Map.empty,
        laneChanging=active,laneConnections=List(LaneConnection(9,1,0),LaneConnection(10,0,0)))
        .filter(_.reachedEnd).foreach(u => exits += u.vehicleId)
    }
    yielded shouldBe true
    withClue(trace.toString + lanes.toVector.map((l,q) => (l,q.map(v => (v.actorId,v.position,v.velocity,v.lastLaneChangeSeconds)).toList)).toString) { exits.toSet shouldBe Set(1L,2L) }
  }

  it should "clear short multi-lane weaving layouts with conflicting handoffs and preserve legal exits" in {
    val cases=List(
      (71.45,30.0,4,List((0L,0,car(1,0,8.33)),(0L,1,car(2,0,8.33).copy(nextLinkId=10))),
        List(LaneConnection(9,3,0),LaneConnection(10,0,0))),
      (12.76,50.004,3,List((0L,1,car(1,0,.9)),(0L,0,car(2,0,1.96).copy(nextLinkId=10))),
        List(LaneConnection(9,0,0),LaneConnection(10,1,0),LaneConnection(10,2,1))),
      (94.65,80.0,7,List((0L,4,car(1,0,22.22)),(2L,0,car(2,0,22.22).copy(nextLinkId=10))),
        List(LaneConnection(9,0,0),LaneConnection(9,1,1)) ++ (2 to 6).map(l => LaneConnection(10,l,0))),
      (56.61,50.004,4,List((1L,0,car(1,0,13.89)),(0L,2,car(2,0,13.89).copy(nextLinkId=10))),
        List(LaneConnection(9,1,0),LaneConnection(10,0,0))))
    cases.foreach { case (length,speed,count,arrivals,connections) =>
      val lanes=mutable.Map.from((0 until count).map(l => l -> mutable.Queue.empty[VehicleInLane]))
      arrivals.filter(_._1==0).foreach((at,lane,v) => lanes(lane).enqueue(v.copy(entryTick=at)))
      val exits=mutable.Set.empty[Long]
      for (tick <- 1L to 200L if exits.size < arrivals.size) {
        arrivals.filter(_._1==tick).foreach((at,lane,v) => lanes(lane).enqueue(v.copy(entryTick=at)))
        DefaultMicroSimulationStrategy().executeSubTick(lanes,0,tick,length,speed,.1,10,mutable.Map.empty,
          laneChanging=active,laneConnections=connections).filter(_.reachedEnd).foreach { update =>
            val vehicle=arrivals.find(_._3.actorId==update.vehicleId).get._3
            connections.exists(c => c.outgoingLinkId==vehicle.nextLinkId && c.fromLane==update.currentLane) shouldBe true
            exits += update.vehicleId
          }
      }
      withClue(s"length=$length remaining=${lanes.toVector}") { exits.toSet shouldBe Set(1L,2L) }
    }
  }
  "Native Link input" should "load configurable lane changing, connections, controls and detectors through JSON checkpoints" in {
    val source = """{"startTick":0,"from":1,"to":2,"length":200,"lanes":2,"speedLimit":50,"capacity":100,"freeSpeed":50,
      "simulationMode":"MICRO","laneChanging":{"model":"Mobil","politeness":0.3,"strategicCooldownSeconds":0.1,"cooperativeStrategicChanges":false},
      "laneConnections":[{"outgoingLinkId":9,"fromLane":1,"toLane":0}],
      "signalControls":[{"targetLinkId":9,"laneIds":[1]}],
      "detectors":[{"id":501,"controllerId":40,"laneId":1,"positionMeters":180}]}"""
    val state = core.util.JsonUtil.fromJson[model.mobility.entity.state.LinkState](source).initializeMicroLanes()
    state.vehiclesByLane(1).enqueue(car(1,100).copy(lastLaneChangeSeconds=Some(3)))
    val restored = core.util.JsonUtil.fromJson[model.mobility.entity.state.LinkState](core.util.JsonUtil.toJson(state))
    restored.laneChanging.politeness shouldBe 0.3
    restored.laneChanging.strategicCooldownSeconds shouldBe 0.1
    restored.laneChanging.cooperativeStrategicChanges shouldBe false
    restored.laneConnections shouldBe List(LaneConnection(9,1,0))
    restored.signalControls shouldBe state.signalControls
    restored.detectors shouldBe state.detectors
    restored.vehiclesByLane(1).head.lastLaneChangeSeconds shouldBe Some(3)
    val journey=model.mobility.entity.state.CarJourneyState(lastMicroLane=Some(3),pendingRoadLane=Some(2))
    val restoredJourney=core.util.JsonUtil.fromJson[model.mobility.entity.state.CarJourneyState](core.util.JsonUtil.toJson(journey))
    restoredJourney.lastMicroLane.get + 1 shouldBe 4
    restoredJourney.pendingRoadLane.get + 1 shouldBe 3
  }

}
