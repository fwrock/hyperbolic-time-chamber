package org.interscity.htc
package model.hybrid.entity.event.data

import org.interscity.htc.core.entity.event.data.BaseEventData

/** Data for microscopic step request from vehicle.
  *
  * Sent from vehicle to link requesting micro simulation step. Contains current state for
  * calculations.
  *
  * @param vehicleId
  *   Vehicle requesting step
  * @param currentPosition
  *   Current position
  * @param currentVelocity
  *   Current velocity
  * @param currentLane
  *   Current lane
  * @param desiredVelocity
  *   Desired free-flow velocity
  * @param maxAcceleration
  *   Maximum acceleration capability
  * @param maxDeceleration
  *   Maximum deceleration capability
  * @param minGap
  *   Minimum safe gap
  * @param vehicleLength
  *   Vehicle physical length
  */
case class MicroStepData(
  vehicleId: String,
  currentPosition: Double,
  currentVelocity: Double,
  currentLane: Int,
  desiredVelocity: Double,
  maxAcceleration: Double,
  maxDeceleration: Double,
  minGap: Double,
  vehicleLength: Double
) extends BaseEventData
