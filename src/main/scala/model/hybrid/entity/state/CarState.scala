package org.interscity.htc
package model.hybrid.entity.state

import core.types.Tick

import org.interscity.htc.core.enumeration.ReportTypeEnum
import org.interscity.htc.model.hybrid.entity.state.enumeration.{ ActorTypeEnum, MovableStatusEnum }
import org.interscity.htc.model.hybrid.entity.state.enumeration.SimulationModeEnum
import org.interscity.htc.model.hybrid.entity.state.model.PrecomputedRouteItem

import scala.collection.mutable

/** Hybrid car state supporting both MESO and MICRO simulation modes.
  *
  * This state wraps the traditional CarState and adds:
  *   - currentSimulationMode: Tracks whether car is currently in MESO or MICRO
  *   - microState: Optional microscopic state activated when entering MICRO links
  *
  * Mode transitions:
  *   1. Car starts in MESO mode (default) 2. When entering a MICRO link, microState is initialized
  *      and mode switches to MICRO 3. When leaving a MICRO link, microState is deactivated and mode
  *      returns to MESO
  *
  * @param startTick
  *   Simulation start tick
  * @param reporterType
  *   Type of reporter
  * @param scheduleOnTimeManager
  *   Whether to schedule on time manager
  * @param origin
  *   Origin node ID
  * @param destination
  *   Destination node ID
  * @param bestRoute
  *   Best route path
  * @param bestCost
  *   Best route cost
  * @param currentNode
  *   Current node ID
  * @param currentPath
  *   Current path segment
  * @param distance
  *   Total distance traveled
  * @param actorType
  *   Actor type enum
  * @param size
  *   Vehicle size
  * @param status
  *   Current status
  * @param currentSimulationMode
  *   Current mode (MESO or MICRO)
  * @param microState
  *   Optional microscopic state (active in MICRO links)
  * @param microDepartSpeed
  *   Optional initial speed in m/s, used until the first accepted departure. Link admission
  *   still applies physical limits and queue stopping; subsequent entries carry exit speed.
  * @param microDepartLane
  *   Optional zero-based origin-road lane preference, used only before first departure.
  *   Junction connectors and subsequent roads retain the Node-selected lane contract.
  */
case class CarState(
  override val startTick: Tick,
  override val reporterType: ReportTypeEnum = null,
  override val scheduleOnTimeManager: Boolean = true,
  override val origin: Long,
  override val destination: Long = 0L,
  var precomputedRoute: Option[List[PrecomputedRouteItem]] = None,
  var distance: Double = 0,
  override val actorType: ActorTypeEnum,
  override val size: Double,
  var currentSimulationMode: SimulationModeEnum = SimulationModeEnum.MESO,
  var microState: Option[MicroCarState] = None,
  var journey: CarJourneyState = CarJourneyState(),
  microAcceleration: Option[Double] = None,
  microDeceleration: Option[Double] = None,
  microDepartPosition: Double = 0.0,
  microArrivalPosition: Option[Double] = None,
  microMinGap: Option[Double] = None,
  microReactionTime: Option[Double] = None,
  microMaxSpeed: Option[Double] = None,
  microSpeedFactor: Double = 1.0,
  microDesiredMaxSpeed: Option[Double] = None,
  microSigma: Double = 0.0,
  microRandomSeed: Long = 0L,
  microLc2013: model.Lc2013Parameters = model.Lc2013Parameters(),
  mesoOriginLinkId: Option[Long] = None,
  @com.fasterxml.jackson.databind.annotation.JsonDeserialize(contentAs = classOf[java.lang.Double])
  microDepartSpeed: Option[Double] = None,
  @com.fasterxml.jackson.databind.annotation.JsonDeserialize(contentAs = classOf[java.lang.Integer])
  microDepartLane: Option[Int] = None
) extends MovableState(
      startTick = startTick,
      reporterType = reporterType,
      scheduleOnTimeManager = scheduleOnTimeManager,
      origin = origin,
      destination = destination,
      actorType = actorType,
      size = size
    ) {

  require(microAcceleration.forall(v => v.isFinite && v > 0), "invalid car MICRO acceleration")
  require(microDeceleration.forall(v => v.isFinite && v > 0), "invalid car MICRO deceleration")
  require(microDepartPosition.isFinite && microDepartPosition >= 0, "invalid car departure position")
  require(microArrivalPosition.forall(v => v.isFinite && v >= 0), "invalid car arrival position")
  require(microMinGap.forall(v => v.isFinite && v >= 0), "invalid car MICRO minimum gap")
  require(microReactionTime.forall(v => v.isFinite && v > 0), "invalid car MICRO reaction time")
  require(microMaxSpeed.forall(v => v.isFinite && v > 0), "invalid car MICRO maximum speed")
  require(microSpeedFactor.isFinite && microSpeedFactor > 0, "invalid car MICRO speed factor")
  require(microDesiredMaxSpeed.forall(v => v.isFinite && v > 0), "invalid car MICRO desired maximum speed")
  require(microSigma.isFinite && microSigma >= 0 && microSigma <= 1, "invalid car MICRO sigma")
  require(microLc2013 != null, "missing LC2013 parameters")
  require(microDepartSpeed.forall(v => v.isFinite && v >= 0), "invalid car MICRO departure speed")
  require(microDepartLane.forall(_ >= 0), "invalid car MICRO departure lane")

  def bestRoute: Option[mutable.Queue[(Long, Long)]] = movableBestRoute
  def bestRoute_=(v: Option[mutable.Queue[(Long, Long)]]): Unit = movableBestRoute = v

  def bestCost: Double = movableBestCost
  def bestCost_=(v: Double): Unit = movableBestCost = v

  def currentNode: Long = movableCurrentNode
  def currentNode_=(v: Long): Unit = movableCurrentNode = v

  def currentPath: Option[(Long, Long)] = movableCurrentPath
  def currentPath_=(v: Option[(Long, Long)]): Unit = movableCurrentPath = v

  def status: MovableStatusEnum = movableStatus
  def status_=(v: MovableStatusEnum): Unit = movableStatus = v

  /** Check if car is currently in MICRO mode */
  def isMicroMode: Boolean = currentSimulationMode == SimulationModeEnum.MICRO

  /** Check if car is currently in MESO mode */
  def isMesoMode: Boolean = currentSimulationMode == SimulationModeEnum.MESO

  /** Activate MICRO mode with initial micro state */
  def activateMicroMode(initialMicroState: MicroCarState): Unit = {
    currentSimulationMode = SimulationModeEnum.MICRO
    microState = Some(initialMicroState)
  }

  /** Deactivate MICRO mode and return to MESO */
  def deactivateMicroMode(): Unit = {
    currentSimulationMode = SimulationModeEnum.MESO
    microState = None
  }

  /** Update micro state (only valid in MICRO mode) */
  def updateMicroState(newMicroState: MicroCarState): Unit =
    if (isMicroMode) {
      microState = Some(newMicroState)
    }

  /** Get current position (works in both modes) */
  def getCurrentPosition: Option[Double] =
    if (isMicroMode) microState.map(_.positionInLink)
    else None // In MESO mode, position is not tracked continuously

  /** Get current velocity (works in both modes) */
  def getCurrentVelocity: Option[Double] =
    if (isMicroMode) microState.map(_.velocity)
    else None
}
