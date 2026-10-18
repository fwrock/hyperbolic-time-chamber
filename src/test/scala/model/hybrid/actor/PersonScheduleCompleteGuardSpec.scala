package org.interscity.htc
package model.hybrid.actor

import core.entity.actor.properties.Properties
import core.types.Tick

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestActorRef
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.compiletime.uninitialized

/** Regression coverage for the Time Warp report-duplication bug found 2026-09-11
  * (docs/KNOWN_GAPS.md, "Report loss/duplication", Cause 2): `Person.dispatch`'s
  * `PlanStepResult.Finished` branch called `metricsReporter.reportScheduleComplete` with no
  * idempotency guard at all, so every replay pass through that branch (routine under heavy Time
  * Warp rollback contention) re-reported the same schedule completion -- confirmed live as 3-4
  * duplicate `person_schedule_complete` rows for the same entity, all at the same tick.
  *
  * `dispatch` itself is private, so this drives the new `scheduleCompleteReported` guard and its
  * `onRollbackDiscardPending` reset directly (both `protected`, same test-accessor pattern as
  * `Car.scala`'s `currentLinkId` etc.) rather than the full plan-completion path -- the guard
  * check/set is a one-line pattern already proven correct by `CarJourneyReporterSpec`; what's new
  * and worth its own test here is the reset wiring.
  */
class PersonScheduleCompleteGuardSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var _system: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit =
    _system = ActorSystem(
      "PersonScheduleCompleteGuardSpec",
      ConfigFactory
        .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
        .withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit = {
    _system.terminate()
    ()
  }

  private class TestPerson(properties: Properties) extends Person(properties) {
    def testGuard: Boolean = scheduleCompleteReported
    def testSetGuard(v: Boolean): Unit = scheduleCompleteReported = v
    def testOnRollbackDiscardPending(targetTick: Tick): Unit = onRollbackDiscardPending(targetTick)
  }

  private var nextEntitySuffix = 0

  private def newTestPerson(): TestPerson = {
    nextEntitySuffix += 1
    TestActorRef(
      new TestPerson(Properties(entityId = "person-1", defaultTimeManagerType = "time-warp")),
      s"person-guard-test-$nextEntitySuffix"
    ).underlyingActor
  }

  "Person" should "start with the schedule-complete guard clear" in {
    newTestPerson().testGuard shouldBe false
  }

  it should "clear the schedule-complete guard on a Time Warp rollback, so a replayed completion can be reported again" in {
    val person = newTestPerson()
    person.testSetGuard(true)

    person.testOnRollbackDiscardPending(100L)

    person.testGuard shouldBe false
  }

  it should "be a safe no-op to clear an already-clear guard" in {
    val person = newTestPerson()

    person.testOnRollbackDiscardPending(100L)

    person.testGuard shouldBe false
  }
}
