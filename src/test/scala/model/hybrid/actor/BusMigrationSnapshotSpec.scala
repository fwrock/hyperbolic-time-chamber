package org.interscity.htc
package model.hybrid.actor

import core.actor.manager.loadbalance.migration.MigrationSnapshot
import core.entity.actor.properties.Properties
import core.types.Tick
import model.hybrid.entity.state.BusState
import model.hybrid.entity.state.enumeration.MovableStatusEnum

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestActorRef
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.compiletime.uninitialized

/** Regression coverage for `docs/TIME_WARP_DESIGN.md`'s "Checkpoint-completeness gap" finding
  * (2026-08-07): `Bus` had **no** `buildMigrationSnapshot`/`applyMigrationSnapshot` override at
  * all, so every actor-local `var` -- including `expectedUnloadResponses`, a genuine reply-count
  * barrier -- was silently lost on any restore. Same harness as `CarLinkWaitMigrationSnapshotSpec`.
  */
class BusMigrationSnapshotSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var _system: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit =
    _system = ActorSystem(
      "BusMigrationSnapshotSpec",
      ConfigFactory
        .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
        .withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit = {
    _system.terminate()
    ()
  }

  private class TestBus(properties: Properties) extends Bus(properties) {
    def testSetState(s: BusState): Unit = state = s
    def testBuildMigrationSnapshot(): MigrationSnapshot = buildMigrationSnapshot()
    def testApplyMigrationSnapshot(snapshot: MigrationSnapshot): Unit = applyMigrationSnapshot(snapshot)

    def testSetFields(
      linkId: Option[String],
      entryTick: Option[Tick],
      exitTick: Option[Tick],
      waitUntil: Option[Tick],
      needsReverify: Boolean,
      unloadResponses: Int,
      stopNode: Option[String]
    ): Unit = {
      currentLinkId = linkId
      linkEntryTick = entryTick
      mesoExitTick = exitTick
      signalWaitUntilTick = waitUntil
      signalWaitNeedsReverify = needsReverify
      expectedUnloadResponses = unloadResponses
      currentStopNode = stopNode
    }

    def testCurrentLinkId: Option[String] = currentLinkId
    def testLinkEntryTick: Option[Tick] = linkEntryTick
    def testMesoExitTick: Option[Tick] = mesoExitTick
    def testSignalWaitUntilTick: Option[Tick] = signalWaitUntilTick
    def testSignalWaitNeedsReverify: Boolean = signalWaitNeedsReverify
    def testExpectedUnloadResponses: Int = expectedUnloadResponses
    def testCurrentStopNode: Option[String] = currentStopNode
  }

  private def freshState(): BusState = {
    val s = BusState(
      startTick = 0L,
      label = "line-1",
      capacity = 40,
      busStops = Map("stop-1" -> "nodeA", "stop-2" -> "nodeB"),
      numberOfPorts = 2,
      origin = "nodeA",
      destination = "nodeB",
      size = 12.0
    )
    s.status = MovableStatusEnum.Moving
    s
  }

  private var nextEntitySuffix = 0

  private def newTestBus(entityId: String): TestBus = {
    nextEntitySuffix += 1
    TestActorRef(new TestBus(Properties(entityId = entityId)), s"$entityId-$nextEntitySuffix").underlyingActor
  }

  "Bus.buildMigrationSnapshot" should "capture link occupancy, signal wait, and the unload reply-count barrier" in {
    val bus = newTestBus("bus-1")
    bus.testSetState(freshState())
    bus.testSetFields(
      linkId = Some("link-42"),
      entryTick = Some(10L),
      exitTick = Some(37L),
      waitUntil = None,
      needsReverify = false,
      unloadResponses = 3,
      stopNode = Some("nodeA")
    )

    val snapshot = bus.testBuildMigrationSnapshot()

    snapshot.vehicleCurrentLinkId shouldBe "link-42"
    snapshot.vehicleLinkEntryTick shouldBe 10L
    snapshot.vehicleMesoExitTick shouldBe 37L
    snapshot.expectedUnloadResponses shouldBe 3
    snapshot.currentStopNode shouldBe "nodeA"
  }

  it should "produce sentinel values for a bus not currently at a stop or on a link" in {
    val bus = newTestBus("bus-2")
    bus.testSetState(freshState())

    val snapshot = bus.testBuildMigrationSnapshot()

    snapshot.vehicleCurrentLinkId shouldBe ""
    snapshot.vehicleLinkEntryTick shouldBe Long.MinValue
    snapshot.vehicleMesoExitTick shouldBe Long.MinValue
    snapshot.expectedUnloadResponses shouldBe 0
    snapshot.currentStopNode shouldBe ""
  }

  "Bus.applyMigrationSnapshot" should "restore the reply-count barrier and stop node onto a freshly-constructed actor" in {
    val sourceBus = newTestBus("bus-3")
    sourceBus.testSetState(freshState())
    sourceBus.testSetFields(
      linkId = None,
      entryTick = None,
      exitTick = None,
      waitUntil = None,
      needsReverify = false,
      unloadResponses = 5,
      stopNode = Some("nodeB")
    )

    val snapshot = sourceBus.testBuildMigrationSnapshot()

    val rehydratedBus = newTestBus("bus-3")
    rehydratedBus.testApplyMigrationSnapshot(snapshot)

    rehydratedBus.testExpectedUnloadResponses shouldBe 5
    rehydratedBus.testCurrentStopNode shouldBe Some("nodeB")
  }

  it should "leave a rehydrated bus with no stale unload barrier when none was in progress" in {
    val sourceBus = newTestBus("bus-4")
    sourceBus.testSetState(freshState())
    val snapshot = sourceBus.testBuildMigrationSnapshot()

    val rehydratedBus = newTestBus("bus-4")
    rehydratedBus.testApplyMigrationSnapshot(snapshot)

    rehydratedBus.testExpectedUnloadResponses shouldBe 0
    rehydratedBus.testCurrentStopNode shouldBe None
    rehydratedBus.testCurrentLinkId shouldBe None
  }
}
