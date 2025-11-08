package org.interscity.htc
package model.hybrid.micro.manager

import core.actor.BaseActor
import core.types.Tick
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}

import org.interscity.htc.model.hybrid.entity.event.data._
import org.interscity.htc.model.hybrid.entity.state.model.VehicleInLane
import org.interscity.htc.model.hybrid.micro.model.{CarFollowingModel, KraussModel}
import org.interscity.htc.model.hybrid.micro.lane.{LaneChangeModel, MobilLaneChange}

import scala.collection.mutable

/** LinkMicroTimeManager actor.
  * 
  * Manages sub-tick execution for microscopic simulation within a single link.
  * Acts as a local time manager to avoid bottlenecks in the global TimeManager.
  * 
  * Responsibilities:
  * - Execute sub-ticks (e.g., 10 sub-ticks per global tick at 0.1s each)
  * - Apply car-following model to all vehicles in link
  * - Process lane changes
  * - Maintain lane-sorted vehicle lists
  * - Send updates to vehicles
  * 
  * @param linkId Link ID being managed
  * @param numberOfLanes Number of lanes in link
  * @param linkLength Link length (meters)
  * @param microTimeStep Sub-tick duration (seconds)
  * @param ticksPerGlobalTick Sub-ticks per global tick
  * @param carFollowingModel Car-following model to use
  * @param laneChangeModel Lane-change model to use
  */
