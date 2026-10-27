package org.interscity.htc
package core.actor.manager.loadbalance.spatial

import core.entity.control.loadbalance.ShardMetrics
import org.apache.pekko.actor.Address
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ShardLoadPlanningSpec extends AnyFlatSpec with Matchers {
  private val nodeA = Address("pekko", "planning", "127.0.0.1", 2551)
  private val b = Address("pekko", "planning", "127.0.0.1", 2552)
  private val c = Address("pekko", "planning", "127.0.0.1", 2553)
  private def metric(id: String, weight: Double) = ShardMetrics(id, entityCount = weight.toInt, totalWeight = weight)

  "shard planning" should "include registered idle nodes without moving a single indivisible load back and forth" in {
    val planner = new KdTreeLoadBalancer
    planner.replaceSnapshot(Vector(metric("large", 10)), Map("large" -> nodeA), Set(nodeA, b, c))
    planner.getNodeLoads shouldBe Map(nodeA -> 10.0, b -> 0.0, c -> 0.0)
    planner.getImbalanceRatio shouldBe Double.MaxValue
    planner.rebalance() shouldBe empty
  }

  it should "produce deterministic beneficial plans while leaving observed ownership unchanged" in {
    val metrics = Vector(metric("pinned", 10), metric("nine", 9), metric("seven", 7), metric("four", 4), metric("two", 2))
    val actual = Map("pinned" -> nodeA, "nine" -> nodeA, "seven" -> nodeA, "four" -> nodeA, "two" -> b)
    def plan(input: Vector[ShardMetrics]): List[(String, Option[Address], Address)] = {
      val planner = new KdTreeLoadBalancer
      planner.replaceSnapshot(input, actual, Set(nodeA, b, c))
      val moves = planner.rebalance(_ != "pinned")
      planner.getAllAssignments shouldBe actual
      planner.rebalance(_ != "pinned") shouldBe moves
      val loads = scala.collection.mutable.Map.from(planner.getNodeLoads)
      moves.foreach { case (shard, source, target) =>
        val before = loads.values.map(x => x * x).sum
        val weight = metrics.find(_.shardId == shard).get.totalWeight
        loads(source.get) -= weight
        loads(target) += weight
        loads.values.map(x => x * x).sum should be < before
      }
      moves.map(_._1) should not contain "pinned"
      moves should not be empty
      moves
    }
    plan(metrics) shouldBe plan(metrics.reverse)
  }

  it should "replace retired metrics atomically and remove departed nodes from planning" in {
    val planner = new KdTreeLoadBalancer
    planner.replaceSnapshot(Vector(metric("old", 5)), Map("old" -> nodeA), Set(nodeA, b))
    intercept[IllegalArgumentException](planner.replaceSnapshot(Vector(metric("bad", Double.NaN)), Map("bad" -> b), Set(nodeA, b)))
    planner.getAllAssignments shouldBe Map("old" -> nodeA)
    planner.replaceSnapshot(Vector(metric("new", 3)), Map("new" -> b), Set(nodeA, b, c))
    planner.getAssignment("old") shouldBe None
    planner.removeNode(b)
    planner.getNodeLoads shouldBe Map(nodeA -> 0.0, c -> 0.0)
    planner.getAllAssignments shouldBe empty
  }

  "spatial capacity buckets" should "open overflow shards without reassigning an earlier entity" in {
    val buckets = new SpatialShardBuckets(2)
    buckets.assign("a", "cell") shouldBe "cell"
    buckets.assign("b", "cell") shouldBe "cell"
    buckets.assign("c", "cell") shouldBe "cell-bucket-1"
    buckets.assign("a", "cell") shouldBe "cell"
    buckets.entityCounts shouldBe Map("cell" -> 2, "cell-bucket-1" -> 1)
    intercept[IllegalArgumentException](buckets.assign("a", "different-cell"))
    buckets.entityCounts.values.sum shouldBe 3
  }
}
