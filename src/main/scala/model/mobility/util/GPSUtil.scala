package org.interscity.htc.model.mobility.util

import org.interscity.htc.model.mobility.entity.state.model.{ EdgeGraph, NodeGraph }

import scala.collection.mutable

object GPSUtil {

  /** Calcula a rota entre dois nós usando A*. Retorna o custo e uma fila de pares (ID da aresta
    * (EdgeGraph), ID do nó de destino).
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
    val originNodeOpt = CityMapUtil.nodesById.get(originId)
    val destinationNodeOpt = CityMapUtil.nodesById.get(destinationId)

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
            Some((cost, routeQueue))
          case None =>
            System.err.println(
              s"GPSUtil: Nenhuma rota encontrada de $originId para $destinationId."
            )
            None
        }
      case (None, _) =>
        System.err.println(s"GPSUtil: Nó de origem $originId não encontrado no mapa.")
        None
      case (_, None) =>
        System.err.println(s"GPSUtil: Nó de destino $destinationId não encontrado no mapa.")
        None
    }
  }

  /** Função heurística para o A*. Usa a distância Euclidiana.
    * @param current
    *   Nó atual.
    * @param goal
    *   Nó objetivo.
    * @return
    *   Distância heurística.
    */
  private val heuristicFunc: (NodeGraph, NodeGraph) => Double = (current, goal) =>
    current.euclideanDistance(goal)
}
