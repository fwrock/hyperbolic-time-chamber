package org.interscity.htc
package model.hybrid.support.link

import model.hybrid.entity.event.data.EnterLinkData
import model.hybrid.entity.state.LinkState
import model.hybrid.entity.state.model.LaneChangingModel

/** An entry must leave enough space for the lane maneuvers that its fixed handoff requires. */
object MicroEntryAdmission {
  def lane(state: LinkState, data: EnterLinkData): Int = {
    val compatible = state.laneConnections.filter(_.outgoingLinkId == data.nextLinkId).map(_.fromLane).distinct
    data.preferredLane.getOrElse((if (compatible.nonEmpty) compatible else (0 until state.lanes))
      .minBy(l => (state.vehiclesByLane.get(l).fold(0)(_.size), l)))
  }

  def reason(state: LinkState, data: EnterLinkData, targetLane: Int): Option[String] = {
    val gap = math.max(data.minGap.getOrElse(2.0), state.laneChanging.minGapMeters)
    val entrySpeed = math.min(data.initialVelocity, model.hybrid.entity.state.model.MicroSpeed.limit(
      state.speedLimit/3.6,data.maxSpeed,data.speedFactor,data.desiredMaxSpeed))
    val requestedPosition = math.min(data.arrivalPosition.getOrElse(state.length),
      data.initialPosition+entrySpeed*data.initialRemainderSeconds)
    val occupants = state.vehiclesByLane.get(targetLane).toList.flatMap(_.toList)
    val space = occupants.forall(v => v.rearPosition - gap >= requestedPosition)
    if (!space) Some("entry_position_occupied")
    else if (!state.microEntry.avoidWeavingConflicts || state.laneChanging.model == LaneChangingModel.Disabled) None
    else {
      def goal(lane: Int, next: Long): Int = state.laneConnections.filter(_.outgoingLinkId == next)
        .map(_.fromLane).distinct.minByOption(l => (math.abs(l-lane),l)).getOrElse(lane)
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
