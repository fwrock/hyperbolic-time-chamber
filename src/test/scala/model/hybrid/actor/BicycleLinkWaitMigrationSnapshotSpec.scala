package org.interscity.htc
package model.hybrid.actor

import core.actor.manager.loadbalance.migration.MigrationSnapshot
import core.entity.actor.properties.Properties
import core.types.Tick
import model.hybrid.entity.state.BicycleState
import model.hybrid.entity.state.enumeration.{ ActorTypeEnum, MovableStatusEnum }

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestActorRef
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.compiletime.uninitialized

/** Regression coverage for `docs/TIME_WARP_DESIGN.md`'s "Checkpoint-completeness gap" finding
  * (2026-08-07), the `Bicycle` half — same fields, same fix, same rationale as
  * `CarLinkWaitMigrationSnapshotSpec`, minus `currentLinkLength` (Bicycle doesn't track it
  * separately from `MigrationSnapshot`'s default).
  */
class BicycleLinkWaitMigrationSnapshotSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var _system: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit =
    _system = ActorSystem(
      "BicycleLinkWaitMigrationSnapshotSpec",
      ConfigFactory
        .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
        .withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit = {
    _system.terminate()
    ()
  }

  private class TestBicycle(properties: Properties) extends Bicycle(properties) {
    override protected def registerOnTimeManager(tick: Tick): Unit = ()

    def testSetState(s: BicycleState): Unit = state = s
    def testBuildMigrationSnapshot(): MigrationSnapshot = buildMigrationSnapshot()
    def testApplyMigrationSnapshot(snapshot: MigrationSnapshot): Unit = applyMigrationSnapshot(snapshot)

    def testSetLinkWaitFields(
      linkId: Option[String],
      entryTick: Option[Tick],
      exitTick: Option[Tick],
      waitUntil: Option[Tick],
      needsReverify: Boolean
    ): Unit = {
      currentLinkId = linkId
      linkEntryTick = entryTick
      mesoExitTick = exitTick
      signalWaitUntilTick = waitUntil
      signalWaitNeedsReverify = needsReverify
    }

    def testCurrentLinkId: Option[String] = currentLinkId
    def testLinkEntryTick: Option[Tick] = linkEntryTick
    def testMesoExitTick: Option[Tick] = mesoExitTick
    def testSignalWaitUntilTick: Option[Tick] = signalWaitUntilTick
    def testSignalWaitNeedsReverify: Boolean = signalWaitNeedsReverify
  }

  private def freshState(): BicycleState = {
    val s = BicycleState(
      startTick = 0L,
      origin = "nodeA",
      destination = "nodeB",
      actorType = ActorTypeEnum.Bicycle,
      size = 2.0
    )
    s.status = MovableStatusEnum.Moving
    s
  }

  private var nextEntitySuffix = 0

  private def newTestBicycle(entityId: String): TestBicycle = {
    nextEntitySuffix += 1
    TestActorRef(new TestBicycle(Properties(entityId = entityId)), s"$entityId-$nextEntitySuffix").underlyingActor
  }

  "Bicycle.buildMigrationSnapshot" should "capture link occupancy and MESO exit-tick bookkeeping" in {
    val bike = newTestBicycle("bike-1")
    bike.testSetState(freshState())
    bike.testSetLinkWaitFields(
      linkId = Some("link-42"),
      entryTick = Some(10L),
      exitTick = Some(37L),
      waitUntil = None,
      needsReverify = false
    )

    val snapshot = bike.testBuildMigrationSnapshot()

    snapshot.vehicleCurrentLinkId shouldBe "link-42"
    snapshot.vehicleLinkEntryTick shouldBe 10L
    snapshot.vehicleMesoExitTick shouldBe 37L
    snapshot.vehicleSignalWaitUntilTick shouldBe Long.MinValue
    snapshot.vehicleSignalWaitNeedsReverify shouldBe false
  }

  it should "capture a pending signal-wait re-verification" in {
    val bike = newTestBicycle("bike-2")
    bike.testSetState(freshState())
    bike.testSetLinkWaitFields(
      linkId = Some("link-7"),
      entryTick = Some(5L),
      exitTick = None,
      waitUntil = Some(120L),
      needsReverify = true
    )

    val snapshot = bike.testBuildMigrationSnapshot()

    snapshot.vehicleSignalWaitUntilTick shouldBe 120L
    snapshot.vehicleSignalWaitNeedsReverify shouldBe true
    snapshot.vehicleMesoExitTick shouldBe Long.MinValue
  }

  it should "produce sentinel values for a vehicle not currently on any link" in {
    val bike = newTestBicycle("bike-3")
    bike.testSetState(freshState())

    val snapshot = bike.testBuildMigrationSnapshot()

    snapshot.vehicleCurrentLinkId shouldBe ""
    snapshot.vehicleLinkEntryTick shouldBe Long.MinValue
    snapshot.vehicleMesoExitTick shouldBe Long.MinValue
    snapshot.vehicleSignalWaitUntilTick shouldBe Long.MinValue
    snapshot.vehicleSignalWaitNeedsReverify shouldBe false
  }

  "Bicycle.applyMigrationSnapshot" should "restore link occupancy and signal-wait bookkeeping onto a freshly-constructed actor" in {
    val sourceBike = newTestBicycle("bike-4")
    sourceBike.testSetState(freshState())
    sourceBike.testSetLinkWaitFields(
      linkId = Some("link-99"),
      entryTick = Some(50L),
      exitTick = None,
      waitUntil = Some(75L),
      needsReverify = true
    )

    val snapshot = sourceBike.testBuildMigrationSnapshot()

    val rehydratedBike = newTestBicycle("bike-4")
    rehydratedBike.testApplyMigrationSnapshot(snapshot)

    rehydratedBike.testCurrentLinkId shouldBe Some("link-99")
    rehydratedBike.testLinkEntryTick shouldBe Some(50L)
    rehydratedBike.testMesoExitTick shouldBe None
    rehydratedBike.testSignalWaitUntilTick shouldBe Some(75L)
    rehydratedBike.testSignalWaitNeedsReverify shouldBe true
  }

  it should "leave a rehydrated vehicle that was never on a link with no stale link-wait state" in {
    val sourceBike = newTestBicycle("bike-5")
    sourceBike.testSetState(freshState())
    val snapshot = sourceBike.testBuildMigrationSnapshot()

    val rehydratedBike = newTestBicycle("bike-5")
    rehydratedBike.testApplyMigrationSnapshot(snapshot)

    rehydratedBike.testCurrentLinkId shouldBe None
    rehydratedBike.testLinkEntryTick shouldBe None
    rehydratedBike.testMesoExitTick shouldBe None
    rehydratedBike.testSignalWaitUntilTick shouldBe None
    rehydratedBike.testSignalWaitNeedsReverify shouldBe false
  }
}
