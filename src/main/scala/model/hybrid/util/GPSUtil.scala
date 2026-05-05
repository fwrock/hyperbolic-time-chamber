package org.interscity.htc.model.hybrid.util

import org.interscity.htc.core.metrics.model.hybrid.GPSMetrics
import org.interscity.htc.model.hybrid.entity.state.model.{EdgeGraph, NodeGraph}

import scala.collection.mutable

/** GPS Utility for route calculation with dynamic weight support.
  *
  * Calculates optimal routes using A* algorithm with real-time traffic data. Weights are
  * dynamically retrieved from cache, reflecting current congestion, incidents, and other traffic
  * conditions.
  */
object GPSUtil {

  /** Route cache: static topology doesn't change between ticks.
    * Stores List (not Queue) so each caller gets a fresh Queue copy.
    * Thread-safe ConcurrentHashMap; None value means "no route exists".
    */
  private val routeCache: java.util.concurrent.ConcurrentHashMap[String, Option[(Double, List[(String, String)])]] =
    new java.util.concurrent.ConcurrentHashMap()

  /** Negative cache: pairs (origin, dest) for which no route was ever found.
    * Prevents repeated expensive searches on disconnected node pairs.
    * Thread-safe; bounded to 100K entries to avoid unbounded memory growth.
    */
  private val noRouteCache: java.util.concurrent.ConcurrentHashMap.KeySetView[String, java.lang.Boolean] =
    java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
  private val NO_ROUTE_CACHE_MAX = 100_000

  private def noRouteCacheKey(originId: String, destinationId: String): String =
    s"$originId|$destinationId"

  private def isNoRouteKnown(originId: String, destinationId: String): Boolean =
    noRouteCache.contains(noRouteCacheKey(originId, destinationId))

  private def markNoRoute(originId: String, destinationId: String): Unit =
    if (noRouteCache.size() < NO_ROUTE_CACHE_MAX)
      noRouteCache.add(noRouteCacheKey(originId, destinationId))

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

    // Check route cache first (static topology: route doesn't change between ticks)
    // CACHE DISABLED
    // val cacheKey = s"$originId|$destinationId"
    // val cached = routeCache.get(cacheKey)
    // if (cached != null) {
    //   GPSMetrics.routeSource.labels("cached").inc()
    //   return cached.map { case (cost, list) => (cost, mutable.Queue(list: _*)) }
    // }

    val originNodeOpt      = CityMapUtil.nodesById.get(originId)
    val destinationNodeOpt = CityMapUtil.nodesById.get(destinationId)

