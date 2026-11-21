package org.interscity.htc
package model.mobility.micro.strategy

import model.mobility.entity.state.LinkState
import model.mobility.entity.state.enumeration.SimulationModeEnum
import model.mobility.entity.state.model.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

object Lc2013LaneChangeStrategySpec {
  case class ShortRoadBlockers(cases: List[ShortRoadBlocker])
  case class ShortRoadBlocker(sourceRoad: Long,length: Double,lanes: Int,speedKmh: Double,
    connections: List[LaneConnection],requester: VehicleInLane,requesterLane: Int,
    blocker: VehicleInLane,blockerLane: Int)
}

class Lc2013LaneChangeStrategySpec extends AnyFlatSpec with Matchers {
  private val active=LaneChangingConfig(model=LaneChangingModel.Lc2013)
  private def car(id: Long,pos: Double,speed: Double=10,next: Long=9)=
    VehicleInLane(id,"cars",pos,speed,0,5,nextLinkId=next)
  private def step(lanes: mutable.Map[Int,mutable.Queue[VehicleInLane]],time: Double=0,
                   connections: List[LaneConnection]=Nil,restrictions: Map[Int,String]=Map.empty,
                   length: Double=1000) = {
    val events=mutable.Buffer.empty[(Long,Int,Int,String)]
    Lc2013LaneChangeStrategy.execute(lanes,active,connections,restrictions,length,20,.1,time,1,
      (id,from,to,reason) => events += ((id,from,to,reason)))
    events.toVector
  }

  "Native LC2013 strategy" should "accumulate speed advantage before a discretionary change" in {
    val lanes=mutable.Map(0->mutable.Queue(car(1,30),car(2,45,0)),1->mutable.Queue.empty[VehicleInLane])
    step(lanes) shouldBe empty
    lanes(0).find(_.actorId==1).get.lc2013Memory.leftGain should be > 0.0
    val events=(1 to 10).flatMap(i => step(lanes,i*.1))
    events.exists(e => e._1==1 && e._4=="lc2013_speed_gain") shouldBe true
  }

  it should "obey zero motivation weights instead of invoking MOBIL" in {
    val p=Lc2013Parameters(strategic=0,cooperative=0,speedGain=0,keepRight=0)
    val lanes=mutable.Map(0->mutable.Queue(car(1,30).copy(lc2013=p),car(2,45,0).copy(lc2013=p)),
      1->mutable.Queue.empty[VehicleInLane])
    (0 to 100).flatMap(i => step(lanes,i*.1)) shouldBe empty
  }

  it should "keep right after sustained free travel while preserving required exit lanes" in {
    def run(connections: List[LaneConnection]) = {
      val lanes=mutable.Map(0->mutable.Queue.empty[VehicleInLane],
        1->mutable.Queue(car(1,100).copy(lc2013=Lc2013Parameters(keepRightTimeSeconds=.5))))
      (0 to 10).flatMap(i => step(lanes,i*.1,connections,length=200)).toVector
    }
    run(Nil) should contain ((1L,1,0,"lc2013_keep_right"))
    run(List(LaneConnection(9,1,0))) shouldBe empty
  }

  it should "vacate a safe adjacent lane to help an urgent lower-ID route request" in {
    val lanes=mutable.Map(0->mutable.Queue(car(1,100)),1->mutable.Queue(car(2,105,next=10)),
      2->mutable.Queue.empty[VehicleInLane])
    val turns=List(LaneConnection(9,1,0),LaneConnection(10,1,0),LaneConnection(10,2,0))
    step(lanes,connections=turns,length=200) should contain ((2L,1,2,"lc2013_cooperative"))
    step(lanes,.1,turns,length=200) should contain ((1L,0,1,"strategic"))
  }

  it should "respect restricted lanes and unsafe target followers" in {
    val turns=List(LaneConnection(9,1,0))
    val restricted=mutable.Map(0->mutable.Queue(car(1,100)),1->mutable.Queue.empty[VehicleInLane])
    step(restricted,connections=turns,restrictions=Map(1->"bus_lane"),length=200) shouldBe empty
    val blocked=mutable.Map(0->mutable.Queue(car(1,100)),
      1->mutable.Queue(car(2,94,20).copy(lastLaneChangeSeconds=Some(0))))
    step(blocked,connections=turns,length=200) shouldBe empty
  }

