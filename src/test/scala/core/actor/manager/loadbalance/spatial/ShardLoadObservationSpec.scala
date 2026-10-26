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
  private def row(kind: String, node: Address, entries: (String, Set[String])*) =
    RegionLoadObservation(kind, node, ShardRegion.CurrentShardRegionState(entries.map {
      case (shard, ids) => ShardRegion.ShardState(shard, ids)
    }.toSet))

  "Load observation" should "combine colocated physical types and retain idle nodes in a complete cohort" in {
    val rows = Vector(row("Car", left, "cell" -> Set("1")), row("Car", right),
      row("Person", left, "cell" -> Set("2")), row("Person", right))
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
    val valid = Vector(row("Car", left, "cell" -> Set("1")), row("Car", right))
    val failed = valid.updated(1, valid(1).copy(state = ShardRegion.CurrentShardRegionState(Set.empty, Set("missing"))))
    val divergent = valid ++ Vector(row("Person", left), row("Person", right, "cell" -> Set("2")))
    for (rows <- Seq(Vector.empty, valid.take(1), valid :+ valid.head, failed, divergent))
      intercept[IllegalArgumentException](ShardLoadObservation.collect(rows, nodes, 17L))
  }
}
