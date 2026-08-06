package org.interscity.htc
package model.hybrid.entity.event.data.person

import core.entity.event.data.BaseEventData
import core.types.Tick
import model.hybrid.entity.state.DriverAttributes

/** Message from Person to Vehicle to start a trip.
  *
  * This activates a passive vehicle asset and configures it with the person's driving attributes.
  *
  * @param personId
  *   ID of the person starting the trip
  * @param origin
  *   Starting node ID
  * @param destination
  *   Destination node ID
  * @param driverAttributes
  *   Person's driving characteristics
  * @param startTick
  *   Tick when trip starts
  */
case class StartTripData(
  personId: String,
  origin: String,
  destination: String,
  driverAttributes: DriverAttributes,
  startTick: Tick,
  precomputedRoute: Option[List[(String, String)]] = None  // route pre-computed by ModeChoiceStrategy; avoids double A*
) extends BaseEventData
