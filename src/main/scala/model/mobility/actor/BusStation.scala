package org.interscity.htc
package model.mobility.actor

import core.actor.SimulationBaseActor

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
) extends SimulationBaseActor[BusStationState](
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
          try {
            val actorRef = createBus(bus)
            val className = classOf[Bus].getName
            dependencies(bus.actorId) = Dependency(
              id = entityId,
              classType = className
            )
            onFinishSpontaneous(Some(currentTick + state.interval))
          } catch {
            case e: IllegalStateException =>
              logError(s"Failed to create bus ${bus.actorId}: ${e.getMessage}")
              // Put the bus back in the queue for later retry
              state.buses.enqueue(bus)
              state.status = RouteWaiting // Go back to waiting for route
            case e: Exception =>
              logError(s"Unexpected error creating bus ${bus.actorId}: ${e.getMessage}")
              state.buses.enqueue(bus)
              state.status = RouteWaiting
          }
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
    
    // We need to find the bus stop pair corresponding to the received origin/destination nodes
    val originBusStop = state.busStops.find(_._2 == route.origin).map(_._1)
    val destinationBusStop = state.busStops.find(_._2 == route.destination).map(_._1)
    
    (originBusStop, destinationBusStop) match {
      case (Some(originStop), Some(destStop)) =>
        val subRoutePair = SubRoutePair(originStop, destStop)
        
        if (route.label == "going-route") {
          state.goingRoute match
            case Some(goingRoute) =>
              goingRoute.put(subRoutePair, route.path)
        } else {
          state.returningRoute match
            case Some(returningRoute) =>
              returningRoute.put(subRoutePair, route.path)
        }
        
        if (isCalculateRoutingComplete) {
          state.status = Ready
          if (state.buses.nonEmpty) {
            val bus = state.buses.dequeue()
            try {
              val actorRef = createBus(bus)
              val className = classOf[Bus].getName
              dependencies(bus.actorId) = Dependency(
                id = entityId,
                classType = className
              )
              state.status = Working
              onFinishSpontaneous(Some(currentTick + state.interval))
            } catch {
              case e: IllegalStateException =>
                logError(s"Failed to create bus ${bus.actorId} after route completion: ${e.getMessage}")
                state.buses.enqueue(bus) // Put back in queue
                state.status = RouteWaiting // Wait for valid route data
              case e: Exception =>
                logError(s"Unexpected error creating bus ${bus.actorId}: ${e.getMessage}")
                state.buses.enqueue(bus)
                state.status = RouteWaiting
            }
          } else {
            logWarn("Route calculation completed but no buses available")
          }
        }
      case _ =>
        logWarn(s"BusStation ${getEntityId} received route for unknown node pair: ${route.origin} -> ${route.destination}")
    }
  }

  private def createBus(bus: BusInformation): ActorRef = {
    val route = calcBusBestRoute()
    if (route.isEmpty) {
      logWarn(s"Cannot create bus ${bus.actorId} - no valid route available")
      throw new IllegalStateException(s"No route available for bus ${bus.actorId}")
    }
    
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
          bestRoute = Some(route),
          numberOfPorts = bus.numberOfPorts,
          label = bus.label
        )
      ),
      dependencies
    )
  }

  private def calcBusBestRoute(): mutable.Queue[(String, String)] = {
    val bestRoute = mutable.Queue[(String, String)]()
    
    // Check if we have valid route data before building the route
    if (state.goingRoute.isDefined && state.goingRoute.get.nonEmpty) {
      val goingRouteData = getTotalRoute(state.goingRoute.get)
      bestRoute ++= goingRouteData.map(pair => (pair._1.id, pair._2.id))
    } else {
      logWarn("No going route defined for bus station")
    }
    
    if (state.returningRoute.isDefined && state.returningRoute.get.nonEmpty) {
      val returningRouteData = getTotalRoute(state.returningRoute.get)
      bestRoute ++= returningRouteData.map(pair => (pair._1.id, pair._2.id))
    } else {
      logWarn("No returning route defined for bus station")
    }
    
    if (bestRoute.isEmpty) {
      logWarn("Bus route calculation resulted in empty route - this may cause buses to terminate immediately")
    }
    
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

  private def requestGoingRoute(): Unit = {
    val busStopsList = state.busStops.keys.toList
    for (pair <- busStopsList.sliding(2)) {
      val originBusStopId = pair.head
      val destinationBusStopId = pair.last
      val originNodeId = state.busStops(originBusStopId)
      val destinationNodeId = state.busStops(destinationBusStopId)
      requestRoute(originBusStopId, destinationBusStopId, originNodeId, destinationNodeId, "going-route")
    }
  }

  private def requestReturningRoute(): Unit = {
    val reversedBusStops = state.busStops.keys.toList.reverse
    for (pair <- reversedBusStops.sliding(2)) {
      val originBusStopId = pair.head
      val destinationBusStopId = pair.last
      val originNodeId = state.busStops(originBusStopId)
      val destinationNodeId = state.busStops(destinationBusStopId)
      requestRoute(originBusStopId, destinationBusStopId, originNodeId, destinationNodeId, "returning-route")
    }
  }

  private def requestRoute(originBusStopId: String, destinationBusStopId: String, originNodeId: String, destinationNodeId: String, label: String): Unit = {
    val data = RequestRouteData(
      requester = getSelfShard,
      requesterClassType = getShardId,
      requesterId = entityId,
      currentCost = 0,
      targetNodeId = destinationNodeId,
      originNodeId = originNodeId,
      path = mutable.Queue(),
      label = label
    )
    // Create a temporary dependency for the origin node to request route
    val nodeActorRef = context.system.actorSelection(s"/user/sharded-actors/hybrid.actor.Node/$originNodeId")
    sendMessageTo(
      originNodeId,
      "hybrid.actor.Node",
      data,
      RequestRoute.toString
    )
  }

  override def onDestruct(event: DestructEvent): Unit =
    state.status = Finish
}
