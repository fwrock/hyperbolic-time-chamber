package org.interscity.htc
package model.hybrid.micro.strategy

import core.types.Tick
import model.hybrid.entity.state.model.VehicleInLane
import model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum
import model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red
import model.hybrid.micro.model.{ CarFollowingModel, KraussModel }

import scala.collection.mutable

/** Default microscopic simulation strategy using a simplified Krauss-based car-following model.
  *
  * This implementation processes each lane independently, updating vehicle positions and velocities
  * based on gap to leader, speed limits, and safety constraints. Vehicles that reach the end of the
  * link or are stopped accumulate waiting time.
  *
  * Key features:
  *   - Car-following with safe velocity calculation based on gap and leader speed
  *   - Position clamping to prevent vehicles from exceeding link length
  *   - Waiting time tracking for stopped/congested vehicles
  *   - Lane-wise processing with front-to-back ordering
  *
  * @param carFollowingModel
  *   The car-following model to use (default: KraussModel)
  */
class DefaultMicroSimulationStrategy(
  private val carFollowingModel: CarFollowingModel = KraussModel()
) extends MicroSimulationStrategy {

  private var initialized = false
  private var cachedLinkLength: Double = 0.0
  private var cachedSpeedLimit: Double = 0.0
  private var cachedLanes: Int = 0
  private var cachedMicroTimeStep: Double = 0.0

  override def initialize(
    linkLength: Double,
    speedLimit: Double,
    lanes: Int,
    microTimeStep: Double
  ): Unit = {
    this.cachedLinkLength = linkLength
    this.cachedSpeedLimit = speedLimit
    this.cachedLanes = lanes
    this.cachedMicroTimeStep = microTimeStep
    this.initialized = true
  }

  override def executeSubTick(
    vehiclesByLane: mutable.Map[Int, mutable.Queue[VehicleInLane]],
    subTick: Int,
    tick: Tick,
    linkLength: Double,
    speedLimit: Double,
    microTimeStep: Double,
    microTicksPerGlobalTick: Int,
    vehicleWaitingSeconds: mutable.Map[String, Double],
    signalAtExit: Option[TrafficSignalPhaseStateEnum] = None
  ): Seq[MicroVehicleUpdate] = {
    val lastUpdateByVehicle = mutable.LinkedHashMap[String, MicroVehicleUpdate]()
    val nSubTicks = math.max(1, microTicksPerGlobalTick)

    for (st <- 0 until nSubTicks)
      vehiclesByLane.foreach {
        case (laneId, vehicles) =>
          if (vehicles.nonEmpty) {
            val laneUpdates = processMicroLane(
              laneId = laneId,
              vehicles = vehicles,
              subTick = st,
              tick = tick,
              linkLength = linkLength,
              speedLimit = speedLimit,
              microTimeStep = microTimeStep,
              microTicksPerGlobalTick = nSubTicks,
              vehicleWaitingSeconds = vehicleWaitingSeconds,
              signalAtExit = signalAtExit
            )
            laneUpdates.foreach(
              u => lastUpdateByVehicle.put(u.vehicleId, u)
            )
          }
      }

    lastUpdateByVehicle.values.toSeq
  }

  /** Processes a single lane during a sub-tick, updating all vehicles in order.
    *
    * For each vehicle:
    *   1. Identifies leader vehicle and calculates gap 2. Computes target velocity based on leader
    *      and safety constraints 3. Updates velocity with damped acceleration 4. Updates position
    *      (clamped to link length) 5. Tracks waiting time if vehicle is stopped 6. Generates update
    *      message for the vehicle actor
    *
    * @param laneId
    *   Lane identifier
    * @param vehicles
    *   Queue of vehicles in this lane (front to back)
    * @param subTick
    *   Current sub-tick index
    * @param tick
    *   Current global tick
    * @param linkLength
    *   Link length in meters
    * @param speedLimit
    *   Speed limit in km/h
    * @param microTimeStep
    *   Sub-tick duration in seconds
    * @param microTicksPerGlobalTick
    *   Number of sub-ticks per global tick
    * @param vehicleWaitingSeconds
    *   Mutable map tracking waiting time
    * @return
    *   Sequence of vehicle updates to broadcast
    */
  private def processMicroLane(
    laneId: Int,
    vehicles: mutable.Queue[VehicleInLane],
    subTick: Int,
    tick: Tick,
    linkLength: Double,
    speedLimit: Double,
    microTimeStep: Double,
    microTicksPerGlobalTick: Int,
    vehicleWaitingSeconds: mutable.Map[String, Double],
    signalAtExit: Option[TrafficSignalPhaseStateEnum] = None
  ): Seq[MicroVehicleUpdate] = {

    // Virtual leader placed at link exit when Red; IDM naturally decelerates to a stop.
    val signalLeader: Option[VehicleInLane] =
      signalAtExit.collect {
        case Red =>
          VehicleInLane(
            actorId         = "__signal__",
            shardId         = "",
            position        = linkLength,
            velocity        = 0.0,
            acceleration    = 0.0,
            vehicleLength   = 0.0,
            maxAcceleration = 0.0,
            maxDeceleration = 0.0
          )
      }

    val updates = mutable.ArrayBuffer[MicroVehicleUpdate]()

    for (i <- vehicles.indices) {
      val vehicle = vehicles(i)
      val leader = if (i > 0) Some(vehicles(i - 1)) else signalLeader

      val (rawGap, leaderVel) = leader match {
        case Some(l) =>
          (l.position - vehicle.position - vehicle.vehicleLength, l.velocity)
        case None =>
          (linkLength - vehicle.position, speedLimit / 3.6)
      }
      val gap = math.max(0.1, rawGap)

      val maxAcceleration = vehicle.maxAcceleration
      val maxDeceleration = vehicle.maxDeceleration
      val rawSafe = carFollowingModel.calculateSafeVelocity(
        currentVelocity = vehicle.velocity,
        desiredVelocity = speedLimit / 3.6,
        gap = gap,
        leaderVelocity = leaderVel,
        maxAcceleration = maxAcceleration,
        maxDeceleration = maxDeceleration,
        minGap = 2.0,
        reactionTime = 1.0,
        deltaT = microTimeStep
      )
      val safeTargetVel = if (rawSafe.isNaN || rawSafe.isInfinite) 0.0 else rawSafe
      val velDiff = safeTargetVel - vehicle.velocity
      val velChange =
        if (velDiff >= 0)
          math.min(velDiff, maxAcceleration * microTimeStep)
        else
          math.max(velDiff, -maxDeceleration * microTimeStep)
      val rawNewVelocity = vehicle.velocity + velChange
      val cappedVelocity = math.max(
        0.0,
        math.min(
          if (rawNewVelocity.isNaN || rawNewVelocity.isInfinite) 0.0 else rawNewVelocity,
          speedLimit / 3.6
        )
      )

      val rawNewPosition = vehicle.position + cappedVelocity * microTimeStep
      val newPosition = math.min(rawNewPosition, linkLength)

      val actualVelocity = if (newPosition >= linkLength) 0.0 else cappedVelocity
      val newAcceleration = velChange / microTimeStep

      if (actualVelocity < 0.1) {
        vehicleWaitingSeconds.update(
          vehicle.actorId,
          vehicleWaitingSeconds.getOrElse(vehicle.actorId, 0.0) + microTimeStep
        )
      }

      vehicles(i) = vehicle.copy(
        position = newPosition,
        velocity = actualVelocity,
        acceleration = newAcceleration
      )

      if (newPosition >= linkLength || subTick >= microTicksPerGlobalTick - 1) {
        updates += MicroVehicleUpdate(
          vehicleId = vehicle.actorId,
          shardId = vehicle.shardId,
          subTick = subTick,
          position = newPosition,
          velocity = actualVelocity,
          acceleration = newAcceleration,
          currentLane = laneId,
          leaderVehicle = leader.map(_.actorId),
          gapToLeader = gap,
          leaderVelocity = leaderVel,
          safeVelocity = actualVelocity,
          reachedEnd = newPosition >= linkLength
        )
      }
    }

    val ordered = vehicles.sortBy(
      v => -v.position
    )
    vehicles.clear()
    vehicles ++= ordered

    updates.toSeq
  }
}

object DefaultMicroSimulationStrategy {

  /** Creates a default micro simulation strategy with Krauss car-following model.
    */
  def apply(): DefaultMicroSimulationStrategy = new DefaultMicroSimulationStrategy()

  /** Creates a micro simulation strategy with a custom car-following model.
    *
    * @param carFollowingModel
    *   The car-following model to use
    */
  def apply(carFollowingModel: CarFollowingModel): DefaultMicroSimulationStrategy =
    new DefaultMicroSimulationStrategy(carFollowingModel)
}
