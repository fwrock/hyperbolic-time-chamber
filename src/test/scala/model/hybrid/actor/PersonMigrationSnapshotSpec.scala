package org.interscity.htc
package model.hybrid.actor

import core.actor.manager.loadbalance.migration.MigrationSnapshot
import core.entity.actor.properties.Properties
import core.entity.event.ActorInteractionEvent
import model.hybrid.entity.event.data.person.PassengerBoardedVehicleData
import model.hybrid.entity.state.PersonState

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestActorRef
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.compiletime.uninitialized

/** Regression coverage for docs/KNOWN_GAPS.md "Shard Migration Silently Drops Actor-Local Reply
  * State" — Gap B.
  *
  * `Person.currentPTVehicleRef` is an actor-local var (not part of `PersonState`) that
  * `Person.onDestruct` depends on to answer a boarded Bus/Subway's unload barrier
  * (`expectedUnloadResponses`) on every path (commit `531ca55`). Without carrying it across a
  * migration, a Person that migrates shard while boarded would reopen that barrier deadlock. These
  * tests exercise the `buildMigrationSnapshot` -> `applyMigrationSnapshot` round trip directly
  * (shard rebalancing itself is disabled in this project, see CLAUDE.md).
  *
  * Actors are constructed via `TestActorRef` (Pekko forbids `new Actor` outside `actorOf`
  * machinery) purely to get a valid instance to call protected methods on; no messages are routed
  * through the mailbox — the migration hooks are called directly on `underlyingActor`.
  */
class PersonMigrationSnapshotSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {


  private var _system: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit =
    _system = ActorSystem(
      "PersonMigrationSnapshotSpec",
      ConfigFactory
        .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
        .withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit = {
    _system.terminate()
    ()
  }

  /** Test-only subclass exposing the protected migration hooks. */
  private class TestPerson(properties: Properties) extends Person(properties) {
    def testSetState(s: PersonState): Unit = state = s
    def testBuildMigrationSnapshot(): MigrationSnapshot = buildMigrationSnapshot()
    def testApplyMigrationSnapshot(snapshot: MigrationSnapshot): Unit = applyMigrationSnapshot(snapshot)
  }

  private var nextEntitySuffix = 0

  private def newTestPerson(entityId: String): TestPerson = {
    nextEntitySuffix += 1
    TestActorRef(new TestPerson(Properties(entityId = entityId)), s"$entityId-$nextEntitySuffix").underlyingActor
  }

  private def boardedEvent(vehicleId: Long, vehicleClassType: String): ActorInteractionEvent =
    ActorInteractionEvent(
      tick = 3L,
      lamportTick = 3L,
      actorRefId = vehicleId,
      shardRefId = vehicleClassType,
      actorPathRef = s"/user/vehicle-$vehicleId",
      actorClassType = vehicleClassType,
      data = PassengerBoardedVehicleData(vehicleId = vehicleId, vehicleClassType = vehicleClassType),
      resourceId = "res-1"
    )

  "Person.buildMigrationSnapshot" should "capture currentPTVehicleRef after boarding a Bus/Subway" in {
    val person = newTestPerson("person-1")
    person.testSetState(PersonState())
    person.actInteractWith(boardedEvent(1L, "hybrid.actor.Bus"))

    val snapshot = person.testBuildMigrationSnapshot()

    snapshot.currentPTVehicleRefId shouldBe 1L
    snapshot.currentPTVehicleRefClassType shouldBe "hybrid.actor.Bus"
  }

  it should "produce an empty currentPTVehicleRef field when not boarded on any PT vehicle" in {
    val person = newTestPerson("person-2")
    person.testSetState(PersonState())

    val snapshot = person.testBuildMigrationSnapshot()

    snapshot.currentPTVehicleRefId shouldBe 0L
    snapshot.currentPTVehicleRefClassType shouldBe ""
  }

  "Person.applyMigrationSnapshot" should "restore currentPTVehicleRef on a freshly-constructed actor so onDestruct can still answer the boarding barrier" in {
    val sourcePerson = newTestPerson("person-3")
    sourcePerson.testSetState(PersonState())
    sourcePerson.actInteractWith(boardedEvent(9L, "hybrid.actor.Subway"))

    val snapshot = sourcePerson.testBuildMigrationSnapshot()

    val rehydratedPerson = newTestPerson("person-3")
    rehydratedPerson.testApplyMigrationSnapshot(snapshot)

    val rebuilt = rehydratedPerson.testBuildMigrationSnapshot()
    rebuilt.currentPTVehicleRefId shouldBe 9L
    rebuilt.currentPTVehicleRefClassType shouldBe "hybrid.actor.Subway"
  }

  it should "leave a rehydrated not-boarded Person with no pending PT reply obligation" in {
    val sourcePerson = newTestPerson("person-4")
    sourcePerson.testSetState(PersonState())
    val snapshot = sourcePerson.testBuildMigrationSnapshot()

    val rehydratedPerson = newTestPerson("person-4")
    rehydratedPerson.testApplyMigrationSnapshot(snapshot)

    rehydratedPerson.testBuildMigrationSnapshot().currentPTVehicleRefId shouldBe 0L
  }
}
