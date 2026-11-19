package org.interscity.htc
package model.hybrid.micro.strategy

import model.hybrid.entity.state.model.*
import model.hybrid.micro.model.KraussModel
import scala.collection.mutable

/** Native LC2013 motivation hierarchy for adjacent lanes and next-road route preparation.
  * SUMO's network-wide best lanes, blocker messaging and opposite-direction rules are outside this scope.
  */
object Lc2013LaneChangeStrategy {
  def execute(
    lanes: mutable.Map[Int,mutable.Queue[VehicleInLane]], config: LaneChangingConfig,
    connections: List[LaneConnection], restrictions: Map[Int,String], length: Double,
    speedMps: Double, deltaSeconds: Double, timeSeconds: Double, tick: Long,
    changed: (Long,Int,Int,String) => Unit,
    downstreamLaneLeader: (Long,Int,Long) => Option[VehicleInLane] = (_,_,_) => None
  ): Map[Long,VehicleInLane] = {
    val yielding=mutable.Map.empty[Long,VehicleInLane]
    val initial=lanes.toVector.sortBy(_._1).flatMap((lane,q) => q.map(v => (lane,v))).sortBy(_._2.actorId)
    def permitted(lane: Int): Boolean = !restrictions.get(lane).exists(Set("bus_lane","bike_lane","emergency"))
    def allowed(v: VehicleInLane): Set[Int] = connections.filter(_.outgoingLinkId==v.nextLinkId).map(_.fromLane).toSet
    def goal(lane: Int,v: VehicleInLane): Int = allowed(v).toVector.sortBy(l => (math.abs(l-lane),l)).headOption.getOrElse(lane)
    def horizon(lane: Int,v: VehicleInLane): Double = {
      val direction=if (goal(lane,v)>lane) v.lc2013.lookaheadLeft else 1.0
      math.max(config.strategicLookaheadMeters,10*math.max(v.velocity,v.lc2013Memory.lookAheadSpeed)*direction)*v.lc2013.strategic
    }
    def needs(lane: Int,v: VehicleInLane): Boolean =
      v.lc2013.strategic>0 && goal(lane,v)!=lane && length-v.position <= horizon(lane,v)
    def neighbours(lane: Int,v: VehicleInLane): (Option[VehicleInLane],Option[VehicleInLane]) = {
      val others=lanes.get(lane).toVector.flatMap(_.iterator).filter(_.actorId!=v.actorId)
      (others.filter(_.position>=v.position).minByOption(o => (o.position,o.actorId)),
        others.filter(_.position<v.position).maxByOption(o => (o.position,-o.actorId)))
    }
    // Same-Link vehicles are always nearer than the downstream path leader, so they win when present.
    def withDownstream(lane: Int,v: VehicleInLane) = {
      val (front,rear)=neighbours(lane,v)
      (front.orElse(downstreamLaneLeader(v.nextLinkId,lane,v.actorId)),rear)
    }
    def follow(v: VehicleInLane,leader: VehicleInLane): Double =
      KraussModel.deterministic.calculateSafeVelocity(v.velocity,v.desiredSpeed(speedMps),
        math.max(0,v.gapTo(leader)),leader.velocity,v.maxAcceleration,v.maxDeceleration,
        math.max(config.minGapMeters,v.minGap),v.reactionTime,deltaSeconds)
    def safe(lane: Int,v: VehicleInLane): Boolean = {
      val (front,rear)=withDownstream(lane,v)
      front.forall(o => v.gapTo(o)>=math.max(config.minGapMeters,v.minGap) &&
        follow(v,o)>=v.velocity-v.maxDeceleration*deltaSeconds) &&
      rear.forall(o => o.gapTo(v)>=math.max(config.minGapMeters,o.minGap) &&
        follow(o,v)>=o.velocity-math.min(config.safeDeceleration,o.maxDeceleration)*deltaSeconds)
    }
    def predicted(lane: Int,v: VehicleInLane): Double =
      math.min(v.desiredSpeed(speedMps),withDownstream(lane,v)._1.fold(v.desiredSpeed(speedMps))(follow(v,_)))
    def advise(follower: VehicleInLane,requester: VehicleInLane): Unit =
      if (follower.lc2013.cooperative>0 && config.cooperativeStrategicChanges) {
        yielding.get(follower.actorId) match {
          case Some(previous) if previous.actorId < requester.actorId =>
          case _ => yielding(follower.actorId)=requester
        }
      }
    def accumulate(old: Double,gain: Double): Double =
      if (gain>0) old+gain*deltaSeconds
      else old*math.pow(if (gain<0) .5 else .8,deltaSeconds)

    initial.foreach { case (current,v0) =>
      if (v0.entryTick<tick && v0.pendingExitTick.isEmpty) {
        val p=v0.lc2013
        val targets=Vector(current-1,current+1).filter(lanes.contains).filter(permitted)
        val base=predicted(current,v0)
        val gains=targets.map(l => l -> ((predicted(l,v0)-base)/math.max(10.0,v0.desiredSpeed(speedMps)))).toMap
        val rightFree=targets.contains(current-1) && predicted(current-1,v0)>=v0.desiredSpeed(speedMps)-1e-6
        val memory=Lc2013Memory(
          if (targets.contains(current+1)) accumulate(v0.lc2013Memory.leftGain,gains(current+1)) else 0,
          if (targets.contains(current-1)) accumulate(v0.lc2013Memory.rightGain,gains(current-1)) else 0,
          if (rightFree) v0.lc2013Memory.keepRightSeconds+deltaSeconds else 0,
          math.max(v0.velocity,v0.lc2013Memory.lookAheadSpeed*math.max(0,1-.1*deltaSeconds)))
        val vehicle=v0.copy(lc2013Memory=memory)
        val ownIndex=lanes(current).indexWhere(_.actorId==vehicle.actorId)
        lanes(current)(ownIndex)=vehicle
        val strategic=needs(current,vehicle)
        val required=goal(current,vehicle)
        val routeLanes=allowed(vehicle)
        val ready=vehicle.lastLaneChangeSeconds.forall(t => timeSeconds-t+1e-9 >=
          (if (strategic) config.strategicCooldownSeconds else config.cooldownSeconds))
        def routeSafe(target: Int): Boolean = routeLanes.isEmpty || routeLanes(target) ||
          length-vehicle.position>horizon(current,vehicle) || strategic
        val helping=initial.exists { case (lane,other) => other.actorId!=vehicle.actorId &&
          math.abs(lane-current)==1 && needs(lane,other) &&
          math.signum(goal(lane,other)-lane).toInt==current-lane &&
          math.abs(other.position-vehicle.position)<math.max(vehicle.vehicleLength,other.vehicleLength)+
            math.max(vehicle.minGap,other.minGap) && other.actorId<vehicle.actorId }
        val candidates=targets.flatMap { target =>
          val toward=math.abs(required-target)<math.abs(required-current)
          val remain=(length-vehicle.position)/math.max(.1,vehicle.velocity)
          val motivation=
            if (strategic && toward) Some((4,"strategic"))
            else if (!strategic && helping && p.cooperative>0 && config.cooperativeStrategicChanges)
              Some((3,"lc2013_cooperative"))
            else if (!strategic && p.speedGain>0 && remain>p.speedGainRemainTimeSeconds && gains(target)>0 &&
              (if (target>current) memory.leftGain>.2/p.speedGain
               else p.speedGainRight>0 && memory.rightGain>.2/(p.speedGain*p.speedGainRight)))
              Some((2,"lc2013_speed_gain"))
            else if (!strategic && target<current && p.keepRight>0 && rightFree &&
              memory.keepRightSeconds*p.keepRight>=p.keepRightTimeSeconds)
              Some((1,"lc2013_keep_right"))
            else None
          motivation.filter(_ => ready && routeSafe(target) && safe(target,vehicle))
            .map((priority,reason) => (priority,target,reason))
        }.sortBy((priority,lane,_) => (-priority,lane))
        candidates.headOption match {
          case Some((_,target,reason)) =>
            yielding.remove(vehicle.actorId)
            lanes(current).dequeueAll(_.actorId==vehicle.actorId)
            VehicleInLane.insertFrontToBack(lanes(target),vehicle.copy(lastLaneChangeSeconds=Some(timeSeconds),
              lc2013Memory=Lc2013Memory()))
            changed(vehicle.actorId,current,target,reason)
          case None if strategic && config.cooperativeStrategicChanges =>
            targets.filter(l => math.abs(required-l)<math.abs(required-current)).foreach { target =>
              val (front,rear)=neighbours(target,vehicle)
              (front.toList++rear.toList).foreach { other =>
                val overlap=math.abs(other.position-vehicle.position)<math.max(vehicle.vehicleLength,other.vehicleLength)+
                  math.max(config.minGapMeters,math.max(vehicle.minGap,other.minGap))
                if (overlap) {
                  // Holding an exit-compatible neighbour beside a wrong-lane car can close the
                  // only way to create a merge gap near the end of a short road.
                  val canExitHere=goal(target,other)==target
                  if (canExitHere || other.actorId<vehicle.actorId) advise(vehicle,other)
                  else advise(other,vehicle)
                } else if (other.position<vehicle.position) advise(other,vehicle)
              }
            }
          case _ =>
        }
      }
    }
    yielding.toMap
  }
}
