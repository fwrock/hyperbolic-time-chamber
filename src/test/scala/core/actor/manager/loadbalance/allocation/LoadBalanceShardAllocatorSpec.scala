package org.interscity.htc
package core.actor.manager.loadbalance.allocation

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ ActorRef, ActorSystem, Address }
import org.apache.pekko.testkit.TestProbe
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LoadBalanceShardAllocatorSpec extends AnyFlatSpec with Matchers with ScalaFutures with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("LoadBalanceShardAllocatorSpec",
    ConfigFactory.parseString("pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off")
      .withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }
  private val sourceAddress = Address("pekko", "cluster", "source", 2551)
  private val targetAddress = Address("pekko", "cluster", "target", 2552)

  "explicit shard placement" should "choose the planned region even when the fallback would prefer the requester" in {
    val source = TestProbe().ref; val target = TestProbe().ref
    val allocator = LoadBalanceShardAllocator()
    allocator.requestRebalance("move", target)
    allocator.allocateShard(source, "move", Map(source -> Vector("move"), target -> Vector("a", "b", "c")))
      .futureValue shouldBe target
    allocator.allocateShard(source, "fresh", Map(source -> Vector("move"), target -> Vector("a", "b", "c")))
      .futureValue shouldBe source
  }

  it should "fail instead of falling back when an explicit region disappears" in {
    val source = TestProbe().ref; val target = TestProbe().ref
    val allocator = LoadBalanceShardAllocator()
    allocator.requestRebalance("move", target)
    allocator.allocateShard(source, "move", Map(source -> Vector("move"))).failed.futureValue shouldBe a[IllegalStateException]
    allocator.getDesiredAllocations("move") shouldBe target
    allocator.clearDesiredAllocation("move")
    allocator.getPendingRebalances shouldBe empty
    allocator.allocateShard(source, "move", Map(source -> Vector("move"))).futureValue shouldBe source
  }

  it should "resolve an idle target by full node address and validate the source owner" in {
    val source = TestProbe().ref; val target = TestProbe().ref
    val allocator = LoadBalanceShardAllocator()
    allocator.registerLocalRegion(source, sourceAddress)
    allocator.registerLocalRegion(target, targetAddress)
    allocator.rebalance(Map(source -> Vector("move"), target -> Vector.empty), Set.empty).futureValue shouldBe empty
    allocator.resolveDestination("move", sourceAddress, targetAddress, targetAddress) shouldBe Right(target)
    allocator.resolveDestination("move", targetAddress, sourceAddress, sourceAddress).isLeft shouldBe true
    allocator.resolveDestination("move", sourceAddress, sourceAddress, sourceAddress).isLeft shouldBe true
    allocator.resolveDestination("unknown", sourceAddress, targetAddress, sourceAddress).isLeft shouldBe true
  }

  it should "drop regions absent from the latest authoritative callback snapshot" in {
    val source = TestProbe().ref; val target = TestProbe().ref
    val allocator = LoadBalanceShardAllocator()
    allocator.registerLocalRegion(source, sourceAddress)
    allocator.registerLocalRegion(target, targetAddress)
    allocator.rebalance(Map(source -> Vector("move"), target -> Vector.empty), Set.empty).futureValue
    allocator.rebalance(Map(source -> Vector("move")), Set.empty).futureValue
    allocator.getRegionIndex.keySet shouldBe Set(source)
    allocator.resolveDestination("move", sourceAddress, targetAddress, sourceAddress).isLeft shouldBe true
  }

  it should "isolate equal shard IDs in different physical actor types" in {
    val source = TestProbe().ref; val target = TestProbe().ref; val other = TestProbe().ref
    val root = LoadBalanceShardAllocator()
    val cars = root.forType("Car"); val buses = root.forType("Bus")
    (root.forType("Car") eq cars) shouldBe true
    cars.requestRebalance("same-id", target)
    buses.rebalance(Map(other -> Vector("same-id")), Set.empty).futureValue shouldBe empty
    root.getPendingRebalances shouldBe Set("same-id")
    cars.rebalance(Map(source -> Vector("same-id"), target -> Vector.empty), Set.empty).futureValue shouldBe Set("same-id")
    buses.allocateShard(other, "same-id", Map(other -> Vector.empty)).futureValue shouldBe other
    cars.allocateShard(source, "same-id", Map(source -> Vector.empty, target -> Vector.empty)).futureValue shouldBe target
    root.clearAll()
    (root.forType("Car") eq cars) shouldBe true
    cars.getDesiredAllocations shouldBe empty
    cars.getRegionIndex shouldBe empty
  }

  it should "retain a requested shard while Pekko already has it in progress" in {
    val source = TestProbe().ref; val target = TestProbe().ref
    val allocator = LoadBalanceShardAllocator()
    val allocations = Map(source -> Vector("move"), target -> Vector.empty[String])
    allocator.requestRebalance("move", target)
    allocator.rebalance(allocations, Set("move")).futureValue shouldBe empty
    allocator.getPendingRebalances shouldBe Set("move")
    allocator.rebalance(allocations, Set.empty).futureValue shouldBe Set("move")
    allocator.rebalance(allocations, Set.empty).futureValue shouldBe empty
  }

  it should "reject multiple target regions at the same address instead of selecting one arbitrarily" in {
    val source = TestProbe().ref; val first = TestProbe().ref; val second = TestProbe().ref
    val allocator = LoadBalanceShardAllocator()
    allocator.registerLocalRegion(source, sourceAddress)
    allocator.registerLocalRegion(first, targetAddress)
    allocator.registerLocalRegion(second, targetAddress)
    allocator.rebalance(Map(source -> Vector("move"), first -> Vector.empty, second -> Vector.empty), Set.empty).futureValue
    allocator.resolveDestination("move", sourceAddress, targetAddress, sourceAddress).isLeft shouldBe true
  }
}
