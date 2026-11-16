package org.interscity.htc
package model.hybrid.support.trafficsignal

import model.hybrid.entity.state.TrafficSignalState
import model.hybrid.entity.state.model.*
import model.hybrid.entity.event.data.signal.TrafficDetectionData
import model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.{Green,Yellow,Red}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SignalProgramHandlerSpec extends AnyFlatSpec with Matchers {
  private val movements = List(SignalMovement(11,1,2,3),SignalMovement(12,1,2,4))
  private def program(control: SignalControlMode = SignalControlMode.Fixed) = SignalProgram(control,
    List(SignalStage(3,List(11),minDurationSeconds=Some(3),maxDurationSeconds=Some(7),detectors=List(90)),
      SignalStage(1,yellow=List(11)),SignalStage(2,List(12))),movements)
  private class Harness(p: SignalProgram, quantum: Double = 1.0) {
    var state = TrafficSignalState(0,program=Some(p))
    var next: Option[Long] = None
    val messages = scala.collection.mutable.Buffer.empty[AnyRef]
    val handler = new SignalProgramHandler(() => state,() => "5",(_,_,data,_) => messages += data,(_,_) => (),
      t => next=t,100,() => quantum)
  }
  "Native signal program" should "retain ordered green, yellow and all-red movement aspects" in {
    val h = new Harness(program())
    h.handler.step(0); h.next shouldBe Some(3L)
    h.state.signalStates(11).state shouldBe Green
    h.state.signalStates(12).state shouldBe Red
    h.handler.step(3); h.next shouldBe Some(4L)
    h.state.signalStates(11).state shouldBe Yellow
    h.handler.step(4)
    h.state.signalStates(11).state shouldBe Red
    h.state.signalStates(12).state shouldBe Green
    h.handler.step(6); h.state.programRuntime.stageIndex shouldBe 0
  }
  it should "gap out at the minimum with no detector demand" in {
    val h = new Harness(program(SignalControlMode.GapActuated))
    h.handler.step(0); h.handler.step(3)
    h.state.programRuntime.stageIndex shouldBe 1
  }
  it should "extend on demand and enforce the maximum" in {
    val h = new Harness(program(SignalControlMode.GapActuated))
    h.handler.step(0)
    for (tick <- 3L to 7L) {
      h.handler.detection(tick-1,TrafficDetectionData(90,(tick-1).toDouble))
      h.handler.step(tick)
      h.state.programRuntime.stageIndex shouldBe (if (tick < 7) 0 else 1)
    }
  }
  it should "ignore same-tick detector messages regardless of arrival order" in {
    val a = new Harness(program(SignalControlMode.GapActuated))
    val b = new Harness(program(SignalControlMode.GapActuated))
    a.handler.step(0); b.handler.step(0)
    a.handler.detection(3,TrafficDetectionData(90,3)); a.handler.step(3)
    b.handler.step(3); b.handler.detection(3,TrafficDetectionData(90,3))
    a.state.programRuntime shouldBe b.state.programRuntime
    a.next shouldBe b.next
  }
  it should "resume from checkpointed stage and detector cohorts" in {
    val a = new Harness(program(SignalControlMode.GapActuated)); a.handler.step(0)
    a.handler.detection(2,TrafficDetectionData(90,2)); a.handler.step(3)
    val b = new Harness(program(SignalControlMode.GapActuated))
    b.state = a.state.copy(signalStates=scala.collection.mutable.Map.from(a.state.signalStates))
    a.handler.step(4); b.handler.step(4)
    a.state.programRuntime shouldBe b.state.programRuntime
    a.next shouldBe b.next
  }
  it should "validate detector identities, future times and phase bounds" in {
    val h = new Harness(program(SignalControlMode.GapActuated))
    intercept[IllegalArgumentException](h.handler.detection(1,TrafficDetectionData(91,1)))
    intercept[IllegalArgumentException](h.handler.detection(1,TrafficDetectionData(90,2)))
    intercept[IllegalArgumentException](SignalStage(1,minDurationSeconds=Some(3),maxDurationSeconds=Some(2)))
  }
  it should "apply a signal change to the following physics interval" in {
    LinkMovementSignal(1,phase=Green,previousPhase=Red,effectiveTick=10).at(10) shouldBe Red
    LinkMovementSignal(1,phase=Green,previousPhase=Red,effectiveTick=10).at(11) shouldBe Green
  }
  it should "load native input without legacy signal-window fields and checkpoint the runtime" in {
    val state = core.util.JsonUtil.fromJson[TrafficSignalState]("""{
      "startTick":0,"program":{"control":"GapActuated",
      "stages":[{"durationSeconds":10,"minDurationSeconds":2,"maxDurationSeconds":5,"green":[11],"detectors":[90]}],
      "movements":[{"origin":11,"nodeId":1,"incomingLinkId":2,"outgoingLinkId":3,"laneIds":[1]}]}}
      """)
    val h = new Harness(state.program.get)
    h.handler.step(0); h.handler.detection(1,TrafficDetectionData(90,1)); h.handler.step(2)
    val restored = core.util.JsonUtil.fromJson[TrafficSignalState](core.util.JsonUtil.toJson(h.state))
    restored.program shouldBe h.state.program
    restored.programRuntime shouldBe h.state.programRuntime
    restored.signalStates(11L).state shouldBe Green
  }

  it should "publish only changed aspects while extending a green stage locally" in {
    val h = new Harness(program(SignalControlMode.GapActuated))
    h.handler.step(0)
    h.messages.size shouldBe 1
    h.handler.detection(2,TrafficDetectionData(90,2)); h.handler.step(3)
    h.state.programRuntime.stageIndex shouldBe 0
    h.next shouldBe Some(4)
    h.messages.size shouldBe 1
    h.state.signalStates(11).nextTick shouldBe 4
    h.handler.step(5)
    h.state.signalStates(11).state shouldBe Yellow
    h.messages.size shouldBe 2
  }

  it should "keep stage durations and detector gaps in seconds on a fractional runtime clock" in {
    val h=new Harness(program(SignalControlMode.GapActuated),.1)
    h.handler.step(0);h.next shouldBe Some(30L)
    for (tick <- List(30L,49L,68L)) {
      h.handler.detection(tick-1,TrafficDetectionData(90,(tick-1)*.1))
      h.handler.step(tick)
      h.state.programRuntime.stageIndex shouldBe 0
    }
    h.next shouldBe Some(70L)
    h.handler.step(70)
    h.state.signalStates(11).state shouldBe Yellow
    h.next shouldBe Some(80L)
  }

}
