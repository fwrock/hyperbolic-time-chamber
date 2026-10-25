package org.interscity.htc
package core.actor.manager.loadbalance.migration

import core.entity.control.loadbalance.{ MigrationPlan, MigrationReason }
import org.apache.pekko.actor.Address
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ShardMigrationCoordinatorSpec extends AnyFlatSpec with Matchers {
  private def plan(id: String): MigrationPlan = MigrationPlan(
    id,
    Address("pekko", "migration", "source", 2551),
    Address("pekko", "migration", "target", 2552),
    MigrationReason.Rebalance
  )

  "migration admission" should "reject non-positive concurrency limits" in {
    intercept[IllegalArgumentException](new ShardMigrationCoordinator(0))
    intercept[IllegalArgumentException](new ShardMigrationCoordinator(-1))
  }

  it should "deduplicate both active and waiting shards without replacing their plans" in {
    val coordinator = new ShardMigrationCoordinator(1)
    val first = plan("first")
    val second = plan("second")
    coordinator.requestMigration(first) shouldBe MigrationRequestResult.Started
    coordinator.requestMigration(second) shouldBe MigrationRequestResult.Queued(1)
    coordinator.requestMigration(first.copy(priority = 99)) shouldBe MigrationRequestResult.AlreadyMigrating
    coordinator.requestMigration(second.copy(priority = 99)) shouldBe MigrationRequestResult.AlreadyMigrating
    coordinator.pendingMigrationCount shouldBe 1
    coordinator.completeMigration(first.shardId, success = true) shouldBe Some(second)
    coordinator.getMigrationState(second.shardId).map(_.plan) shouldBe Some(second)
  }

  it should "reserve each released slot for the next waiting plan in FIFO order" in {
    val coordinator = new ShardMigrationCoordinator(2)
    val plans = (1 to 5).map(i => plan(s"shard-$i"))
    plans.take(2).foreach(p => coordinator.requestMigration(p) shouldBe MigrationRequestResult.Started)
    plans.drop(2).zipWithIndex.foreach { case (p, i) =>
      coordinator.requestMigration(p) shouldBe MigrationRequestResult.Queued(i + 1)
    }
    coordinator.completeMigration(plans(0).shardId, success = true) shouldBe Some(plans(2))
    coordinator.completeMigration(plans(1).shardId, success = false) shouldBe Some(plans(3))
    coordinator.activeMigrationCount shouldBe 2
    coordinator.completeMigration(plans(2).shardId, success = true) shouldBe Some(plans(4))
    coordinator.completeMigration(plans(3).shardId, success = true) shouldBe None
    coordinator.completeMigration(plans(4).shardId, success = true) shouldBe None
    coordinator.activeMigrationCount shouldBe 0
    coordinator.pendingMigrationCount shouldBe 0
    coordinator.getStats.completedMigrations shouldBe 4L
    coordinator.getStats.failedMigrations shouldBe 1L
  }

  it should "ignore unknown and repeated completions without changing admission or statistics" in {
    val coordinator = new ShardMigrationCoordinator(1)
    val second = plan("second")
    coordinator.requestMigration(plan("first"))
    coordinator.requestMigration(second)
    coordinator.completeMigration("unknown", success = true) shouldBe None
    coordinator.pendingMigrationCount shouldBe 1
    coordinator.completeMigration("first", success = true) shouldBe Some(second)
    val before = coordinator.getStats
    coordinator.completeMigration("first", success = false) shouldBe None
    coordinator.getStats shouldBe before
    coordinator.isMigrating("second") shouldBe true
  }

  it should "release all admission identities when aborted" in {
    val coordinator = new ShardMigrationCoordinator(1)
    val first = plan("first")
    val second = plan("second")
    coordinator.requestMigration(first)
    coordinator.requestMigration(second)
    coordinator.abortAll()
    coordinator.activeMigrationCount shouldBe 0
    coordinator.pendingMigrationCount shouldBe 0
    coordinator.completeMigration(first.shardId, success = true) shouldBe None
    coordinator.requestMigration(second) shouldBe MigrationRequestResult.Started
    coordinator.requestMigration(first) shouldBe MigrationRequestResult.Queued(1)
    coordinator.completeMigration(second.shardId, success = true) shouldBe Some(first)
  }
}
