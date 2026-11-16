package org.interscity.htc
package model.hybrid.entity.state.model

import core.types.Tick
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum
import model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.{Green, Yellow, Red}

enum SignalControlMode:
  case Fixed, GapActuated

final case class SignalStage(
  durationSeconds: Double,
  @JsonDeserialize(contentAs = classOf[java.lang.Long])
  green: List[Long] = Nil,
  @JsonDeserialize(contentAs = classOf[java.lang.Long])
  yellow: List[Long] = Nil,
  @JsonDeserialize(contentAs = classOf[java.lang.Double])
  minDurationSeconds: Option[Double] = None,
  @JsonDeserialize(contentAs = classOf[java.lang.Double])
  maxDurationSeconds: Option[Double] = None,
  @JsonDeserialize(contentAs = classOf[java.lang.Long])
  detectors: List[Long] = Nil,
  @JsonDeserialize(contentAs = classOf[java.lang.Long])
  off: List[Long] = Nil
) {
  def minimum: Double = minDurationSeconds.getOrElse(durationSeconds)
  def maximum: Double = maxDurationSeconds.getOrElse(durationSeconds)
  require(durationSeconds.isFinite && durationSeconds > 0, "invalid signal-stage duration")
  require(minimum.isFinite && minimum > 0 && maximum.isFinite && maximum >= minimum, "invalid signal-stage bounds")
  require(green.toSet.intersect(yellow.toSet).isEmpty, "signal movement cannot be green and yellow together")
  require(off.toSet.intersect((green ++ yellow).toSet).isEmpty, "off movement cannot have another aspect")
  def aspect(movement: Long): TrafficSignalPhaseStateEnum =
    if (green.contains(movement)) Green else if (yellow.contains(movement)) Yellow
    else if (off.contains(movement)) TrafficSignalPhaseStateEnum.Off else Red
}

final case class SignalMovement(origin: Long, nodeId: Long, incomingLinkId: Long,
                                outgoingLinkId: Long, @JsonDeserialize(contentAs = classOf[java.lang.Integer]) laneIds: List[Int] = Nil) {
  require(laneIds.forall(_ >= 0), "invalid controlled signal lanes")
}

final case class SignalProgram(
  control: SignalControlMode = SignalControlMode.Fixed,
  stages: List[SignalStage],
  movements: List[SignalMovement],
  maxGapSeconds: Double = 3.0,
  extensionSeconds: Double = 2.0,
  checkIntervalSeconds: Double = 1.0,
  initialStage: Int = 0,
  initialElapsedSeconds: Double = 0.0,
  reportMovementChanges: Boolean = false
) {
  require(stages.nonEmpty && movements.nonEmpty, "signal program needs stages and movements")
  require(initialStage >= 0 && initialStage < stages.size, "invalid initial signal stage")
  require(initialElapsedSeconds.isFinite && initialElapsedSeconds >= 0 &&
    initialElapsedSeconds < stages(initialStage).maximum, "invalid initial signal elapsed time")
  require(maxGapSeconds.isFinite && maxGapSeconds > 0 && extensionSeconds.isFinite && extensionSeconds > 0 &&
    checkIntervalSeconds.isFinite && checkIntervalSeconds > 0, "invalid actuated signal timing")
  private val origins = movements.map(_.origin)
  require(origins.distinct.size == origins.size, "duplicate signal movement identity")
  require(stages.forall(s => (s.green ++ s.yellow ++ s.off).forall(origins.contains)), "unknown signal movement in stage")
}

final case class SignalProgramRuntime(
  initialized: Boolean = false,
  stageIndex: Int = 0,
  stageStartedSeconds: Double = 0.0,
  nextDecisionSeconds: Double = 0.0,
  @JsonDeserialize(contentAs = classOf[SignalDetectionObservation])
  observations: List[SignalDetectionObservation] = Nil
)

final case class SignalDetectionObservation(detectorId: Long, tick: Tick, occupiedUntilSeconds: Double)

final case class MicroDetectorConfig(id: Long, controllerId: Long, laneId: Int,
                                     positionMeters: Double, lengthMeters: Double = 0.0) {
  require(laneId >= 0 && positionMeters.isFinite && positionMeters >= 0 &&
    lengthMeters.isFinite && lengthMeters >= 0, "invalid signal detector geometry")
}

final case class MovementSignalBinding(incomingLinkId: Long, outgoingLinkId: Long,
                                       origin: Long, @JsonDeserialize(contentAs = classOf[java.lang.Integer]) laneIds: List[Int] = Nil)

final case class LinkMovementSignal(
  targetLinkId: Long, @JsonDeserialize(contentAs = classOf[java.lang.Integer]) laneIds: List[Int] = Nil,
  phase: TrafficSignalPhaseStateEnum = Red,
  previousPhase: TrafficSignalPhaseStateEnum = Red,
  effectiveTick: Tick = Long.MinValue
) {
  def at(tick: Tick): TrafficSignalPhaseStateEnum = if (tick <= effectiveTick) previousPhase else phase
}
