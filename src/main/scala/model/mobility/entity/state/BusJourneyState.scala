package org.interscity.htc
package model.mobility.entity.state

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
  var sumoTripInfoReported: Boolean = false,
  var mesoRoundingCredit: Double = 0.0,
  var mesoLastExitTick: Long = -1L,
  /** Length of the MESO link the bus is on, kept so a leave that the Link does not answer can still be completed. */
  var mesoLinkLength: Double = 0.0,
  /** Whether the current MESO link will answer this bus's leave; set from the entry reply, so a lost value only restores the reply. */
  var leaveAckRequired: Boolean = true,
  /** The Link took the bus off the link when the Node granted the crossing, so the bus sends no leave. */
  var leaveHandledByLink: Boolean = false,
  /** The Link held the entry reply until the exit tick, so the bus ran its exit chain in fewer wakes than it otherwise would. */
  var linkOwnedExit: Boolean = false
) extends org.interscity.htc.model.mobility.support.car.MesoTimingState
