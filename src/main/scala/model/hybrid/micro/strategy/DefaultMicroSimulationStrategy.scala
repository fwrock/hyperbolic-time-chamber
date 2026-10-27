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
  import DefaultMicroSimulationStrategy.SignalBlockerId

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
    vehicleWaitingSeconds: mutable.Map[Long, Double],
    signalAtExit: Option[TrafficSignalPhaseStateEnum] = None,
    downstreamLeaders: Map[Long, VehicleInLane] = Map.empty,
    stopLineGapM: Double = 0.0,
    retainExits: Boolean = false
  ): Seq[MicroVehicleUpdate] = {
    val lastUpdateByVehicle = mutable.LinkedHashMap[Long, MicroVehicleUpdate]()
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
              signalAtExit = signalAtExit,
              downstreamLeaders = downstreamLeaders,
              stopLineGapM = stopLineGapM,
              retainExits = retainExits
            )
            laneUpdates.foreach(
              u => lastUpdateByVehicle.put(u.vehicleId, u)
            )
            val departed = laneUpdates.iterator.filter(_.reachedEnd).map(_.vehicleId).toSet
            if (departed.nonEmpty && !retainExits) vehicles.filterInPlace(v => !departed.contains(v.actorId))
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
    vehicleWaitingSeconds: mutable.Map[Long, Double],
    signalAtExit: Option[TrafficSignalPhaseStateEnum] = None,
    downstreamLeaders: Map[Long, VehicleInLane] = Map.empty,
    stopLineGapM: Double = 0.0,
    retainExits: Boolean = false
  ): Seq[MicroVehicleUpdate] = {
    VehicleInLane.sortFrontToBack(vehicles)

    // Only a red signal supplies a stopped leader; an uncontrolled exit must preserve momentum.
    val signalLeader: Option[VehicleInLane] =
      signalAtExit.collect {
        case Red =>
          VehicleInLane(
            actorId         = SignalBlockerId,
            shardId         = "",
            // A signal is a stop line, so it has no vehicle-to-vehicle standstill gap.
            position        = linkLength - stopLineGapM + 2.0,
            velocity        = 0.0,
            acceleration    = 0.0,
            vehicleLength   = 0.0,
            maxAcceleration = 0.0,
            maxDeceleration = 0.0
          )
      }

    val updates = mutable.ArrayBuffer[MicroVehicleUpdate]()
    val previous = vehicles.toVector

    def safeBehind(vehicle: VehicleInLane, leader: VehicleInLane): Double =
      carFollowingModel.calculateSafeVelocity(
        currentVelocity = vehicle.velocity,
        desiredVelocity = speedLimit / 3.6,
        gap = math.max(0.1, vehicle.gapTo(leader)),
        leaderVelocity = leader.velocity,
        maxAcceleration = vehicle.maxAcceleration,
        maxDeceleration = vehicle.maxDeceleration,
        minGap = 2.0,
        reactionTime = 1.0,
        deltaT = microTimeStep
      )

    for (i <- vehicles.indices) {
      val vehicle = previous(i)
      if (vehicle.pendingExitTick.nonEmpty) {
        vehicleWaitingSeconds.update(vehicle.actorId,
          vehicleWaitingSeconds.getOrElse(vehicle.actorId, 0.0) + microTimeStep)
      } else if (vehicle.entryTick < tick) {
        val leader = if (i > 0) Some(previous(i - 1))
          // A nearer moving vehicle must not hide the stricter stopped signal constraint.
          else (signalLeader.toList ++ downstreamLeaders.get(vehicle.actorId))
            .minByOption(v => (safeBehind(vehicle, v), v.rearPosition, v.actorId))

        val (rawGap, leaderVel) = leader match {
          case Some(l) =>
            (vehicle.gapTo(l), l.velocity)
          case None =>
            (linkLength - vehicle.position, speedLimit / 3.6)
        }
        val gap = math.max(0.1, rawGap)

        val maxAcceleration = vehicle.maxAcceleration
        val maxDeceleration = vehicle.maxDeceleration
        val rawSafe = leader.fold(speedLimit / 3.6)(safeBehind(vehicle, _))
        val turnSafe = vehicle.exitSpeedLimitMps.map { limit =>
          val brakingStep = maxDeceleration * microTimeStep
          // Include this step's travel before the remaining braking distance to avoid late turn braking.
          math.max(limit, -brakingStep + math.sqrt(brakingStep * brakingStep + limit * limit +
            2.0 * maxDeceleration * math.max(0.0, linkLength - vehicle.position)))
        }.getOrElse(Double.PositiveInfinity)
        val safeTargetVel = if (rawSafe.isNaN || rawSafe.isInfinite) 0.0 else math.min(rawSafe, turnSafe)
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
        val boundaryPosition = if (math.abs(rawNewPosition - linkLength) < 1e-10) linkLength
          else math.min(rawNewPosition, linkLength)
        // A count-based admission stop can remove the leader's speed within one step.
        // Preserve geometric spacing even when bounded car-following deceleration cannot do so.
        val positionLimit = if (retainExits) {
          val obstacles = if (i > 0) List(previous(i - 1))
            else signalLeader.toList ++ downstreamLeaders.get(vehicle.actorId)
          obstacles.map(_.rearPosition - 2.0).minOption
            .fold(linkLength)(limit => math.max(vehicle.position, limit))
        } else linkLength
        val newPosition = math.min(boundaryPosition, positionLimit)

        val exitBlocked = signalAtExit.contains(Red)
        val reachedEnd = newPosition >= linkLength && !exitBlocked
        val actualVelocity = if (newPosition >= linkLength && exitBlocked) 0.0
          else if (newPosition + 1e-10 < boundaryPosition)
            math.max(0.0, (newPosition - vehicle.position) / microTimeStep)
          else cappedVelocity
        val newAcceleration = if (retainExits) (actualVelocity - vehicle.velocity) / microTimeStep
          else velChange / microTimeStep

        if (actualVelocity < 0.1) {
          vehicleWaitingSeconds.update(
            vehicle.actorId,
            vehicleWaitingSeconds.getOrElse(vehicle.actorId, 0.0) + microTimeStep
          )
        }

        vehicles(i) = vehicle.copy(
          position = newPosition,
          // Admission may outlive this tick. Keep its rear bumper visible until LeaveLink cleanup.
          velocity = if (retainExits && reachedEnd) 0.0 else actualVelocity,
          acceleration = if (retainExits && reachedEnd) 0.0 else newAcceleration,
          pendingExitTick = Option.when(retainExits && reachedEnd)(tick)
        )

        if (reachedEnd || subTick >= microTicksPerGlobalTick - 1) {
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
            reachedEnd = reachedEnd,
            remainingStepSeconds = if (reachedEnd && cappedVelocity > 0.0)
              math.max(0.0, math.min(microTimeStep, (rawNewPosition - linkLength) / cappedVelocity))
            else 0.0
          )
        }
      }
    }

    VehicleInLane.sortFrontToBack(vehicles)

    updates.toSeq
  }
}

object DefaultMicroSimulationStrategy {

  /** Sentinel `VehicleInLane.actorId` for the virtual signal-blocker leader placed at the link
    * exit on Red (see `processMicroLane`) -- never a real vehicle id, so kept out-of-band as a
    * named negative constant rather than a magic literal (real vehicle ids are non-negative,
    * generated by `IdUtil`/the load pipeline).
    */
  val SignalBlockerId: Long = -1L

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