  it should "arbitrate simultaneous requests deterministically without overlapping swaps" in {
    def run(reverse: Boolean) = {
      val entries=Vector(0->mutable.Queue(car(1,100)),1->mutable.Queue.empty[VehicleInLane],2->mutable.Queue(car(2,100)))
      step(mutable.Map.from(if (reverse) entries.reverse else entries),connections=List(LaneConnection(9,1,0)),length=200)
    }
    run(false) shouldBe run(true)
    run(false) shouldBe Vector((1L,0,1,"strategic"))
  }

  it should "cross multiple adjacent lanes before the road ends with the real physics strategy" in {
    val lanes=mutable.Map.from((0 until 5).map(l => l->mutable.Queue.empty[VehicleInLane]))
    lanes(4).enqueue(car(1,0,22))
    val changes=mutable.Buffer.empty[(Int,Int)]
    var completed=false
    for (tick <- 1L to 100L if !completed) {
      val updates=DefaultMicroSimulationStrategy().executeSubTick(lanes,0,tick,94.65,80,.1,1,mutable.Map.empty,
        laneChanging=active,laneConnections=List(LaneConnection(9,0,0)),
        laneChangedFn=(_,from,to,_) => changes += ((from,to)))
      updates.find(_.reachedEnd).foreach { u => u.currentLane shouldBe 0; completed=true }
    }
    completed shouldBe true
    changes.toVector shouldBe Vector((4,3),(3,2),(2,1),(1,0))
  }

  it should "let a compatible stop-line blocker depart before merging beside it" in {
    for ((requesterId,blockerId) <- List((1L,2L),(2L,1L)); sigma <- List(0.0,0.5)) {
      val length=27.02
      val turns=List(LaneConnection(9,2,0),LaneConnection(10,2,0))
      val lanes=mutable.Map.from((0 until 4).map(l => l->mutable.Queue.empty[VehicleInLane]))
      lanes(1).enqueue(car(requesterId,length-.5,0,9).copy(sigma=sigma,randomSeed=42))
      lanes(2).enqueue(car(blockerId,length-.5,0,10).copy(sigma=sigma,randomSeed=42))
      val finished=mutable.Set.empty[Long]
      val strategy=DefaultMicroSimulationStrategy()
      for (tick <- 1L to 300L if finished.size<2) {
        val updates=strategy.executeSubTick(lanes,0,tick,length,50,.1,1,mutable.Map.empty,
          laneChanging=active,laneConnections=turns)
        updates.filter(_.reachedEnd).foreach { u =>
          withClue(s"illegal exit for $u") { u.currentLane shouldBe 2 }
          finished += u.vehicleId
        }
        lanes.values.foreach { queue =>
          queue.sliding(2).filter(_.size==2).foreach { pair =>
            pair(0).rearPosition-pair(1).position should be >= (2.0-1e-8)
          }
        }
      }
      withClue(s"requester=$requesterId blocker=$blockerId sigma=$sigma lanes=$lanes") {
        finished.toSet shouldBe Set(requesterId,blockerId)
      }
    }
  }

  it should "preserve a compatible blocker's signal stop until its own green interval" in {
    import model.mobility.entity.state.enumeration.TrafficSignalPhaseStateEnum.{Red,Green}
    val lanes=mutable.Map(0->mutable.Queue(car(1,24.5,0,9)),1->mutable.Queue(car(2,24.5,0,10)))
    val turns=List(LaneConnection(9,1,0),LaneConnection(10,1,0))
    val finished=mutable.Set.empty[Long]
    for (tick <- 1L to 300L if finished.size<2) {
      val updates=DefaultMicroSimulationStrategy().executeSubTick(lanes,0,tick,25,50,.1,1,mutable.Map.empty,
        laneChanging=active,laneConnections=turns,signalAtExit=Some(if(tick<=30) Red else Green))
      if(tick<=30) updates.exists(_.reachedEnd) shouldBe false
      updates.filter(_.reachedEnd).foreach { u =>
        u.currentLane shouldBe 1
        finished += u.vehicleId
      }
    }
    finished.toSet shouldBe Set(1L,2L)
  }

