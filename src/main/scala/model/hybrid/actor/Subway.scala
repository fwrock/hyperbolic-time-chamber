package org.interscity.htc
package model.hybrid.actor

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.{ Dependency, Identify }
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import org.interscity.htc.core.entity.event.data.BaseEventData
import org.interscity.htc.model.hybrid.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.hybrid.entity.event.data.subway.{ SubwayLoadPassengerData, SubwayRequestPassengerData, SubwayRequestUnloadPassengerData, SubwayUnloadPassengerData }
import org.interscity.htc.model.hybrid.entity.state.SubwayState
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.{ Moving, Ready, Start, Stopped, WaitingLoadPassenger }
import org.interscity.htc.model.hybrid.entity.state.model.RoutePathItem
import org.interscity.htc.model.hybrid.util.SubwayUtil
import org.interscity.htc.model.hybrid.util.SubwayUtil.timeToNextStation

import scala.collection.mutable

/** Subway actor - Metro train following predefined rail routes.
  * 
  * IMPORTANT: Subways use PREDEFINED routes (bestRoute) created by SubwayStation.
  * Unlike cars/buses that use dynamic routing (GraphRouter), subway trains follow
  * fixed rail lines that never change.
  * 
  * Key characteristics:
  * - Uses RAIL_LINKS (not road links) - exclusive subway infrastructure
  * - Route is predefined at creation by SubwayStation (bestRoute field)
  * - Follows digital rail path (stations in order)
  * - No dynamic rerouting - trains stay on their assigned line
  * - RailLink validates vehicle type (only Subway can enter)
  * 
  * Flow:
  * 1. SubwayStation creates Subway with bestRoute = rail_link IDs
  * 2. Subway calls enterLink() with next rail_link from bestRoute
  * 3. RailLink validates vehicle type (Subway ✓) and sends LinkInfoData
  * 4. Subway travels through rail_link (no congestion)
  * 5. Subway calls leavingLink() when reaching next station
  * 6. Repeat for next rail_link in bestRoute
  * 
  * @param properties Actor properties
  */
class Subway(
  private val properties: Properties
) extends Movable[SubwayState](
      properties = properties
    ) {

  override def actSpontaneous(event: SpontaneousEvent): Unit =
    state.movableStatus match
      case Start =>
        state.movableStatus = Ready
        enterLink()
      case Ready =>
        enterLink()
      case Moving =>
        state.status = Stopped
        requestLoadPassenger()
        requestUnloadPeopleData()
      case Stopped =>
        leavingLink()
      case _ =>
        logInfo(s"Event current status not handled ${state.movableStatus}")

  override def actInteractWith(event: ActorInteractionEvent): Unit = {
    super.actInteractWith(event)
    event.data match {
      case d: SubwayLoadPassengerData   => handleBusLoadPeople(event, d)
      case d: SubwayUnloadPassengerData => handleUnloadPassenger(event, d)
      case _ =>
        logInfo("Event not handled")
    }
  }

  private def requestLoadPassenger(): Unit = {
    state.movableStatus = WaitingLoadPassenger
    val availableSpace = math.min(
      x = state.capacity - state.passengers.size,
      y = SubwayUtil.numberOfPassengerToBoarding(
        numberOfPorts = state.numberOfPorts,
        portsCapacity = state.capacity,
        stopTime = state.stopTime,
        boardingTimeByPassenger = state.boardingTimeByPassenger
      )
    )
    state.currentPath match
      case Some((node, _)) =>
//        val station = retrieveSubwayStationFromNodeId(node.id)
//        station match
//          case Some(stationId) =>
//            val dependency = getDependency(stationId)
//            sendMessageTo(
//              dependency.id,
//              dependency.classType,
//              data = SubwayRequestPassengerData(
//                line = state.line,
//                availableSpace = availableSpace
//              )
//            )
//          case None =>
//            logInfo("No has bus stop to load passenger")
      case None =>
        logInfo("No path to load passenger")
  }

  private def requestUnloadPeopleData(): Unit =
    state.currentPath match
      case Some((node, _)) =>
//        val busStop = retrieveSubwayStationFromNodeId(node.id)
//        busStop match
//          case Some(busStopId) =>
//            state.passengers.foreach {
//              person =>
//                sendMessageTo(
//                  person._2.id,
//                  person._2.classType,
//                  data = SubwayRequestUnloadPassengerData(
//                    nodeId = node.id,
//                    nodeRef = getActorRef(node.actorRef)
//                  )
//                )
//            }
//          case None =>
//            logInfo("No has bus stop to unload passenger")
      case None =>
        logInfo("No path to unload passenger")

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
    state.countUnloadPassenger += (if (data.isArrival) 1 else 0)
    if (state.countUnloadReceived == state.passengers.size) {
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
    state.movableStatus = Ready
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
    state.movableStatus = Moving
    onFinishSpontaneous(Some(currentTick + time.toLong))
  }

  override def getNextPath: Option[(String, String)] =
    state.movableBestRoute match
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
      onFinishSpontaneous(Some(currentTick + state.stopTime), destruct = false)

  private def isEndNodeState: Boolean =
    state.nodeState.isLoaded && state.nodeState.isUnloaded
}