class LinkMicroTimeManager(
  linkId: String,
  numberOfLanes: Int,
  linkLength: Double,
  microTimeStep: Double,
  ticksPerGlobalTick: Int,
  carFollowingModel: CarFollowingModel,
  laneChangeModel: LaneChangeModel
) {
  
  import LinkMicroTimeManager._
  
  /** Internal state: vehicles organized by lane.
    * Key: lane ID
    * Value: Queue of vehicles (sorted by position, front to back)
    */
  private val vehiclesByLane: mutable.Map[Int, mutable.Queue[VehicleInLane]] =
    mutable.Map((0 until numberOfLanes).map(lane => lane -> mutable.Queue.empty[VehicleInLane]): _*)
  
  /** Vehicle actor references for sending updates.
    * Key: vehicle ID
    * Value: actor reference
    */
  private val vehicleActors: mutable.Map[String, ActorRef[MicroUpdateData]] = mutable.Map.empty
  
  /** Current global tick being executed.
    */
  private var currentGlobalTick: Tick = 0
  
  /** Current sub-tick within global tick.
    */
  private var currentSubTick: Int = 0
  
  def onMessage(msg: Command)(implicit context: ActorContext[Command]): Behavior[Command] = {
    msg match {
      case RegisterVehicle(vehicleId, lane, position, velocity, vehicleLength, actor) =>
        registerVehicle(vehicleId, lane, position, velocity, vehicleLength, actor)
        Behaviors.same
      
      case UnregisterVehicle(vehicleId) =>
        unregisterVehicle(vehicleId)
        Behaviors.same
      
      case ExecuteGlobalTick(globalTick) =>
        executeGlobalTick(globalTick)
        Behaviors.same
      
      case UpdateVehicleState(vehicleId, position, velocity, lane) =>
        updateVehicleState(vehicleId, position, velocity, lane)
        Behaviors.same
      
      case RequestLaneChange(vehicleId, fromLane, toLane) =>
        processLaneChangeRequest(vehicleId, fromLane, toLane)
        Behaviors.same
    }
  }
  
  /** Register vehicle entering link.
    */
  private def registerVehicle(
    vehicleId: String,
    lane: Int,
    position: Double,
    velocity: Double,
    vehicleLength: Double,
    actor: ActorRef[MicroUpdateData]
  )(implicit context: ActorContext[Command]): Unit = {
    context.log.debug(s"[$linkId] Registering vehicle $vehicleId in lane $lane at position $position")
    
    val vehicle = VehicleInLane(vehicleId, shardId = "", position, velocity, acceleration = 0.0, vehicleLength)
    
    vehiclesByLane.get(lane) match {
      case Some(queue) =>
        // Insert in sorted order (front to back by position)
        val insertIndex = queue.indexWhere(_.position < position)
        if (insertIndex >= 0) {
          queue.insert(insertIndex, vehicle)
        } else {
          queue.enqueue(vehicle)
        }
      case None =>
        context.log.error(s"[$linkId] Invalid lane $lane for vehicle $vehicleId")
    }
    
    vehicleActors.put(vehicleId, actor)
  }
  
  /** Unregister vehicle leaving link.
    */
  private def unregisterVehicle(vehicleId: String)(implicit context: ActorContext[Command]): Unit = {
    context.log.debug(s"[$linkId] Unregistering vehicle $vehicleId")
    
    vehiclesByLane.values.foreach { queue =>
      queue.dequeueAll(_.actorId == vehicleId)
    }
    
    vehicleActors.remove(vehicleId)
  }
  
  /** Execute all sub-ticks for a global tick.
    */
  private def executeGlobalTick(globalTick: Tick)(implicit context: ActorContext[Command]): Unit = {
    currentGlobalTick = globalTick
    context.log.debug(s"[$linkId] Executing global tick $globalTick with $ticksPerGlobalTick sub-ticks")
    
    // Execute all sub-ticks
    for (subTick <- 0 until ticksPerGlobalTick) {
      currentSubTick = subTick
      executeSubTick(subTick)
    }
    
    // Notify all vehicles that global tick completed
    vehicleActors.values.foreach { actor =>
      // Send completion signal (would need to define this message)
      context.log.trace(s"[$linkId] Notifying vehicle of tick completion")
    }
    
    context.log.debug(s"[$linkId] Completed global tick $globalTick")
  }
  
  /** Execute a single sub-tick.
    */
  private def executeSubTick(subTick: Int)(implicit context: ActorContext[Command]): Unit = {
    context.log.trace(s"[$linkId] Executing sub-tick $subTick")
    
    // Process each lane
    vehiclesByLane.foreach { case (laneId, vehicles) =>
      processLane(laneId, vehicles, subTick)
    }
    
    // Process lane changes
    // (In full implementation, would evaluate and execute lane changes here)
  }
  
  /** Process all vehicles in a lane for one sub-tick.
    */
  private def processLane(
    laneId: Int,
    vehicles: mutable.Queue[VehicleInLane],
    subTick: Int
  )(implicit context: ActorContext[Command]): Unit = {
    
    if (vehicles.isEmpty) return
    
    // Process from front to back
    for (i <- vehicles.indices) {
      val vehicle = vehicles(i)
      
      // Find leader (vehicle ahead in same lane)
      val leader = if (i > 0) Some(vehicles(i - 1)) else None
      
      // Calculate gap and leader velocity
      val (gap, leaderVelocity) = leader match {
        case Some(l) =>
          val g = l.position - vehicle.position - vehicle.vehicleLength
          (g, l.velocity)
        case None =>
          // No leader: free road
          (linkLength - vehicle.position, vehicle.velocity)
      }
      
      // Apply car-following model (simplified - would need full MicroMovableState)
      val newVelocity = if (gap > 0) {
        // Simple update: move towards leader velocity or maintain speed
        val targetVel = math.min(leader.map(_.velocity).getOrElse(vehicle.velocity + 2.0), 13.89) // 50 km/h max
        val velChange = (targetVel - vehicle.velocity) * 0.5 // Smooth transition
        math.max(0.0, vehicle.velocity + velChange)
      } else {
        math.max(0.0, leaderVelocity - 1.0) // Emergency deceleration
      }
      
      val newPosition = vehicle.position + newVelocity * microTimeStep
      
      // Update vehicle state
      vehicles(i) = vehicle.copy(position = newPosition, velocity = newVelocity)
      
      // Send update to vehicle actor
      vehicleActors.get(vehicle.actorId).foreach { actor =>
        actor ! MicroUpdateData(
          subTick = subTick,
          position = newPosition,
          velocity = newVelocity,
          acceleration = (newVelocity - vehicle.velocity) / microTimeStep,
          currentLane = laneId,
          leaderVehicle = leader.map(_.actorId),
          gapToLeader = gap,
          leaderVelocity = leaderVelocity,
          safeVelocity = newVelocity
        )
      }
      
      // Check if vehicle reached end of link
      if (newPosition >= linkLength) {
        context.log.debug(s"[$linkId] Vehicle ${vehicle.actorId} reached end of link")
        // Would send MicroLeaveLinkData here
      }
    }
  }
  
  /** Update vehicle state from external source.
    */
  private def updateVehicleState(
    vehicleId: String,
    position: Double,
    velocity: Double,
    lane: Int
  )(implicit context: ActorContext[Command]): Unit = {
    
    vehiclesByLane.get(lane).foreach { queue =>
      val index = queue.indexWhere(_.actorId == vehicleId)
      if (index >= 0) {
        val oldVehicle = queue(index)
        queue(index) = oldVehicle.copy(position = position, velocity = velocity)
        context.log.trace(s"[$linkId] Updated vehicle $vehicleId: pos=$position, vel=$velocity")
      }
    }
  }
  
  /** Process lane change request.
    */
  private def processLaneChangeRequest(
    vehicleId: String,
    fromLane: Int,
    toLane: Int
  )(implicit context: ActorContext[Command]): Unit = {
    
    context.log.debug(s"[$linkId] Lane change request: $vehicleId from $fromLane to $toLane")
    
    // Find vehicle in current lane
    vehiclesByLane.get(fromLane).flatMap { fromQueue =>
      fromQueue.find(_.actorId == vehicleId).map { vehicle =>
        
        // Check if target lane is valid
        if (toLane >= 0 && toLane < numberOfLanes) {
          // Remove from current lane
          fromQueue.dequeueAll(_.actorId == vehicleId)
          
          // Add to target lane (maintaining sorted order)
          vehiclesByLane.get(toLane).foreach { toQueue =>
            val insertIndex = toQueue.indexWhere(_.position < vehicle.position)
            if (insertIndex >= 0) {
              toQueue.insert(insertIndex, vehicle)
            } else {
              toQueue.enqueue(vehicle)
            }
          }
          
          context.log.info(s"[$linkId] Vehicle $vehicleId changed from lane $fromLane to $toLane")
        } else {
          context.log.error(s"[$linkId] Invalid target lane $toLane")
        }
      }
    }
  }
}

