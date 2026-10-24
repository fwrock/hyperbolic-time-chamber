package org.interscity.htc
package model.hybrid.support.link

import core.types.Tick
import model.hybrid.entity.event.data.link.{MicroBoundaryUpdateData, SubscribeMicroBoundaryData}
import model.hybrid.entity.state.LinkState
import model.hybrid.entity.state.model.{MicroBoundaryHistory, MicroBoundarySnapshot, MicroBoundaryVehicle, VehicleInLane}
import model.hybrid.entity.state.enumeration.EventTypeEnum

class LinkFollowingHandler(
  stateFn: () => LinkState,
  entityIdFn: () => Long,
  currentTickFn: () => Tick,
  sendFn: (Long, String, AnyRef, String) => Unit
) {
  def observeNextRoad(roadId: Long): Unit = {
    val state = stateFn()
    if (state.downstreamLookahead && roadId != 0L) {
      val targets = List(roadId, state.downstreamConnectors.getOrElse(roadId, roadId)).distinct.sorted
      targets.filter(id => id != entityIdFn() && !state.following.requested.contains(id)).foreach { target =>
        state.following.requested = (target :: state.following.requested).sorted
        sendFn(target, "hybrid.actor.Link", SubscribeMicroBoundaryData(), EventTypeEnum.SubscribeMicroBoundary.toString)
      }
    }
  }

  def subscribe(subscriber: Long): Unit = {
    val following = stateFn().following
    following.subscribers = (subscriber :: following.subscribers).distinct.sorted
    sendSnapshot(List(subscriber))
  }

  def publish(): Unit =
    if (stateFn().following.subscribers.nonEmpty) sendSnapshot(stateFn().following.subscribers)

  private def sendSnapshot(subscribers: List[Long]): Unit = {
    val state = stateFn()
    state.following.sequence += 1L
    val nearest = state.vehiclesByLane.valuesIterator.flatMap(_.iterator)
      .minByOption(v => (v.rearPosition, v.actorId))
      .map(v => MicroBoundaryVehicle(v.actorId, v.position, v.velocity, v.vehicleLength))
    val snapshot = MicroBoundarySnapshot(entityIdFn(), currentTickFn(), state.following.sequence,
      state.length, nearest, supported = state.isMicroMode && state.lanes == 1 && state.microTicksPerGlobalTick == 1,
      stepSeconds = state.microTimeStep)
    subscribers.foreach { target =>
      sendFn(target, "hybrid.actor.Link", MicroBoundaryUpdateData(snapshot), EventTypeEnum.MicroBoundaryUpdate.toString)
    }
  }

  def receive(source: Long, snapshot: MicroBoundarySnapshot): Unit = {
    val state = stateFn()
    require(source == snapshot.linkId, "downstream MICRO boundary source mismatch")
    // A positive publication can outlive an undone subscription until its anti-message arrives.
    if (!state.following.requested.contains(source)) return
    require(snapshot.supported, "unsupported downstream MICRO boundary")
    require(math.abs(snapshot.stepSeconds - state.microTimeStep) < 1e-9, "downstream MICRO timestep mismatch")
    val history = state.following.observations.getOrElse(source, MicroBoundaryHistory())
    history.accept(snapshot)
    state.following.observations = state.following.observations.updated(source, history)
  }

  def leaders(tick: Tick): Map[Long, VehicleInLane] = {
    val state = stateFn()
    if (!state.downstreamLookahead) return Map.empty
    def snapshot(id: Long) = state.following.observations.get(id).flatMap(_.before(tick))
    state.vehiclesByLane.valuesIterator.flatMap(_.iterator).flatMap { vehicle =>
      val road = vehicle.nextLinkId
      val first = state.downstreamConnectors.getOrElse(road, road)
      val near = snapshot(first)
      val farther = if (first != road) snapshot(road).flatMap(s => near.map(n => (s, n.length))) else None
      val candidates = near.toList.map(_ -> 0.0) ++ farther.toList
      candidates.flatMap { case (s, offset) => s.vehicle.filter(_.id != vehicle.actorId).map { v =>
        VehicleInLane(v.id, "hybrid.actor.Link", state.length + offset + v.position,
          v.velocity, 0.0, v.length)
      }}.minByOption(v => (v.rearPosition, v.actorId)).map(vehicle.actorId -> _)
    }.toMap
  }
}
