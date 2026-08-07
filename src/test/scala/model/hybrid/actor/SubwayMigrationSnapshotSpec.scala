package org.interscity.htc
package model.hybrid.actor

import core.actor.manager.loadbalance.migration.MigrationSnapshot
import core.entity.actor.properties.Properties
import core.types.Tick
import model.hybrid.entity.state.SubwayState

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestActorRef
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.compiletime.uninitialized

/** Regression coverage for `docs/TIME_WARP_DESIGN.md`'s "Checkpoint-completeness gap" finding
  * (2026-08-07): `Subway` had **no** `buildMigrationSnapshot`/`applyMigrationSnapshot` override at
  * all, so `expectedUnloadResponses` -- the same reply-count-barrier pattern as `Bus`'s -- was
  * silently lost on any restore. Same harness as `BusMigrationSnapshotSpec`.
  */
class SubwayMigrationSnapshotSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var _system: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit =
    _system = ActorSystem(
      "SubwayMigrationSnapshotSpec",
      ConfigFactory
        .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
        .withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit = {
    _system.terminate()
    ()
  }

  private class TestSubway(properties: Properties) extends Subway(properties) {
    def testSetState(s: SubwayState): Unit = state = s
    def testBuildMigrationSnapshot(): MigrationSnapshot = buildMigrationSnapshot()
    def testApplyMigrationSnapshot(snapshot: MigrationSnapshot): Unit = applyMigrationSnapshot(snapshot)

    def testSetExpectedUnloadResponses(v: Int): Unit = expectedUnloadResponses = v
    def testExpectedUnloadResponses: Int = expectedUnloadResponses
  }

  private def freshState(): SubwayState =
    SubwayState(
      startTick = 0L,
      capacity = 200,
      numberOfPorts = 4,
      velocity = 20.0,
      stopTime = 30L,
      origin = "nodeA",
      destination = "nodeB",
      line = "line-1"
    )

  private var nextEntitySuffix = 0

  private def newTestSubway(entityId: String): TestSubway = {
    nextEntitySuffix += 1
    TestActorRef(new TestSubway(Properties(entityId = entityId)), s"$entityId-$nextEntitySuffix").underlyingActor
  }

  "Subway.buildMigrationSnapshot" should "capture the unload reply-count barrier" in {
    val subway = newTestSubway("subway-1")
    subway.testSetState(freshState())
    subway.testSetExpectedUnloadResponses(7)

    val snapshot = subway.testBuildMigrationSnapshot()

    snapshot.expectedUnloadResponses shouldBe 7
  }

  it should "produce the zero sentinel when no unload round is in progress" in {
    val subway = newTestSubway("subway-2")
    subway.testSetState(freshState())

    val snapshot = subway.testBuildMigrationSnapshot()

    snapshot.expectedUnloadResponses shouldBe 0
  }

  "Subway.applyMigrationSnapshot" should "restore the reply-count barrier onto a freshly-constructed actor" in {
    val sourceSubway = newTestSubway("subway-3")
    sourceSubway.testSetState(freshState())
    sourceSubway.testSetExpectedUnloadResponses(4)

    val snapshot = sourceSubway.testBuildMigrationSnapshot()

    val rehydratedSubway = newTestSubway("subway-3")
    rehydratedSubway.testApplyMigrationSnapshot(snapshot)

    rehydratedSubway.testExpectedUnloadResponses shouldBe 4
  }

  it should "leave a rehydrated subway with no stale unload barrier when none was in progress" in {
    val sourceSubway = newTestSubway("subway-4")
    sourceSubway.testSetState(freshState())
    val snapshot = sourceSubway.testBuildMigrationSnapshot()

    val rehydratedSubway = newTestSubway("subway-4")
    rehydratedSubway.testApplyMigrationSnapshot(snapshot)

    rehydratedSubway.testExpectedUnloadResponses shouldBe 0
  }
}
