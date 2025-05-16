package org.interscity.htc.model.mobility.util

// Importa NodeGraph e EdgeGraph do novo pacote em Model.scala
import org.interscity.htc.model.mobility.entity.state.model.{EdgeGraph, NodeGraph}
import scala.collection.mutable

object GPSUtil {

  /**
   * Calcula a rota entre dois nós usando A*.
   * Retorna o custo e uma fila de pares (ID da aresta (EdgeGraph), ID do nó de destino).
   *
   * @param originId ID do nó de origem.
   * @param destinationId ID do nó de destino.
   * @return Option contendo (custo, fila de rota) ou None se a rota não for encontrada.
   */
  def calcRoute(originId: String, destinationId: String): Option[(Double, mutable.Queue[(String, String)])] = {
    // Busca nós de origem e destino usando o mapa de consulta rápida O(1)
    val originNodeOpt = CityMapUtil.nodesById.get(originId)
    val destinationNodeOpt = CityMapUtil.nodesById.get(destinationId)

    (originNodeOpt, destinationNodeOpt) match {
      case (Some(originNode), Some(destinationNode)) =>
        // Usa o A* da classe Graph, que retorna (Custo, Lista de (Aresta, NóDestino))
        CityMapUtil.cityMap.aStarEdgeTargets(originNode, destinationNode, heuristicFunc) match {
          case Some((cost, path)) => // path é List[(Edge[NodeGraph, Double, EdgeGraph], NodeGraph)]
            val routeQueue = mutable.Queue[(String, String)]()
            path.foreach { case (edgeObject, targetNodeOfEdgeInPath) =>
              // Armazena o ID do EdgeGraph (label da aresta) e o ID do nó de destino dessa aresta no caminho
              routeQueue.enqueue((edgeObject.label.id, targetNodeOfEdgeInPath.id))
            }
            Some((cost, routeQueue))
          case None =>
            System.err.println(s"GPSUtil: Nenhuma rota encontrada de $originId para $destinationId.")
            None // Nenhuma rota encontrada pelo A*
        }
      case (None, _) =>
        System.err.println(s"GPSUtil: Nó de origem $originId não encontrado no mapa.")
        None // Nó de origem não encontrado
      case (_, None) =>
        System.err.println(s"GPSUtil: Nó de destino $destinationId não encontrado no mapa.")
        None // Nó de destino não encontrado
    }
  }

  /**
   * Função heurística para o A*. Usa a distância Euclidiana.
   * @param current Nó atual.
   * @param goal Nó objetivo.
   * @return Distância heurística.
   */
  private val heuristicFunc: (NodeGraph, NodeGraph) => Double = (current, goal) =>
    current.euclideanDistance(goal)
}
