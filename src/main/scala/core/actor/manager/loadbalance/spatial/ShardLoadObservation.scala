package org.interscity.htc
package core.actor.manager.loadbalance.spatial

import core.entity.control.loadbalance.ShardMetrics
import org.apache.pekko.actor.Address
import org.apache.pekko.cluster.sharding.ShardRegion

case class RegionLoadObservation(typeName: String, address: Address, state: ShardRegion.CurrentShardRegionState)
case class ShardLoadObservation(metrics: Vector[ShardMetrics], locations: Map[String, Address])

object ShardLoadObservation {
  def collect(rows: Vector[RegionLoadObservation], nodes: Set[Address], timestamp: Long): ShardLoadObservation = {
    require(nodes.nonEmpty && rows.nonEmpty, "empty region observation")
    require(rows.map(row => row.typeName -> row.address).distinct.size == rows.size, "duplicate region observation")
    require(rows.groupBy(_.typeName).values.forall(_.map(_.address).toSet == nodes), "incomplete region cohort")
    require(rows.forall(_.state.failed.isEmpty), "incomplete shard observation")
    val observed = rows.flatMap(row => row.state.shards.map(shard => (shard.shardId, row.address, shard.entityIds)))
      .groupBy(_._1)
    require(observed.values.forall(_.map(_._2).distinct.size == 1), "logical shard occupies different nodes")
    val locations = observed.map { case (shard, entries) => shard -> entries.head._2 }
    val metrics = observed.toVector.sortBy(_._1).map { case (shard, entries) =>
      val count = entries.iterator.flatMap(_._3).toSet.size
      ShardMetrics(shard, entityCount = count, totalWeight = count.toDouble, timestamp = timestamp)
    }
    ShardLoadObservation(metrics, locations)
  }
}
