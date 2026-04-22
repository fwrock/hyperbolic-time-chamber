package org.interscity.htc
package model.hybrid.entity.state.model

import model.hybrid.entity.state.enumeration.LaneTypeEnum

/** Configuration for a single lane in a microscopic link.
  *
  * @param laneId
  *   Unique identifier for the lane within the link (0-indexed from left)
  * @param laneType
  *   Type of lane (NORMAL, BUS_LANE, BIKE_LANE, etc.)
  * @param speedLimit
  *   Optional speed limit specific to this lane (m/s)
  * @param width
  *   Lane width in meters (default 3.5m for normal lanes)
  */
case class LaneConfig(
  laneId: Int,
  laneType: LaneTypeEnum = LaneTypeEnum.NORMAL,
  speedLimit: Option[Double] = None,
  width: Double = 3.5
)
