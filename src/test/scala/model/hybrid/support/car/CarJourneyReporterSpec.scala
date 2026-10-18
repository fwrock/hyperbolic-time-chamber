package org.interscity.htc
package model.hybrid.support.car

import model.hybrid.entity.state.{CarState, DriverAttributes}
import model.hybrid.entity.state.enumeration.ActorTypeEnum

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** Regression coverage for the Time Warp report-loss bug found 2026-09-11 (docs/KNOWN_GAPS.md):
  * `journeyFinishedReported`/`sumoTripInfoReported` are actor-local, not `state`, so a rollback
  * restoring `state` never resets them on its own -- without `clearCompletionGuards()` wired into
  * the owning actor's `onRollbackDiscardPending`, a trip's completion report is silently dropped
  * forever once a rollback undoes back to before a prior completion attempt.
  */
class CarJourneyReporterSpec extends AnyWordSpec with Matchers {

  private def newState(): CarState =
    CarState(startTick = 0L, origin = 1L, destination = 2L, actorType = ActorTypeEnum.Car, size = 4.5)

  private def newReporter(reports: mutable.ArrayBuffer[Map[String, Any]]): CarJourneyReporter =
    new CarJourneyReporter(
      reportFn = (data, _) => reports += data,
      entityIdFn = () => "car-1",
      currentTickFn = () => 100L,
      tripOriginFn = () => Some(1L),
      tripDestFn = () => Some(2L),
      tripStartTickFn = () => Some(0L),
      driverAttrsFn = () => DriverAttributes()
    )

  "CarJourneyReporter" should {
    "report journey_completed only once per trip, guarding against genuine duplicate calls" in {
      val reports = mutable.ArrayBuffer.empty[Map[String, Any]]
      val reporter = newReporter(reports)
      val state = newState()

      reporter.finishJourney("reached_destination", 2L, state)
      reporter.finishJourney("reached_destination", 2L, state)

      reports.count(_("event_type") == "journey_completed") shouldBe 1
    }

    "allow a fresh journey_completed report after clearCompletionGuards, as a Time Warp rollback must trigger" in {
      val reports = mutable.ArrayBuffer.empty[Map[String, Any]]
      val reporter = newReporter(reports)
      val state = newState()

      reporter.finishJourney("reached_destination", 2L, state)
      reports.count(_("event_type") == "journey_completed") shouldBe 1

      // Simulates a Time Warp rollback undoing back to before this same completion, then replay
      // reaching the (possibly corrected) completion again.
      reporter.clearCompletionGuards()
      reporter.finishJourney("reached_destination", 2L, state)

      reports.count(_("event_type") == "journey_completed") shouldBe 2
    }

    "not touch accumulated SUMO metrics when clearing completion guards, unlike reset()" in {
      val reports = mutable.ArrayBuffer.empty[Map[String, Any]]
      val reporter = newReporter(reports)

      reporter.sumoDepartTick = Some(42L)
      reporter.sumoWaitingCount = 3

      reporter.clearCompletionGuards()

      reporter.sumoDepartTick shouldBe Some(42L)
      reporter.sumoWaitingCount shouldBe 3
    }
  }
}
