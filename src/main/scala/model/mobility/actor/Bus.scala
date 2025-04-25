package org.interscity.htc
package model.mobility.actor

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.{ Dependency, Identify }
import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import org.interscity.htc.core.entity.event.data.BaseEventData
import org.interscity.htc.model.mobility.entity.event.data.bus.{ BusLoadPassengerData, BusRequestPassengerData, BusRequestUnloadPassengerData, BusUnloadPassengerData }
import org.interscity.htc.model.mobility.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.mobility.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.mobility.entity.event.node.SignalStateData
import org.interscity.htc.model.mobility.entity.state.BusState
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum.{ Moving, Ready, Start, WaitingLoadPassenger, WaitingSignal, WaitingSignalState, WaitingUnloadPassenger }
import org.interscity.htc.model.mobility.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red
import org.interscity.htc.model.mobility.entity.state.model.RoutePathItem
import org.interscity.htc.model.mobility.util.{ BusUtil, SpeedUtil }
import org.interscity.htc.model.mobility.util.BusUtil.loadPersonTime
import org.interscity.htc.model.mobility.util.SpeedUtil.linkDensitySpeed

import scala.collection.mutable

class Bus(
  private val id: String = null,
  private val timeManager: ActorRef = null
) extends Movable[BusState](
      movableId = id,
      timeManager = timeManager
    ) {

  override def actSpontaneous(event: SpontaneousEvent): Unit =
    state.movableStatus match
      case Start =>
        state.movableStatus = Ready
        linkEnter()
      case Ready =>
        linkEnter()
      case Moving =>
        requestSignalState()
        requestLoadPassenger()
        requestUnloadPeopleData()
      case WaitingSignal | WaitingLoadPassenger | WaitingUnloadPassenger =>
        if (isEndNodeState && nodeStateMaxTime == event.tick) {
          state.movableStatus = Moving
          linkLeaving()
        }
      case _ =>
        logInfo(s"Event current status not handled ${state.movableStatus}")

  override def actInteractWith(event: ActorInteractionEvent): Unit = {
    super.actInteractWith(event)
    event.data match {
      case d: BusLoadPassengerData   => handleBusLoadPeople(event, d)
      case d: BusUnloadPassengerData => handleUnloadPassenger(event, d)
      case d: SignalStateData        => handleSignalState(event, d)
      case _ =>
        logInfo("Event not handled")
    }
  }

  override def actHandleReceiveLeaveLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    state.distance += data.linkLength
    onFinishSpontaneous(Some(currentTick + 1))
  }

  override def actHandleReceiveEnterLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    val time = linkDensitySpeed(
      length = data.linkLength,
      capacity = data.linkCapacity,
      numberOfCars = data.linkNumberOfCars,
      freeSpeed = data.linkFreeSpeed,
      lanes = data.linkLanes
    )
    state.movableStatus = Moving
    onFinishSpontaneous(Some(currentTick + time.toLong))
  }

  private def handleBusLoadPeople(event: ActorInteractionEvent, data: BusLoadPassengerData): Unit =
    if (data.people.nonEmpty) {
      val nextTickTime = currentTick + loadPersonTime(
        numberOfPorts = state.numberOfPorts,
        numberOfPassengers = data.people.size
      )
      scheduleEvent(nextTickTime)
      for (person <- data.people)
        state.people.put(person.id, person)
      state.nodeState.timeToLoadedPassengers = nextTickTime
      onFinishNodeState()
    } else {
      state.nodeState.timeToLoadedPassengers = currentTick
      onFinishNodeState()
    }

  private def handleSignalState(event: ActorInteractionEvent, data: SignalStateData): Unit =
    if (data.phase == Red) {
      state.movableStatus = WaitingSignal
      state.nodeState.timeToOpenSignal = data.nextTick
      scheduleEvent(data.nextTick)
      onFinishNodeState()
    } else {
      state.nodeState.timeToOpenSignal = currentTick
      onFinishNodeState()
    }

  private def handleUnloadPassenger(
    event: ActorInteractionEvent,
    data: BusUnloadPassengerData
  ): Unit = {
    state.countUnloadReceived += 1
    state.countUnloadPassenger += (if (data.isArrival) 1 else 0)
    if (state.countUnloadReceived == state.people.size) {
      val nextTickTime = currentTick + BusUtil.unloadPersonTime(
        numberOfPorts = state.numberOfPorts,
        numberOfPassengers = state.countUnloadPassenger
      )
      scheduleEvent(nextTickTime)
      onFinishNodeState()
    }
  }

  override def getNextPath: Option[(Identify, Identify)] =
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

  private def requestSignalState(): Unit = {
    state.movableStatus = WaitingSignalState
    viewNextPath match
      case Some(item) =>
        (item._1, item._2) match
          case (node, link) =>
            sendMessageTo(
              node.id,
              node.classType,
              RequestSignalStateData(
                targetLinkId = link.id
              ),
              EventTypeEnum.RequestSignalState.toString
            )
      case None => ???
  }

  private def requestLoadPassenger(): Unit = {
    state.movableStatus = WaitingLoadPassenger
    state.currentPath match
      case Some((node, _)) =>
        val busStop = retrieveBusStopFromNodeId(node.id)
        busStop match
          case Some(busStopId) =>
            val dependency = dependencies(busStopId)
            sendMessageTo(
              dependency.id,
              dependency.classType,
              data = BusRequestPassengerData(
                label = state.label,
                availableSpace = state.capacity - state.people.size
              )
            )
          case None =>
            logInfo("No has bus stop to load passenger")
      case None =>
        logInfo("No path to load passenger")
  }

  private def requestUnloadPeopleData(): Unit = {
    state.movableStatus = WaitingUnloadPassenger
    state.currentPath match
      case Some((node, _)) =>
        val busStop = retrieveBusStopFromNodeId(node.id)
        busStop match
          case Some(busStopId) =>
            state.people.foreach {
              person =>
                sendMessageTo(
                  person._2.id,
                  person._2.classType,
                  data = BusRequestUnloadPassengerData(
                    nodeId = node.id,
                    nodeRef = getActorRef(node.actorRef)
                  )
                )
            }
          case None =>
            logInfo("No has bus stop to unload passenger")
      case None =>
        logInfo("No path to unload passenger")
  }

  private def retrieveBusStopFromNodeId(value: String): Option[String] =
    state.busStops.find {
      case (_, v) => v == value
    }.map(_._1)

  private def nodeStateMaxTime: Long =
    List(
      state.nodeState.timeToLoadedPassengers,
      state.nodeState.timeToOpenSignal,
      state.nodeState.timeToUnloadedPassengers
    ).max

  private def onFinishNodeState(): Unit =
    if isEndNodeState then
      state.nodeState.timeToLoadedPassengers = Long.MinValue
      state.nodeState.timeToOpenSignal = Long.MinValue
      state.nodeState.timeToUnloadedPassengers = Long.MinValue
      state.movableStatus = Ready
      onFinishSpontaneous()

  private def isEndNodeState: Boolean =
    state.nodeState.timeToLoadedPassengers != Long.MinValue &&
      state.nodeState.timeToOpenSignal != Long.MinValue &&
      state.nodeState.timeToUnloadedPassengers != Long.MinValue
}