  it should "clear the blocker patterns on all fourteen affected Berlin road geometries" in {
    import Lc2013LaneChangeStrategySpec.ShortRoadBlockers
    import model.mobility.entity.state.enumeration.TrafficSignalPhaseStateEnum.{Red,Green}
    val source=scala.io.Source.fromResource("micro/berlin-short-road-blockers.json")
    val cases=try core.util.JsonUtil.fromJson[ShortRoadBlockers](source.mkString).cases finally source.close()
    cases.size shouldBe 14
    for (test <- cases; swapIds <- List(false,true); stochastic <- List(false,true)) {
      val requester=test.requester.copy(actorId=if(swapIds) test.blocker.actorId else test.requester.actorId,
        sigma=if(stochastic) test.requester.sigma else 0)
      val blocker=test.blocker.copy(actorId=if(swapIds) test.requester.actorId else test.blocker.actorId,
        sigma=if(stochastic) test.blocker.sigma else 0)
      val lanes=mutable.Map.from((0 until test.lanes).map(l => l->mutable.Queue.empty[VehicleInLane]))
      lanes(test.requesterLane).enqueue(requester);lanes(test.blockerLane).enqueue(blocker)
      val goals=Map(requester.actorId->requester.nextLinkId,blocker.actorId->blocker.nextLinkId)
      val finished=mutable.Set.empty[Long]
      for (tick <- 1L to 500L if finished.size<2) {
        val updates=DefaultMicroSimulationStrategy().executeSubTick(lanes,0,tick,test.length,test.speedKmh,.1,1,
          mutable.Map.empty,laneChanging=active,laneConnections=test.connections,
          signalAtExit=Some(if(tick<=30) Red else Green),
          laneChangedFn=(_,from,to,_) => math.abs(to-from).shouldBe(1))
        if(tick<=30) updates.exists(_.reachedEnd) shouldBe false
        updates.filter(_.reachedEnd).foreach { u =>
          test.connections.exists(c=>c.outgoingLinkId==goals(u.vehicleId) && c.fromLane==u.currentLane) shouldBe true
          finished += u.vehicleId
        }
        lanes.values.foreach(_.sliding(2).filter(_.size==2).foreach { pair =>
          pair(0).rearPosition-pair(1).position should be >= (pair(1).minGap-1e-8)
        })
      }
      withClue(s"road=${test.sourceRoad} swap=$swapIds stochastic=$stochastic remaining=$lanes") {
        finished.toSet shouldBe goals.keySet
      }
    }
  }

  it should "preserve decision memory through independent Link checkpoints" in {
    def run(restore: Boolean) = {
      var state=LinkState(0,from=1,to=2,length=1000,lanes=2,speedLimit=72,capacity=100,freeSpeed=72,
        simulationMode=SimulationModeEnum.MICRO,laneChanging=active).initializeMicroLanes()
      state.vehiclesByLane(0).enqueue(car(1,30),car(2,45,0))
      val events=mutable.Buffer.empty[(Long,Long,Int,Int,String)]
      for (tick <- 1L to 30L) {
        if (restore && tick==2)
          state=core.util.JsonUtil.fromJson[LinkState](core.util.JsonUtil.toJson(state))
        DefaultMicroSimulationStrategy().executeSubTick(mutable.Map.from(state.vehiclesByLane),0,tick,1000,72,.1,1,
          mutable.Map.empty,laneChanging=active,laneChangedFn=(id,a,b,r) => events += ((tick,id,a,b,r)))
      }
      (events.toVector,state.vehiclesByLane.values.flatMap(_.toList).map(v => v.actorId->(v.position,v.velocity,v.lc2013Memory)).toMap)
    }
    run(true) shouldBe run(false)
  }
}
