package org.interscity.htc
package model.interscsimulator.actor

import core.actor.BaseActor

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.control.execution.DestructEvent
import org.interscity.htc.core.entity.event.data.BaseEventData
import org.interscity.htc.core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import org.interscity.htc.core.util.ActorCreatorUtil.{createShardedActor, createShardedActorSeveralArgs}
import org.interscity.htc.core.util.JsonUtil.toJson
import org.interscity.htc.core.util.{ActorCreatorUtil, JsonUtil}
import org.interscity.htc.model.interscsimulator.entity.event.data.{ReceiveRouteData, RequestRouteData}
import org.interscity.htc.model.interscsimulator.entity.state.{BusState, BusStationState}
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.BusStationStateEnum.{Finish, Ready, RouteWaiting, Start, Working, WorkingWithOutBus}
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.EventTypeEnum.RequestRoute
import org.interscity.htc.model.interscsimulator.entity.state.model.{BusInformation, RoutePathItem, SubRoutePair}

import scala.collection.mutable

class BusStation(
  override protected val actorId: String = null,
  private val timeManager: ActorRef = null,
  private val data: String = null,
  override protected val dependencies: mutable.Map[String, ActorRef] =
    mutable.Map[String, ActorRef]()
) extends BaseActor[BusStationState](
      actorId = actorId,
      timeManager = timeManager,
      data = data,
      dependencies = dependencies
    ) {

  override def actSpontaneous(event: SpontaneousEvent): Unit =
    state.status match {
      case Start =>
        state.status = RouteWaiting
        requestGoingRoute()
        requestReturningRoute()
      case Working =>
        if (state.buses.nonEmpty) {
          val bus = state.buses.dequeue()
          val actorRef = createBus(bus)
          dependencies(bus.actorId) = actorRef
          onFinishSpontaneous(Some(currentTick + state.interval))
        } else {
          state.status = WorkingWithOutBus
        }
      case _ =>
        logEvent(s"Event current status not handled ${state.status}")
    }

  override def actInteractWith[D <: BaseEventData](event: ActorInteractionEvent[D]): Unit =
    event match {
      case e: ActorInteractionEvent[ReceiveRouteData] => handleRequestRoute(e)
      case _ =>
        logEvent("Event not handled")
    }

  private def handleRequestRoute(value: ActorInteractionEvent[ReceiveRouteData]): Unit = {
    val route = value.data
    if (route.label == "going-route") {
      state.goingRoute match
        case Some(goingRoute) =>
          goingRoute.put(SubRoutePair(route.origin, route.destination), route.path)
    } else {
      state.returningRoute match
        case Some(returningRoute) =>
          returningRoute.put(SubRoutePair(route.origin, route.destination), route.path)
    }
    if (isCalculateRoutingComplete) {
      state.status = Ready
      val bus = state.buses.dequeue()
      val actorRef = createBus(bus)
      dependencies(bus.actorId) = actorRef
      state.status = Working
      onFinishSpontaneous(Some(currentTick + state.interval))
    }
  }

  private def createBus(bus: BusInformation): ActorRef =
    createShardedActorSeveralArgs(
      system = context.system,
      actorClass = classOf[Bus],
      entityId = bus.actorId,
      getTimeManager,
      toJson(
        BusState(
          startTick = currentTick,
          busStops = state.busStops,
          capacity = bus.capacity,
          size = bus.size,
          origin = state.origin,
          destination = state.destination,
          bestRoute = Some(calcBusBestRoute()),
          numberOfPorts = bus.numberOfPorts,
          label = bus.label
        )
      ),
      dependencies
    )

  private def calcBusBestRoute(): mutable.Queue[(RoutePathItem, RoutePathItem)] = {
    val bestRoute = mutable.Queue[(RoutePathItem, RoutePathItem)]()
    bestRoute ++= getTotalRoute(state.goingRoute.get)
    bestRoute ++= getTotalRoute(state.returningRoute.get)
  }

  private def getTotalRoute(
    route: mutable.Map[SubRoutePair, mutable.Queue[(RoutePathItem, RoutePathItem)]]
  ): mutable.Queue[(RoutePathItem, RoutePathItem)] = {
    val totalRoute = mutable.Queue[(RoutePathItem, RoutePathItem)]()
    for (pair <- state.busStops.keys.sliding(2)) {
      val pathPart = route(SubRoutePair(pair.head, pair.last))
      totalRoute ++= pathPart
    }
    totalRoute
  }

  private def isCalculateRoutingComplete: Boolean =
    isCalculateRoutingComplete(state.goingRoute) &&
      isCalculateRoutingComplete(state.returningRoute)

  private def isCalculateRoutingComplete(
    route: Option[mutable.Map[SubRoutePair, mutable.Queue[(RoutePathItem, RoutePathItem)]]]
  ): Boolean =
    route match
      case Some(r) => r.keys.size == state.busStops.sliding(2, 2).size
      case None    => false

  private def requestGoingRoute(): Unit =
    for (pair <- state.busStops.keys.sliding(2))
      requestRoute(pair.head, pair.last, "going-route")

  private def requestReturningRoute(): Unit = {
    val reversedBusStops = state.busStops.keys.toList.reverse
    for (pair <- reversedBusStops.sliding(2))
      requestRoute(pair.head, pair.last, "returning-route")
  }

  private def requestRoute(origin: String, destination: String, label: String): Unit = {
    val data = RequestRouteData(
      requester = self,
      requesterId = actorId,
      currentCost = 0,
      targetNodeId = destination,
      originNodeId = origin,
      path = mutable.Queue(),
      label = label
    )
    sendMessageTo(
      actorId = origin,
      actorRef = dependencies(origin),
      data,
      RequestRoute.toString
    )
  }

  override def onDestruct(event: DestructEvent): Unit =
    state.status = Finish
}
