package org.interscity.htc
package model.hybrid.actor

import core.actor.manager.loadbalance.migration.MigrationSnapshot
import core.entity.actor.properties.Properties
import core.types.Tick
import model.hybrid.entity.state.LinkState

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestActorRef
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.compiletime.uninitialized

/** Regression coverage for `docs/TIME_WARP_DESIGN.md`'s "Checkpoint-completeness gap" finding
  * (2026-08-07), the last of the five actor types found: `Link` had **no**
  * `buildMigrationSnapshot`/`applyMigrationSnapshot` override at all, so `vehicleEntryTick`/
  * `vehicleWaitingSeconds` -- which feed travel-time math sent to a departing vehicle on leave --
  * were silently lost on any restore. Same harness as `BusMigrationSnapshotSpec`.
  */
class LinkMigrationSnapshotSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var _system: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit =
    _system = ActorSystem(
      "LinkMigrationSnapshotSpec",
      ConfigFactory
        .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
        .withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit = {
    _system.terminate()
    ()
  }

  private class TestLink(properties: Properties) extends Link(properties) {
    def testSetState(s: LinkState): Unit = state = s
    def testBuildMigrationSnapshot(): MigrationSnapshot = buildMigrationSnapshot()
    def testApplyMigrationSnapshot(snapshot: MigrationSnapshot): Unit = applyMigrationSnapshot(snapshot)

    def testPutVehicleEntryTick(vehicleId: String, tick: Tick): Unit = vehicleEntryTick.put(vehicleId, tick)
    def testPutVehicleWaitingSeconds(vehicleId: String, seconds: Double): Unit = vehicleWaitingSeconds.put(vehicleId, seconds)

    def testVehicleEntryTick: Map[String, Tick] = vehicleEntryTick.toMap
    def testVehicleWaitingSeconds: Map[String, Double] = vehicleWaitingSeconds.toMap
  }

  private def freshState(): LinkState =
    LinkState(
      startTick = 0L,
      from = "nodeA",
      to = "nodeB",
      length = 250.0,
      lanes = 2,
      speedLimit = 13.9,
      capacity = 1800.0,
      freeSpeed = 13.9
    )

  private var nextEntitySuffix = 0

  private def newTestLink(entityId: String): TestLink = {
    nextEntitySuffix += 1
    TestActorRef(new TestLink(Properties(entityId = entityId)), s"$entityId-$nextEntitySuffix").underlyingActor
  }

  "Link.buildMigrationSnapshot" should "capture entry ticks and waiting seconds for every registered vehicle" in {
    val link = newTestLink("link-1")
    link.testSetState(freshState())
    link.testPutVehicleEntryTick("car-1", 10L)
    link.testPutVehicleEntryTick("car-2", 15L)
    link.testPutVehicleWaitingSeconds("car-1", 3.5)

    val snapshot = link.testBuildMigrationSnapshot()

    snapshot.linkVehicleEntryTick shouldBe Map("car-1" -> 10L, "car-2" -> 15L)
    snapshot.linkVehicleWaitingSeconds shouldBe Map("car-1" -> 3.5)
  }

  it should "produce empty maps for a link with no registered vehicles" in {
    val link = newTestLink("link-2")
    link.testSetState(freshState())

    val snapshot = link.testBuildMigrationSnapshot()

    snapshot.linkVehicleEntryTick shouldBe empty
    snapshot.linkVehicleWaitingSeconds shouldBe empty
  }

  "Link.applyMigrationSnapshot" should "restore entry ticks and waiting seconds onto a freshly-constructed actor" in {
    val sourceLink = newTestLink("link-3")
    sourceLink.testSetState(freshState())
    sourceLink.testPutVehicleEntryTick("car-9", 42L)
    sourceLink.testPutVehicleWaitingSeconds("car-9", 7.0)

    val snapshot = sourceLink.testBuildMigrationSnapshot()

    val rehydratedLink = newTestLink("link-3")
    rehydratedLink.testApplyMigrationSnapshot(snapshot)

    rehydratedLink.testVehicleEntryTick shouldBe Map("car-9" -> 42L)
    rehydratedLink.testVehicleWaitingSeconds shouldBe Map("car-9" -> 7.0)
  }

  it should "leave a rehydrated link with empty maps when none were registered" in {
    val sourceLink = newTestLink("link-4")
    sourceLink.testSetState(freshState())
    val snapshot = sourceLink.testBuildMigrationSnapshot()

    val rehydratedLink = newTestLink("link-4")
    rehydratedLink.testApplyMigrationSnapshot(snapshot)

    rehydratedLink.testVehicleEntryTick shouldBe empty
    rehydratedLink.testVehicleWaitingSeconds shouldBe empty
  }

  it should "not leave stale entries from a pre-existing actor when restoring a smaller snapshot" in {
    val link = newTestLink("link-5")
    link.testSetState(freshState())
    link.testPutVehicleEntryTick("stale-car", 1L)

    val otherLink = newTestLink("link-6")
    otherLink.testSetState(freshState())
    val emptySnapshot = otherLink.testBuildMigrationSnapshot()
    link.testApplyMigrationSnapshot(emptySnapshot)

    link.testVehicleEntryTick shouldBe empty
  }
}
