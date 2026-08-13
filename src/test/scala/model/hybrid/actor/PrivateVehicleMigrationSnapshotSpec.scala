package org.interscity.htc
package model.hybrid.actor

import core.actor.manager.loadbalance.migration.MigrationSnapshot
import core.entity.actor.properties.Properties
import core.entity.event.ActorInteractionEvent
import core.types.Tick
import model.hybrid.entity.event.data.person.{ ParkVehicleData, PersonScheduleCompleteData, StartTripData }
import model.hybrid.entity.state.CarState
import model.hybrid.entity.state.DriverAttributes
import model.hybrid.entity.state.enumeration.{ ActorTypeEnum, MovableStatusEnum }

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestActorRef
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.compiletime.uninitialized

/** Regression coverage for docs/KNOWN_GAPS.md "Shard Migration Silently Drops Actor-Local Reply
  * State" — Gap A.
  *
  * `PrivateVehicle`'s reply-linkage vars (`ownerPersonRef`, `personCentric`, `tripOrigin`,
  * `tripDestination`, `tripStartTick`, `tripStartDistance`, `destroyAfterNextPark`) are
  * actor-local, not part of `CarState`, so the default `BaseActor.buildMigrationSnapshot`/
  * `applyMigrationSnapshot` (state-only) would silently drop them across a shard migration. These
  * tests exercise the `buildMigrationSnapshot` -> `applyMigrationSnapshot` round trip directly
  * (shard rebalancing itself is disabled in this project, see CLAUDE.md) to prove
  * `Car.buildMigrationSnapshot`/`applyMigrationSnapshot` (which wire in
  * `PrivateVehicle.captureMigrationFields`/`restoreMigrationFields`) carry them across.
  *
  * Actors are constructed via `TestActorRef` (Pekko forbids `new Actor` outside `actorOf`
  * machinery) purely to get a valid instance to call protected methods on; no messages are routed
  * through the mailbox — the migration hooks are called directly on `underlyingActor`.
  */
class PrivateVehicleMigrationSnapshotSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var _system: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit =
    _system = ActorSystem(
      "PrivateVehicleMigrationSnapshotSpec",
      ConfigFactory
        .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
        .withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit = {
    _system.terminate()
    ()
  }

  /** Test-only subclass exposing the protected migration hooks and avoiding real TimeManager
    * plumbing (`registerOnTimeManager` normally sends a `ScheduleEvent` to a live TimeManager
    * `ActorRef` that isn't wired up in this unit test; the migration-snapshot logic under test
    * never depends on it).
    */
  private class TestCar(properties: Properties) extends Car(properties) {
    override protected def registerOnTimeManager(tick: Tick): Unit = ()

    def testSetState(s: CarState): Unit = state = s
    def testBuildMigrationSnapshot(): MigrationSnapshot = buildMigrationSnapshot()
    def testApplyMigrationSnapshot(snapshot: MigrationSnapshot): Unit = applyMigrationSnapshot(snapshot)
    def testHandleStartTrip(event: ActorInteractionEvent, data: StartTripData): Unit = handleStartTrip(event, data)
    def testHandleParkVehicle(event: ActorInteractionEvent, data: ParkVehicleData): Unit = handleParkVehicle(event, data)
    def testGetTripOrigin: Option[Long] = getTripOrigin
    def testGetTripDestination: Option[Long] = getTripDestination
    def testGetTripStartTick: Option[Tick] = getTripStartTick
    def testIsPersonCentric: Boolean = isPersonCentric
  }

  private def freshParkedState(): CarState = {
    val s = CarState(
      startTick = 0L,
      origin = 1001L,
      destination = 1002L,
      actorType = ActorTypeEnum.Car,
      size = 4.5
    )
    s.status = MovableStatusEnum.Parked
    s
  }

  private var nextEntitySuffix = 0

  private def newTestCar(entityId: String): TestCar = {
    nextEntitySuffix += 1
    TestActorRef(new TestCar(Properties(entityId = entityId)), s"$entityId-$nextEntitySuffix").underlyingActor
  }

  private def startTripEvent(personId: Long, personClassType: String): (ActorInteractionEvent, StartTripData) = {
    val data = StartTripData(
      personId = personId,
      origin = 1001L,
      destination = 1002L,
      driverAttributes = DriverAttributes(),
      startTick = 5L
    )
    val event = ActorInteractionEvent(
      tick = 5L,
      lamportTick = 5L,
      actorRefId = personId,
      shardRefId = personClassType,
      actorPathRef = s"/user/person-$personId",
      actorClassType = personClassType,
      data = data,
      resourceId = "res-1"
    )
    (event, data)
  }

  "PrivateVehicle.buildMigrationSnapshot" should "capture ownerPersonRef, trip linkage, and personCentric after StartTrip" in {
    val car = newTestCar("car-1")
    car.testSetState(freshParkedState())

    val (event, data) = startTripEvent(1L, "hybrid.actor.Person")
    car.testHandleStartTrip(event, data)

    val snapshot = car.testBuildMigrationSnapshot()

    snapshot.ownerPersonRefId shouldBe 1L
    snapshot.ownerPersonRefClassType shouldBe "hybrid.actor.Person"
    snapshot.personCentric shouldBe true
    snapshot.tripOrigin shouldBe 1001L
    snapshot.tripDestination shouldBe 1002L
    snapshot.tripStartTick shouldBe 5L
    snapshot.destroyAfterNextPark shouldBe false
  }

  it should "produce empty/sentinel reply-linkage fields for a never-activated (Parked) vehicle" in {
    val car = newTestCar("car-2")
    car.testSetState(freshParkedState())

    val snapshot = car.testBuildMigrationSnapshot()

    snapshot.ownerPersonRefId shouldBe 0L
    snapshot.personCentric shouldBe false
    snapshot.tripOrigin shouldBe 0L
    snapshot.tripDestination shouldBe 0L
    snapshot.tripStartTick shouldBe Long.MinValue
  }

  "PrivateVehicle.applyMigrationSnapshot" should "restore the reply-linkage fields on a freshly-constructed actor" in {
    val sourceCar = newTestCar("car-3")
    sourceCar.testSetState(freshParkedState())
    val (event, data) = startTripEvent(42L, "hybrid.actor.Person")
    sourceCar.testHandleStartTrip(event, data)

    val snapshot = sourceCar.testBuildMigrationSnapshot()

    val rehydratedCar = newTestCar("car-3")
    rehydratedCar.testApplyMigrationSnapshot(snapshot)

    rehydratedCar.testGetTripOrigin shouldBe Some(1001L)
    rehydratedCar.testGetTripDestination shouldBe Some(1002L)
    rehydratedCar.testGetTripStartTick shouldBe Some(5L)
    rehydratedCar.testIsPersonCentric shouldBe true

    val rebuilt = rehydratedCar.testBuildMigrationSnapshot()
    rebuilt.ownerPersonRefId shouldBe 42L
    rebuilt.ownerPersonRefClassType shouldBe "hybrid.actor.Person"
  }

  it should "leave a rehydrated Parked (never-activated) vehicle with no owner reply obligation" in {
    val sourceCar = newTestCar("car-4")
    sourceCar.testSetState(freshParkedState())
    val snapshot = sourceCar.testBuildMigrationSnapshot()

    val rehydratedCar = newTestCar("car-4")
    rehydratedCar.testApplyMigrationSnapshot(snapshot)

    rehydratedCar.testGetTripOrigin shouldBe None
    rehydratedCar.testIsPersonCentric shouldBe false
  }

  "PrivateVehicle migration round trip" should "preserve destroyAfterNextPark set by an owner's schedule-complete signal mid-trip" in {
    val car = newTestCar("car-5")
    car.testSetState(freshParkedState())
    val (event, data) = startTripEvent(7L, "hybrid.actor.Person")
    car.testHandleStartTrip(event, data)

    car.actInteractWith(
      ActorInteractionEvent(
        tick = 6L,
        lamportTick = 6L,
        actorRefId = 7L,
        shardRefId = "hybrid.actor.Person",
        actorPathRef = "/user/person-7",
        actorClassType = "hybrid.actor.Person",
        data = PersonScheduleCompleteData(personId = "person-7"),
        resourceId = "res-1"
      )
    )

    val snapshot = car.testBuildMigrationSnapshot()
    snapshot.destroyAfterNextPark shouldBe true

    val rehydratedCar = newTestCar("car-5")
    rehydratedCar.testApplyMigrationSnapshot(snapshot)
    rehydratedCar.testBuildMigrationSnapshot().destroyAfterNextPark shouldBe true
  }
}
