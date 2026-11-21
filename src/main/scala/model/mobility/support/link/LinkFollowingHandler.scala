package org.interscity.htc
package model.mobility.support.link

import core.types.Tick
import core.util.{StringPool, StringUtil}
import model.mobility.entity.event.data.link.{MicroBoundaryUpdateData, SubscribeMicroBoundaryData}
import model.mobility.entity.state.LinkState
import model.mobility.entity.state.model.{MicroBoundaryHistory, MicroBoundarySnapshot, MicroBoundaryVehicle, MicroBoundarySubscriber, MicroLaneBoundary, VehicleInLane}
import model.mobility.entity.state.enumeration.EventTypeEnum

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
        sendFn(target, "mobility.actor.Link", SubscribeMicroBoundaryData(), EventTypeEnum.SubscribeMicroBoundary.toString)
      }
    }
  }

  /** Register a logical destination in checkpointed state and publish its initial observation. */
  def subscribe(subscriber: Long, shardId: String = "mobility.actor.Link"): Unit = {
    val following = stateFn().following
    val canonicalShard = StringPool.intern(StringUtil.getModelClassNameWithoutPackage(shardId))
    val destination = MicroBoundarySubscriber(subscriber, canonicalShard)
    if (canonicalShard == "mobility.actor.Link")
      following.subscribers = (subscriber :: following.subscribers).distinct.sorted
    else
      following.otherSubscribers = (destination :: following.otherSubscribers).distinct
        .sortBy(s => (s.shardId, s.id))
    sendSnapshot(List(destination))
  }

  /** Publication does not schedule work or await replies from its observers. */
  def publish(): Unit = {
    val following = stateFn().following
    val destinations = following.subscribers.map(MicroBoundarySubscriber(_, "mobility.actor.Link")) ++
      following.otherSubscribers
    if (destinations.nonEmpty) sendSnapshot(destinations.distinct.sortBy(s => (s.shardId, s.id)))
  }

  /** Preserve the legacy aggregate and its capability flag while adding explicit lane occupancy. */
  private def sendSnapshot(subscribers: List[MicroBoundarySubscriber]): Unit = {
    val state = stateFn()
    state.following.sequence += 1L
    val nearest = state.vehiclesByLane.valuesIterator.flatMap(_.iterator)
      .minByOption(v => (v.rearPosition, v.actorId))
      .map(v => MicroBoundaryVehicle(v.actorId, v.position, v.velocity, v.vehicleLength))
    def observed(v: VehicleInLane) = MicroBoundaryVehicle(v.actorId, v.position, v.velocity,
      v.vehicleLength, v.nextLinkId, v.pendingExitTick.nonEmpty)
    val perLane = if (state.isMicroMode && state.microTicksPerGlobalTick == 1)
      (0 until state.lanes).toList.map { lane =>
        val vehicles = state.vehiclesByLane.get(lane).toList.flatMap(_.toList)
        MicroLaneBoundary(lane,
          vehicles.minByOption(v => (v.rearPosition, v.actorId)).map(observed),
          vehicles.maxByOption(v => (v.position, -v.actorId)).map(observed))
      }
    else Nil
    val snapshot = MicroBoundarySnapshot(entityIdFn(), currentTickFn(), state.following.sequence,
      state.length, nearest, supported = state.isMicroMode && state.microTicksPerGlobalTick == 1,
      stepSeconds = state.microTimeStep, laneCount = state.lanes, laneBoundaries = perLane)
    subscribers.foreach { target =>
      sendFn(target.id, target.shardId, MicroBoundaryUpdateData(snapshot), EventTypeEnum.MicroBoundaryUpdate.toString)
    }
  }

  def receive(source: Long, snapshot: MicroBoundarySnapshot): Unit = {
    val state = stateFn()
    require(source == snapshot.linkId, "downstream MICRO boundary source mismatch")
    // A positive publication can outlive an undone subscription until its anti-message arrives.
    if (!state.following.requested.contains(source)) return
    require(snapshot.supported, "unsupported downstream MICRO boundary")
    require(snapshot.laneCount == 1 || snapshot.laneBoundaries.nonEmpty,
      "multi-lane downstream boundary without per-lane occupancy cannot be consumed")
    require(math.abs(snapshot.stepSeconds - state.microTimeStep) < 1e-9, "downstream MICRO timestep mismatch")
    val history = state.following.observations.getOrElse(source, MicroBoundaryHistory())
    history.accept(snapshot)
    state.following.observations = state.following.observations.updated(source, history)
  }

  /** Physical leader on the downstream path of (next road, origin lane), in this Link's coordinates.
    *
    * Only the lane the path actually enters is consulted, so occupancy in another lane never blocks
    * it. A missing connection, observation or lane detail yields no leader: that is "unknown", and
    * the LinkState guard keeps multi-lane lookahead from being enabled without explicit connections.
    */
  def laneLeader(tick: Tick, road: Long, lane: Int, exclude: Long = Long.MinValue): Option[VehicleInLane] = {
    val state = stateFn()
    if (!state.downstreamLookahead || road == 0L) return None
    val connection = if (state.lanes == 1 && state.laneConnections.isEmpty) None
      else state.laneConnections.find(c => c.outgoingLinkId == road && c.fromLane == lane)
    if (connection.isEmpty && !(state.lanes == 1 && state.laneConnections.isEmpty)) return None
    val first = state.downstreamConnectors.getOrElse(road, road)
    def rearmost(id: Long, laneId: Int) = state.following.observations.get(id).flatMap(_.before(tick)).flatMap { s =>
      val vehicle = if (s.laneBoundaries.isEmpty) (if (laneId == 0) s.vehicle else None)
        else s.laneBoundaries.find(_.laneId == laneId).flatMap(_.rearmost)
      vehicle.filter(_.id != exclude).map(v => (s, v))
    }
    def snapshot(id: Long) = state.following.observations.get(id).flatMap(_.before(tick))
    val nearLane = connection.fold(0)(_.toLane)
    val near = rearmost(first, nearLane)
    val farther = if (first != road) snapshot(first).flatMap { connector =>
      rearmost(road, connection.fold(0)(_.roadLane)).map { case (_, v) => (v, connector.length) }
    } else None
    (near.toList.map { case (_, v) => (v, 0.0) } ++ farther.toList).map { case (v, offset) =>
      VehicleInLane(v.id, "mobility.actor.Link", state.length + offset + v.position, v.velocity, 0.0, v.length)
    }.minByOption(v => (v.rearPosition, v.actorId))
  }

  /** One leader per vehicle, from the lane the vehicle currently occupies. */
  def leaders(tick: Tick): Map[Long, VehicleInLane] = {
    val state = stateFn()
    if (!state.downstreamLookahead) return Map.empty
    state.vehiclesByLane.iterator.flatMap { case (lane, queue) =>
      queue.iterator.flatMap { vehicle =>
        laneLeader(tick, vehicle.nextLinkId, lane, vehicle.actorId).map(vehicle.actorId -> _)
      }
    }.toMap
  }
}
