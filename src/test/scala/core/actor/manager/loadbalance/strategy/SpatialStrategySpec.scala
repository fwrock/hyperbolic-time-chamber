package org.interscity.htc
package core.actor.manager.loadbalance.strategy

import core.entity.control.loadbalance.{ SpatialBounds, SpatialEntityData }
import core.enumeration.ShardTypeEnum
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SpatialStrategySpec extends AnyFlatSpec with Matchers {
  private val bounds = SpatialBounds(0, 0, 16, 16)
  private def entity(id: String, classType: String = "", x: Double = 1, y: Double = 1) =
    SpatialEntityData(id, x, y, entityClassType = classType)
  private def initialized(strategy: BalancingStrategy, capacity: Int = 1): BalancingStrategy = {
    strategy.initialize(bounds, StrategyConfig(maxEntitiesPerShard = capacity, minEntitiesPerShard = 0,
      enablePrediction = false))
    strategy
  }

  "spatial strategies" should "classify numeric infrastructure IDs using class metadata" in {
    for (make <- Seq(() => new HybridStrategy, () => new GeoAffinityStrategy);
      classType <- Seq("hybrid.actor.Node", "hybrid.actor.Link", "hybrid.actor.RailLink",
        "hybrid.actor.TrafficSignal", "hybrid.actor.rail_link", "TRAFFIC_SIGNAL")) {
      val strategy = initialized(make())
      val shard = strategy.assignShard(entity("101", classType))
      strategy.getShardType(shard) shouldBe ShardTypeEnum.Static
      strategy.isMigratable(shard) shouldBe false
    }
  }

  it should "prefer class metadata over legacy IDs and package substrings" in {
    for (make <- Seq(() => new HybridStrategy, () => new GeoAffinityStrategy);
      classType <- Seq("custom.linkpackage.Car", "hybrid.actor.LinkVehicle", "unknown")) {
      val strategy = initialized(make())
      val shard = strategy.assignShard(entity("htcaid:link;1", classType))
      strategy.getShardType(shard) shouldBe ShardTypeEnum.Dynamic
    }
  }

  it should "keep legacy infrastructure classification when metadata is absent" in {
    for (make <- Seq(() => new HybridStrategy, () => new GeoAffinityStrategy);
      kind <- Seq("node", "link", "rail_link", "traffic_signal", "signal");
      classType <- Seq("", "  ", null)) {
      val strategy = initialized(make())
      val shard = strategy.assignShard(entity(s"htcaid:$kind;1", classType))
      strategy.getShardType(shard) shouldBe ShardTypeEnum.Static
    }
  }

  it should "preserve published shard assignments across repeated evaluations" in {
    for (make <- Seq(() => new HybridStrategy, () => new GeoAffinityStrategy)) {
      val strategy = initialized(make())
      val entities = Seq(entity("a", "hybrid.actor.Car"), entity("b", "hybrid.actor.Car", 15, 15))
      val assignments = entities.map(e => e.spatialEntityId -> strategy.assignShard(e)).toMap
      for (_ <- 1 to 3) {
        strategy.evaluate() shouldBe empty
        strategy.getShardEntityCounts shouldBe assignments.values.groupMapReduce(identity)(_ => 1)(_ + _)
        entities.foreach(e => strategy.getShardForPosition(e.lon, e.lat) shouldBe assignments(e.spatialEntityId))
      }
    }
  }

  "GeoAffinity counts" should "count static and dynamic memberships separately and deduplicate registrations" in {
    val strategy = initialized(new GeoAffinityStrategy, capacity = 10)
    val staticShard = strategy.assignShard(entity("1", "hybrid.actor.Link"))
    val dynamicShard = strategy.assignShard(entity("2", "hybrid.actor.Car"))
    strategy.assignShard(entity("3", "hybrid.actor.Person")) shouldBe dynamicShard
    strategy.assignShard(entity("2", "hybrid.actor.Car")) shouldBe dynamicShard
    strategy.getShardEntityCounts shouldBe Map(staticShard -> 1, dynamicShard -> 2)
    strategy.getStats("entityCount") shouldBe 3
  }

  it should "reject a namespace change for an entity whose assignment has already been issued" in {
    val strategy = initialized(new GeoAffinityStrategy)
    val oldShard = strategy.assignShard(entity("1", "hybrid.actor.Link"))
    intercept[IllegalArgumentException](strategy.assignShard(entity("1", "hybrid.actor.Car")))
    strategy.getShardEntityCounts shouldBe Map(oldShard -> 1)
    strategy.getStats("entityCount") shouldBe 1
    strategy.getStats("staticShards") shouldBe 1
  }

  "Hybrid classification" should "preserve Mixed when infrastructure and vehicles share a shard" in {
    val strategy = initialized(new HybridStrategy, capacity = 10)
    val shard = strategy.assignShard(entity("1", "hybrid.actor.Link"))
    strategy.assignShard(entity("2", "hybrid.actor.Car")) shouldBe shard
    strategy.assignShard(entity("3", "hybrid.actor.Link")) shouldBe shard
    strategy.getShardType(shard) shouldBe ShardTypeEnum.Mixed
    strategy.isMigratable(shard) shouldBe false
  }

  "spatial strategy initialization" should "clear prior membership counts and classification" in {
    for (make <- Seq(() => new HybridStrategy, () => new GeoAffinityStrategy)) {
      val strategy = initialized(make())
      strategy.assignShard(entity("1", "hybrid.actor.Link"))
      initialized(strategy)
      strategy.getShardEntityCounts shouldBe empty
      val shard = strategy.assignShard(entity("2", "hybrid.actor.Car"))
      strategy.getShardType(shard) shouldBe ShardTypeEnum.Dynamic
      strategy.getStats("entityCount") shouldBe 1
    }
  }

  "spatial initial partitions" should "separate geographic cells and open stable capacity overflow" in {
    for (make <- Seq(() => new HybridStrategy, () => new GeoAffinityStrategy)) {
      val strategy = initialized(make())
      val first = strategy.assignShard(entity("1", "hybrid.actor.Car", 1, 1))
      val distant = strategy.assignShard(entity("2", "hybrid.actor.Car", 15, 15))
      val overflow = strategy.assignShard(entity("3", "hybrid.actor.Car", 1, 1))
      first should not be distant
      overflow should not be first
      strategy.assignShard(entity("1", "hybrid.actor.Car", 1, 1)) shouldBe first
      strategy.evaluate()
      strategy.getShardEntityCounts.values.sum shouldBe 3
    }
  }

  "type-aware assignment" should "deduplicate registrations pin infrastructure and clear bucket state on initialization" in {
    val strategy = initialized(new TypeAwareStrategy)
    val first = strategy.assignShard(entity("1", "hybrid.actor.Car"))
    strategy.assignShard(entity("1", "hybrid.actor.Car")) shouldBe first
    strategy.assignShard(entity("2", "hybrid.actor.Car")) should not be first
    strategy.getShardEntityCounts.values.sum shouldBe 2
    val node = strategy.assignShard(entity("3", "hybrid.actor.Node"))
    strategy.isMigratable(node) shouldBe false
    initialized(strategy)
    strategy.getShardEntityCounts shouldBe empty
    strategy.assignShard(entity("4", "hybrid.actor.Car")) shouldBe first
  }
}
