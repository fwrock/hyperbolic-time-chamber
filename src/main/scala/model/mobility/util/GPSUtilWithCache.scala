package org.interscity.htc
package model.mobility.util

import org.interscity.htc.model.mobility.entity.state.model.NodeGraph
import org.interscity.htc.core.metrics.model.hybrid.GPSMetrics

import scala.collection.mutable
import org.interscity.htc.system.database.redis.RedisClient

object GPSUtilWithCache {

  private val redisClientManager = RedisClient.instance

  private lazy val cache: mutable.Map[String, Option[(Double, List[(String, String)])]] =
    mutable.Map[String, Option[(Double, List[(String, String)])]]()

  @volatile private var cacheHits: Long = 0
  @volatile private var cacheMisses: Long = 0
  @volatile private var cacheStores: Long = 0

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
      case Some(cachedData) =>
        cacheHits += 1
        GPSMetrics.routeSource.labels("preloaded").inc()
        return cachedData.map {
          case (cost, list) => (cost, mutable.Queue(list: _*))
        }
      case None =>
        cacheMisses += 1
    }

    val originNodeOpt = CityMapUtil.nodesById.get(originId)
    val destinationNodeOpt = CityMapUtil.nodesById.get(destinationId)

    val result: Option[(Double, mutable.Queue[(String, String)])] =
      (originNodeOpt, destinationNodeOpt) match {
        case (Some(originNode), Some(destinationNode)) =>
          val startNanos = System.nanoTime()
          CityMapUtil.cityMap.aStarEdgeTargetsOptimized(
            originNode,
            destinationNode,
            heuristicFunc
          ) match {
            case Some((cost, path)) =>
              val elapsed = (System.nanoTime() - startNanos) / 1e9
              val routeQueue = mutable.Queue[(String, String)]()
              path.foreach {
                case (edgeObject, targetNodeOfEdgeInPath) =>
                  routeQueue.enqueue((edgeObject.label.id, targetNodeOfEdgeInPath.id))
              }
              val dataToCache = Some((cost, routeQueue.toList))
              cache.put(cacheKey, dataToCache)
              cacheStores += 1
              GPSMetrics.routeCalcDuration.labels("astar_pure").observe(elapsed)
              GPSMetrics.routeHops.labels("astar_pure").observe(routeQueue.size.toDouble)
              GPSMetrics.routeSource.labels("gps_calculated").inc()
              Some((cost, routeQueue))
            case None =>
              println(
                s"GPSUtilWithCache: Nenhuma rota encontrada de $originId para $destinationId pelo A*."
              )
              cache.put(cacheKey, None)
              cacheStores += 1
              None
          }
        case (None, _) =>
          println(s"GPSUtilWithCache: Nó de origem $originId não encontrado no mapa.")
          GPSMetrics.gpsNodeNotFound.labels("origin").inc()
          None
        case (_, None) =>
          println(s"GPSUtilWithCache: Nó de destino $destinationId não encontrado no mapa.")
          GPSMetrics.gpsNodeNotFound.labels("destination").inc()
          None
      }
    result
  }

  /** Get cache statistics
    * @return
    *   (hits, misses, stores, hitRate)
    */
  def getCacheStats: (Long, Long, Long, Double) = {
    val hits = cacheHits
    val misses = cacheMisses
    val stores = cacheStores
    val total = hits + misses
    val hitRate = if (total > 0) (hits.toDouble / total.toDouble) * 100.0 else 0.0
    (hits, misses, stores, hitRate)
  }

  /** Print cache statistics with timing analysis */
  def printCacheStats(): Unit = {
    val (hits, misses, stores, hitRate) = getCacheStats
    val total = hits + misses
    println(s"=== GPSUtilWithCache Statistics ===")
    println(s"Cache Hits:    $hits")
    println(s"Cache Misses:  $misses")
    println(s"Cache Stores:  $stores")
    println(s"Total Requests: $total")
    println(f"Hit Rate:      $hitRate%.2f%%")
    println(s"Cache Size:    ${cache.size} entries")
    println()
    println("NOTE: Route calculation is typically NOT the bottleneck.")
    println("Main simulation time is spent on:")
    println("  1. Actor message passing (car ↔ link ↔ node)")
    println("  2. Link density/speed calculations")
    println("  3. TimeManager synchronization barriers")
    println("  4. Event scheduling and processing")
    println(s"With only $misses unique routes, caching saves minimal time.")
    println(s"Consider profiling actor interactions and link calculations.")
    println(s"===================================")
  }

  /** Reset cache statistics */
  def resetCacheStats(): Unit = {
    cacheHits = 0
    cacheMisses = 0
    cacheStores = 0
  }

  def shutdown(): Unit =
    redisClientManager.closePool()
}
