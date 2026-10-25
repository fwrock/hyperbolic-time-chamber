package org.interscity.htc
package model.hybrid.actor

import core.actor.manager.loadbalance.migration.MigrationSnapshot
import core.entity.actor.properties.Properties
import core.enumeration.CreationTypeEnum
import core.util.JsonUtil
import model.hybrid.entity.state.PersonState
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{ TestActorRef, TestProbe }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

class PersonScheduleCompleteGuardSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("PersonScheduleCompleteGuardSpec",
    ConfigFactory.parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off").withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }
  private class TestPerson(properties: Properties) extends Person(properties) {
    def ready: Boolean = recoveryFinished
    def init(): Unit = state = PersonState()
    def guard: Boolean = scheduleCompleteReported
    def setGuard(value: Boolean): Unit = scheduleCompleteReported = value
    def truncate(): Unit = state.scheduleAlreadyTruncated = true
    def snapshot: MigrationSnapshot = buildMigrationSnapshot()
    def restore(value: MigrationSnapshot): Unit = applyMigrationSnapshot(value)
    def discardPending(): Unit = onRollbackDiscardPending(100L)
  }
  private def person(): TestPerson = {
    val ref = TestActorRef(new TestPerson(Properties(entityId = "1", actorType = CreationTypeEnum.PoolDistributed)))
    TestProbe().awaitAssert(ref.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
    ref.underlyingActor.init()
    ref.underlyingActor
  }
  "Person completion state" should "restore guards from the exact checkpoint prefix" in {
    val actor = person()
    val before = actor.snapshot
    actor.setGuard(true)
    actor.truncate()
    val after = JsonUtil.fromJson[MigrationSnapshot](JsonUtil.toJson(actor.snapshot))
    actor.restore(before)
    actor.guard shouldBe false
    actor.restore(after)
    actor.guard shouldBe true
    actor.discardPending()
    actor.guard shouldBe true
    val state = JsonUtil.fromJson[PersonState](actor.snapshot.stateJson)
    state.scheduleAlreadyTruncated shouldBe true
  }
}
