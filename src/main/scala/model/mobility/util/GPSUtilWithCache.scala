package org.interscity.htc
package model.mobility.util

import org.interscity.htc.model.mobility.entity.state.model.NodeGraph

import scala.collection.mutable
import org.interscity.htc.system.database.redis.{ RedisClient, RedisClientManager } // Use o seu RedisClientManager

object GPSUtilWithCache {

  private val redisClientManager = RedisClient.instance

  private lazy val cache: mutable.Map[String, Option[(Double, mutable.Queue[(String, String)])]] =
    mutable.Map[String, Option[(Double, mutable.Queue[(String, String)])]]()

  // Função heurística (exemplo, substitua pela sua)
  private val heuristicFunc: (NodeGraph, NodeGraph) => Double = (current, goal) =>
    current.euclideanDistance(goal)

  /** Calcula a rota entre dois nós usando A*, com cache. Retorna o custo e uma fila de pares (ID da
    * aresta (EdgeGraph), ID do nó de destino).
    *
    * @param originId
    *   ID do nó de origem.
    * @param destinationId
    *   ID do nó de destino.
    * @return
    *   Option contendo (custo, fila de rota) ou None se a rota não for encontrada.
    */
  def calcRoute(
    originId: String,
    destinationId: String
  ): Option[(Double, mutable.Queue[(String, String)])] = {
    val cacheKey = s"route:$originId:$destinationId"

    cache.get(cacheKey) match {
      case Some(cachedBytes) =>
        return cachedBytes.map {
          case (cost, queue) => (cost, queue.clone())
        }
      case None =>
    }

    val originNodeOpt = CityMapUtil.nodesById.get(originId)
    val destinationNodeOpt = CityMapUtil.nodesById.get(destinationId)

    val result: Option[(Double, mutable.Queue[(String, String)])] =
      (originNodeOpt, destinationNodeOpt) match {
        case (Some(originNode), Some(destinationNode)) =>
          CityMapUtil.cityMap.aStarEdgeTargetsOptimized(
            originNode,
            destinationNode,
            heuristicFunc
          ) match {
            case Some((cost, path)) =>
              val routeQueue = mutable.Queue[(String, String)]()
              path.foreach {
                case (edgeObject, targetNodeOfEdgeInPath) =>
                  routeQueue.enqueue((edgeObject.label.id, targetNodeOfEdgeInPath.id))
              }
              val dataToCache = Some((cost, routeQueue.clone()))
              cache.put(cacheKey, dataToCache)
              Some((cost, routeQueue.clone()))
            case None =>
              println(
                s"GPSUtilWithCache: Nenhuma rota encontrada de $originId para $destinationId pelo A*."
              )
              cache.put(cacheKey, None)
              None
          }
        case (None, _) =>
          println(s"GPSUtilWithCache: Nó de origem $originId não encontrado no mapa.")
          None
        case (_, None) =>
          println(s"GPSUtilWithCache: Nó de destino $destinationId não encontrado no mapa.")
          None
      }
    result
  }

  // Método para fechar o pool do Redis quando a aplicação terminar.
  // Chame isso no ponto apropriado do ciclo de vida da sua aplicação.
  def shutdown(): Unit =
    redisClientManager.closePool()
}
