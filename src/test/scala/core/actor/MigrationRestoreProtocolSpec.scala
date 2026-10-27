package org.interscity.htc
package core.actor

import core.actor.manager.loadbalance.migration.MigrationSnapshot
import core.entity.actor.properties.Properties
import core.entity.event.control.migration.*
import core.entity.state.DefaultState
import core.enumeration.CreationTypeEnum
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ ActorRef, ActorSystem, Props }
import org.apache.pekko.testkit.TestProbe
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

class MigrationRestoreProtocolSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("MigrationRestoreProtocolSpec",
    ConfigFactory.parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
      .withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }
  override def beforeAll(): Unit = {
    // Actor-exchange deadlines must not include first-use Jackson class loading under parallel suites.
    core.util.JsonUtil.fromJson[DefaultState]("{\"startTick\":0}")
    ()
  }

  private case object Increment
  private case object Read
  private case class HookApplied(value: Long)
  private class Target(properties: Properties, observer: ActorRef, failHook: Boolean)
      extends SimulationBaseActor[DefaultState](properties) {
    override protected def onStart(): Unit = state = DefaultState(0L)
    override protected def internStateStrings(s: DefaultState): DefaultState = s
    override protected def shouldRegisterOnTimeManagerAfterMigration(): Boolean = false
    override protected def onMigrationRestore(): Unit = {
      observer ! HookApplied(state.eventsAmount)
      require(!failHook, "controlled restore hook failure")
    }
    override def handleEvent: Receive = {
      case Increment => state.eventsAmount += 1L; sender() ! state.eventsAmount
      case Read => sender() ! state.eventsAmount
      case event => super.handleEvent(event)
    }
  }

  private def target(id: String, store: TestProbe, failHook: Boolean = false): ActorRef = {
    val actor = system.actorOf(Props(new Target(Properties(entityId = id, resourceId = "restore",
      actorType = CreationTypeEnum.LoadBalancedDistributed, creatorManager = store.ref), store.ref, failHook)), id)
    store.send(actor, Read)
    store.expectMsg(10.seconds, 0L)
    actor
  }

  private def context(id: String, store: TestProbe, controller: TestProbe): MigrationContextEvent =
    MigrationContextEvent(scala.collection.mutable.Map.empty, scala.collection.mutable.Map.empty,
      MigrationSnapshot("{\"startTick\":0,\"eventsAmount\":7}", classOf[DefaultState].getName, entityId = id),
      "batch", controller.ref, store.ref, "token", 1L)

  "a migration target" should "confirm only after its restore hook and acknowledge duplicates without applying them again" in {
    val store = TestProbe(); val controller = TestProbe()
    val actor = target("711", store)
    val offered = context("711", store, controller)
    store.send(actor, offered)
    store.expectMsg(HookApplied(7L))
    store.expectMsg(MigrationRestoredAckEvent("711", "batch", "token", 1L))
    store.send(actor, Increment)
    store.expectMsg(8L)
    store.send(actor, offered)
    store.expectMsg(MigrationRestoredAckEvent("711", "batch", "token", 1L))
    store.send(actor, Read)
    store.expectMsg(8L)
    store.send(actor, MigrationRestoreAlreadyAcceptedEvent("711", "batch", "token", 1L))
    store.send(actor, Read)
    store.expectMsg(8L)
    store.expectNoMessage(100.millis)
    controller.expectNoMessage(100.millis)
  }

  it should "stop on a failing restore hook without emitting a success confirmation" in {
    val store = TestProbe(); val controller = TestProbe(); val watcher = TestProbe()
    val actor = target("712", store, failHook = true)
    watcher.watch(actor)
    store.send(actor, context("712", store, controller))
    store.expectMsg(HookApplied(7L))
    store.expectMsgType[MigrationRestoreFailedEvent].reason should include("controlled restore hook failure")
    controller.expectMsgType[MigrationPreparationFailedEvent]
    watcher.expectTerminated(actor)
    store.expectNoMessage(100.millis)
  }

  it should "stop on an invalid snapshot without running the restore hook or confirming application" in {
    val store = TestProbe(); val controller = TestProbe(); val watcher = TestProbe()
    val actor = target("713", store)
    watcher.watch(actor)
    val offered = context("713", store, controller)
    store.send(actor, offered.copy(snapshot = offered.snapshot.copy(stateJson = "invalid JSON")))
    store.expectMsgType[MigrationRestoreFailedEvent]
    controller.expectMsgType[MigrationPreparationFailedEvent]
    watcher.expectTerminated(actor)
    store.expectNoMessage(100.millis)
  }

  it should "reject a completed receipt if this actor has no matching applied restore" in {
    val store = TestProbe(); val watcher = TestProbe()
    val actor = target("714", store)
    watcher.watch(actor)
    store.send(actor, MigrationRestoreAlreadyAcceptedEvent("714", "batch", "token", 1L))
    store.expectMsgType[MigrationRestoreFailedEvent].reason should include("no matching application")
    watcher.expectTerminated(actor)
    store.expectNoMessage(100.millis)
  }
}
