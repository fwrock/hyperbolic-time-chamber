package org.interscity.htc
package model.mobility.support.link

import core.entity.event.ActorInteractionEvent
import core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import model.mobility.entity.event.data.{EnterLinkData,LeaveLinkData,MicroEnterLinkData,MicroLeaveLinkData}
import model.mobility.entity.state.LinkState
import model.mobility.entity.state.enumeration.{ActorTypeEnum,SimulationModeEnum}
import model.mobility.entity.state.model.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

class MicroEntryAdmissionSpec extends AnyFlatSpec with Matchers {
  private class Road(length: Double, lanes: Int, speed: Double, connections: List[LaneConnection]=Nil,
                     enabled: Boolean=true, laneModel: LaneChangingModel=LaneChangingModel.Mobil,
                     snapshotTick: Option[Long]=None) {
    var tick=0L
    var state=LinkState(0,from=1,to=2,length=length,lanes=lanes,speedLimit=speed,capacity=100,freeSpeed=speed,
      simulationMode=SimulationModeEnum.MICRO,microTicksPerGlobalTick=1,
      laneChanging=LaneChangingConfig(model=laneModel),laneConnections=connections,
      microEntry=MicroEntryConfig(enabled=enabled,initialSnapshotTick=snapshotTick)).initializeMicroLanes()
    var scheduled=false
    val entryTicks=mutable.Map.empty[Long,Long]
    val waiting=mutable.Map.empty[Long,Double]
    val sent=mutable.Buffer.empty[(Long,AnyRef)]
    val reports=mutable.Buffer.empty[(String,Map[String,Any])]
    val exits=mutable.Set.empty[Long]
    val metrics=new LinkMetricsReporter((_,_)=>(),()=>"7",()=>tick,60,1000,()=>state,
      entryTicks.get, id=>waiting.getOrElse(id,0.0),()=>state.registered.iterator.map(_.actorId).toIterable,_=>())
    val flow=new LinkVehicleFlowHandler(()=>"7",()=>tick,(id,_,data,_)=>{sent += ((id,data));()},_=>(),
      ()=>state,s=>state=s,(id,t)=>{entryTicks(id)=t},entryTicks.get,id=>{entryTicks.remove(id);()},
      id=>waiting.getOrElseUpdate(id,0),id=>{waiting.remove(id);()},id=>waiting.getOrElse(id,0),
      ()=>scheduled,b=>scheduled=b,metrics,
      id=>state.vehiclesByLane.collectFirst{case(l,q) if q.exists(_.actorId==id)=>l},()=>0,_=>(),
      (data,label)=>{reports += ((label,data));()})
    val physics=new LinkMicroSimulationHandler(()=>"7",()=>tick,(id,_,data,_)=>{sent += ((id,data));()},
      ()=>state,s=>state=s,entryTicks.get,id=>{entryTicks.remove(id);()},id=>waiting.getOrElse(id,0),
      id=>{waiting.remove(id);()},waiting,()=>scheduled,b=>scheduled=b,()=>None,metrics,_=>(),
      reportFn=(data,label)=>{reports += ((label,data));()})
    def event(id: Long, seq: Long=0)=ActorInteractionEvent(tick,tick,id,"mobility.actor.Car",id.toString,
      "mobility.actor.Car",data="unused",resourceId="test",seq=seq)
    def enter(id: Long,lane: Int,next: Long=9,pos: Double=0,velocity: Double=0,seq: Long=0): Unit =
      flow.handleEnterLinkMicro(event(id,seq),EnterLinkData(ActorTypeEnum.Car,LoadBalancedDistributed,5,
        initialVelocity=velocity,nextLinkId=next,initialPosition=pos,preferredLane=Some(lane)))
    def advance(): Unit = {
      tick+=1
      metrics.ensureSummaryTick(tick)
      flow.drainMicroEntries()
      val start=sent.size
      physics.handleGlobalTick(tick)
      sent.drop(start).collect{case(id,d:MicroLeaveLinkData)=>(id,d)}.foreach{case(id,_)=>
        exits += id
        flow.handleLeaveLink(event(id),LeaveLinkData(ActorTypeEnum.Car,5,LoadBalancedDistributed),true)
      }
    }
    def acknowledgements(id: Long)=sent.collect{case(`id`,d:MicroEnterLinkData)=>d}.toList
  }

  "Queued MICRO entry" should "preserve an occupied departure position and acknowledge its real insertion time" in {
    val road=new Road(200,1,50)
    road.enter(1,0,pos=5.1)
    road.enter(2,0,pos=5.1,seq=17)
    road.state.pendingMicroEntries.map(_.actorId) shouldBe List(2L)
    road.acknowledgements(2) shouldBe empty
    road.state.totalVehiclesInMicro shouldBe 1
    for (_ <- 1 to 100 if road.acknowledgements(2).isEmpty) road.advance()
    val ack=road.acknowledgements(2).head
    ack.initialPosition shouldBe 5.1
    ack.initialVelocity shouldBe Some(0.0)
    ack.queuedWaitSeconds shouldBe road.tick*.1 +- 1e-9
    ack.requestSeq shouldBe 17L
    road.entryTicks(2) shouldBe road.tick
    road.state.pendingMicroEntries shouldBe empty
  }

