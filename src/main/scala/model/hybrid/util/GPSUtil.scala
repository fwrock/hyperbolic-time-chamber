package org.interscity.htc.model.hybrid.util

import org.interscity.htc.core.util.JsonUtil
import org.interscity.htc.core.util.JsonUtil.writeJsonBytes
import org.interscity.htc.model.hybrid.entity.state.model.{EdgeGraph, NodeGraph}
import org.interscity.htc.system.database.redis.RedisClientManager

import java.util.UUID
import scala.collection.mutable

/** GPS Utility for route calculation with dynamic weight support.
  * 
  * Calculates optimal routes using A* algorithm with real-time traffic data.
  * Weights are dynamically retrieved from cache, reflecting current congestion,
  * incidents, and other traffic conditions.
  */
object GPSUtil {

  /**
   * Calcula a rota entre dois nós usando A* com pesos dinâmicos.
   * Retorna o custo e uma fila de pares (ID da aresta (EdgeGraph), ID do nó de destino).
   *
   * @param originId ID do nó de origem.
   * @param destinationId ID do nó de destino.
   * @param useDynamicWeights Se true, usa pesos dinâmicos do cache; se false, usa pesos estáticos (padrão: true).
   * @return Option contendo (custo, fila de rota) ou None se a rota não for encontrada.
   */
  def calcRoute(
    originId: String,
    destinationId: String,
    useDynamicWeights: Boolean = true
  ): Option[(Double, mutable.Queue[(String, String)])] = {
    
    val originNodeOpt = CityMapUtil.nodesById.get(originId)
    val destinationNodeOpt = CityMapUtil.nodesById.get(destinationId)


    (originNodeOpt, destinationNodeOpt) match {
      case (Some(originNode), Some(destinationNode)) =>
        System.err.println(s"[GPSUtil] Calling shortestPathByHops from ${originNode.id} to ${destinationNode.id}")
        // If using dynamic weights, create a weight function that queries the cache
        val weightFunc: (NodeGraph, NodeGraph) => Option[Double] = if (useDynamicWeights) {
          (source, target) =>
            // Get the edge label to find the link ID
            CityMapUtil.cityMap.label(source, target).map { edgeLabel =>
              val staticWeight = CityMapUtil.cityMap.weight(source, target).getOrElse(0.0)
              // Query dynamic weight cache, fallback to static if not found
              DynamicWeightCache.getWeight(edgeLabel.id, staticWeight)  
            }
        } else {
          // Use static weights from graph
          (source, target) => CityMapUtil.cityMap.weight(source, target)
        }
        
        // Use shortest path by hops for now, but with dynamic weight consideration
        // TODO: Implement A* with custom weight function
        val pathResult = CityMapUtil.cityMap.dijkstraEdgeTargetsOptimized(originNode, destinationNode)
        
        pathResult match {
          case Some((hopCount, path)) =>
            // Recalculate cost using dynamic weights
            val dynamicCost = if (useDynamicWeights) {
              path.foldLeft(0.0) { case (acc, (edgeObject, targetNode)) =>
                val staticWeight = edgeObject.weight
                val dynamicWeight = DynamicWeightCache.getWeight(edgeObject.label.id, staticWeight)
                acc + dynamicWeight
              }
            } else {
              hopCount.toDouble
            }
            
            val routeQueue = mutable.Queue[(String, String)]()
            path.foreach { case (edgeObject, targetNodeOfEdgeInPath) =>
              // Keep original IDs (with : and ;) for CityMapUtil lookups
              routeQueue.enqueue((edgeObject.label.id, targetNodeOfEdgeInPath.id))
            }
            Some((dynamicCost, routeQueue))
          case None =>
            System.err.println(s"GPSUtil: Nenhuma rota encontrada de $originId para $destinationId.")
            None // Nenhuma rota encontrada
        }
      case (None, _) =>
        System.err.println(s"GPSUtil: Nó de origem $originId não encontrado no mapa.")
        None // Nó de origem não encontrado
      case (_, None) =>
        System.err.println(s"GPSUtil: Nó de destino $destinationId não encontrado no mapa.")
        None // Nó de destino não encontrado
    }
  }
}
