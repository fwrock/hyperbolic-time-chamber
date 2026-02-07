package org.interscity.htc
package model.hybrid.entity.state.model

import com.fasterxml.jackson.annotation.JsonProperty

/** Dynamic cost information for a link, reflecting real-time traffic conditions.
  * This data is cached in Redis and updated periodically by Link actors.
  * 
  * The total cost combines multiple factors:
  * - Base distance (link length)
  * - Congestion (vehicle density)
  * - Current speed vs free-flow speed
  * - Incidents (accidents, road work, etc.)
  * 
  * @param linkId Unique identifier of the link
  * @param baseCost Base cost (typically link length in meters)
  * @param congestionFactor Congestion multiplier (1.0 = no congestion, >1.0 = congested)
  * @param currentSpeed Current average speed on link (m/s)
  * @param freeFlowSpeed Free-flow speed when uncongested (m/s)
  * @param vehicleCount Number of vehicles currently on link
  * @param capacity Maximum vehicle capacity
  * @param incidentFactor Additional cost due to incidents (0.0 = none, >0.0 = incident)
  * @param lastUpdateTick Simulation tick when this was last updated
  * @param timestamp System time when this was last updated (for debugging)
  */
case class DynamicLinkCost(
  @JsonProperty("linkId") linkId: String,
  @JsonProperty("baseCost") baseCost: Double,
  @JsonProperty("congestionFactor") congestionFactor: Double,
  @JsonProperty("currentSpeed") currentSpeed: Double,
  @JsonProperty("freeFlowSpeed") freeFlowSpeed: Double,
  @JsonProperty("vehicleCount") vehicleCount: Int,
  @JsonProperty("capacity") capacity: Double,
  @JsonProperty("incidentFactor") incidentFactor: Double = 0.0,
  @JsonProperty("lastUpdateTick") lastUpdateTick: Long,
  @JsonProperty("timestamp") timestamp: Long = System.currentTimeMillis()
) {
  
  /** Calculate total dynamic cost for routing.
    * 
    * Formula: baseCost * congestionFactor * speedPenalty + incidentPenalty
    * 
    * - congestionFactor: reflects vehicle density (1.0 to 3.0+)
    * - speedPenalty: ratio of free-flow to current speed (1.0 if at free-flow, higher if slower)
    * - incidentPenalty: additional fixed cost for incidents
    */
  def totalCost: Double = {
    val speedPenalty = if (currentSpeed > 0.0) {
      math.max(1.0, freeFlowSpeed / currentSpeed)
    } else {
      10.0 // Very high penalty if link is stopped
    }
    
    val dynamicCost = baseCost * congestionFactor * speedPenalty
    val incidentPenalty = incidentFactor * baseCost * 2.0 // Incidents double the cost
    
    dynamicCost + incidentPenalty
  }
  
  /** Calculate utilization ratio (0.0 to 1.0+).
    */
  def utilization: Double = {
    if (capacity > 0.0) vehicleCount / capacity
    else 0.0
  }
  
  /** Check if link is congested (utilization > 0.8).
    */
  def isCongested: Boolean = utilization > 0.8
  
  /** Check if link has an incident.
    */
  def hasIncident: Boolean = incidentFactor > 0.0
}

object DynamicLinkCost {
  
  /** Create from link state with default values.
    */
  def fromLinkState(
    linkId: String,
    length: Double,
    currentSpeed: Double,
    freeFlowSpeed: Double,
    vehicleCount: Int,
    capacity: Double,
    congestionFactor: Double,
    tick: Long
  ): DynamicLinkCost = {
    DynamicLinkCost(
      linkId = linkId,
      baseCost = length,
      congestionFactor = congestionFactor,
      currentSpeed = currentSpeed,
      freeFlowSpeed = freeFlowSpeed,
      vehicleCount = vehicleCount,
      capacity = capacity,
      incidentFactor = 0.0,
      lastUpdateTick = tick
    )
  }
}