  it should "restore a measured initial snapshot in either spatial arrival order" in {
    for (order <- List(List(1L,2L),List(2L,1L))) {
      val road=new Road(100,1,50,snapshotTick=Some(0L))
      order.foreach { id =>
        road.enter(id,0,pos=if (id==1) 20 else 35,velocity=if (id==1) 3 else 4,seq=17+id)
      }
      road.acknowledgements(1).head.initialPosition shouldBe 20.0
      road.acknowledgements(2).head.initialPosition shouldBe 35.0
      road.acknowledgements(1).head.initialVelocity shouldBe Some(3.0)
      road.acknowledgements(2).head.initialVelocity shouldBe Some(4.0)
      road.acknowledgements(1).head.requestSeq shouldBe 18L
      road.acknowledgements(2).head.requestSeq shouldBe 19L
      road.state.pendingMicroEntries shouldBe empty
      road.state.vehiclesByLane(0).map(_.actorId).toList shouldBe List(2L,1L)
    }
  }

  it should "reject overlapping snapshots instead of moving or delaying measured vehicles" in {
    val road=new Road(100,1,50,snapshotTick=Some(0L))
    road.enter(1,0,pos=20)
    intercept[IllegalArgumentException](road.enter(2,0,pos=22))
    road.acknowledgements(2) shouldBe empty
    road.state.totalVehiclesInMicro shouldBe 1
    road.state.vehiclesByLane(0).head.position shouldBe 20.0
  }

  it should "reject invalid measured lengths before admitting a snapshot vehicle" in {
    for (length <- List(0.0,-1.0,Double.NaN,Double.PositiveInfinity)) {
      val road=new Road(100,1,50,snapshotTick=Some(0L))
      intercept[IllegalArgumentException] {
        road.flow.handleEnterLinkMicro(road.event(1),EnterLinkData(ActorTypeEnum.Car,
          LoadBalancedDistributed,length,initialPosition=20,preferredLane=Some(0)))
      }
      road.state.totalVehiclesInMicro shouldBe 0
      road.acknowledgements(1) shouldBe empty
    }
  }

  it should "keep ordinary queue rules after the configured initial snapshot tick" in {
    val road=new Road(100,1,50,snapshotTick=Some(0L))
    road.enter(1,0,pos=20)
    road.tick=1L
    road.enter(2,0,pos=35)
    road.acknowledgements(2) shouldBe empty
    road.state.pendingMicroEntries.map(_.actorId) shouldBe List(2L)
    intercept[IllegalArgumentException](MicroEntryConfig(initialSnapshotTick=Some(0L)))
  }

  it should "retain the old clamped-position behavior when disabled explicitly" in {
    val road=new Road(200,1,50,enabled=false)
    road.enter(1,0,pos=5.1);road.enter(2,0,pos=5.1)
    road.acknowledgements(2).head.initialPosition shouldBe 0.0
    road.state.pendingMicroEntries shouldBe empty
  }

  it should "wait instead of shifting a requested position even at a sub-nanometre boundary" in {
    val road=new Road(200,1,50)
    road.enter(1,0,pos=5.1)
    val blocker=road.state.vehiclesByLane(0).dequeue()
    road.state.vehiclesByLane(0).enqueue(blocker.copy(position=12.1-1e-10))
    road.enter(2,0,pos=5.1)
    road.acknowledgements(2) shouldBe empty
    road.state.vehiclesByLane(0).clear()
    road.advance()
    road.acknowledgements(2).head.initialPosition shouldBe 5.1
  }

  it should "retain each queued driver's parameters and wait for its larger entry gap" in {
    val road=new Road(200,1,50)
    road.enter(1,0,pos=14)
    road.flow.handleEnterLinkMicro(road.event(2,17),EnterLinkData(ActorTypeEnum.Car,LoadBalancedDistributed,5,
      initialPosition=5,minGap=Some(5),reactionTime=Some(1.8),maxSpeed=Some(7),
      speedFactor=.8,desiredMaxSpeed=Some(6),sigma=.5,randomSeed=9007199254740993L,
      lc2013=Lc2013Parameters(strategic=2,keepRight=0)))
    road.acknowledgements(2) shouldBe empty
    road.state=core.util.JsonUtil.fromJson[LinkState](core.util.JsonUtil.toJson(road.state))
    val data=road.state.pendingMicroEntries.head.data
    data.minGap.get+1 shouldBe 6.0
    data.reactionTime.get+1 shouldBe 2.8
    data.maxSpeed.get+1 shouldBe 8.0
    data.speedFactor shouldBe .8
    data.desiredMaxSpeed.get+1 shouldBe 7.0
    data.sigma shouldBe .5
    data.randomSeed shouldBe 9007199254740993L
    data.lc2013.strategic shouldBe 2.0
    road.state.vehiclesByLane(0).clear()
    road.advance()
    val vehicle=road.state.vehiclesByLane(0).head
    vehicle.minGap shouldBe 5.0
    vehicle.reactionTime shouldBe 1.8
    vehicle.maxSpeed shouldBe Some(7.0)
    vehicle.speedFactor shouldBe .8
    vehicle.desiredMaxSpeed shouldBe Some(6.0)
    vehicle.sigma shouldBe .5
    vehicle.randomSeed shouldBe 9007199254740993L
    vehicle.lc2013.keepRight shouldBe 0.0
    road.acknowledgements(2).head.initialPosition shouldBe 5.0
  }

