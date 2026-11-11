package org.interscity.htc
package core.actor.manager.loadbalance.spatial

import core.entity.control.loadbalance.ShardMetrics
import org.apache.pekko.actor.Address
import org.apache.pekko.cluster.sharding.ShardRegion

case class RegionLoadObservation(typeName: String, address: Address, state: ShardRegion.ShardRegionStats)
case class ShardLoadObservation(metrics: Vector[ShardMetrics], locations: Map[String, Address])

object ShardLoadObservation {
  def collect(rows: Vector[RegionLoadObservation], nodes: Set[Address], timestamp: Long): ShardLoadObservation = {
    require(nodes.nonEmpty && rows.nonEmpty, "empty region observation")
    require(rows.map(row => row.typeName -> row.address).distinct.size == rows.size, "duplicate region observation")
    require(rows.groupBy(_.typeName).values.forall(_.map(_.address).toSet == nodes), "incomplete region cohort")
    require(rows.forall(_.state.failed.isEmpty), "incomplete shard observation")
    require(rows.forall(_.state.stats.values.forall(_ >= 0)), "negative entity count")
    val observed = rows.flatMap { row =>
      row.state.stats.toVector.map { case (shardId, count) => (shardId, row.address, count) }
    }.groupBy(_._1)
    require(observed.values.forall(_.map(_._2).distinct.size == 1), "logical shard occupies different nodes")
    val locations = observed.map { case (shard, entries) => shard -> entries.head._2 }
    val metrics = observed.toVector.sortBy(_._1).map { case (shard, entries) =>
      // The spatial directory assigns each entity ID to exactly one physical type.
      val count = entries.iterator.map(_._3.toLong).sum
      require(count <= Int.MaxValue, "entity count exceeds supported range")
      ShardMetrics(shard, entityCount = count.toInt, totalWeight = count.toDouble, timestamp = timestamp)
    }
    ShardLoadObservation(metrics, locations)
  }
}
