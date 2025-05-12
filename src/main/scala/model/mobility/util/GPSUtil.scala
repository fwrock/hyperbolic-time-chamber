package org.interscity.htc
package model.mobility.util

import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.model.mobility.entity.model.model.{IdentifyPair, Route}
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.model.mobility.collections.graph.Edge
import org.interscity.htc.model.mobility.entity.state.model.{EdgeGraph, NodeGraph}
import org.interscity.htc.model.mobility.util.CityMapUtil.cityMap
import org.interscity.htc.system.database.redis.RedisClientManager

import java.util.UUID
import scala.collection.mutable

object GPSUtil {

  def calcRoute(redisManager: RedisClientManager, originId: String, destinationId: String): Route = {
    val routeId = UUID.nameUUIDFromBytes(s"route:${originId}-${destinationId}".getBytes).toString
    redisManager.load(routeId) match
      case Some(route) =>
        Route.parseFrom(route)
      case None =>
        val origin = cityMap.vertices.find(_.id == originId)
        val destination = cityMap.vertices.find(_.id == destinationId)
        (origin, destination) match {
          case (Some(originNode), Some(destinationNode)) =>
            cityMap.aStarEdgeTargets(originNode, destinationNode, heuristicFunc) match
              case Some(path) =>
                val route = Route(cost = path._1, path = convertPath(path._2).toList)
                redisManager.save(routeId, route.toByteArray)
                route
              case _ =>
                throw Exception()
          case _ =>
            throw Exception()
        }
  }

  def calcRoute(originId: String, destinationId: String): Route = {
    val origin = cityMap.vertices.find(_.id == originId)
    val destination = cityMap.vertices.find(_.id == destinationId)
    (origin, destination) match {
      case (Some(originNode), Some(destinationNode)) =>
        cityMap.aStarEdgeTargets(originNode, destinationNode, heuristicFunc) match
          case Some(path) =>
            val route = Route(cost = path._1, path = convertPath(path._2).toList)
            route
          case _ =>
            throw Exception()
      case _ =>
        throw Exception()
    }
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

  private val heuristicFunc: (NodeGraph, NodeGraph) => Double = (current, goal) =>
    current.euclideanDistance(goal)
}
