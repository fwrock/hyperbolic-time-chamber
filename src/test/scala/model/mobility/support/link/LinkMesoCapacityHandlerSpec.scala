package org.interscity.htc
package model.mobility.support.link

import core.entity.event.ActorInteractionEvent
import core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import core.util.JsonUtil
import model.mobility.entity.event.data.{EnterLinkData,LeaveLinkData}
import model.mobility.entity.event.data.link.LinkInfoData
import model.mobility.entity.state.LinkState
import model.mobility.entity.state.enumeration.{ActorTypeEnum,SimulationModeEnum}
import model.mobility.entity.state.model.MesoCapacityConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

class LinkMesoCapacityHandlerSpec extends AnyFlatSpec with Matchers {
  class Fixture(flow: Double = 3600, storage: Int = 2) {
    var tick = 0L
    var state = LinkState(0,from=1,to=2,length=100,lanes=1,speedLimit=36,capacity=20,freeSpeed=36,
      mesoCapacity=Some(MesoCapacityConfig(flow,storage)))
    val sent = mutable.ArrayBuffer.empty[(Long,LinkInfoData,String,Long)]
    val scheduled = mutable.ArrayBuffer.empty[Long]
    var admissions = 0
    var releases = 0
    def handler = new LinkMesoCapacityHandler(() => state,s => state=s,() => tick,() => 7,
      (id,_,d,kind) => sent += ((id,d.asInstanceOf[LinkInfoData],kind,tick)),
      t => scheduled += t,(_,_) => (),_ => admissions+=1,_ => releases+=1)
    def event(id: Long, seq: Long = 0) = ActorInteractionEvent(tick=tick,lamportTick=tick,
      actorRefId=id,shardRefId="mobility.actor.Car",actorPathRef=id.toString,
      actorClassType="mobility.actor.Car",data="unused",resourceId="test",seq=seq)
    def enter(id: Long, origin: Boolean = false, seq: Long = 0, nextLink: Long = 8): Unit = handler.enter(event(id,seq),
      EnterLinkData(ActorTypeEnum.Car,LoadBalancedDistributed,5,nextLinkId=nextLink,mesoOriginAtEnd=origin))
    def leave(id: Long, seq: Long = 0): Unit = handler.leave(event(id,seq),
      LeaveLinkData(ActorTypeEnum.Car,5,LoadBalancedDistributed))
    def restore(): Unit = state=JsonUtil.fromJson[LinkState](JsonUtil.toJson(state))
  }

  "Separate MESO capacities" should "hold physical entries at storage and admit them immediately after a real release" in {
    val f=new Fixture(storage=1)
    f.enter(10);f.enter(11,seq=77)
    f.state.registered.size shouldBe 1
    f.state.currentSpeed shouldBe model.mobility.util.SpeedUtil.linkDensitySpeed(100,20,1,36,1)
    f.state.mesoCapacityState.entries.map(_.actorId) shouldBe List(11)
    f.scheduled shouldBe empty
    f.tick=5;f.restore();f.leave(10)
    f.state.registered.containsActor(11) shouldBe true
    val ack=f.sent.find(x => x._1==11 && x._3=="ReceiveEnterLinkInfo").get._2
    ack.requestSeq shouldBe 77
    ack.mesoEntryWaitSeconds shouldBe 5.0
  }

  it should "rate limit discharge independently from storage and preserve queued reply identities" in {
    val f=new Fixture(flow=1800,storage=4)
    f.enter(10);f.enter(11);f.enter(12)
    f.tick=10;f.leave(10,1);f.leave(11,2);f.leave(12,3)
    f.state.registered.size shouldBe 2
    f.scheduled.last shouldBe 12
    f.restore();f.tick=12;f.handler.onTick() shouldBe Some(14)
    f.sent.last._2.requestSeq shouldBe 2
    f.sent.last._2.mesoExitWaitSeconds shouldBe 2.0
    f.tick=14;f.handler.onTick() shouldBe None
    f.state.registered.size shouldBe 0
    f.sent.last._2.requestSeq shouldBe 3
  }

  it should "complete destination arrivals without spending or replenishing exhausted through-flow credit" in {
    val f=new Fixture(flow=1800,storage=4)
    f.enter(10);f.enter(11);f.enter(20,nextLink=0);f.enter(21,nextLink=0)
    f.enter(22,nextLink=0,seq=22)
    f.tick=10;f.leave(10,seq=10);f.leave(11,seq=11)
    f.state.mesoCapacityState.credit shouldBe -0.5
    f.state.mesoCapacityState.exits.map(_.actorId) shouldBe List(11)
    f.restore()
    f.state.mesoCapacityState.destinationVehicles.toSet shouldBe Set(20L,21L,22L)
    f.leave(20,seq=20);f.leave(21,seq=21);f.leave(22,seq=23)
    val arrivals=f.sent.filter(x => Set(20L,21L,22L).contains(x._1) && x._3=="ReceiveLeaveLinkInfo")
    arrivals should have size 3
    all(arrivals.map(_._4)) shouldBe 10L
    all(arrivals.map(_._2.linkLength)) shouldBe 100.0
    all(arrivals.map(_._2.mesoExitWaitSeconds)) shouldBe 0.0
    f.state.mesoCapacityState.credit shouldBe -0.5
    f.state.mesoCapacityState.destinationVehicles shouldBe empty
    f.state.mesoCapacityState.exits.map(_.requestSeq) shouldBe List(11L)
    f.enter(30,origin=true)
    f.sent.exists(x => x._1==30 && x._3=="ReceiveEnterLinkInfo") shouldBe false
    f.tick=12;f.handler.onTick() shouldBe Some(14)
    f.sent.last._2.requestSeq shouldBe 11L
    f.state.registered.containsActor(11) shouldBe false
    f.sent.exists(x => x._1==30 && x._3=="ReceiveEnterLinkInfo") shouldBe false
    f.tick=14;f.handler.onTick()
    f.state.registered.containsActor(30) shouldBe true
  }

