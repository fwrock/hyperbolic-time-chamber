package org.interscity.htc
package core.actor.manager.loadbalance.spatial

import org.apache.pekko.actor.Address
import org.apache.pekko.cluster.sharding.ShardRegion
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ShardLoadObservationSpec extends AnyFlatSpec with Matchers {
  private val left = Address("pekko", "test", "127.0.0.1", 1001)
  private val right = Address("pekko", "test", "127.0.0.1", 1002)
  private val nodes = Set(left, right)
  private def row(kind: String, node: Address, entries: (String, Int)*) =
    RegionLoadObservation(kind, node, ShardRegion.ShardRegionStats(entries.toMap))

  "Load observation" should "combine colocated physical types and retain idle nodes in a complete cohort" in {
    val rows = Vector(row("Car", left, "cell" -> 1), row("Car", right),
      row("Person", left, "cell" -> 1), row("Person", right))
    val observed = ShardLoadObservation.collect(rows, nodes, 17L)
    observed.locations shouldBe Map("cell" -> left)
    observed.metrics.head.entityCount shouldBe 2
    observed.metrics.head.totalWeight shouldBe 2.0
    observed.metrics.head.timestamp shouldBe 17L
    val planner = new KdTreeLoadBalancer
    planner.replaceSnapshot(observed.metrics, observed.locations, nodes)
    planner.getNodeLoads(right) shouldBe 0.0
  }

  it should "reject partial failed duplicate and contradictory physical observations" in {
    val valid = Vector(row("Car", left, "cell" -> 1), row("Car", right))
    val failed = valid.updated(1, valid(1).copy(state = ShardRegion.ShardRegionStats(Map.empty, Set("missing"))))
    val divergent = valid ++ Vector(row("Person", left), row("Person", right, "cell" -> 1))
    val negative = Vector(row("Link", left, "cell" -> -1), row("Link", right))
    for (rows <- Seq(Vector.empty, valid.take(1), valid :+ valid.head, failed, divergent, negative))
      intercept[IllegalArgumentException](ShardLoadObservation.collect(rows, nodes, 17L))
  }

  it should "aggregate large populations and retain empty shard placement without entity inventories" in {
    val rows = Vector(row("Link", left, "static" -> 500000, "empty" -> 0), row("Link", right),
      row("Node", left, "static" -> 100000), row("Node", right))
    val observed = ShardLoadObservation.collect(rows, nodes, 17L)
    observed.locations shouldBe Map("static" -> left, "empty" -> left)
    observed.metrics.map(m => m.shardId -> m.entityCount).toMap shouldBe Map("static" -> 600000, "empty" -> 0)
    observed.metrics.map(m => m.shardId -> m.totalWeight).toMap shouldBe Map("static" -> 600000.0, "empty" -> 0.0)
  }

  it should "reject count overflow rather than publish a negative load" in {
    val rows = Vector(row("Car", left, "cell" -> Int.MaxValue), row("Car", right),
      row("Person", left, "cell" -> 1), row("Person", right))
    intercept[IllegalArgumentException](ShardLoadObservation.collect(rows, nodes, 17L))
  }
}
