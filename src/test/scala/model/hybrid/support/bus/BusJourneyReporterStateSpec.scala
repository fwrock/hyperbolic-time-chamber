package org.interscity.htc
package model.hybrid.support.bus

import core.util.JsonUtil
import model.hybrid.entity.state.{BusJourneyState, BusState}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

class BusJourneyReporterStateSpec extends AnyWordSpec with Matchers {
  "Bus journey reporting" should {
    "follow restored state and preserve completion guards from the surviving prefix" in {
      var state = BusState(startTick = 0L, label = "line", capacity = 40,
        busStops = Map(1L -> 2L), numberOfPorts = 2, origin = 1L, destination = 2L, size = 12.0)
      state.distance = 200.0
      state.journey = BusJourneyState(sumoDepartTick = Some(5L), sumoDepartSpeed = 4.0,
        sumoArrivalSpeed = 7.0, sumoDepartLane = Some("1_0"), sumoDepartPos = 3.0,
        sumoArrivalLane = Some("2_0"), sumoArrivalPos = 9.0, sumoWaitingTimeSeconds = 6.0,
        sumoWaitingCount = 2, sumoStopTimeSeconds = 8.0, sumoIdealTravelTimeSeconds = 20.0,
        sumoCurrentMicroTimeStepSeconds = 0.5, sumoIsHalting = true, sumoRerouteNo = 1)
      val reports = mutable.ArrayBuffer.empty[Map[String, Any]]
      val reporter = new BusJourneyReporter((data, _) => reports += data, () => "bus-1",
        () => 100L, () => state.journey)
      val before = JsonUtil.toJson(state)
      val expected = state.journey.copy()
      reporter.updateHaltingState(0.0, 30.0)
      reporter.finishJourney("reached_destination", 2L, state)

      state = JsonUtil.fromJson[BusState](before)
      state.journey shouldBe expected
      reporter.sumoWaitingTimeSeconds shouldBe 6.0
      reporter.finishJourney("reached_destination", 2L, state)
      reports.count(_("event_type") == "journey_completed") shouldBe 2
      val restoredReport = reports.filter(_("event_type") == "sumo_tripinfo").last
      restoredReport("waitingTime") shouldBe 6.0
      restoredReport("routeLength") shouldBe 200.0

      val completed = JsonUtil.toJson(state)
      reporter.reset()
      state = JsonUtil.fromJson[BusState](completed)
      reporter.finishJourney("reached_destination", 2L, state)
      reports.count(_("event_type") == "journey_completed") shouldBe 2
      reports.count(_("event_type") == "sumo_tripinfo") shouldBe 2
    }
  }
}
