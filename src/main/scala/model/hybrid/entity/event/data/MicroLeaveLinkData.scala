package org.interscity.htc
package model.hybrid.entity.event.data

import org.interscity.htc.core.entity.event.data.BaseEventData

/** Data for leaving a link from microscopic mode.
  *
  * Sent from link to vehicle when exiting a MICRO link. Contains final microscopic statistics.
  *
  * @param linkId
  *   Link being exited
  * @param finalPosition
  *   Position at exit (should be ≈ linkLength)
  * @param finalVelocity
  *   Velocity at exit
  * @param travelTime
  *   Total time spent in link (seconds)
  * @param distanceTraveled
  *   Total distance (for verification)
  * @param averageSpeed
  *   Average speed during traversal
  * @param waitingTimeSeconds
  *   Total time spent halting (velocity < 0.1 m/s) in seconds
  */
case class MicroLeaveLinkData(
  linkId: String,
  finalPosition: Double,
  finalVelocity: Double,
  travelTime: Double,
  distanceTraveled: Double,
  averageSpeed: Double,
  waitingTimeSeconds: Double = 0.0
) extends BaseEventData
