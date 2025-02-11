package org.interscity.htc
package model.interscsimulator.actor

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import org.interscity.htc.core.entity.event.data.BaseEventData
import org.interscity.htc.model.interscsimulator.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.interscsimulator.entity.event.data.subway.{SubwayLoadPassengerData, SubwayRequestPassengerData, SubwayRequestUnloadPassengerData, SubwayUnloadPassengerData}
import org.interscity.htc.model.interscsimulator.entity.state.SubwayState
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.MovableStatusEnum.{Moving, Ready, Start, Stopped, WaitingLoadPassenger}
import org.interscity.htc.model.interscsimulator.entity.state.model.RoutePathItem
import org.interscity.htc.model.interscsimulator.util.SubwayUtil
import org.interscity.htc.model.interscsimulator.util.SubwayUtil.timeToNextStation

import scala.collection.mutable

class Subway(
  override protected val actorId: String = null,
  private val timeManager: ActorRef = null,
  private val data: String = null,
  override protected val dependencies: mutable.Map[String, ActorRef] =
    mutable.Map[String, ActorRef]()
) extends Movable[SubwayState](
      actorId = actorId,
      timeManager = timeManager,
      data = data,
      dependencies = dependencies
    ) {

  override def actSpontaneous(event: SpontaneousEvent): Unit =
    state.movableStatus match
      case Start =>
        state.movableStatus = Ready
        linkEnter()
      case Ready =>
        linkEnter()
      case Moving =>
        state.status = Stopped
        requestLoadPassenger()
        requestUnloadPeopleData()
      case Stopped =>
        linkLeaving()
      case _ =>
        logEvent(s"Event current status not handled ${state.movableStatus}")

  override def actInteractWith[D <: BaseEventData](event: ActorInteractionEvent[D]): Unit = {
    super.actInteractWith(event)
    event match {
      case e: ActorInteractionEvent[SubwayLoadPassengerData]   => handleBusLoadPeople(e)
      case e: ActorInteractionEvent[SubwayUnloadPassengerData] => handleUnloadPassenger(e)
      case _ =>
        logEvent("Event not handled")
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
        val station = retrieveSubwayStationFromNodeId(node.actorId)
        station match
          case Some(stationId) =>
            sendMessageTo(
              actorId = stationId,
              actorRef = dependencies(stationId),
              data = SubwayRequestPassengerData(
                line = state.line,
                availableSpace = availableSpace
              )
            )
          case None =>
            logEvent("No has bus stop to load passenger")
      case None =>
        logEvent("No path to load passenger")
  }

  private def requestUnloadPeopleData(): Unit = {
    state.currentPath match
      case Some((node, _)) =>
        val busStop = retrieveSubwayStationFromNodeId(node.actorId)
        busStop match
          case Some(busStopId) =>
            state.passengers.foreach {
              person =>
                sendMessageTo(
                  actorId = person._1,
                  actorRef = person._2,
                  data = SubwayRequestUnloadPassengerData(
                    nodeId = node.actorId,
                    nodeRef = node.actorRef
                  )
                )
            }
          case None =>
            logEvent("No has bus stop to unload passenger")
      case None =>
        logEvent("No path to unload passenger")
  }

  private def retrieveSubwayStationFromNodeId(value: String): Option[String] =
    state.subwayStations.find {
      case (_, v) => v == value
    }.map(_._1)

  private def handleBusLoadPeople(event: ActorInteractionEvent[SubwayLoadPassengerData]): Unit = {
    state.nodeState.isLoaded = true
    for (person <- event.data.people)
      state.passengers.put(person.id, person.actorRef)
    onFinishNodeState()
  }

  private def handleUnloadPassenger(event: ActorInteractionEvent[SubwayUnloadPassengerData]): Unit = {
    state.countUnloadReceived += 1
    state.countUnloadPassenger += (if (event.data.isArrival) 1 else 0)
    if (state.countUnloadReceived == state.passengers.size) {
      state.nodeState.isUnloaded = true
      onFinishNodeState()
    }
  }

  override def actHandleReceiveLeaveLinkInfo(event: ActorInteractionEvent[LinkInfoData]): Unit = {
    state.distance += event.data.linkLength
    state.movableStatus = Ready
    onFinishSpontaneous(Some(currentTick + 1))
  }

  override def actHandleReceiveEnterLinkInfo(event: ActorInteractionEvent[LinkInfoData]): Unit = {
    val time = timeToNextStation(
      distance = event.data.linkLength,
      velocity = state.velocity
    )
    state.movableStatus = Moving
    onFinishSpontaneous(Some(currentTick + time.toLong))
  }

  override def getNextPath: Option[(RoutePathItem, RoutePathItem)] =
    state.movableBestRoute match
      case Some(path) =>
        if state.currentPathPosition < path.size then
          val nextPath = path(state.currentPathPosition)
          state.currentPathPosition += 1
          Some(nextPath)
        else
          state.currentPathPosition = 0
          Some(path(state.currentPathPosition))
      case None =>
        None

  private def onFinishNodeState(): Unit =
    if isEndNodeState then
      state.nodeState.isLoaded = false
      state.nodeState.isUnloaded = false
      onFinishSpontaneous(Some(currentTick + state.stopTime))

  private def isEndNodeState: Boolean =
    state.nodeState.isLoaded && state.nodeState.isUnloaded
}
