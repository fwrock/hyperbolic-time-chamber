package org.interscity.htc
package model.hybrid.entity.state

import core.types.Tick

// Completion guards and cumulative SUMO counters must rewind with the vehicle.
case class BusJourneyState(
  var journeyFinishedReported: Boolean = false,
  var sumoDepartTick: Option[Tick] = None,
  var sumoDepartSpeed: Double = 0.0,
  var sumoArrivalSpeed: Double = 0.0,
  var sumoDepartLane: Option[String] = None,
  var sumoDepartPos: Double = 0.0,
  var sumoArrivalLane: Option[String] = None,
  var sumoArrivalPos: Double = 0.0,
  var sumoWaitingTimeSeconds: Double = 0.0,
  var sumoWaitingCount: Int = 0,
  var sumoStopTimeSeconds: Double = 0.0,
  var sumoIdealTravelTimeSeconds: Double = 0.0,
  var sumoCurrentMicroTimeStepSeconds: Double = 1.0,
  var sumoIsHalting: Boolean = false,
  var sumoRerouteNo: Int = 0,
  var sumoTripInfoReported: Boolean = false
)
