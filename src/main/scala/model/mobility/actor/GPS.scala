package org.interscity.htc
package model.mobility.actor

import core.actor.SimulationBaseActor
import model.mobility.entity.state.GPSState

import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.model.mobility.entity.model.model.{ IdentifyPair, Route }
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.ActorInteractionEvent
import org.interscity.htc.core.entity.event.control.load.InitializeEvent
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.model.mobility.collections.Graph
import org.interscity.htc.model.mobility.collections.graph.Edge
import org.interscity.htc.model.mobility.entity.event.data.{ ReceiveRoute, RequestRoute }
import org.interscity.htc.model.mobility.entity.state.model.{ EdgeGraph, NodeGraph }
import org.interscity.htc.system.database.redis.RedisClientManager

import java.util.UUID
import scala.collection.mutable
import scala.util.{ Failure, Success }
import scala.compiletime.uninitialized

class GPS(
  private val properties: Properties
) extends SimulationBaseActor[GPSState](
      properties = properties
    ) {

  private var cityMap: Graph[NodeGraph, Double, EdgeGraph] = uninitialized

  private val redisManager = RedisClientManager()

  override def onInitialize(event: InitializeEvent): Unit = {
    super.onInitialize(event)
    loadCityMap()
  }

  override def onStart(): Unit =
    loadCityMap()

  private def loadCityMap(): Unit = {}

  private val heuristicFunc: (NodeGraph, NodeGraph) => Double = (current, goal) =>
    current.euclideanDistance(goal)

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case data: RequestRoute =>
        if (cityMap == null) {
          state.requests.enqueue(
            (
              Identify(
                id = event.actorRefId,
                resourceId = event.shardRefId,
                classType = event.actorClassType,
                actorType = event.actorType
              ),
              data
            )
          )
        } else if (cityMap != null && state.requests.nonEmpty) {
          processQueuedRequests()
          handleRequestRoute(
            Identify(
              id = event.actorRefId,
              resourceId = event.shardRefId,
              classType = event.actorClassType,
              actorType = event.actorType
            ),
            data
          )
        } else {
          handleRequestRoute(
            Identify(
              id = event.actorRefId,
              resourceId = event.shardRefId,
              classType = event.actorClassType,
              actorType = event.actorType
            ),
            data
          )
        }
      case _ =>
        logWarn(s"Unknown event type: ${event.getClass}")
    }

  private def processQueuedRequests(): Unit =
    if (cityMap != null && state.requests.nonEmpty) {
      while (state.requests.nonEmpty) {
        val request = state.requests.dequeue()
        handleRequestRoute(request._1, request._2)
      }
    }

  private def handleRequestRoute(identify: Identify, request: RequestRoute): Unit = {
    val routeId =
      UUID.nameUUIDFromBytes(s"route:${request.origin}-${request.destination}".getBytes).toString
    var data = ReceiveRoute()
    redisManager.load(routeId) match
      case Some(route) =>
        data = ReceiveRoute(routeId = routeId)
      case None =>
        val origin = cityMap.vertices.find(_.id == request.origin)
        val destination = cityMap.vertices.find(_.id == request.destination)
        (origin, destination) match {
          case (Some(originNode), Some(destinationNode)) =>
            cityMap.aStarEdgeTargets(originNode, destinationNode, heuristicFunc) match
              case Some(path) =>
//                val route = Route(cost = path._1, path = convertPath(path._2).toList)
//                redisManager.save(routeId, route.toByteArray)
                data = ReceiveRoute(routeId = routeId)
              case _ =>
                data = ReceiveRoute()
          case _ =>
            data = ReceiveRoute()
        }
    sendMessageTo(
      entityId = identify.id,
      shardId = identify.classType,
      actorType = CreationTypeEnum.valueOf(identify.actorType),
      data = data
    )
  }

  private def convertPath(
    path: List[(Edge[NodeGraph, Double, EdgeGraph], NodeGraph)]
  ): mutable.Queue[IdentifyPair] = {
    val convertedPath = mutable.Queue[IdentifyPair]()
    path.foreach {
      case (edge, node) =>
        val edgeId = Identify(
          id = edge.label.id,
          resourceId = edge.label.resourceId,
          classType = edge.label.classType,
          actorType = CreationTypeEnum.LoadBalancedDistributed.toString
        )
        val nodeId = Identify(
          id = node.id,
          resourceId = node.resourceId,
          classType = node.classType,
          actorType = CreationTypeEnum.LoadBalancedDistributed.toString
        )
        convertedPath.enqueue(IdentifyPair(link = Some(edgeId), node = Some(nodeId)))
    }
    convertedPath
  }
}