    val t0 = System.nanoTime()
    (originNodeOpt, destinationNodeOpt) match {
      case (Some(originNode), Some(destinationNode)) =>
        val pathResult =
          CityMapUtil.cityMap.dijkstraEdgeTargetsOptimized(originNode, destinationNode)

        pathResult match {
          case Some((hopCount, path)) =>
            val dynamicCost = if (useDynamicWeights) {
              path.foldLeft(0.0) {
                case (acc, (edgeObject, _)) =>
                  acc + DynamicWeightCache.getWeight(edgeObject.label.id, edgeObject.weight)
              }
            } else {
              hopCount.toDouble
            }

            val routeList = path.map { case (edgeObject, targetNode) => (edgeObject.label.id, targetNode.id) }
            // routeCache.put(cacheKey, Some((dynamicCost, routeList))) // CACHE DISABLED

            val elapsed = (System.nanoTime() - t0) / 1e9
            if (elapsed > 1.0)
              System.err.println(
                s"[GPSUtil][SLOW] dijkstra de $originId para $destinationId levou ${elapsed}s (${routeList.size} hops)"
              )
            GPSMetrics.routeCalcDuration.labels("dijkstra").observe(elapsed)
            GPSMetrics.routeHops.labels("dijkstra").observe(routeList.size.toDouble)
            GPSMetrics.routeSource.labels("gps_calculated").inc()
            Some((dynamicCost, mutable.Queue(routeList: _*)))
          case None =>
            val elapsed = (System.nanoTime() - t0) / 1e9
            System.err.println(
              s"[GPSUtil] Nenhuma rota (dijkstra) de $originId para $destinationId (${elapsed}s)."
            )
            // routeCache.put(cacheKey, None) // CACHE DISABLED
            None
        }
      case (None, _) =>
        System.err.println(s"[GPSUtil] Nó de origem $originId não encontrado no mapa.")
        GPSMetrics.gpsNodeNotFound.labels("origin").inc()
        None
      case (_, None) =>
        GPSMetrics.gpsNodeNotFound.labels("destination").inc()
        System.err.println(s"[GPSUtil] Nó de destino $destinationId não encontrado no mapa.")
        None
    }
  }

  /** Calcula a rota entre dois nós usando ALT (A* + Landmarks + Triangle inequality).
    *
    * Usa [[CityMapUtil.altIndex]] para guiar o A* com uma heurística baseada em distâncias
    * pré-computadas a/de k landmarks. Em grafos de tráfego reais, ALT tipicamente expande
    * significativamente menos nós que A* euclidiano puro, com qualidade de rota idêntica (ótima).
    *
    * O índice ALT é construído uma única vez no primeiro acesso (lazy). O número de landmarks
    * é configurável via `htc.mobility.landmark-count` (padrão: 16).
    *
    * @param originId
    *   ID do nó de origem.
    * @param destinationId
    *   ID do nó de destino.
    * @param useDynamicWeights
    *   Se true, recalcula o custo com pesos dinâmicos do cache (padrão: true).
    * @return
    *   Option contendo (custo, fila de rota) ou None se a rota não for encontrada.
    */
  /** Maximum A* node expansions per route search.
    * Keeps disconnected-node failures bounded (~100ms instead of 48s on a 488K-node graph).
    * Any route requiring more expansions is treated as unreachable.
    */
  private val MAX_ROUTE_EXPANSIONS = 150_000

  def calcRouteALT(
    originId: String,
    destinationId: String,
    useDynamicWeights: Boolean = true
  ): Option[(Double, mutable.Queue[(String, String)])] = {

    if (originId == destinationId) {
      return Some((0.0, mutable.Queue.empty))
    }

    // NEGATIVE CACHE DISABLED
    // if (isNoRouteKnown(originId, destinationId)) {
    //   return None
    // }

    val originNodeOpt      = CityMapUtil.nodesById.get(originId)
    val destinationNodeOpt = CityMapUtil.nodesById.get(destinationId)

    val t0 = System.nanoTime()
    (originNodeOpt, destinationNodeOpt) match {
      case (Some(originNode), Some(destinationNode)) =>
        val altHeuristic: (NodeGraph, NodeGraph) => Double =
          (v, target) => CityMapUtil.altIndex.heuristic(v, target)

        CityMapUtil.cityMap.aStarEdgeTargetsOptimized(
          originNode,
          destinationNode,
          altHeuristic,
          maxExpansions = MAX_ROUTE_EXPANSIONS
        ) match {
          case Some((staticCost, path)) =>
            val routeQueue = mutable.Queue[(String, String)]()
            path.foreach {
              case (edgeObject, targetNodeOfEdgeInPath) =>
                routeQueue.enqueue((edgeObject.label.id, targetNodeOfEdgeInPath.id))
            }
            val finalCost = if (useDynamicWeights) {
              path.foldLeft(0.0) {
                case (acc, (edgeObject, _)) =>
                  acc + DynamicWeightCache.getWeight(edgeObject.label.id, edgeObject.weight)
              }
            } else {
              staticCost
            }
            val elapsed = (System.nanoTime() - t0) / 1e9
            GPSMetrics.routeCalcDuration.labels("alt").observe(elapsed)
            GPSMetrics.routeHops.labels("alt").observe(routeQueue.size.toDouble)
            Some((finalCost, routeQueue))
          case None =>
            System.err.println(
              s"GPSUtil (ALT): Nenhuma rota encontrada de $originId para $destinationId."
            )
            // markNoRoute(originId, destinationId) // CACHE DISABLED
            None
        }
      case (None, _) =>
        System.err.println(s"GPSUtil (ALT): Nó de origem $originId não encontrado no mapa.")
        GPSMetrics.gpsNodeNotFound.labels("origin").inc()
        None
      case (_, None) =>
        System.err.println(s"GPSUtil (ALT): Nó de destino $destinationId não encontrado no mapa.")
        GPSMetrics.gpsNodeNotFound.labels("destination").inc()
        None
    }
  }

  /** Calcula a rota entre dois nós usando A* puro com heurística euclidiana.
    *
    * Usa [[Graph.aStarEdgeTargetsOptimized]] diretamente no grafo da cidade, sem pré-processamento
    * CH. Mais lento que [[calcRouteCHAStar]] em grafos grandes, mas respeita pesos dinâmicos do
    * cache ao calcular o custo real da rota retornada.
    *
    * @param originId
    *   ID do nó de origem.
    * @param destinationId
    *   ID do nó de destino.
    * @param useDynamicWeights
    *   Se true, recalcula o custo com pesos dinâmicos do cache (padrão: true).
    * @return
    *   Option contendo (custo, fila de rota) ou None se a rota não for encontrada.
    */
  def calcRouteAStar(
    originId: String,
    destinationId: String,
    useDynamicWeights: Boolean = true
  ): Option[(Double, mutable.Queue[(String, String)])] = {

    if (originId == destinationId) {
      return Some((0.0, mutable.Queue.empty))
    }

    val originNodeOpt      = CityMapUtil.nodesById.get(originId)
    val destinationNodeOpt = CityMapUtil.nodesById.get(destinationId)

    val t0 = System.nanoTime()
    (originNodeOpt, destinationNodeOpt) match {
      case (Some(originNode), Some(destinationNode)) =>
        CityMapUtil.cityMap.aStarEdgeTargetsOptimized(originNode, destinationNode, heuristicFunc) match {
          case Some((staticCost, path)) =>
            val routeQueue = mutable.Queue[(String, String)]()
            path.foreach {
              case (edgeObject, targetNodeOfEdgeInPath) =>
                routeQueue.enqueue((edgeObject.label.id, targetNodeOfEdgeInPath.id))
            }
            val finalCost = if (useDynamicWeights) {
              path.foldLeft(0.0) {
                case (acc, (edgeObject, _)) =>
                  acc + DynamicWeightCache.getWeight(edgeObject.label.id, edgeObject.weight)
              }
            } else {
              staticCost
            }
            val elapsed = (System.nanoTime() - t0) / 1e9
            GPSMetrics.routeCalcDuration.labels("astar_pure").observe(elapsed)
            GPSMetrics.routeHops.labels("astar_pure").observe(routeQueue.size.toDouble)
            Some((finalCost, routeQueue))
          case None =>
            System.err.println(
              s"GPSUtil (A*): Nenhuma rota encontrada de $originId para $destinationId."
            )
            None
        }
      case (None, _) =>
        System.err.println(s"GPSUtil (A*): Nó de origem $originId não encontrado no mapa.")
        GPSMetrics.gpsNodeNotFound.labels("origin").inc()
        None
      case (_, None) =>
        System.err.println(s"GPSUtil (A*): Nó de destino $destinationId não encontrado no mapa.")
        GPSMetrics.gpsNodeNotFound.labels("destination").inc()
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

    val t0 = System.nanoTime()
    (originNodeOpt, destinationNodeOpt) match {
      case (Some(originNode), Some(destinationNode)) =>
        CityMapUtil.chIndex.queryAStar(originNode, destinationNode, heuristicFunc) match {
          case Some((cost, path)) =>
            val routeQueue = mutable.Queue[(String, String)]()
            path.foreach {
              case (edgeLabel, targetNode) =>
                routeQueue.enqueue((edgeLabel.id, targetNode.id))
            }
            val elapsed = (System.nanoTime() - t0) / 1e9
            GPSMetrics.routeCalcDuration.labels("ch_astar_static").observe(elapsed)
            GPSMetrics.routeHops.labels("ch_astar_static").observe(routeQueue.size.toDouble)
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

    val t0 = System.nanoTime()
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
            val elapsed = (System.nanoTime() - t0) / 1e9
            GPSMetrics.routeCalcDuration.labels("ch_astar_adaptive").observe(elapsed)
            GPSMetrics.routeHops.labels("ch_astar_adaptive").observe(routeQueue.size.toDouble)
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
