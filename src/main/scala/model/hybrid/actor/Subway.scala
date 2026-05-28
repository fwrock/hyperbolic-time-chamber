package org.interscity.htc
package model.hybrid.actor

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import org.interscity.htc.model.hybrid.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.hybrid.entity.event.data.subway.{ SubwayLoadPassengerData, SubwayRequestPassengerData, SubwayRequestUnloadPassengerData, SubwayUnloadPassengerData }
import org.interscity.htc.model.hybrid.entity.state.SubwayState
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.{ Moving, Ready, Start, Stopped }
import org.interscity.htc.model.hybrid.util.SubwayUtil
import org.interscity.htc.model.hybrid.util.SubwayUtil.timeToNextStation
import org.interscity.htc.core.metrics.model.hybrid.{ MovableMetrics, SubwayMetrics }
import core.actor.trace.ActorTrace

/** Subway actor - Metro train following predefined rail routes.
  *
  * IMPORTANT: Subways use PREDEFINED routes (bestRoute) created by SubwayStation. Unlike cars/buses
  * that use dynamic routing (GraphRouter), subway trains follow fixed rail lines that never change.
  *
  * Key characteristics:
  *   - Uses RAIL_LINKS (not road links) - exclusive subway infrastructure
  *   - Route is predefined at creation by SubwayStation (bestRoute field)
  *   - Follows digital rail path (stations in order)
  *   - No dynamic rerouting - trains stay on their assigned line
  *   - RailLink validates vehicle type (only Subway can enter)
  *
  * Flow:
  *   1. SubwayStation creates Subway with bestRoute = rail_link IDs 2. Subway calls enterLink()
  *      with next rail_link from bestRoute 3. RailLink validates vehicle type (Subway ✓) and sends
  *      LinkInfoData 4. Subway travels through rail_link (no congestion) 5. Subway calls
  *      leavingLink() when reaching next station 6. Repeat for next rail_link in bestRoute
  *
  * @param properties
  *   Actor properties
  */
