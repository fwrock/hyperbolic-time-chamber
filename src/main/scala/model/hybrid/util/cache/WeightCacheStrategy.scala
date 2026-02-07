package org.interscity.htc.model.hybrid.util.cache

import org.interscity.htc.model.hybrid.entity.state.model.DynamicLinkCost

import scala.util.Try

/** Strategy interface for dynamic weight caching.
  * 
  * Supports multiple implementations:
  * - Redis: Centralized, cluster-wide, external
  * - InMemory: Fast local cache with Pekko Distributed Data sync
  * - Hybrid: In-memory with Redis fallback
  */
trait WeightCacheStrategy {
  
  /** Publish dynamic cost.
    */
  def publishCost(cost: DynamicLinkCost, ttlSeconds: Int): Try[Unit]
  
  /** Get dynamic cost.
    */
  def getCost(linkId: String): Option[DynamicLinkCost]
  
  /** Get weight with fallback to static.
    */
  def getWeight(linkId: String, staticWeight: Double): Double
  
  /** Get batch weights.
    */
  def getBatchWeights(linkWeights: Map[String, Double]): Map[String, Double]
  
  /** Clear specific cost.
    */
  def clearCost(linkId: String): Try[Unit]
  
  /** Clear all costs.
    */
  def clearAllCosts(): Try[Unit]
  
  /** Get statistics.
    */
  def getStatistics(): (Int, Double, Int)
}
