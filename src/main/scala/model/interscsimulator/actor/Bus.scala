package org.interscity.htc
package model.interscsimulator.actor

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import org.interscity.htc.core.entity.event.data.BaseEventData
import org.interscity.htc.model.interscsimulator.entity.event.data.bus.{ BusLoadPassengerData, BusRequestPassengerData, BusRequestUnloadPassengerData, BusUnloadPassengerData }
import org.interscity.htc.model.interscsimulator.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.interscsimulator.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.interscsimulator.entity.event.node.SignalStateData
import org.interscity.htc.model.interscsimulator.entity.state.BusState
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.MovableStatusEnum.{ Moving, Ready, Start, WaitingLoadPassenger, WaitingSignal, WaitingSignalState, WaitingUnloadPassenger }
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red
import org.interscity.htc.model.interscsimulator.entity.state.model.RoutePathItem
import org.interscity.htc.model.interscsimulator.util.{ BusUtil, SpeedUtil }
import org.interscity.htc.model.interscsimulator.util.BusUtil.loadPersonTime
import org.interscity.htc.model.interscsimulator.util.SpeedUtil.linkDensitySpeed

import scala.collection.mutable

class Bus(
  override protected val actorId: String = null,
  private val timeManager: ActorRef = null,
  private val data: String = null,
  override protected val dependencies: mutable.Map[String, ActorRef] =
    mutable.Map[String, ActorRef]()
) extends Movable[BusState](
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
        requestSignalState()
        requestLoadPassenger()
        requestUnloadPeopleData()
      case WaitingSignal | WaitingLoadPassenger | WaitingUnloadPassenger =>
        if (isEndNodeState && nodeStateMaxTime == event.tick) {
          state.movableStatus = Moving
          linkLeaving()
        }
      case _ =>
        logEvent(s"Event current status not handled ${state.movableStatus}")

  override def actInteractWith[D <: BaseEventData](event: ActorInteractionEvent[D]): Unit = {
    super.actInteractWith(event)
    event match {
      case e: ActorInteractionEvent[BusLoadPassengerData]   => handleBusLoadPeople(e)
      case e: ActorInteractionEvent[BusUnloadPassengerData] => handleUnloadPassenger(e)
      case e: ActorInteractionEvent[SignalStateData]        => handleSignalState(e)
      case _ =>
        logEvent("Event not handled")
    }
  }

  override def actHandleReceiveLeaveLinkInfo(event: ActorInteractionEvent[LinkInfoData]): Unit = {
    state.distance += event.data.linkLength
    onFinishSpontaneous(Some(currentTick + 1))
  }

  override def actHandleReceiveEnterLinkInfo(event: ActorInteractionEvent[LinkInfoData]): Unit = {
    val time = linkDensitySpeed(
      length = event.data.linkLength,
      capacity = event.data.linkCapacity,
      numberOfCars = event.data.linkNumberOfCars,
      freeSpeed = event.data.linkFreeSpeed,
      lanes = event.data.linkLanes
    )
    state.movableStatus = Moving
    onFinishSpontaneous(Some(currentTick + time.toLong))
  }

  private def handleBusLoadPeople(event: ActorInteractionEvent[BusLoadPassengerData]): Unit =
    if (event.data.people.nonEmpty) {
      val nextTickTime = currentTick + loadPersonTime(
        numberOfPorts = state.numberOfPorts,
        numberOfPassengers = event.data.people.size
      )
      scheduleEvent(nextTickTime)
      for (person <- event.data.people)
        state.people.put(person.id, person.actorRef)
      state.nodeState.timeToLoadedPassengers = nextTickTime
      onFinishNodeState()
    } else {
      state.nodeState.timeToLoadedPassengers = currentTick
      onFinishNodeState()
    }

  private def handleSignalState(event: ActorInteractionEvent[SignalStateData]): Unit =
    if (event.data.phase == Red) {
      state.movableStatus = WaitingSignal
      state.nodeState.timeToOpenSignal = event.data.nextTick
      scheduleEvent(event.data.nextTick)
      onFinishNodeState()
    } else {
      state.nodeState.timeToOpenSignal = currentTick
      onFinishNodeState()
    }

  private def handleUnloadPassenger(event: ActorInteractionEvent[BusUnloadPassengerData]): Unit = {
    state.countUnloadReceived += 1
    state.countUnloadPassenger += (if (event.data.isArrival) 1 else 0)
    if (state.countUnloadReceived == state.people.size) {
      val nextTickTime = currentTick + BusUtil.unloadPersonTime(
        numberOfPorts = state.numberOfPorts,
        numberOfPassengers = state.countUnloadPassenger
      )
      scheduleEvent(nextTickTime)
      onFinishNodeState()
    }
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

  private def requestSignalState(): Unit = {
    state.movableStatus = WaitingSignalState
    viewNextPath match
      case Some(item) =>
        (item._1, item._2) match
          case (node, link) =>
            sendMessageTo(
              actorId = node.actorId,
              actorRef = node.actorRef,
              RequestSignalStateData(
                targetLinkId = link.actorId
              ),
              EventTypeEnum.RequestSignalState.toString
            )
      case None => ???
  }

  private def requestLoadPassenger(): Unit = {
    state.movableStatus = WaitingLoadPassenger
    state.currentPath match
      case Some((node, _)) =>
        val busStop = retrieveBusStopFromNodeId(node.actorId)
        busStop match
          case Some(busStopId) =>
            sendMessageTo(
              actorId = busStopId,
              actorRef = dependencies(busStopId),
              data = BusRequestPassengerData(
                label = state.label,
                availableSpace = state.capacity - state.people.size
              )
            )
          case None =>
            logEvent("No has bus stop to load passenger")
      case None =>
        logEvent("No path to load passenger")
  }

  private def requestUnloadPeopleData(): Unit = {
    state.movableStatus = WaitingUnloadPassenger
    state.currentPath match
      case Some((node, _)) =>
        val busStop = retrieveBusStopFromNodeId(node.actorId)
        busStop match
          case Some(busStopId) =>
            state.people.foreach {
              person =>
                sendMessageTo(
                  actorId = person._1,
                  actorRef = person._2,
                  data = BusRequestUnloadPassengerData(
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
