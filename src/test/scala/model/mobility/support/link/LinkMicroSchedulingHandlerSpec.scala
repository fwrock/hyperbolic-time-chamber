package org.interscity.htc
package model.mobility.support.link

import core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import core.util.JsonUtil
import model.mobility.entity.event.data.{EnterLinkData, LinkAccessRequest, MicroLeaveLinkData}
import model.mobility.entity.event.data.vehicle.RequestLinkAccessData
import model.mobility.entity.event.node.LinkAccessData
import model.mobility.entity.state.LinkState
import model.mobility.entity.state.enumeration.{ActorTypeEnum, LinkCapacityStateEnum, SimulationModeEnum}
import model.mobility.entity.state.enumeration.TrafficSignalPhaseStateEnum.{Green, Red}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

class LinkMicroSchedulingHandlerSpec extends AnyFlatSpec with Matchers {
  private class Fixture(enabled: Boolean = true) {
    var state = LinkState(0L, from = 1L, to = 2L, length = 100, lanes = 2, speedLimit = 50,
      capacity = 20, freeSpeed = 50, simulationMode = SimulationModeEnum.MICRO)
    val sent = mutable.ArrayBuffer.empty[(Long, String, AnyRef, String)]
    val left = mutable.ArrayBuffer.empty[Long]
    val handler = new LinkMicroSchedulingHandler(() => state, () => enabled,
      (id, shard, data, event) => { sent += ((id, shard, data, event)); () },
      id => { left += id; state = state.copy(); () })
    val access = LinkAccessRequest(2L, "mobility.actor.Node",
      RequestLinkAccessData(30L, approachLinkId = 10L, approachLane = Some(0), followingLinkId = 40L))
    val entry = EnterLinkData(ActorTypeEnum.Car, LoadBalancedDistributed, 4.5,
      microAccessRequest = Some(access), delegateMicroScheduling = true)
    val exit = MicroLeaveLinkData(10L, 100, 12, 4, 90, 22.5, waitingTimeSeconds = 1.2,
      remainingStepSeconds = 0.04, finalLane = Some(1))
    def admit(id: Long = 11L, data: EnterLinkData = entry): Unit = handler.admit(id, data, 7L)
    def finish(id: Long = 11L, data: MicroLeaveLinkData = exit): Unit =
      handler.exit(id, "mobility.actor.Car", data)
    def answer(phase: model.mobility.entity.state.enumeration.TrafficSignalPhaseStateEnum = Green,
               capacity: LinkCapacityStateEnum = LinkCapacityStateEnum.Available): LinkAccessData =
      LinkAccessData(phase, 8L, capacityState = capacity, requestSeq = 7L,
        requesterId = 11L, requesterShard = "mobility.actor.Car")
    def reply: MicroLeaveLinkData = sent.last._3.asInstanceOf[MicroLeaveLinkData]
  }

  "MICRO delegation" should "require both the runtime capability and the vehicle opt-in" in {
    for ((enabled, requested) <- Seq(false -> true, true -> false)) {
      val f = new Fixture(enabled)
      f.admit(data = f.entry.copy(delegateMicroScheduling = requested))
      f.finish()
      f.state.microDelegations shouldBe empty
      f.state.microAccessAwaiting shouldBe empty
      f.sent.map(_._3).toSeq shouldBe Seq(f.exit)
      f.left shouldBe empty
    }
  }

  it should "wait for physics and request access with the actual crossing lane and the vehicle's identity" in {
    val f = new Fixture
    f.admit()
    f.sent shouldBe empty
    f.finish()
    val request = f.sent.head._3.asInstanceOf[RequestLinkAccessData]
    request shouldBe f.access.request.copy(signalAlreadyCleared = true, approachLane = Some(1),
      requesterId = 11L, requesterShard = "mobility.actor.Car", requesterSeq = 7L)
    f.state.microDelegations shouldBe empty
    f.state.microAccessAwaiting.keySet shouldBe Set(11L)
    f.left shouldBe empty
  }

  it should "preserve lane-selection semantics for vehicle types that do not request a crossing lane" in {
    val f = new Fixture
    f.admit(data = f.entry.copy(actorType = ActorTypeEnum.Bus,
      microAccessRequest = Some(f.access.copy(request = f.access.request.copy(approachLane = None)))))
    f.finish()
    f.sent.head._3.asInstanceOf[RequestLinkAccessData].approachLane shouldBe None
  }