  it should "delay origin-end admission with shared flow and never add origin road distance" in {
    val f=new Fixture(flow=1800,storage=1)
    f.enter(10,origin=true,seq=10);f.enter(11,origin=true,seq=11)
    f.sent should have size 1
    f.sent.head._2.linkLength shouldBe 0.0
    f.sent.head._2.mesoOriginAtEnd shouldBe true
    f.tick=1;f.leave(10)
    f.scheduled.last shouldBe 2
    f.tick=2;f.handler.onTick()
    val admitted=f.sent.find(x => x._1==11 && x._3=="ReceiveEnterLinkInfo").get
    admitted._4 shouldBe 2
    admitted._2.mesoEntryWaitSeconds shouldBe 2.0
    f.tick=3;f.leave(11)
    f.sent.last._2.linkLength shouldBe 0.0
    f.releases shouldBe 2
  }

  it should "share origin and through-flow debits without charging origin exits twice" in {
    val f=new Fixture(flow=3600,storage=2)
    f.enter(20)
    f.enter(10,origin=true)
    f.leave(20,seq=8)
    f.sent.count(_._3=="ReceiveLeaveLinkInfo") shouldBe 0
    f.tick=1;f.leave(10,seq=9)
    f.sent.filter(_._3=="ReceiveLeaveLinkInfo").map(_._2.requestSeq).toList shouldBe List(9,8)
    f.state.registered.size shouldBe 0
  }

  it should "deduplicate retries and retain FIFO storage order across a checkpoint" in {
    val f=new Fixture(storage=1)
    f.enter(1);f.enter(2,seq=10);f.enter(3,seq=11);f.enter(2,seq=12);f.enter(2,seq=9)
    f.state.mesoCapacityState.entries.map(_.actorId) shouldBe List(2,3)
    f.restore();f.tick=4;f.leave(1)
    f.sent.find(x => x._1==2 && x._3=="ReceiveEnterLinkInfo").get._2.requestSeq shouldBe 12
    f.state.mesoCapacityState.entries.map(_.actorId) shouldBe List(3)
    f.admissions shouldBe 2
  }

  it should "replay pending fractional flow credit identically from an independent snapshot" in {
    val f=new Fixture(flow=1440)
    f.enter(1,origin=true);f.enter(2,origin=true,seq=71);f.enter(3,origin=true,seq=72)
    f.tick=1;f.leave(1)
    val snapshot=JsonUtil.toJson(f.state)
    def replay(): List[(Long,Long)] = {
      val g=new Fixture(flow=1440);g.state=JsonUtil.fromJson[LinkState](snapshot)
      for(t <- 2L to 10L) {
        g.tick=t;g.handler.onTick()
        g.state.mesoCapacityState.originVehicles.toList.foreach(id => g.leave(id))
      }
      g.sent.filter(_._3=="ReceiveEnterLinkInfo").map(x => x._1 -> x._4).toList
    }
    replay() shouldBe replay()
    replay().map(_._1) shouldBe List(2,3)
  }

  it should "not replenish fractional flow twice when several origin exits arrive in one tick" in {
    val f=new Fixture(flow=5400,storage=10)
    (1L to 100L).foreach(id => f.enter(id,origin=true))
    for (t <- 1L to 20L) {
      f.tick=t
      f.state.mesoCapacityState.originVehicles.toList.foreach(id => f.leave(id))
      f.handler.onTick()
    }
    f.admissions should be <= math.ceil(1.5*21).toInt
    f.admissions should be >= 30
  }

  it should "limit idle-time burst credit and reject invalid or MICRO configurations" in {
    val f=new Fixture(flow=7200,storage=10)
    f.tick=10000
    (1L to 5L).foreach(id => f.enter(id,origin=true))
    f.admissions shouldBe 2
    f.state.mesoCapacityState.entries should have size 3
    intercept[IllegalArgumentException](MesoCapacityConfig(0,1))
    intercept[IllegalArgumentException](MesoCapacityConfig(100,0))
    intercept[IllegalArgumentException](f.state.copy(simulationMode=SimulationModeEnum.MICRO))
    LinkState(0,from=1,to=2,length=100,lanes=1,speedLimit=36,capacity=20,freeSpeed=36).mesoCapacity shouldBe None
  }
}
