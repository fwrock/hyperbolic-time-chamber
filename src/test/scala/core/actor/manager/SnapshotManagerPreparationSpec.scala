package org.interscity.htc
package core.actor.manager

import core.actor.manager.loadbalance.migration.MigrationSnapshot
import core.entity.event.control.migration.*
import core.entity.state.DefaultState
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestProbe
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

class SnapshotManagerPreparationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("SnapshotManagerPreparationSpec",
    ConfigFactory.parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
      .withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }

  private def snapshot(id: String): MigrationSnapshot =
    MigrationSnapshot("{\"startTick\":0}", classOf[DefaultState].getName, entityId = id)

  "the snapshot save barrier" should "acknowledge only after every distinct expected entity is stored" in {
    val controller = TestProbe(); val source = TestProbe(); val target = TestProbe()
    val manager = system.actorOf(SnapshotManager.props(controller.ref))
    val registration = RegisterMigrationBatchEvent("batch", Set("one", "two"), controller.ref)
    controller.send(manager, registration)
    controller.expectMsg(MigrationBatchRegisteredEvent("batch"))
    source.send(manager, SaveMigrationSnapshotEvent("one", "batch", snapshot("one")))
    source.send(manager, SaveMigrationSnapshotEvent("one", "batch", snapshot("one")))
    controller.expectNoMessage(100.millis)
    controller.send(manager, registration)
    controller.expectMsg(MigrationBatchRegisteredEvent("batch"))
    source.send(manager, SaveMigrationSnapshotEvent("two", "batch", snapshot("two")))
    controller.expectMsg(MigrationSnapshotsReadyEvent("batch", Set("one", "two")))
    source.send(manager, SaveMigrationSnapshotEvent("two", "batch", snapshot("two")))
    controller.expectNoMessage(100.millis)
    source.send(manager, SaveFinalMigrationSnapshotEvent("one", "batch", snapshot("one"), 1L))
    source.expectMsgType[FinalMigrationSnapshotSavedEvent]
    target.send(manager, QueryMigrationEvent("one", target.ref))
    val restored = target.expectMsgType[MigrationContextEvent]
    restored.snapshot shouldBe snapshot("one")
    restored.lbmRef shouldBe controller.ref
  }

  it should "reject saves that race ahead of registration and not retain their state" in {
    val controller = TestProbe(); val source = TestProbe(); val target = TestProbe()
    val manager = system.actorOf(SnapshotManager.props(controller.ref))
    source.send(manager, SaveMigrationSnapshotEvent("one", "unknown", snapshot("one")))
    source.expectMsgType[MigrationPreparationFailedEvent].batchId shouldBe "unknown"
    target.send(manager, QueryMigrationEvent("one", target.ref))
    target.expectMsgType[NoPendingMigrationEvent]
  }

  it should "reject foreign entities and conflicting duplicate saves without replacing a valid snapshot" in {
    val controller = TestProbe(); val source = TestProbe(); val target = TestProbe()
    val manager = system.actorOf(SnapshotManager.props(controller.ref))
    controller.send(manager, RegisterMigrationBatchEvent("batch", Set("one", "two"), controller.ref))
    controller.expectMsgType[MigrationBatchRegisteredEvent]
    source.send(manager, SaveMigrationSnapshotEvent("foreign", "batch", snapshot("foreign")))
    controller.expectMsgType[MigrationPreparationFailedEvent].entityId shouldBe "foreign"
    source.send(manager, SaveMigrationSnapshotEvent("one", "batch", snapshot("two")))
    controller.expectMsgType[MigrationPreparationFailedEvent].entityId shouldBe "one"
    source.send(manager, SaveMigrationSnapshotEvent("one", "batch", snapshot("one")))
    source.send(manager, SaveMigrationSnapshotEvent("one", "batch", snapshot("one").copy(currentTick = 99L)))
    controller.expectMsgType[MigrationPreparationFailedEvent].entityId shouldBe "one"
    source.send(manager, SaveMigrationSnapshotEvent("two", "batch", snapshot("two")))
    controller.expectMsgType[MigrationSnapshotsReadyEvent]
    source.send(manager, SaveFinalMigrationSnapshotEvent("one", "batch", snapshot("one"), 1L))
    source.expectMsgType[FinalMigrationSnapshotSavedEvent]
    target.send(manager, QueryMigrationEvent("one", target.ref))
    target.expectMsgType[MigrationContextEvent].snapshot.currentTick shouldBe 0L
  }

  it should "reject conflicting registrations without resetting the first batch's membership" in {
    val controller = TestProbe(); val other = TestProbe(); val source = TestProbe()
    val manager = system.actorOf(SnapshotManager.props(controller.ref))
    controller.send(manager, RegisterMigrationBatchEvent("batch", Set("one"), controller.ref))
    controller.expectMsgType[MigrationBatchRegisteredEvent]
    other.send(manager, RegisterMigrationBatchEvent("batch", Set("two"), other.ref))
    other.expectMsgType[MigrationPreparationFailedEvent]
    other.send(manager, RegisterMigrationBatchEvent("other", Set("one"), other.ref))
    other.expectMsgType[MigrationPreparationFailedEvent]
    source.send(manager, SaveMigrationSnapshotEvent("one", "batch", snapshot("one")))
    controller.expectMsg(MigrationSnapshotsReadyEvent("batch", Set("one")))
  }

  it should "acknowledge final cuts in revision order without overwriting them with stale or conflicting saves" in {
    val controller = TestProbe(); val source = TestProbe(); val target = TestProbe()
    val manager = system.actorOf(SnapshotManager.props(controller.ref))
    controller.send(manager, RegisterMigrationBatchEvent("batch", Set("one"), controller.ref))
    controller.expectMsgType[MigrationBatchRegisteredEvent]
    val firstFinal = SaveFinalMigrationSnapshotEvent("one", "batch", snapshot("one").copy(currentTick = 10L), 1L)
    source.send(manager, firstFinal)
    controller.expectMsgType[MigrationPreparationFailedEvent]
    source.expectMsgType[MigrationPreparationFailedEvent]
    source.send(manager, SaveMigrationSnapshotEvent("one", "batch", snapshot("one")))
    controller.expectMsgType[MigrationSnapshotsReadyEvent]
    source.send(manager, firstFinal)
    source.expectMsg(FinalMigrationSnapshotSavedEvent("one", "batch", 1L))
    source.send(manager, firstFinal)
    source.expectMsg(FinalMigrationSnapshotSavedEvent("one", "batch", 1L))
    val secondFinal = firstFinal.copy(snapshot = snapshot("one").copy(currentTick = 20L), revision = 2L)
    source.send(manager, secondFinal)
    source.expectMsg(FinalMigrationSnapshotSavedEvent("one", "batch", 2L))
    source.send(manager, firstFinal)
    source.expectMsg(FinalMigrationSnapshotSavedEvent("one", "batch", 1L))
    source.send(manager, secondFinal.copy(snapshot = snapshot("one")))
    controller.expectMsgType[MigrationPreparationFailedEvent]
    source.expectMsgType[MigrationPreparationFailedEvent]
    source.send(manager, secondFinal.copy(revision = 4L))
    controller.expectMsgType[MigrationPreparationFailedEvent]
    source.expectMsgType[MigrationPreparationFailedEvent]
    target.send(manager, QueryMigrationEvent("one", target.ref))
    target.expectMsgType[MigrationContextEvent].snapshot.currentTick shouldBe 20L
  }

  it should "fail a premature target query without consuming the preparation checkpoint or allowing fresh initialization" in {
    val controller = TestProbe(); val source = TestProbe(); val target = TestProbe()
    val manager = system.actorOf(SnapshotManager.props(controller.ref))
    controller.send(manager, RegisterMigrationBatchEvent("batch", Set("one"), controller.ref))
    controller.expectMsgType[MigrationBatchRegisteredEvent]
    source.send(manager, SaveMigrationSnapshotEvent("one", "batch", snapshot("one")))
    controller.expectMsgType[MigrationSnapshotsReadyEvent]
    target.send(manager, QueryMigrationEvent("one", target.ref))
    val failed = target.expectMsgType[MigrationPreparationFailedEvent]
    controller.expectMsg(failed)
    target.expectNoMessage(100.millis)
    source.send(manager, SaveFinalMigrationSnapshotEvent("one", "batch", snapshot("one").copy(currentTick = 7L), 1L))
    source.expectMsgType[FinalMigrationSnapshotSavedEvent]
    target.send(manager, QueryMigrationEvent("one", target.ref))
    target.expectMsgType[MigrationContextEvent].snapshot.currentTick shouldBe 7L
  }
}
