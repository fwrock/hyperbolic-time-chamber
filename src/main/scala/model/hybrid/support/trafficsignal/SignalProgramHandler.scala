package org.interscity.htc
package model.hybrid.support.trafficsignal

import core.types.Tick
import model.hybrid.entity.state.TrafficSignalState
import model.hybrid.entity.state.model.*
import model.hybrid.entity.state.model.SignalControlMode.{Fixed, GapActuated}
import model.hybrid.entity.event.data.signal.{TrafficDetectionData, TrafficSignalChangeStatusData, SignalAspectUpdate}
import model.hybrid.entity.state.enumeration.EventTypeEnum

/** Native ordered-stage controller. All evolving control and detector state is checkpointed. */
final class SignalProgramHandler(
  stateFn: () => TrafficSignalState, entityIdFn: () => String,
  sendFn: (Long, String, AnyRef, String) => Unit,
  reportFn: (Map[String,Any], String) => Unit,
  finishFn: Option[Tick] => Unit, endTick: Tick,
  secondsPerTickFn: () => Double = () => core.util.SimulationClock.current.secondsPerTick
) {
  def detection(tick: Tick, data: TrafficDetectionData): Unit = {
    val state = stateFn()
    val program = state.program.getOrElse(throw new IllegalArgumentException("detector input needs a signal program"))
    require(program.stages.exists(_.detectors.contains(data.detectorId)), "unknown signal detector")
    require(data.occupiedUntilSeconds.isFinite && data.occupiedUntilSeconds <= tick * secondsPerTickFn() + 1e-8,
      "signal detector observation names future or nonfinite time")
    val old = state.programRuntime.observations
    val same = old.find(o => o.detectorId == data.detectorId && o.tick == tick)
    val observation = SignalDetectionObservation(data.detectorId,tick,
      math.max(data.occupiedUntilSeconds,same.map(_.occupiedUntilSeconds).getOrElse(Double.NegativeInfinity)))
    val kept = (old.filterNot(o => o.detectorId == data.detectorId && o.tick == tick) :+ observation)
      .groupBy(_.detectorId).valuesIterator.flatMap(_.sortBy(o => -o.tick).take(2)).toList
      .sortBy(o => (o.detectorId,o.tick))
    state.programRuntime = state.programRuntime.copy(observations = kept)
  }

  def step(tick: Tick): Unit = {
    val state = stateFn()
    val program = state.program.get
    val quantum = secondsPerTickFn()
    val now = tick * quantum
    val clock = core.util.SimulationClock(quantum)
    var runtime = state.programRuntime
    var changed = !runtime.initialized
    if (!runtime.initialized) runtime = runtime.copy(initialized = true, stageIndex = program.initialStage,
      stageStartedSeconds = now-program.initialElapsedSeconds,
      nextDecisionSeconds = now-program.initialElapsedSeconds + (if (program.control == Fixed)
        program.stages(program.initialStage).durationSeconds else program.stages(program.initialStage).minimum))
    if (now + 1e-8 >= runtime.nextDecisionSeconds) {
      val stage = program.stages(runtime.stageIndex)
      val elapsed = now-runtime.stageStartedSeconds
      val gaps = runtime.observations.filter(o => o.tick < tick && stage.detectors.contains(o.detectorId))
        .groupBy(_.detectorId).valuesIterator.map(_.maxBy(o => (o.tick,o.occupiedUntilSeconds)))
        .map(o => math.max(0.0,now-o.occupiedUntilSeconds)).toVector
      val gap = gaps.minOption.getOrElse(Double.PositiveInfinity)
      val extend = program.control == GapActuated && stage.green.nonEmpty &&
        elapsed + 1e-8 < stage.maximum && gap < program.maxGapSeconds
      if (extend) {
        val delay = math.max(program.checkIntervalSeconds,program.extensionSeconds-gap)
        runtime = runtime.copy(nextDecisionSeconds = math.min(runtime.stageStartedSeconds+stage.maximum,
          now+math.ceil(delay/quantum-1e-9)*quantum))
      } else {
        val index = (runtime.stageIndex+1)%program.stages.size
        val next = program.stages(index)
        runtime = runtime.copy(stageIndex=index,stageStartedSeconds=now,
          nextDecisionSeconds=now+(if (program.control == Fixed) next.durationSeconds else next.minimum))
        changed = true
      }
    }
    state.programRuntime = runtime
    val nextTick = math.max(tick+1,clock.ticksCeil(runtime.nextDecisionSeconds))
    val stage = program.stages(runtime.stageIndex)
    if (changed) reportFn(Map("signal_id" -> entityIdFn(), "stage" -> runtime.stageIndex,
      "control" -> program.control.toString,"tick" -> tick),"signal_program_stage")
    val publications = scala.collection.mutable.Buffer.empty[(Long,SignalAspectUpdate)]
    program.movements.foreach { movement =>
      val aspect = stage.aspect(movement.origin)
      val prior = state.signalStates.get(movement.origin)
      val aspectChanged = prior.forall(_.state != aspect)
      if (aspectChanged && program.reportMovementChanges)
        reportFn(Map("signal_id" -> entityIdFn(),"phase_origin" -> movement.origin,
          "phase_state" -> aspect.toString,"stage" -> runtime.stageIndex,"tick" -> tick),"signal_phase_change")
      val signal = SignalState(aspect,nextTick-tick,nextTick)
      state.signalStates.put(movement.origin,signal)
      // An unchanged aspect cannot alter a MICRO interval. A Node's earlier red deadline
      // remains a safe retry bound (requests clamp it above their current tick).
      if (aspectChanged)
        publications += ((movement.nodeId,SignalAspectUpdate(movement.origin,signal,nextTick)))
    }
    publications.groupBy(_._1).toVector.sortBy(_._1).foreach { case (node,items) =>
      val changes = items.map(_._2).toList
      val head = changes.head
      sendFn(node,"hybrid.actor.Node",TrafficSignalChangeStatusData(head.signalState,head.nextTick,head.phaseOrigin,changes.tail),
        EventTypeEnum.TrafficSignalChangeStatus.toString)
    }
    finishFn(Option.when(nextTick <= endTick)(nextTick))
  }
}