  it should "prevent the observed short-road opposing-lane deadlocks without illegal lane exits" in {
    val cases=List((4.96,4,50.004,1,0,List(LaneConnection(9,0,0),LaneConnection(10,3,1))),
      (29.41,3,60.012,2,0,List(LaneConnection(9,0,0),LaneConnection(10,1,0),LaneConnection(10,2,1))))
    cases.foreach{case(length,lanes,speed,first,second,connections)=>
      for (enabled <- List(false,true)) {
        val road=new Road(length,lanes,speed,connections,enabled)
        road.enter(1,first,9,velocity=speed/3.6);road.enter(2,second,10,velocity=speed/3.6)
        if (enabled) road.state.pendingMicroEntries.map(_.actorId) shouldBe List(2L)
        for (_ <- 1 to 300 if road.exits.size<2) road.advance()
        withClue(s"length=$length enabled=$enabled state=${road.state.vehiclesByLane}") {
          if (enabled) road.exits.toSet shouldBe Set(1L,2L)
          else road.exits shouldBe empty
        }
        road.sent.collect{case(id,d:MicroLeaveLinkData)=>(id,d)}.foreach{case(id,exit)=>
          connections.exists(c=>c.outgoingLinkId==(if(id==1)9L else 10L) && exit.finalLane.contains(c.fromLane)) shouldBe true
        }
        road.reports.filter(_._1=="lane_change").foreach{case(_,data)=>
          math.abs(data("from_lane").asInstanceOf[Int]-data("to_lane").asInstanceOf[Int]) shouldBe 1
        }
      }
    }
  }

  it should "allow independent legal lanes and long-road cooperative maneuvers concurrently" in {
    val legal=new Road(4.96,4,50,List(LaneConnection(9,0,0),LaneConnection(10,3,0)))
    legal.enter(1,0,9);legal.enter(2,3,10)
    legal.state.totalVehiclesInMicro shouldBe 2
    val long=new Road(71.45,2,30,List(LaneConnection(9,1,0),LaneConnection(10,0,0)))
    long.enter(1,0,9,velocity=8.33);long.enter(2,1,10,velocity=8.33)
    long.state.pendingMicroEntries shouldBe empty
    for (_ <- 1 to 250 if long.exits.size<2) long.advance()
    long.exits.toSet shouldBe Set(1L,2L)
  }

  it should "reserve short-road weaving space for the LC2013 strategy too" in {
    for ((length,count,first,second,turns) <- List(
      (4.96,4,1,0,List(LaneConnection(9,0,0),LaneConnection(10,3,1))),
      (29.41,3,2,0,List(LaneConnection(9,0,0),LaneConnection(10,1,0),LaneConnection(10,2,1))))) {
      val road=new Road(length,count,60,turns,laneModel=LaneChangingModel.Lc2013)
      road.enter(1,first,9,velocity=60/3.6);road.enter(2,second,10,velocity=60/3.6)
      road.state.pendingMicroEntries.map(_.actorId) shouldBe List(2L)
      for (_ <- 1 to 300 if road.exits.size<2) road.advance()
      road.exits.toSet shouldBe Set(1L,2L)
    }
  }

  it should "checkpoint typed pending requests and deduplicate retries before resuming" in {
    val road=new Road(200,1,50)
    road.enter(1,0,pos=5.1);road.enter(2,0,pos=5.1,seq=5);road.enter(2,0,pos=5.1,seq=6)
    road.state.pendingMicroEntries.size shouldBe 1
    val original=road.state
    road.state=core.util.JsonUtil.fromJson[LinkState](core.util.JsonUtil.toJson(original))
    val pending=road.state.pendingMicroEntries.head
    pending.data.actorType shouldBe ActorTypeEnum.Car
    pending.data.preferredLane.get+1 shouldBe 1
    pending.requestSeq shouldBe 6L
    for (_ <- 1 to 100 if road.acknowledgements(2).isEmpty) road.advance()
    road.acknowledgements(2).size shouldBe 1
    road.state.pendingMicroEntries shouldBe empty
    original.pendingMicroEntries.size shouldBe 1
  }
}
