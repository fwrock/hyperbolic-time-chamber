package org.interscity.htc
package model.hybrid.support.trafficsignal

import core.entity.actor.ShardActorId
import core.types.Tick
import model.hybrid.entity.state.TrafficSignalState
import model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum
import model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.{ Green, Red }
import model.hybrid.entity.state.model.{ Phase, SignalState }
import model.mobility.entity.state.enumeration.{ TrafficSignalPhaseStateEnum => PhaseDefStateEnum }

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** Regression coverage for the bug fixed 2026-07-31 (docs/KNOWN_GAPS.md, "TrafficSignalPhaseHandler
  * Never Transitions Past Its First Phase"): `handlePhaseTransition` used to compute its own
  * next-reevaluation tick as the start of the next FULL cycle (`((ticksSinceStart / cycleDuration)
  * + 1) * cycleDuration`), which for a phase with `greenStart=0` always lands back inside the Green
  * window — so the signal notified Green once at tick 0 and never re-evaluated the Red transition
  * at `greenStart + greenDuration`, for the rest of the simulation. Confirmed via
  * `tools/sumo_validation`'s scenario_b (60s cycle, 30s green): only one `signal_phase_change`
  * event was ever recorded in a 240-tick run.
  */
class TrafficSignalPhaseHandlerSpec extends AnyFlatSpec with Matchers {

  private val phaseOrigin    = "sig_b_phase"
  private val nodeId         = "n_b"
  private val cycleDuration  = 60L
  private val greenStart     = 0L
  private val greenDuration  = 30L

  private class Harness {
    val scheduledTicks: mutable.ListBuffer[Tick] = mutable.ListBuffer.empty
    val notifiedStates: mutable.ListBuffer[(Tick, TrafficSignalPhaseStateEnum)] = mutable.ListBuffer.empty
    var finished = false
    var currentTick: Tick = 0L

    val state: TrafficSignalState = TrafficSignalState(
      startTick = 0L,
      cycleDuration = cycleDuration,
      offset = 0L,
      nodes = List(nodeId),
      phases = List(Phase(origin = phaseOrigin, greenStart = greenStart, greenDuration = greenDuration, state = PhaseDefStateEnum.Green)),
      signalStates = mutable.Map(phaseOrigin -> SignalState(state = Red, remainingTime = greenDuration, nextTick = 0L))
    )

    val handler = new TrafficSignalPhaseHandler(
      getStateFn = () => state,
      entityIdFn = () => "sig_b",
      currentTickFn = () => currentTick,
      simulationEnd = 10000L,
      reportFn = (_, _) => (),
      sendMessageFn = (_, _, data, _) =>
        data match {
          case d: model.hybrid.entity.event.data.signal.TrafficSignalChangeStatusData =>
            notifiedStates += (d.nextTick -> d.signalState.state)
          case _ => ()
        },
      getDependencyFn = id => ShardActorId(entityId = id, classType = "hybrid.actor.Node"),
      scheduleNextFn = tick => scheduledTicks += tick,
      finishFn = () => finished = true,
      logDebugFn = _ => ()
    )

    def fireAt(tick: Tick): Unit = {
      currentTick = tick
      handler.handlePhaseTransition(tick)
    }
  }

  "handlePhaseTransition" should "notify Green at tick 0 and schedule the Red transition at greenStart + greenDuration, not a full cycle later" in {
    val h = new Harness
    h.fireAt(0)

    h.state.signalStates(phaseOrigin).state shouldBe Green
    h.scheduledTicks.last shouldBe 30L
    h.notifiedStates.last shouldBe (30L -> Green)
  }

  it should "notify Red exactly at the greenStart + greenDuration boundary" in {
    val h = new Harness
    h.fireAt(0)
    h.fireAt(30)

    h.state.signalStates(phaseOrigin).state shouldBe Red
    h.scheduledTicks.last shouldBe 60L
    h.notifiedStates.last shouldBe (60L -> Red)
  }

  it should "flip back to Green at the start of the next cycle, not stay stuck forever" in {
    val h = new Harness
    h.fireAt(0)
    h.fireAt(30)
    h.fireAt(60)

    h.state.signalStates(phaseOrigin).state shouldBe Green
    h.scheduledTicks.last shouldBe 90L
    h.notifiedStates.last shouldBe (90L -> Green)
  }

  it should "keep alternating every half-cycle across multiple cycles (was: only ever fired once, at tick 0)" in {
    val h = new Harness
    val ticksToFire = List(0L, 30L, 60L, 90L, 120L, 150L)
    ticksToFire.foreach(h.fireAt)

    h.notifiedStates.map(_._2).toList shouldBe List(Green, Red, Green, Red, Green, Red)
    h.scheduledTicks.toList shouldBe List(30L, 60L, 90L, 120L, 150L, 180L)
  }

  it should "not call finishFn while still within simulationEnd" in {
    val h = new Harness
    h.fireAt(0)

    h.finished shouldBe false
  }
}
