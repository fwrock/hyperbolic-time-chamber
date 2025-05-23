package org.interscity.htc
package model.mobility.actor

import core.actor.BaseActor

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.{ Dependency, Identify }
import org.htc.protobuf.core.entity.event.control.execution.DestructEvent
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.data.BaseEventData
import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import org.interscity.htc.core.util.ActorCreatorUtil.createShardedActorSeveralArgs
import org.interscity.htc.core.util.JsonUtil.toJson
import org.interscity.htc.core.util.{ ActorCreatorUtil, JsonUtil }
import org.interscity.htc.model.mobility.entity.event.data.{ ReceiveRouteData, RequestRouteData }
import org.interscity.htc.model.mobility.entity.state.{ BusState, BusStationState }
import org.interscity.htc.model.mobility.entity.state.enumeration.BusStationStateEnum.{ Finish, Ready, RouteWaiting, Start, Working, WorkingWithOutBus }
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum.RequestRoute
import org.interscity.htc.model.mobility.entity.state.model.{ BusInformation, RoutePathItem, SubRoutePair }

import scala.collection.mutable

class BusStation(
  protected val properties: Properties
) extends BaseActor[BusStationState](
      properties = properties
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
          val className = classOf[Bus].getName
          dependencies(bus.actorId) = Dependency(
            id = entityId,
            classType = className
          )
          onFinishSpontaneous(Some(currentTick + state.interval))
        } else {
          state.status = WorkingWithOutBus
        }
      case _ =>
        logInfo(s"Event current status not handled ${state.status}")
    }

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case e: ReceiveRouteData => handleRequestRoute(event)
      case _ =>
        logInfo("Event not handled")
    }

  private def handleRequestRoute(value: ActorInteractionEvent): Unit = {
    val route = value.data.asInstanceOf[ReceiveRouteData]
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
      val className = classOf[Bus].getName
      dependencies(bus.actorId) = Dependency(
        id = entityId,
        classType = className
      )
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

  private def calcBusBestRoute(): mutable.Queue[(String, String)] = {
    val bestRoute = mutable.Queue[(String, String)]()
//    bestRoute ++= getTotalRoute(state.goingRoute.get)
//    bestRoute ++= getTotalRoute(state.returningRoute.get)
    bestRoute
  }

  private def getTotalRoute(
    route: mutable.Map[SubRoutePair, mutable.Queue[(Identify, Identify)]]
  ): mutable.Queue[(Identify, Identify)] = {
    val totalRoute = mutable.Queue[(Identify, Identify)]()
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
    route: Option[mutable.Map[SubRoutePair, mutable.Queue[(Identify, Identify)]]]
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
      requester = getSelfShard,
      requesterClassType = getShardId,
      requesterId = entityId,
      currentCost = 0,
      targetNodeId = destination,
      originNodeId = origin,
      path = mutable.Queue(),
      label = label
    )
    val dependency = getDependency(origin)
    sendMessageTo(
      dependency.id,
      dependency.classType,
      data,
      RequestRoute.toString
    )
  }

  override def onDestruct(event: DestructEvent): Unit =
    state.status = Finish
}
