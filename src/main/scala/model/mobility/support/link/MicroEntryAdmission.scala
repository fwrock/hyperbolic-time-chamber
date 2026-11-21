package org.interscity.htc
package model.mobility.support.link

import model.mobility.entity.event.data.EnterLinkData
import model.mobility.entity.state.LinkState
import model.mobility.entity.state.model.LaneChangingModel

/** An entry must leave enough space for the lane maneuvers that its fixed handoff requires. */
object MicroEntryAdmission {
  /** A measured state can be restored in either spatial order; desired gap is not physical length. */
  def snapshotReason(state: LinkState, data: EnterLinkData, targetLane: Int): Option[String] = {
    require(data.actorSize.isFinite && data.actorSize > 0.0,
      "initial MICRO snapshot requires a finite positive vehicle length")
    require(targetLane >= 0 && targetLane < state.lanes, "invalid initial MICRO snapshot lane")
    require(data.initialRemainderSeconds == 0.0, "initial MICRO snapshot cannot carry boundary remainder")
    val occupants = state.vehiclesByLane.get(targetLane).toList.flatMap(_.toList)
    val space = occupants.forall { other =>
      if (other.position >= data.initialPosition)
        other.rearPosition >= data.initialPosition
      else data.initialPosition-data.actorSize >= other.position
    }
    Option.unless(space)("initial_snapshot_overlap")
  }

  def lane(state: LinkState, data: EnterLinkData): Int = {
    val compatible = state.laneConnectionIndex.legal(data.nextLinkId).distinct
    data.preferredLane.getOrElse((if (compatible.nonEmpty) compatible else (0 until state.lanes))
      .minBy(l => (state.vehiclesByLane.get(l).fold(0)(_.size), l)))
  }

  def reason(state: LinkState, data: EnterLinkData, targetLane: Int): Option[String] = {
    val gap = math.max(data.minGap.getOrElse(2.0), state.laneChanging.minGapMeters)
    val entrySpeed = math.min(data.initialVelocity, model.mobility.entity.state.model.MicroSpeed.limit(
      state.speedLimit/3.6,data.maxSpeed,data.speedFactor,data.desiredMaxSpeed))
    val requestedPosition = math.min(data.arrivalPosition.getOrElse(state.length),
      data.initialPosition+entrySpeed*data.initialRemainderSeconds)
    val occupants = state.vehiclesByLane.get(targetLane).toList.flatMap(_.toList)
    val space = occupants.forall(v => v.rearPosition - gap >= requestedPosition)
    if (!space) Some("entry_position_occupied")
    else if (!state.microEntry.avoidWeavingConflicts || state.laneChanging.model == LaneChangingModel.Disabled) None
    else {
      def goal(lane: Int, next: Long): Int = state.laneConnectionIndex.legal(next)
        .distinct.minByOption(l => (math.abs(l-lane),l)).getOrElse(lane)
      val incomingGoal = goal(targetLane,data.nextLinkId)
      val conflict = state.vehiclesByLane.exists { case (otherLane, vehicles) => vehicles.exists { other =>
        val otherGoal = goal(otherLane,other.nextLinkId)
        val needsChange = incomingGoal != targetLane || otherGoal != otherLane
        val sharedLanes = math.max(math.min(targetLane,incomingGoal),math.min(otherLane,otherGoal)) <=
          math.min(math.max(targetLane,incomingGoal),math.max(otherLane,otherGoal))
        val clearance = math.max(data.actorSize,other.vehicleLength)+math.max(gap,other.minGap)
        val overlap = math.abs(other.position-data.initialPosition) < clearance
        val velocity = math.max(entrySpeed,other.velocity)
        val braking = math.min(data.maxDeceleration.getOrElse(4.5),other.maxDeceleration)
        val changes = math.max(math.abs(incomingGoal-targetLane),math.abs(otherGoal-otherLane))
        val maneuverDistance = clearance + velocity*velocity/(2*braking) +
          velocity*changes*state.laneChanging.strategicCooldownSeconds
        needsChange && sharedLanes && overlap &&
          state.length-data.initialPosition < maneuverDistance + 1e-9
      }}
      Option.when(conflict)("insufficient_weaving_space")
    }
  }
}
