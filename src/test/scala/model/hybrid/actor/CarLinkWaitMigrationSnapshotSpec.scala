package org.interscity.htc
package model.hybrid.actor

import core.actor.manager.loadbalance.migration.MigrationSnapshot
import core.entity.actor.properties.Properties
import core.types.Tick
import model.hybrid.entity.state.CarState
import model.hybrid.entity.state.enumeration.{ ActorTypeEnum, MovableStatusEnum }

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestActorRef
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.compiletime.uninitialized

/** Regression coverage for `docs/TIME_WARP_DESIGN.md`'s "Checkpoint-completeness gap" finding
  * (2026-08-07): `Car`'s own link/signal-wait bookkeeping (`currentLinkId`, `currentLinkLength`,
  * `linkEntryTick`, `mesoExitTick`, `signalWaitUntilTick`, `signalWaitNeedsReverify`) are
  * actor-local `var`s, not part of `CarState`, so — same mechanism as Gap A/B, which this test
  * mirrors — `buildMigrationSnapshot`/`applyMigrationSnapshot` (the primitive `RollbackHistoryHandler`
  * checkpointing reuses verbatim) would silently drop them without `captureCarMigrationFields`/
  * `restoreCarMigrationFields`.
  *
  * Same harness as `PrivateVehicleMigrationSnapshotSpec`: a `TestActorRef` purely to get a valid
  * instance to call protected methods on, no mailbox traffic. `currentLinkId` etc. are `protected`
  * (not `private`) specifically so this spec can set them directly instead of reverse-engineering
  * `LinkInfoData`/`LinkAccessData` payloads just to exercise the capture/restore round trip.
  */
class CarLinkWaitMigrationSnapshotSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var _system: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit =
    _system = ActorSystem(
      "CarLinkWaitMigrationSnapshotSpec",
      ConfigFactory
        .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
        .withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit = {
    _system.terminate()
    ()
  }

  private class TestCar(properties: Properties) extends Car(properties) {
    override protected def registerOnTimeManager(tick: Tick): Unit = ()

    def testSetState(s: CarState): Unit = state = s
    def testBuildMigrationSnapshot(): MigrationSnapshot = buildMigrationSnapshot()
    def testApplyMigrationSnapshot(snapshot: MigrationSnapshot): Unit = applyMigrationSnapshot(snapshot)

    def testSetLinkWaitFields(
      linkId: Option[String],
      linkLength: Double,
      entryTick: Option[Tick],
      exitTick: Option[Tick],
      waitUntil: Option[Tick],
      needsReverify: Boolean
    ): Unit = {
      currentLinkId = linkId
      currentLinkLength = linkLength
      linkEntryTick = entryTick
      mesoExitTick = exitTick
      signalWaitUntilTick = waitUntil
      signalWaitNeedsReverify = needsReverify
    }

    def testCurrentLinkId: Option[String] = currentLinkId
    def testCurrentLinkLength: Double = currentLinkLength
    def testLinkEntryTick: Option[Tick] = linkEntryTick
    def testMesoExitTick: Option[Tick] = mesoExitTick
    def testSignalWaitUntilTick: Option[Tick] = signalWaitUntilTick
    def testSignalWaitNeedsReverify: Boolean = signalWaitNeedsReverify
  }

  private def freshState(): CarState = {
    val s = CarState(
      startTick = 0L,
      origin = "nodeA",
      destination = "nodeB",
      actorType = ActorTypeEnum.Car,
      size = 4.5
    )
    s.status = MovableStatusEnum.Moving
    s
  }

  private var nextEntitySuffix = 0

  private def newTestCar(entityId: String): TestCar = {
    nextEntitySuffix += 1
    TestActorRef(new TestCar(Properties(entityId = entityId)), s"$entityId-$nextEntitySuffix").underlyingActor
  }

  "Car.buildMigrationSnapshot" should "capture link occupancy and MESO exit-tick bookkeeping" in {
    val car = newTestCar("car-1")
    car.testSetState(freshState())
    car.testSetLinkWaitFields(
      linkId = Some("link-42"),
      linkLength = 250.5,
      entryTick = Some(10L),
      exitTick = Some(37L),
      waitUntil = None,
      needsReverify = false
    )

    val snapshot = car.testBuildMigrationSnapshot()

    snapshot.vehicleCurrentLinkId shouldBe "link-42"
    snapshot.vehicleCurrentLinkLength shouldBe 250.5
    snapshot.vehicleLinkEntryTick shouldBe 10L
    snapshot.vehicleMesoExitTick shouldBe 37L
    snapshot.vehicleSignalWaitUntilTick shouldBe Long.MinValue
    snapshot.vehicleSignalWaitNeedsReverify shouldBe false
  }

  it should "capture a pending signal-wait re-verification" in {
    val car = newTestCar("car-2")
    car.testSetState(freshState())
    car.testSetLinkWaitFields(
      linkId = Some("link-7"),
      linkLength = 80.0,
      entryTick = Some(5L),
      exitTick = None,
      waitUntil = Some(120L),
      needsReverify = true
    )

    val snapshot = car.testBuildMigrationSnapshot()

    snapshot.vehicleSignalWaitUntilTick shouldBe 120L
    snapshot.vehicleSignalWaitNeedsReverify shouldBe true
    snapshot.vehicleMesoExitTick shouldBe Long.MinValue
  }

  it should "produce sentinel values for a vehicle not currently on any link" in {
    val car = newTestCar("car-3")
    car.testSetState(freshState())
    // Defaults: never entered a link, never waited on a signal.

    val snapshot = car.testBuildMigrationSnapshot()

    snapshot.vehicleCurrentLinkId shouldBe ""
    snapshot.vehicleCurrentLinkLength shouldBe 0.0
    snapshot.vehicleLinkEntryTick shouldBe Long.MinValue
    snapshot.vehicleMesoExitTick shouldBe Long.MinValue
    snapshot.vehicleSignalWaitUntilTick shouldBe Long.MinValue
    snapshot.vehicleSignalWaitNeedsReverify shouldBe false
  }

  "Car.applyMigrationSnapshot" should "restore link occupancy and signal-wait bookkeeping onto a freshly-constructed actor" in {
    val sourceCar = newTestCar("car-4")
    sourceCar.testSetState(freshState())
    sourceCar.testSetLinkWaitFields(
      linkId = Some("link-99"),
      linkLength = 400.0,
      entryTick = Some(50L),
      exitTick = None,
      waitUntil = Some(75L),
      needsReverify = true
    )

    val snapshot = sourceCar.testBuildMigrationSnapshot()

    val rehydratedCar = newTestCar("car-4")
    rehydratedCar.testApplyMigrationSnapshot(snapshot)

    rehydratedCar.testCurrentLinkId shouldBe Some("link-99")
    rehydratedCar.testCurrentLinkLength shouldBe 400.0
    rehydratedCar.testLinkEntryTick shouldBe Some(50L)
    rehydratedCar.testMesoExitTick shouldBe None
    rehydratedCar.testSignalWaitUntilTick shouldBe Some(75L)
    rehydratedCar.testSignalWaitNeedsReverify shouldBe true
  }

  it should "leave a rehydrated vehicle that was never on a link with no stale link-wait state" in {
    val sourceCar = newTestCar("car-5")
    sourceCar.testSetState(freshState())
    val snapshot = sourceCar.testBuildMigrationSnapshot()

    val rehydratedCar = newTestCar("car-5")
    rehydratedCar.testApplyMigrationSnapshot(snapshot)

    rehydratedCar.testCurrentLinkId shouldBe None
    rehydratedCar.testLinkEntryTick shouldBe None
    rehydratedCar.testMesoExitTick shouldBe None
    rehydratedCar.testSignalWaitUntilTick shouldBe None
    rehydratedCar.testSignalWaitNeedsReverify shouldBe false
  }
}
