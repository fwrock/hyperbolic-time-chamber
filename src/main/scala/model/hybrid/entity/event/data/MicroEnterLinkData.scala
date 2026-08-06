package org.interscity.htc
package model.hybrid.entity.event.data

import org.interscity.htc.core.entity.event.data.BaseEventData
import org.interscity.htc.model.hybrid.entity.state.enumeration.SimulationModeEnum

/** Data for entering a link in microscopic mode.
  *
  * Sent from link to vehicle when entering a MICRO link. Contains initial microscopic state
  * information.
  *
  * @param linkId
  *   Link being entered
  * @param mode
  *   Simulation mode (should be MICRO)
  * @param assignedLane
  *   Initial lane assignment
  * @param linkLength
  *   Total link length
  * @param speedLimit
  *   Link speed limit
  * @param numberOfLanes
  *   Total lanes available
  * @param microTimeStep
  *   Duration of sub-tick (seconds)
  * @param ticksPerGlobalTick
  *   Sub-ticks per global tick
  */
case class MicroEnterLinkData(
  linkId: String,
  mode: SimulationModeEnum,
  assignedLane: Int,
  linkLength: Double,
  speedLimit: Double,
  numberOfLanes: Int,
  microTimeStep: Double,
  ticksPerGlobalTick: Int
) extends BaseEventData
