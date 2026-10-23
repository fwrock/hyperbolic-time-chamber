package org.interscity.htc
package model.hybrid.support.car

import model.hybrid.entity.state.{CarJourneyState, CarState, DriverAttributes}
import core.util.JsonUtil
import model.hybrid.entity.state.enumeration.ActorTypeEnum

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** Completion and SUMO inputs must describe the same state prefix after checkpoint restore. */
class CarJourneyReporterSpec extends AnyWordSpec with Matchers {

  private def newState(): CarState =
    CarState(startTick = 0L, origin = 1L, destination = 2L, actorType = ActorTypeEnum.Car, size = 4.5)

  private def newReporter(
    reports: mutable.ArrayBuffer[Map[String, Any]],
    journeyState: CarJourneyState = CarJourneyState(),
    tripStartDistanceFn: () => Double = () => 0.0
  ): CarJourneyReporter =
    new CarJourneyReporter(
      reportFn = (data, _) => reports += data,
      entityIdFn = () => "car-1",
      currentTickFn = () => 100L,
      tripOriginFn = () => Some(1L),
      tripDestFn = () => Some(2L),
      tripStartTickFn = () => Some(0L),
      driverAttrsFn = () => DriverAttributes(),
      journeyStateFn = () => journeyState,
      tripStartDistanceFn = tripStartDistanceFn
    )

  "CarJourneyReporter" should {
    "report SUMO route length per trip while preserving the lifetime odometer" in {
      val reports = mutable.ArrayBuffer.empty[Map[String, Any]]
      val state = newState()
      var tripStartDistance = 0.0
      val reporter = newReporter(reports, state.journey, () => tripStartDistance)
      state.distance = 200.0
      reporter.finishJourney("reached_destination", 2L, state)
      reporter.reset()
      tripStartDistance = state.distance
      state.distance = 400.0
      reporter.finishJourney("reached_destination", 2L, state)

      reports.filter(_("event_type") == "sumo_tripinfo").map(_("routeLength")).toList shouldBe List(200.0, 200.0)
      state.distance shouldBe 400.0
    }

    "report journey_completed only once per trip, guarding against genuine duplicate calls" in {
      val reports = mutable.ArrayBuffer.empty[Map[String, Any]]
      val reporter = newReporter(reports)
      val state = newState()

      reporter.finishJourney("reached_destination", 2L, state)
      reporter.finishJourney("reached_destination", 2L, state)

      reports.count(_("event_type") == "journey_completed") shouldBe 1
    }

    "allow a fresh journey_completed report after explicitly clearing completion guards" in {
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

    "restore completion guards and physical/reporting inputs from the exact JSON prefix" in {
      val reports = mutable.ArrayBuffer.empty[Map[String, Any]]
      val journey = CarJourneyState(sumoArrivalSpeed = 7.0, sumoWaitingTimeSeconds = 3.0,
        sumoDepartLane = Some("42_0"), sumoIdealTravelTimeSeconds = 20.0)
      val beforeCompletion = JsonUtil.toJson(journey)
      newReporter(reports, journey).finishJourney("reached_destination", 2L, newState())
      val afterCompletion = JsonUtil.toJson(journey)

      newReporter(reports, JsonUtil.fromJson[CarJourneyState](afterCompletion))
        .finishJourney("reached_destination", 2L, newState())
      reports.count(_("event_type") == "journey_completed") shouldBe 1

      newReporter(reports, JsonUtil.fromJson[CarJourneyState](beforeCompletion))
        .finishJourney("reached_destination", 2L, newState())
      reports.count(_("event_type") == "journey_completed") shouldBe 2
      val tripInfo = reports.filter(_("event_type") == "sumo_tripinfo")
      tripInfo.map(_("arrivalSpeed")).toList shouldBe List(7.0, 7.0)
      tripInfo.map(_("waitingTime")).toList shouldBe List(3.0, 3.0)
    }
  }
}
