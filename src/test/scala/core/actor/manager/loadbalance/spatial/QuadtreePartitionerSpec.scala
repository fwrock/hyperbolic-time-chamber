package org.interscity.htc
package core.actor.manager.loadbalance.spatial

import core.entity.control.loadbalance.{ SpatialBounds, SpatialEntityData }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class QuadtreePartitionerSpec extends AnyFlatSpec with Matchers {
  private val bounds = SpatialBounds(0, 0, 16, 16)
  private def entity(id: String, x: Double, y: Double) = SpatialEntityData(id, x, y)

  private def assertMembership(tree: QuadtreePartitioner, positions: Map[String, (Double, Double)]): Unit = {
    val leaves = tree.getLeaves
    leaves.map(_.shardId).distinct.size shouldBe leaves.size
    leaves.flatMap(_.entities).sorted shouldBe positions.keys.toList.sorted
    tree.entityCount shouldBe positions.size
    tree.getShardEntityCounts.values.sum shouldBe positions.size
    tree.getShardEntityCounts.keySet.subsetOf(tree.getAllShardIds) shouldBe true
    leaves.foreach { leaf =>
      tree.getEntitiesInShard(leaf.shardId) shouldBe leaf.entities.toSet
      leaf.entities.foreach { id =>
        val (x, y) = positions(id)
        leaf.bounds.contains(x, y) shouldBe true
        tree.getShardId(x, y) shouldBe leaf.shardId
        tree.getEntityShard(id) shouldBe Some(leaf.shardId)
      }
    }
  }

  "quadtree registration" should "populate leaves and refine using actual coordinates" in {
    val tree = new QuadtreePartitioner(bounds, maxEntitiesPerLeaf = 1, minEntitiesPerLeaf = 0)
    val positions = Map("nw" -> (1.0, 15.0), "ne" -> (15.0, 15.0),
      "sw" -> (1.0, 1.0), "se" -> (15.0, 1.0), "center" -> (8.0, 8.0))
    positions.foreach { case (id, (x, y)) => tree.registerEntity(entity(id, x, y)) }
    tree.getLeaves.head.entities.size shouldBe positions.size
    tree.refine()
    tree.shardCount should be > 1
    assertMembership(tree, positions)
  }

  it should "replace duplicate registration and retain same-leaf position updates for later splits" in {
    val tree = new QuadtreePartitioner(bounds, maxEntitiesPerLeaf = 1, minEntitiesPerLeaf = 0)
    tree.registerEntity(entity("a", 1, 15))
    tree.registerEntity(entity("a", 15, 1))
    tree.updateEntityPosition("a", 15, 15) shouldBe None
    tree.registerEntity(entity("b", 1, 1))
    tree.refine()
    assertMembership(tree, Map("a" -> (15.0, 15.0), "b" -> (1.0, 1.0)))
  }

  it should "move and remove leaf membership without leaving obsolete counts" in {
    val tree = new QuadtreePartitioner(bounds, maxEntitiesPerLeaf = 1, minEntitiesPerLeaf = 0)
    tree.registerEntity(entity("a", 1, 15))
    tree.registerEntity(entity("b", 15, 1))
    tree.refine()
    tree.updateEntityPosition("a", 15, 1) shouldBe Some(tree.getShardId(15, 1))
    tree.updateEntityPosition("c", 1, 1) shouldBe Some(tree.getShardId(1, 1))
    tree.removeEntity("b")
    tree.removeEntity("missing")
    assertMembership(tree, Map("a" -> (15.0, 1.0), "c" -> (1.0, 1.0)))
    tree.getShardEntityCounts.values.forall(_ > 0) shouldBe true
  }

  "quadtree refinement" should "recurse to the depth limit without losing coincident entities" in {
    val tree = new QuadtreePartitioner(bounds, maxDepth = 4, maxEntitiesPerLeaf = 1, minEntitiesPerLeaf = 0)
    val positions = (1 to 4).map(i => i.toString -> (1.0, 15.0)).toMap
    positions.foreach { case (id, (x, y)) => tree.registerEntity(entity(id, x, y)) }
    tree.refine()
    tree.getLeaves.map(_.depth).max shouldBe 4
    assertMembership(tree, positions)
    val before = tree.getAllShardIds
    tree.refine()
    tree.getAllShardIds shouldBe before
  }

  it should "merge sparse siblings and rebuild all shard lookups" in {
    val tree = new QuadtreePartitioner(bounds, maxDepth = 3, maxEntitiesPerLeaf = 2, minEntitiesPerLeaf = 2)
    val positions = Map("a" -> (1.0, 15.0), "b" -> (15.0, 15.0), "c" -> (15.0, 1.0))
    positions.foreach { case (id, (x, y)) => tree.registerEntity(entity(id, x, y)) }
    tree.refine()
    tree.shardCount shouldBe 4
    tree.removeEntity("b")
    tree.removeEntity("c")
    tree.refine()
    tree.shardCount shouldBe 1
    assertMembership(tree, Map("a" -> (1.0, 15.0)))
  }

  it should "give different-depth leaves distinct IDs even when their Morton codes match" in {
    QuadtreeLeaf(0, 1, bounds).shardId should not be QuadtreeLeaf(0, 2, bounds).shardId
  }

  it should "preserve membership through repeated insertion movement removal and refinement" in {
    val tree = new QuadtreePartitioner(bounds, maxDepth = 4, maxEntitiesPerLeaf = 3, minEntitiesPerLeaf = 2)
    val random = new scala.util.Random(581L)
    var positions = Map.empty[String, (Double, Double)]
    for (step <- 0 until 120) {
      val id = random.nextInt(20).toString
      if (step % 3 == 0) {
        tree.removeEntity(id)
        positions -= id
      } else {
        val position = (random.nextDouble() * 16, random.nextDouble() * 16)
        tree.updateEntityPosition(id, position._1, position._2)
        positions += id -> position
      }
      tree.refine()
      tree.applyTwoToOneBalance()
      assertMembership(tree, positions)
    }
  }

  "2:1 balancing" should "reach a fixed point while preserving occupied coarse neighbors" in {
    val tree = new QuadtreePartitioner(bounds, maxDepth = 5, maxEntitiesPerLeaf = 1, minEntitiesPerLeaf = 0)
    val positions = Map("a" -> (7.9, 8.1), "b" -> (7.9, 8.1), "c" -> (12.0, 12.0), "d" -> (12.0, 4.0))
    positions.foreach { case (id, (x, y)) => tree.registerEntity(entity(id, x, y)) }
    tree.refine()
    tree.getLeaves.map(_.depth).max shouldBe 5
    tree.applyTwoToOneBalance()
    assertMembership(tree, positions)
    for (a <- tree.getLeaves; b <- tree.getLeaves if a.bounds.intersects(b.bounds)) {
      math.abs(a.depth - b.depth) should be <= 1
    }
    val before = tree.getAllShardIds
    tree.applyTwoToOneBalance()
    tree.getAllShardIds shouldBe before
  }

  "quadtree coordinates" should "handle outer edges and quadrant boundaries deterministically" in {
    val tree = new QuadtreePartitioner(bounds, maxDepth = 2, maxEntitiesPerLeaf = 1, minEntitiesPerLeaf = 0)
    val positions = Map("a" -> (0.0, 0.0), "b" -> (16.0, 16.0), "c" -> (8.0, 8.0), "d" -> (0.0, 16.0))
    positions.foreach { case (id, (x, y)) => tree.registerEntity(entity(id, x, y)) }
    tree.refine()
    assertMembership(tree, positions)
  }

  it should "reject invalid positions without partially changing an existing registration" in {
    val tree = new QuadtreePartitioner(bounds)
    tree.registerEntity(entity("a", 1, 1))
    for ((x, y) <- Seq((-1.0, 1.0), (17.0, 1.0), (Double.NaN, 1.0), (1.0, Double.PositiveInfinity))) {
      intercept[IllegalArgumentException](tree.registerEntity(entity("a", x, y)))
      intercept[IllegalArgumentException](tree.updateEntityPosition("a", x, y))
      intercept[IllegalArgumentException](tree.getShardId(x, y))
      assertMembership(tree, Map("a" -> (1.0, 1.0)))
    }
  }

  "quadtree configuration" should "reject invalid bounds thresholds and overflowing Morton depths" in {
    for (depth <- Seq(-1, 32)) intercept[IllegalArgumentException](new QuadtreePartitioner(bounds, maxDepth = depth))
    intercept[IllegalArgumentException](new QuadtreePartitioner(bounds, maxEntitiesPerLeaf = 0))
    intercept[IllegalArgumentException](new QuadtreePartitioner(bounds, minEntitiesPerLeaf = -1))
    intercept[IllegalArgumentException](new QuadtreePartitioner(bounds, maxEntitiesPerLeaf = 1, minEntitiesPerLeaf = 2))
    for (invalid <- Seq(SpatialBounds(0, 0, 0, 1), SpatialBounds(1, 0, 0, 1),
      SpatialBounds(0, 0, 1, Double.PositiveInfinity), SpatialBounds(Double.NaN, 0, 1, 1),
      SpatialBounds(-Double.MaxValue, 0, Double.MaxValue, 1))) {
      intercept[IllegalArgumentException](new QuadtreePartitioner(invalid))
    }
    val tree = new QuadtreePartitioner(bounds, maxDepth = 0, maxEntitiesPerLeaf = 1, minEntitiesPerLeaf = 0)
    tree.registerEntity(entity("a", 1, 1))
    tree.registerEntity(entity("b", 2, 2))
    tree.refine()
    tree.shardCount shouldBe 1
    assertMembership(tree, Map("a" -> (1.0, 1.0), "b" -> (2.0, 2.0)))
  }
}
