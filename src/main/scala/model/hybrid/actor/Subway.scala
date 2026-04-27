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

  override def actSpontaneous(event: SpontaneousEvent): Unit =
    state.status match
      case Start =>
        state.status = Ready
        enterLink()
      case Ready =>
        enterLink()
      case Moving =>
        val nodeId = getCurrentNode
        val stationOpt = if (nodeId != null) retrieveSubwayStationFromNodeId(nodeId) else None
        stationOpt match {
          case Some(_) =>
            state.status = Stopped
            requestUnloadPeopleData()
            requestLoadPassenger()
            onFinishSpontaneous(None)
          case None =>
            leavingLink()
        }
      case Stopped =>
        leavingLink()
      case _ =>
        logWarn(s"Event current status not handled ${state.status}")

  override def actInteractWith(event: ActorInteractionEvent): Unit = {
    super.actInteractWith(event)
    event.data match {
      case d: SubwayLoadPassengerData   => handleBusLoadPeople(event, d)
      case d: SubwayUnloadPassengerData => handleUnloadPassenger(event, d)
      case _ =>
        logWarn("Event not handled")
    }
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
    if (state.countUnloadReceived >= state.countUnloadPassenger + state.passengers.size) {
      state.countUnloadReceived = 0
      state.countUnloadPassenger = 0
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
    state.status = Ready
    onFinishSpontaneous(Some(currentTick + 1))
  }

  override def actHandleReceiveEnterLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    val time = timeToNextStation(
      distance = data.linkLength,
      velocity = state.velocity
    )
    logDebug(s"Subway ${getEntityId} entering rail link")
    logDebug(s"  Line: ${state.line}")
    logDebug(s"  Length: ${data.linkLength}m")
    logDebug(s"  Speed: ${state.velocity} km/h")
    logDebug(s"  Travel time: ${time} ticks")
    state.status = Moving
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
          state.currentPathPosition = 0
          Some(routePath(state.currentPathPosition))
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