  it should "refresh a repeated entry's correlation without letting an older entry overwrite it" in {
    val f = new Fixture
    f.admit()
    f.handler.admit(11L, f.entry, 9L)
    f.handler.admit(11L, f.entry, 7L)
    f.finish()
    f.sent.head._3.asInstanceOf[RequestLinkAccessData].requesterSeq shouldBe 9L
    f.handler.forwardNodeAccess(f.answer().copy(requestSeq = 9L)) shouldBe true
    f.left.toSeq shouldBe Seq(11L)
  }

  it should "combine a grant with the unchanged physics report and clean up exactly once" in {
    val f = new Fixture
    f.admit(); f.finish()
    val grant = f.answer()
    f.handler.forwardNodeAccess(grant) shouldBe true
    f.reply shouldBe f.exit.copy(access = Some(grant), leaveDone = true)
    f.left.toSeq shouldBe Seq(11L)
    f.state.microAccessAwaiting shouldBe empty
    f.handler.forwardNodeAccess(grant) shouldBe false
    f.left.toSeq shouldBe Seq(11L)
  }

  it should "deliver Red and Full answers without releasing the approach capacity" in {
    for ((phase, capacity) <- Seq(Red -> LinkCapacityStateEnum.Available, Green -> LinkCapacityStateEnum.Full)) {
      val f = new Fixture
      f.admit(); f.finish()
      val answer = f.answer(phase, capacity)
      f.handler.forwardNodeAccess(answer) shouldBe true
      f.reply shouldBe f.exit.copy(access = Some(answer))
      f.left shouldBe empty
      f.state.microAccessAwaiting shouldBe empty
      // A later capacity grant must be forwarded through the existing vehicle reply path.
      f.handler.forwardNodeAccess(f.answer()) shouldBe false
    }
  }

  it should "retain the physical footprint until downstream admission even after a green grant" in {
    val f = new Fixture
    f.admit(); f.finish(data = f.exit.copy(retainedInLane = true))
    f.handler.forwardNodeAccess(f.answer()) shouldBe true
    f.reply.leaveDone shouldBe false
    f.reply.retainedInLane shouldBe true
    f.left shouldBe empty
  }

  it should "complete a terminal road without asking the Node and preserve retained terminal cleanup" in {
    for (retained <- Seq(false, true)) {
      val f = new Fixture
      f.admit(data = f.entry.copy(microAccessRequest = None))
      f.finish(data = f.exit.copy(retainedInLane = retained))
      f.sent.size shouldBe 1
      f.reply shouldBe f.exit.copy(retainedInLane = retained, leaveDone = !retained)
      f.left.toSeq shouldBe (if (retained) Seq.empty else Seq(11L))
    }
  }

  it should "keep a pending reply when an obsolete request sequence arrives" in {
    val f = new Fixture
    f.admit(); f.finish()
    f.handler.forwardNodeAccess(f.answer().copy(requestSeq = 6L)) shouldBe true
    f.state.microAccessAwaiting.keySet shouldBe Set(11L)
    f.sent.size shouldBe 1
    f.handler.forwardNodeAccess(f.answer()) shouldBe true
    f.left.toSeq shouldBe Seq(11L)
  }

  it should "retain admission and pending reply obligations through JSON state restoration" in {
    val f = new Fixture
    f.admit()
    f.state = JsonUtil.fromJson[LinkState](JsonUtil.toJson(f.state))
    f.state.microDelegations.keySet shouldBe Set(11L)
    f.finish()
    f.state = JsonUtil.fromJson[LinkState](JsonUtil.toJson(f.state))
    f.state.microAccessAwaiting.keySet shouldBe Set(11L)
    f.handler.forwardNodeAccess(f.answer()) shouldBe true
    f.reply shouldBe f.exit.copy(access = Some(f.answer()), leaveDone = true)
  }

  it should "preserve another pending exit when cleanup replaces the Link state" in {
    val f = new Fixture
    f.admit(11L); f.admit(12L)
    f.finish(11L); f.finish(12L)
    f.handler.forwardNodeAccess(f.answer()) shouldBe true
    f.state.microAccessAwaiting.keySet shouldBe Set(12L)
    f.handler.forwardNodeAccess(f.answer().copy(requesterId = 12L)) shouldBe true
    f.left.toSeq shouldBe Seq(11L, 12L)
    f.state.microAccessAwaiting shouldBe empty
  }
  it should "use the MICRO context independently of a different MESO access request" in {
    val f = new Fixture
    val meso = f.access.copy(request = f.access.request.copy(targetLinkId = 999L))
    f.admit(data = f.entry.copy(accessRequest = Some(meso)))
    f.finish()
    f.sent.head._3.asInstanceOf[RequestLinkAccessData].targetLinkId shouldBe 30L
  }

}
