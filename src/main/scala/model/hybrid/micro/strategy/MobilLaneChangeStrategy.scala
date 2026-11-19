package org.interscity.htc
package model.hybrid.micro.strategy

import model.hybrid.entity.state.MicroCarState
import model.hybrid.entity.state.model.{LaneChangingConfig, LaneConnection, VehicleInLane}
import model.hybrid.micro.lane.MobilLaneChange
import model.hybrid.micro.model.KraussModel
import scala.collection.mutable

/** Atomic, adjacent-lane changes; deterministic arbitration prevents simultaneous overlap. */
object MobilLaneChangeStrategy {
  def execute(
    lanes: mutable.Map[Int, mutable.Queue[VehicleInLane]], config: LaneChangingConfig,
    connections: List[LaneConnection], restrictions: Map[Int, String],
    length: Double, speedMps: Double, deltaSeconds: Double, timeSeconds: Double, tick: Long,
    changed: (Long, Int, Int, String) => Unit,
    downstreamLaneLeader: (Long, Int, Long) => Option[VehicleInLane] = (_, _, _) => None
  ): Map[Long,VehicleInLane] = {
    val yielding = mutable.Map.empty[Long,VehicleInLane]
    val model = MobilLaneChange(politeness = config.politeness, safeDeceleration = config.safeDeceleration,
      accelerationThreshold = config.accelerationThreshold, biasToRight = config.biasToRight,
      carFollowingModel = KraussModel.deterministic, decisionTimeStep = deltaSeconds)
    val initial = lanes.toVector.sortBy(_._1).flatMap { case (lane, q) => q.map(v => (lane, v)) }
      .sortBy(_._2.actorId)
    def neighbours(lane: Int, vehicle: VehicleInLane) = {
      val others = lanes.get(lane).toVector.flatMap(_.iterator).filter(_.actorId != vehicle.actorId)
      val ahead = others.filter(_.position >= vehicle.position).minByOption(v => (v.position, v.actorId))
      val behind = others.filter(_.position < vehicle.position).maxByOption(v => (v.position, -v.actorId))
      (ahead, behind)
    }
    // Safety and incentive must see the leader of the lane's own downstream path, not only the
    // same-Link leader; same-Link vehicles are always nearer, so they win whenever present.
    def withDownstream(lane: Int, vehicle: VehicleInLane) = {
      val (ahead, behind) = neighbours(lane, vehicle)
      (ahead.orElse(downstreamLaneLeader(vehicle.nextLinkId, lane, vehicle.actorId)), behind)
    }
    def safe(lane: Int, vehicle: VehicleInLane): Boolean = {
      val (ahead, behind) = withDownstream(lane, vehicle)
      val frontSafe = ahead.forall { leader =>
        val gap = vehicle.gapTo(leader)
        gap >= math.max(config.minGapMeters,vehicle.minGap) && {
          val safeSpeed = KraussModel.deterministic.calculateSafeVelocity(vehicle.velocity,vehicle.desiredSpeed(speedMps),
            gap,leader.velocity,vehicle.maxAcceleration,vehicle.maxDeceleration,math.max(config.minGapMeters,vehicle.minGap),vehicle.reactionTime,deltaSeconds)
          safeSpeed >= vehicle.velocity - vehicle.maxDeceleration * deltaSeconds
        }
      }
      val rearSafe = behind.forall { v =>
        val gap = vehicle.rearPosition - v.position
        gap >= math.max(config.minGapMeters,v.minGap) && {
          val safeSpeed = KraussModel.deterministic.calculateSafeVelocity(v.velocity, v.desiredSpeed(speedMps),
            gap, vehicle.velocity, v.maxAcceleration, v.maxDeceleration, math.max(config.minGapMeters,v.minGap), v.reactionTime, deltaSeconds)
          safeSpeed >= v.velocity - math.min(config.safeDeceleration,v.maxDeceleration) * deltaSeconds
        }
      }
      frontSafe && rearSafe
    }
    initial.foreach { case (currentLane, vehicle) =>
      if (vehicle.entryTick < tick && vehicle.pendingExitTick.isEmpty) {
        val allowed = connections.filter(_.outgoingLinkId == vehicle.nextLinkId).map(_.fromLane).toSet
        val strategic = allowed.nonEmpty && !allowed(currentLane) && length - vehicle.position <= config.strategicLookaheadMeters
        val cooldown = if (strategic) config.strategicCooldownSeconds else config.cooldownSeconds
        val ready = vehicle.lastLaneChangeSeconds.forall(t => timeSeconds - t + 1e-9 >= cooldown)
        val targetGoal = allowed.toVector.sortBy(l => (math.abs(l-currentLane), l)).headOption
        val state = MicroCarState(vehicle.position, vehicle.velocity, vehicle.acceleration, currentLane,
          maxAcceleration = vehicle.maxAcceleration, maxDeceleration = vehicle.maxDeceleration,
          minGap = math.max(config.minGapMeters,vehicle.minGap), reactionTime = vehicle.reactionTime,
          desiredVelocity = vehicle.desiredSpeed(speedMps), vehicleLength = vehicle.vehicleLength)
        val (oldAhead, oldBehind) = withDownstream(currentLane, vehicle)
        val targets = Vector(currentLane-1, currentLane+1).filter(lanes.contains)
        val candidates = targets.flatMap { target =>
          val inReach = !strategic || targetGoal.exists(goal => math.abs(goal-target) < math.abs(goal-currentLane))
          val routeSafe = allowed.isEmpty || allowed(target) || length - vehicle.position > config.strategicLookaheadMeters || strategic
          if (!ready || !inReach || !routeSafe || !safe(target, vehicle) || !model.isLaneAvailable(state,target,restrictions)) None
          else {
            val (ahead, behind) = withDownstream(target, vehicle)
            def front(v: VehicleInLane) = (v.actorId.toString, vehicle.gapTo(v), v.velocity)
            def back(v: VehicleInLane) = (v.actorId.toString, vehicle.rearPosition-v.position, v.velocity)
            val decision = model.evaluateLaneChange(state,currentLane,oldAhead.map(front),oldBehind.map(back),
              ahead.map(front),behind.map(back),target,lanes.size,restrictions,
              vehicle.actorId ^ java.lang.Double.doubleToLongBits(timeSeconds))
            if (strategic) Some((target, Double.PositiveInfinity, "strategic"))
            else Option.when(decision.shouldChange)((target,decision.urgency,"mobil"))
          }
        }.sortBy { case (lane, urgency, _) => (-urgency, lane) }
        candidates.headOption.foreach { case (target,_,reason) =>
          lanes(currentLane).dequeueAll(_.actorId == vehicle.actorId)
          VehicleInLane.insertFrontToBack(lanes(target),vehicle.copy(lastLaneChangeSeconds = Some(timeSeconds)))
          changed(vehicle.actorId,currentLane,target,reason)
        }
        if (strategic && candidates.isEmpty && config.cooperativeStrategicChanges) {
          val blockers = targets.filter(target => targetGoal.exists(goal => math.abs(goal-target) < math.abs(goal-currentLane)))
            .filter(target => model.isLaneAvailable(state,target,restrictions) && !safe(target,vehicle))
            .flatMap { target =>
              val (ahead,behind) = neighbours(target,vehicle)
              (ahead.toList ++ behind.toList).filter { other =>
                val clearance = math.max(config.minGapMeters,math.max(vehicle.minGap,other.minGap))
                val overlap = vehicle.position < other.position + vehicle.vehicleLength + clearance &&
                  other.position < vehicle.position + other.vehicleLength + clearance
                if (overlap) other.actorId < vehicle.actorId else other.position > vehicle.position
              }
            }.sortBy(other => (math.abs(other.position-vehicle.position),other.actorId))
          blockers.headOption.foreach(other => yielding(vehicle.actorId)=other)
        }
      }
    }
    yielding.toMap
  }
}