class Subway(
  private val properties: Properties
) extends Movable[SubwayState](
      properties = properties
    ) {

  private var expectedUnloadResponses: Int = 0

  override def actSpontaneous(event: SpontaneousEvent): Unit =
    state.status match
      case Start =>
        state.status = Ready
        SubwayMetrics.journeysStarted.labels(state.line).inc()
        MovableMetrics.journeysStarted.labels(getClass.getSimpleName).inc()
        report(
          data = Map(
            "event_type" -> "journey_started",
            "subway_id" -> getEntityId,
            "line" -> state.line,
            "origin" -> state.origin,
            "destination" -> state.destination,
            "capacity" -> state.capacity,
            "tick" -> currentTick
          ),
          label = "journey_started"
        )
        ActorTrace.trace(getEntityId, currentTick, "subway_journey_started", // #actor-trace
          s"line=${state.line} origin=${state.origin} destination=${state.destination} capacity=${state.capacity}") // #actor-trace
        enterLink()
      case Ready =>
        enterLink()
      case Moving =>
        val nodeId = getCurrentNode
        val stationOpt = if (nodeId != null) retrieveSubwayStationFromNodeId(nodeId) else None
        stationOpt match {
          case Some(_) =>
            state.status = Stopped
            ActorTrace.trace(getEntityId, currentTick, "subway_stop_arrived", // #actor-trace
              s"line=${state.line} node=$nodeId passengers=${state.passengers.size}") // #actor-trace
            requestUnloadPeopleData()
            requestLoadPassenger()
            onFinishSpontaneous(None)
          case None =>
            leavingLink()
        }
      case Stopped =>
        leavingLink()
      case _ =>
        super.actSpontaneous(event)

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: SubwayLoadPassengerData   => handleBusLoadPeople(event, d)
      case d: SubwayUnloadPassengerData => handleUnloadPassenger(event, d)
      case _                            => super.actInteractWith(event)
    }

  private def requestLoadPassenger(): Unit = {
    val nodeId = getCurrentNode
    val stationOpt = if (nodeId != null) retrieveSubwayStationFromNodeId(nodeId) else None
    stationOpt match {
      case Some(stationId) =>
        val availableSpace = math.min(
          x = state.capacity - state.passengers.size,
          y = SubwayUtil.numberOfPassengerToBoarding(
            numberOfPorts = state.numberOfPorts,
            portsCapacity = state.capacity,
            stopTime = state.stopTime,
            boardingTimeByPassenger = state.boardingTimeByPassenger
          )
        )
        sendMessageTo(
          entityId = stationId,
          shardId = "hybrid.actor.SubwayStation",
          data = SubwayRequestPassengerData(
            line = state.line,
            availableSpace = availableSpace
          )
        )
      case None =>
        state.nodeState.isLoaded = true
        onFinishNodeState()
    }
  }

  private def requestUnloadPeopleData(): Unit = {
    if (state.passengers.isEmpty) {
      state.nodeState.isUnloaded = true
      onFinishNodeState()
      return
    }

    state.countUnloadReceived = 0
    state.countUnloadPassenger = 0
    expectedUnloadResponses = state.passengers.size

    val nodeId = getCurrentNode
    state.passengers.foreach {
      case (_, person) =>
        sendMessageTo(
          entityId = person.id,
          shardId = person.classType,
          data = SubwayRequestUnloadPassengerData(
            nodeId = nodeId,
            nodeRef = self
          )
        )
    }
  }

  private def retrieveSubwayStationFromNodeId(value: String): Option[String] =
    state.subwayStations.find {
      case (_, v) => v == value
    }.map(_._1)

  private def handleBusLoadPeople(
    event: ActorInteractionEvent,
    data: SubwayLoadPassengerData
  ): Unit = {
    state.nodeState.isLoaded = true
    for (person <- data.people)
      state.passengers.put(person.id, person)
    if (data.people.nonEmpty) {
      SubwayMetrics.passengersBoarded.labels(state.line).inc(data.people.size)
      SubwayMetrics.activePassengers.inc(data.people.size)
      report(
        data = Map(
          "event_type" -> "subway_load_passengers",
          "subway_id" -> getEntityId,
          "line" -> state.line,
          "passengers_loaded" -> data.people.size,
          "total_passengers" -> state.passengers.size,
          "capacity" -> state.capacity,
          "tick" -> currentTick
        ),
        label = "subway_load_passengers"
      )
    }
    onFinishNodeState()
  }

  private def handleUnloadPassenger(
    event: ActorInteractionEvent,
    data: SubwayUnloadPassengerData
  ): Unit = {
    state.countUnloadReceived += 1
    if (data.isArrival) {
      state.passengers.remove(event.actorRefId)
      state.countUnloadPassenger += 1
    }
    if (state.countUnloadReceived >= expectedUnloadResponses) {
      val unloadedCount = state.countUnloadPassenger
      state.countUnloadReceived = 0
      state.countUnloadPassenger = 0
      expectedUnloadResponses = 0
      if (unloadedCount > 0) {
        SubwayMetrics.passengersAlighted.labels(state.line).inc(unloadedCount)
        SubwayMetrics.activePassengers.dec(unloadedCount)
        report(
          data = Map(
            "event_type" -> "subway_unload_passengers",
            "subway_id" -> getEntityId,
            "line" -> state.line,
            "passengers_unloaded" -> unloadedCount,
            "remaining_passengers" -> state.passengers.size,
            "tick" -> currentTick
          ),
          label = "subway_unload_passengers"
        )
      }
      state.nodeState.isUnloaded = true
      onFinishNodeState()
    }
  }

  override def actHandleReceiveLeaveLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    state.distance += data.linkLength
    logDebug(s"Subway ${getEntityId} left rail link (total distance: ${state.distance}m)")
    report(
      data = Map(
        "event_type" -> "leave_link",
        "subway_id" -> getEntityId,
        "link_id" -> event.actorRefId,
        "line" -> state.line,
        "passengers" -> state.passengers.size,
        "total_distance" -> state.distance,
        "tick" -> currentTick
      ),
      label = "leave_link"
    )
    state.status = Ready
    onFinishSpontaneous(Some(currentTick + 1))
  }

  override def actHandleReceiveEnterLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    val effectiveVelocity = state.velocity * math.max(0.5, math.min(1.5, state.speedFactor))
    val time = timeToNextStation(
      distance = data.linkLength,
      velocity = effectiveVelocity
    )
    logDebug(s"Subway ${getEntityId} entering rail link")
    logDebug(s"  Line: ${state.line}")
    logDebug(s"  Length: ${data.linkLength}m")
    logDebug(s"  Speed: ${effectiveVelocity} km/h (factor=${state.speedFactor})")
    logDebug(s"  Travel time: ${time} ticks")
    state.status = Moving
    report(
      data = Map(
        "event_type" -> "enter_link",
        "subway_id" -> getEntityId,
        "link_id" -> event.actorRefId,
        "line" -> state.line,
        "passengers" -> state.passengers.size,
        "capacity" -> state.capacity,
        "travel_time" -> time,
        "tick" -> currentTick
      ),
      label = "enter_link"
    )
    onFinishSpontaneous(Some(currentTick + time.toLong))
  }

  override def getNextPath: Option[(String, String)] =
    state.bestRoute match
      case Some(routePath) =>
        if state.currentPathPosition < routePath.size then
          val nextPath = routePath(state.currentPathPosition)
          state.currentPathPosition += 1
          Some(nextPath)
        else
          state.currentPathPosition = 1
          Some(routePath(0))
      case None =>
        None

  private def onFinishNodeState(): Unit =
    if isEndNodeState then
      state.nodeState.isLoaded = false
      state.nodeState.isUnloaded = false
      scheduleEvent(currentTick + state.stopTime)

  private def isEndNodeState: Boolean =
    state.nodeState.isLoaded && state.nodeState.isUnloaded
}
