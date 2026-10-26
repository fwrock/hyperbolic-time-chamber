package org.interscity.htc
package core.actor.manager.loadbalance.spatial

import scala.collection.mutable

/** Capacity overflow opens a new bucket without changing an already published entity assignment. */
class SpatialShardBuckets(capacity: Int) {
  require(capacity > 0, "spatial shard capacity must be positive")
  private val entities = mutable.Map.empty[String, (String, String)]
  private val current = mutable.Map.empty[String, Int]
  private val counts = mutable.Map.empty[String, Int]
  private def id(base: String, bucket: Int): String = if (bucket == 0) base else s"$base-bucket-$bucket"
  def candidate(base: String): String = id(base, current.getOrElse(base, 0))
  def assign(entityId: String, base: String): String = entities.get(entityId) match {
    case Some((existingBase, shard)) =>
      require(existingBase == base, "published entity spatial cell or namespace changed")
      shard
    case None =>
      val bucket = current.getOrElse(base, 0)
      val next = if (counts.getOrElse(id(base, bucket), 0) >= capacity) bucket + 1 else bucket
      val shard = id(base, next)
      current(base) = next
      counts(shard) = counts.getOrElse(shard, 0) + 1
      entities(entityId) = base -> shard
      shard
  }
  def shardIds: Set[String] = counts.keySet.toSet
  def entityCounts: Map[String, Int] = counts.toMap
}
