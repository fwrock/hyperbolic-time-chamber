package org.interscity.htc
package model.hybrid.entity.state

import core.entity.state.BaseState
import core.types.Tick

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.enumeration.ReportTypeEnum
import org.interscity.htc.model.mobility.entity.state.model.SignalState

import scala.collection.mutable

/** Conflict zone in a microscopic intersection.
  * 
  * Represents an area where vehicle paths may intersect, requiring
  * conflict resolution (e.g., right-of-way rules, signal control).
  * 
  * @param zoneId Unique identifier for the conflict zone
  * @param fromLink Link ID entering the zone
  * @param toLink Link ID exiting the zone
  * @param priority Priority level (higher = more priority)
  * @param occupiedBy Vehicle currently occupying the zone
  */
case class ConflictZone(
  zoneId: String,
  fromLink: String,
  toLink: String,
  priority: Int = 0,
  occupiedBy: Option[String] = None
) {
  def isOccupied: Boolean = occupiedBy.isDefined
  def isFree: Boolean = occupiedBy.isEmpty
}

/** Hybrid node state supporting both MESO and MICRO intersections.
  * 
  * This state extends the mesoscopic NodeState with additional fields
  * for microscopic intersection management. When connected to MICRO links,
  * the node manages conflict zones, collision detection, and micro-level
  * traffic signal coordination.
  * 
  * Key additions for MICRO mode:
  * - hasHybridConnections: Detects if any connected link is MICRO
  * - conflictZones: Manages intersection conflict areas
  * - microIntersectionController: Optional dedicated controller for MICRO logic
  * 
  * @param startTick Simulation start tick
  * @param reporterType Type of reporter
  * @param scheduleOnTimeManager Whether to schedule on time manager
  * @param latitude Node latitude
  * @param longitude Node longitude
  * @param links List of connected link IDs
  * @param connections Map of node connections
  * @param signals Traffic signal states
  * @param busStops Connected bus stops
  * @param subwayStations Connected subway stations
  * @param hasHybridConnections Whether any connected link is MICRO
  * @param conflictZones List of conflict zones for MICRO intersections
  * @param microIntersectionController Optional actor managing micro intersection logic
  */
case class HybridNodeState(
  // ========== Meso fields (inherited from NodeState) ==========
  startTick: Tick,
  reporterType: ReportTypeEnum = null,
  scheduleOnTimeManager: Boolean = true,
  latitude: Double,
  longitude: Double,
  links: List[String],
  connections: mutable.Map[String, Identify] = mutable.Map.empty,
  signals: mutable.Map[String, SignalState] = mutable.Map.empty,
  busStops: mutable.Map[String, Identify] = mutable.Map.empty,
  subwayStations: mutable.Map[String, Identify] = mutable.Map.empty,
  
  // ========== Hybrid fields ==========
  hasHybridConnections: Boolean = false,    // Has any MICRO links?
  conflictZones: List[ConflictZone] = List.empty,
  microIntersectionController: Option[ActorRef] = None
) extends BaseState(
      startTick = startTick,
      reporterType = reporterType,
      scheduleOnTimeManager = scheduleOnTimeManager
    ) {
  
  /** Check if node is managing a MICRO intersection */
  def isMicroIntersection: Boolean = hasHybridConnections
  
  /** Get number of conflict zones */
  def conflictZoneCount: Int = conflictZones.size
  
  /** Check if any conflict zone is occupied */
  def hasConflicts: Boolean = conflictZones.exists(_.isOccupied)
  
  /** Get occupied conflict zones */
  def occupiedZones: List[ConflictZone] = conflictZones.filter(_.isOccupied)
  
  /** Get free conflict zones */
  def freeZones: List[ConflictZone] = conflictZones.filter(_.isFree)
  
  /** Find conflict zone by ID */
  def findConflictZone(zoneId: String): Option[ConflictZone] = {
    conflictZones.find(_.zoneId == zoneId)
  }
  
  /** Update a conflict zone's occupation status */
  def updateConflictZone(zoneId: String, vehicleId: Option[String]): HybridNodeState = {
    val updatedZones = conflictZones.map { zone =>
      if (zone.zoneId == zoneId) zone.copy(occupiedBy = vehicleId)
      else zone
    }
    this.copy(conflictZones = updatedZones)
  }
  
  /** Check if vehicle can enter a conflict zone (not occupied or occupied by self) */
  def canEnterConflictZone(zoneId: String, vehicleId: String): Boolean = {
    findConflictZone(zoneId) match {
      case Some(zone) => zone.isFree || zone.occupiedBy.contains(vehicleId)
      case None => true  // Zone doesn't exist, allow entry
    }
  }
}

object HybridNodeState {
  /** Create a basic hybrid node (MESO-only, backward compatible) */
  def createMeso(
    startTick: Tick,
    latitude: Double,
    longitude: Double,
    links: List[String]
  ): HybridNodeState = {
    HybridNodeState(
      startTick = startTick,
      latitude = latitude,
      longitude = longitude,
      links = links,
      hasHybridConnections = false
    )
  }
  
  /** Create a hybrid node with MICRO intersection capabilities */
  def createMicro(
    startTick: Tick,
    latitude: Double,
    longitude: Double,
    links: List[String],
    conflictZones: List[ConflictZone] = List.empty
  ): HybridNodeState = {
    HybridNodeState(
      startTick = startTick,
      latitude = latitude,
      longitude = longitude,
      links = links,
      hasHybridConnections = true,
      conflictZones = conflictZones
    )
  }
}
