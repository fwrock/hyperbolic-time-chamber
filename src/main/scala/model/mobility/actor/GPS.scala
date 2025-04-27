package org.interscity.htc
package model.mobility.actor

import core.actor.BaseActor
import model.mobility.entity.state.GPSState

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.ActorInteractionEvent
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.model.mobility.collections.Graph
import org.interscity.htc.model.mobility.collections.graph.Edge
import org.interscity.htc.model.mobility.entity.event.data.{ ReceiveRoute, RequestRoute }
import org.interscity.htc.model.mobility.entity.state.model.{ EdgeGraph, NodeGraph }

import scala.collection.mutable
import scala.util.{ Failure, Success }
import scala.compiletime.uninitialized

class GPS(
  private val properties: Properties
) extends BaseActor[GPSState](
      properties = properties
    ) {

  private var cityMap: Graph[NodeGraph, Double, EdgeGraph] = uninitialized

  override def onStart(): Unit =
    if (state != null) {
      logInfo(s"Starting actor $entityId: ${properties.data}")
      val nodeGraphIdExtractor: NodeGraph => String = (node: NodeGraph) => node.id
      logInfo(s"Loading city map from json file: ${state.cityMapPath}")
      Graph.loadFromJsonFile[NodeGraph, String, Double, EdgeGraph](
        state.cityMapPath,
        nodeGraphIdExtractor,
        0.0
      ) match {
        case Success(graph) =>
          cityMap = graph
          logInfo("City map loaded successfully")
          logInfo(s"Nodes amount: ${cityMap.vertices.size}")
        case Failure(e) =>
          logError(s"Error on load and process city map from json file: ${e.getMessage}")
          e.printStackTrace()
      }
    } else {
      logError(s"State is null, GPS cannot be initialized")
    }

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
                shardId = event.shardRefId,
                classType = event.actorClassType,
                typeActor = event.actorType
              ),
              data
            )
          )
        } else if (cityMap != null && state.requests.nonEmpty) {
          processQueuedRequests()
          handleRequestRoute(
            Identify(
              id = event.actorRefId,
              shardId = event.shardRefId,
              classType = event.actorClassType,
              typeActor = event.actorType
            ),
            data
          )
        } else {
          handleRequestRoute(
            Identify(
              id = event.actorRefId,
              shardId = event.shardRefId,
              classType = event.actorClassType,
              typeActor = event.actorType
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
    val origin = cityMap.vertices.find(_.id == request.origin)
    val destination = cityMap.vertices.find(_.id == request.destination)
    var data = ReceiveRoute()
    (origin, destination) match {
      case (Some(originNode), Some(destinationNode)) =>
        cityMap.aStarEdgeTargets(originNode, destinationNode, heuristicFunc) match
          case Some(path) =>
            if (path._2.isEmpty) {
              logInfo(s"Path not found from ${request.origin} to ${request.destination}")
            } else {
              logInfo(s"Path found from ${request.origin} to ${request.destination}. Path: ${path._2}")
            }
            data = ReceiveRoute(cost = path._1, path = Some(convertPath(path._2)))
          case _ =>
            data = ReceiveRoute()
      case _ =>
        data = ReceiveRoute()
    }
    logInfo(s"Identify: $identify")
    sendMessageTo(
      entityId = identify.id,
      shardId = identify.shardId,
      actorType = CreationTypeEnum.valueOf(identify.typeActor),
      data = data
    )
  }

  private def convertPath(
    path: List[(Edge[NodeGraph, Double, EdgeGraph], NodeGraph)]
  ): mutable.Queue[(Identify, Identify)] = {
    val convertedPath = mutable.Queue[(Identify, Identify)]()
    path.foreach {
      case (edge, node) =>
        val edgeId = Identify(
          id = edge.label.id,
          shardId = edge.label.shardId,
          classType = edge.label.classType,
          typeActor = CreationTypeEnum.LoadBalancedDistributed.toString
        )
        val nodeId = Identify(
          id = node.id,
          shardId = node.shardId,
          classType = node.classType,
          typeActor = CreationTypeEnum.LoadBalancedDistributed.toString
        )
        convertedPath.enqueue((edgeId, nodeId))
    }
    convertedPath
  }
}