/** LinkMicroTimeManager companion object.
  */
object LinkMicroTimeManager {
  
  /** Command ADT for LinkMicroTimeManager.
    */
  sealed trait Command
  
  case class RegisterVehicle(
    vehicleId: String,
    lane: Int,
    position: Double,
    velocity: Double,
    vehicleLength: Double,
    actor: ActorRef[MicroUpdateData]
  ) extends Command
  
  case class UnregisterVehicle(vehicleId: String) extends Command
  
  case class ExecuteGlobalTick(globalTick: Tick) extends Command
  
  case class UpdateVehicleState(
    vehicleId: String,
    position: Double,
    velocity: Double,
    lane: Int
  ) extends Command
  
  case class RequestLaneChange(
    vehicleId: String,
    fromLane: Int,
    toLane: Int
  ) extends Command
  
  /** Create behavior for LinkMicroTimeManager.
    */
  def apply(
    linkId: String,
    numberOfLanes: Int,
    linkLength: Double,
    microTimeStep: Double = 0.1,
    ticksPerGlobalTick: Int = 10,
    carFollowingModel: CarFollowingModel = KraussModel(),
    laneChangeModel: LaneChangeModel = MobilLaneChange()
  ): Behavior[Command] = Behaviors.setup { context =>
    context.log.info(s"[$linkId] LinkMicroTimeManager started: $numberOfLanes lanes, ${linkLength}m, ${microTimeStep}s steps")
    
    val manager = new LinkMicroTimeManager(
      linkId,
      numberOfLanes,
      linkLength,
      microTimeStep,
      ticksPerGlobalTick,
      carFollowingModel,
      laneChangeModel
    )
    
    Behaviors.receiveMessage { msg =>
      manager.onMessage(msg)(context)
    }
  }
}
