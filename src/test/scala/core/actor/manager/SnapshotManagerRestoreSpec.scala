package org.interscity.htc
package core.actor.manager

import core.actor.manager.loadbalance.migration.MigrationSnapshot
import core.entity.event.control.migration.*
import core.entity.state.DefaultState
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ ActorRef, ActorSystem }
import org.apache.pekko.testkit.TestProbe
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

class SnapshotManagerRestoreSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("SnapshotManagerRestoreSpec",
    ConfigFactory.parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
      .withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }

  private def install(manager: ActorRef, controller: TestProbe, source: TestProbe,
    batch: String = "batch", ids: Set[String] = Set("one")): Unit = {
    controller.send(manager, RegisterMigrationBatchEvent(batch, ids, controller.ref))
    controller.expectMsg(MigrationBatchRegisteredEvent(batch))
    ids.foreach { id =>
      source.send(manager, SaveMigrationSnapshotEvent(id, batch,
        MigrationSnapshot("{\"startTick\":0}", classOf[DefaultState].getName, entityId = id)))
    }
    controller.expectMsg(MigrationSnapshotsReadyEvent(batch, ids))
    ids.foreach { id =>
      source.send(manager, SaveFinalMigrationSnapshotEvent(id, batch,
        MigrationSnapshot("{\"startTick\":0}", classOf[DefaultState].getName, entityId = id, currentTick = 7L), 1L))
      source.expectMsg(FinalMigrationSnapshotSavedEvent(id, batch, 1L))
    }
  }

  private def query(manager: ActorRef, target: TestProbe, id: String = "one"): MigrationContextEvent = {
    target.send(manager, QueryMigrationEvent(id, target.ref))
    target.expectMsgType[MigrationContextEvent]
  }
  private def ack(context: MigrationContextEvent): MigrationRestoredAckEvent =
    MigrationRestoredAckEvent(context.snapshot.entityId, context.batchId, context.restoreToken, context.snapshotRevision)

  "restore retention" should "keep the final snapshot until its offered target confirms the matching token and revision" in {
    val controller = TestProbe(); val source = TestProbe(); val target = TestProbe(); val other = TestProbe()
    val manager = system.actorOf(SnapshotManager.props(controller.ref))
    install(manager, controller, source)
    val offered = query(manager, target)
    offered.snapshotManagerRef shouldBe manager
    offered.restoreToken should not be empty
    offered.snapshotRevision shouldBe 1L
    query(manager, target) shouldBe offered
    target.send(manager, ack(offered).copy(batchId = "old"))
    target.send(manager, ack(offered).copy(restoreToken = "wrong"))
    target.send(manager, ack(offered).copy(snapshotRevision = 2L))
    other.send(manager, ack(offered))
    controller.expectNoMessage(100.millis)
    query(manager, target) shouldBe offered
    target.send(manager, ack(offered))
    controller.expectMsg(MigrationRestoreAcceptedEvent("one", "batch"))
    target.send(manager, QueryMigrationEvent("one", target.ref))
    target.expectMsg(MigrationRestoreAlreadyAcceptedEvent("one", "batch", offered.restoreToken, 1L))
    controller.expectMsg(MigrationRestoreAcceptedEvent("one", "batch"))
    target.send(manager, ack(offered))
    controller.expectMsg(MigrationRestoreAcceptedEvent("one", "batch"))
    controller.expectNoMessage(100.millis)
    controller.send(manager, RegisterMigrationBatchEvent("batch", Set("one"), controller.ref))
    controller.expectMsgType[MigrationPreparationFailedEvent]
    target.send(manager, MigrationRestoreFailedEvent("one", "batch", offered.restoreToken, 1L, "lost local restore state"))
    controller.expectMsgType[MigrationPreparationFailedEvent]
    target.send(manager, QueryMigrationEvent("one", target.ref))
    target.expectMsgType[MigrationPreparationFailedEvent]
    controller.expectMsgType[MigrationPreparationFailedEvent]
    target.send(manager, ack(offered))
    controller.expectNoMessage(100.millis)
  }

  it should "complete a batch only after every entity has confirmed application" in {
    val controller = TestProbe(); val source = TestProbe(); val one = TestProbe(); val two = TestProbe()
    val manager = system.actorOf(SnapshotManager.props(controller.ref))
    install(manager, controller, source, ids = Set("one", "two"))
    val first = query(manager, one)
    val second = query(manager, two, "two")
    one.send(manager, ack(first))
    controller.expectMsg(MigrationRestoreAcceptedEvent("one", "batch"))
    controller.send(manager, RegisterMigrationBatchEvent("next", Set("two"), controller.ref))
    controller.expectMsgType[MigrationPreparationFailedEvent]
    query(manager, two, "two") shouldBe second
    two.send(manager, ack(second))
    controller.expectMsg(MigrationRestoreAcceptedEvent("two", "batch"))
    controller.send(manager, RegisterMigrationBatchEvent("next", Set("two"), controller.ref))
    controller.expectMsg(MigrationBatchRegisteredEvent("next"))
  }

  it should "reject a competing or restarted target without transferring the first target's restore offer" in {
    val controller = TestProbe(); val source = TestProbe(); val target = TestProbe(); val other = TestProbe()
    val manager = system.actorOf(SnapshotManager.props(controller.ref))
    install(manager, controller, source)
    val offered = query(manager, target)
    other.send(manager, QueryMigrationEvent("one", other.ref))
    controller.expectMsgType[MigrationPreparationFailedEvent]
    other.expectMsgType[MigrationPreparationFailedEvent]
    query(manager, target) shouldBe offered
    target.send(manager, ack(offered))
    controller.expectMsg(MigrationRestoreAcceptedEvent("one", "batch"))
    other.send(manager, QueryMigrationEvent("one", other.ref))
    other.expectMsgType[MigrationPreparationFailedEvent]
    controller.expectMsgType[MigrationPreparationFailedEvent]
    other.send(manager, QueryMigrationEvent("fresh", other.ref))
    other.expectMsg(NoPendingMigrationEvent())
  }

  it should "retain a failed restore and refuse a later success ACK for that failed offer" in {
    val controller = TestProbe(); val source = TestProbe(); val target = TestProbe()
    val manager = system.actorOf(SnapshotManager.props(controller.ref))
    install(manager, controller, source)
    val offered = query(manager, target)
    target.send(manager, MigrationRestoreFailedEvent("one", "batch", offered.restoreToken, 1L, "hook failed"))
    controller.expectMsgType[MigrationPreparationFailedEvent].reason should include("hook failed")
    target.send(manager, ack(offered))
    controller.expectNoMessage(100.millis)
    target.send(manager, QueryMigrationEvent("one", target.ref))
    target.expectMsgType[MigrationPreparationFailedEvent]
    controller.expectMsgType[MigrationPreparationFailedEvent]
    controller.send(manager, RegisterMigrationBatchEvent("next", Set("one"), controller.ref))
    controller.expectMsgType[MigrationPreparationFailedEvent]
  }

  it should "isolate successive migrations of an entity from old queries, old ACKs, and late final revisions" in {
    val controller = TestProbe(); val source = TestProbe(); val oldTarget = TestProbe(); val newTarget = TestProbe()
    val manager = system.actorOf(SnapshotManager.props(controller.ref))
    install(manager, controller, source)
    val first = query(manager, oldTarget)
    oldTarget.send(manager, ack(first))
    controller.expectMsgType[MigrationRestoreAcceptedEvent]
    source.send(manager, SaveMigrationSnapshotEvent("one", "batch", first.snapshot))
    source.expectNoMessage(100.millis)
    controller.expectNoMessage(100.millis)
    install(manager, controller, source, batch = "next")
    oldTarget.send(manager, QueryMigrationEvent("one", oldTarget.ref))
    oldTarget.expectMsgType[MigrationRestoreAlreadyAcceptedEvent].batchId shouldBe "batch"
    controller.expectMsg(MigrationRestoreAcceptedEvent("one", "batch"))
    val second = query(manager, newTarget)
    second.restoreToken should not be first.restoreToken
    source.send(manager, SaveFinalMigrationSnapshotEvent("one", "next", second.snapshot.copy(currentTick = 99L), 2L))
    source.expectMsgType[MigrationPreparationFailedEvent]
    controller.expectMsgType[MigrationPreparationFailedEvent]
    oldTarget.send(manager, ack(first))
    controller.expectMsg(MigrationRestoreAcceptedEvent("one", "batch"))
    newTarget.send(manager, ack(second).copy(restoreToken = first.restoreToken))
    controller.expectNoMessage(100.millis)
    query(manager, newTarget) shouldBe second
    newTarget.send(manager, ack(second))
    controller.expectMsg(MigrationRestoreAcceptedEvent("one", "next"))
  }
}
