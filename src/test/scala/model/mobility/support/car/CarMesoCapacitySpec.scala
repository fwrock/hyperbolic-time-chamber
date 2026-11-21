package org.interscity.htc
package model.mobility.support.car

import model.mobility.entity.event.data.link.LinkInfoData
import model.mobility.entity.state.CarState
import model.mobility.entity.state.enumeration.{ActorTypeEnum,MovableStatusEnum}
import model.mobility.entity.state.DriverAttributes
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

class CarMesoCapacitySpec extends AnyFlatSpec with Matchers {
  "A MESO entry whose wake the link already scheduled" should "keep the car's state but not schedule a second wake" in {
    def run(delegatedExitTick: Long): (Option[Long], Seq[Option[Long]]) = {
      var state=CarState(0,origin=1,destination=3,actorType=ActorTypeEnum.Car,size=7.5)
      state.status=MovableStatusEnum.Waiting
      var tick=4L
      var exit: Option[Long]=None
      val wakes=mutable.ArrayBuffer.empty[Option[Long]]
      val reporter=new CarJourneyReporter((_,_)=>(),()=>"42",()=>tick,
        ()=>Some(1),()=>Some(3),()=>Some(0),()=>DriverAttributes(),()=>state.journey)
      val handler=new CarLinkHandler((_,_)=>(),()=>"42",()=>tick,reporter,
        t=>wakes+=t,(_,_)=>(),_=>(),_=>(),_=>(),_=>(),()=>None,e=>exit=e,()=>Some(3))
      handler.handleEnterLink(7,LinkInfoData(100,100,1,36,1,delegatedExitTick=delegatedExitTick),state)
      (exit, wakes.toSeq)
    }
    val own = run(-1L)
    own._2 shouldBe Seq(own._1)            // the car schedules its own exit tick
    val delegated = run(own._1.get)
    delegated._1 shouldBe own._1           // same state either way
    delegated._2 shouldBe empty            // the link owns the schedule
  }

  "MESO origin-end acknowledgement" should "record actual departure, preserve zero distance and hand off without an extra tick" in {
    var state=CarState(0,origin=1,destination=3,actorType=ActorTypeEnum.Car,size=7.5,mesoOriginLinkId=Some(7))
    state.status=MovableStatusEnum.Waiting
    state.bestRoute=Some(mutable.Queue(8L -> 3L))
    var tick=4L
    var entry: Option[Long]=None
    val wakes=mutable.ArrayBuffer.empty[Option[Long]]
    val reports=mutable.ArrayBuffer.empty[(String,Map[String,Any])]
    val reporter=new CarJourneyReporter((d,l)=>reports += l->d,()=>"42",()=>tick,
      ()=>Some(1),()=>Some(3),()=>Some(0),()=>DriverAttributes(),()=>state.journey)
    def handler=new CarLinkHandler((d,l)=>reports += l->d,()=>"42",()=>tick,reporter,
      t=>wakes+=t,(_,_)=>(),_=>(),_=>(),_=>(),t=>entry=t,()=>entry,_=>(),()=>Some(3))
    handler.handleEnterLink(7,LinkInfoData(0,100,0,36,1,mesoOriginAtEnd=true,mesoEntryWaitSeconds=4),state)
    state.journey.sumoDepartTick shouldBe Some(4)
    wakes.last shouldBe Some(5)
    reports.last._1 shouldBe "meso_origin_admitted"
    state=core.util.JsonUtil.fromJson[CarState](core.util.JsonUtil.toJson(state))
    state.journey.mesoOriginActive shouldBe true
    tick=5;handler.handleLeaveLink(7,LinkInfoData(0,100,0,36,1,mesoOriginAtEnd=true),state)
    state.distance shouldBe 0.0
    state.journey.sumoIdealTravelTimeSeconds shouldBe 0.0
    wakes.last shouldBe Some(5)
    state.journey.mesoOriginActive shouldBe false
    handler.handleEnterLink(8,LinkInfoData(100,100,1,36,1,mesoEntryWaitSeconds=2),state)
    reports.last._1 shouldBe "enter_link"
    state.journey.sumoDepartTick shouldBe Some(4)
    state.journey.sumoWaitingTimeSeconds should be >= 2.0
    state.journey.sumoIdealTravelTimeSeconds shouldBe 10.0
    reporter.finishJourney("reached_destination",3,state)
    val trip=reports.find(_._1=="sumo_tripinfo").get._2
    trip("scheduledDepart") shouldBe 0.0
    trip("actualDepart") shouldBe 4.0
    trip("departDelay") shouldBe 4.0
  }
}
