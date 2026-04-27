package org.interscity.htc.model.hybrid.util

import org.interscity.htc.model.hybrid.entity.state.model.{ EdgeGraph, NodeGraph }

import scala.collection.mutable

/** GPS Utility for route calculation with dynamic weight support.
  *
  * Calculates optimal routes using A* algorithm with real-time traffic data. Weights are
  * dynamically retrieved from cache, reflecting current congestion, incidents, and other traffic
  * conditions.
  */
object GPSUtil {

  /** Calcula a rota entre dois nós usando A* com pesos dinâmicos. Retorna o custo e uma fila de
    * pares (ID da aresta (EdgeGraph), ID do nó de destino).
    *
    * @param originId
    *   ID do nó de origem.
    * @param destinationId
    *   ID do nó de destino.
    * @param useDynamicWeights
    *   Se true, usa pesos dinâmicos do cache; se false, usa pesos estáticos (padrão: true).
    * @return
    *   Option contendo (custo, fila de rota) ou None se a rota não for encontrada.
    */
  def calcRoute(
    originId: String,
    destinationId: String,
    useDynamicWeights: Boolean = true
  ): Option[(Double, mutable.Queue[(String, String)])] = {

    if (originId == destinationId) {
      return Some((0.0, mutable.Queue.empty))
    }

    val originNodeOpt = CityMapUtil.nodesById.get(originId)
    val destinationNodeOpt = CityMapUtil.nodesById.get(destinationId)

    (originNodeOpt, destinationNodeOpt) match {
      case (Some(originNode), Some(destinationNode)) =>
        val weightFunc: (NodeGraph, NodeGraph) => Option[Double] = if (useDynamicWeights) {
          (source, target) =>
            CityMapUtil.cityMap.label(source, target).map {
              edgeLabel =>
                val staticWeight = CityMapUtil.cityMap.weight(source, target).getOrElse(0.0)
                DynamicWeightCache.getWeight(edgeLabel.id, staticWeight)
            }
        } else {
          (source, target) => CityMapUtil.cityMap.weight(source, target)
        }

        // TODO: Implement A* with custom weight function
        val pathResult =
          CityMapUtil.cityMap.dijkstraEdgeTargetsOptimized(originNode, destinationNode)

        pathResult match {
          case Some((hopCount, path)) =>
            val dynamicCost = if (useDynamicWeights) {
              path.foldLeft(0.0) {
                case (acc, (edgeObject, targetNode)) =>
                  val staticWeight = edgeObject.weight
                  val dynamicWeight =
                    DynamicWeightCache.getWeight(edgeObject.label.id, staticWeight)
                  acc + dynamicWeight
              }
            } else {
              hopCount.toDouble
            }

            val routeQueue = mutable.Queue[(String, String)]()
            path.foreach {
              case (edgeObject, targetNodeOfEdgeInPath) =>
                routeQueue.enqueue((edgeObject.label.id, targetNodeOfEdgeInPath.id))
            }
            Some((dynamicCost, routeQueue))
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

  /** Calcula a rota usando Contraction Hierarchies com A* (CH+A*).
    *
    * Combina a poda hierárquica do CH com a heurística do A* (distância euclidiana), resultando na
    * busca mais rápida entre os algoritmos disponíveis para grafos estáticos.
    *
    * Mesmas restrições do [[calcRouteCH]]: usa pesos estáticos do grafo no momento do
    * pré-processamento. Para tráfego em tempo real use [[calcRoute]].
    *
    * @param originId
    *   ID do nó de origem.
    * @param destinationId
    *   ID do nó de destino.
    * @return
    *   Option contendo (custo estático, fila de rota) ou None se a rota não for encontrada.
    */
  def calcRouteCHAStar(
    originId: String,
    destinationId: String
  ): Option[(Double, mutable.Queue[(String, String)])] = {

    if (originId == destinationId) return Some((0.0, mutable.Queue.empty))

    val originNodeOpt = CityMapUtil.nodesById.get(originId)
    val destinationNodeOpt = CityMapUtil.nodesById.get(destinationId)

    (originNodeOpt, destinationNodeOpt) match {
      case (Some(originNode), Some(destinationNode)) =>
        CityMapUtil.chIndex.queryAStar(originNode, destinationNode, heuristicFunc) match {
          case Some((cost, path)) =>
            val routeQueue = mutable.Queue[(String, String)]()
            path.foreach {
              case (edgeLabel, targetNode) =>
                routeQueue.enqueue((edgeLabel.id, targetNode.id))
            }
            Some((cost, routeQueue))
          case None =>
            System.err.println(
              s"GPSUtil (CH+A*): Nenhuma rota encontrada de $originId para $destinationId."
            )
            None
        }
      case (None, _) =>
        System.err.println(s"GPSUtil (CH+A*): Nó de origem $originId não encontrado no mapa.")
        None
      case (_, None) =>
        System.err.println(s"GPSUtil (CH+A*): Nó de destino $destinationId não encontrado no mapa.")
        None
    }
  }

  private val heuristicFunc: (NodeGraph, NodeGraph) => Double = (a, b) => a.euclideanDistance(b)

  /** Calcula a rota usando CH+A* com índice reconstruído de forma adaptativa.
    *
    * O índice CH é reconstruído automaticamente quando qualquer uma das condições da
    * [[CHRebuildPolicy]] for satisfeita:
    *   - `currentTick` está na lista de ticks agendados (início, pico manhã, meio-dia, pico tarde)
    *   - Algum link tem peso dinâmico > `pesoEstático × blockThresholdFactor` (via bloqueado)
    *
    * Fora dessas condições, reutiliza o índice em cache — queries são tão rápidas quanto
    * [[calcRouteCHAStar]]. Pesos dinâmicos do Kafka só influenciam ''quando'' reconstruir, não os
    * pesos internos do CH após a reconstrução.
    *
    * Para tráfego contínuo em tempo real use [[calcRoute]] com `useDynamicWeights = true`.
    *
    * @param originId
    *   ID do nó de origem.
    * @param destinationId
    *   ID do nó de destino.
    * @param currentTick
    *   Tick global atual da simulação.
    * @param policy
    *   Política de reconstrução — padrão [[CHRebuildPolicy.fromConfig]].
    * @return
    *   Option contendo (custo, fila de rota) ou None se a rota não for encontrada.
    */
  def calcRouteCHAStarAdaptive(
    originId: String,
    destinationId: String,
    currentTick: Int,
    policy: CHRebuildPolicy = CHRebuildPolicy.fromConfig
  ): Option[(Double, mutable.Queue[(String, String)])] = {

    if (originId == destinationId) return Some((0.0, mutable.Queue.empty))

    val originNodeOpt = CityMapUtil.nodesById.get(originId)
    val destinationNodeOpt = CityMapUtil.nodesById.get(destinationId)

    (originNodeOpt, destinationNodeOpt) match {
      case (Some(originNode), Some(destinationNode)) =>
        CityMapUtil
          .getOrRebuildCHIndex(currentTick, policy)
          .queryAStar(originNode, destinationNode, heuristicFunc) match {
          case Some((cost, path)) =>
            val routeQueue = mutable.Queue[(String, String)]()
            path.foreach {
              case (edgeLabel, targetNode) =>
                routeQueue.enqueue((edgeLabel.id, targetNode.id))
            }
            Some((cost, routeQueue))
          case None =>
            System.err.println(
              s"GPSUtil (CH+A* adaptive): Nenhuma rota encontrada de $originId para $destinationId."
            )
            None
        }
      case (None, _) =>
        System.err.println(
          s"GPSUtil (CH+A* adaptive): Nó de origem $originId não encontrado no mapa."
        )
        None
      case (_, None) =>
        System.err.println(
          s"GPSUtil (CH+A* adaptive): Nó de destino $destinationId não encontrado no mapa."
        )
        None
    }
  }
}
