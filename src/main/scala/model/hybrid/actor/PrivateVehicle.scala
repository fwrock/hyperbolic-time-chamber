package org.interscity.htc
package model.hybrid.actor

import core.entity.event.ActorInteractionEvent
import core.types.Tick
import model.hybrid.entity.state.{ DriverAttributes, MovableState }
import model.hybrid.entity.state.enumeration.MovableStatusEnum
import model.hybrid.entity.state.enumeration.MovableStatusEnum.{ Parked, Start }
import model.hybrid.entity.event.data.person.{ ParkVehicleData, StartTripData, TripCompletedData }
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import org.htc.protobuf.core.entity.actor.Identify

/** Common trait for private vehicles (Car, Bicycle, Motorcycle).
  *
  * Private vehicles are ASSETS owned by Person actors. They are:
  *   - Passive (Parked) by default
  *   - Activated by Person via StartTrip message
  *   - Configured with person's DriverAttributes
  *   - Report back with TripCompleted when done
  *
  * This trait provides common behavior for activation/deactivation and driver attribute
  * configuration.
  */
trait PrivateVehicle[T <: MovableState] {
  self: Movable[T] =>

  /** Owner person reference with id and classType (set when vehicle is activated).
    */
  private var ownerPersonRef: Option[Identify] = None

  /** Current trip origin (override state's immutable origin).
    */
  private var tripOrigin: Option[String] = None

  /** Current trip destination (override state's immutable destination).
    */
  private var tripDestination: Option[String] = None

  /** Current trip driver attributes (overrides defaults).
    */
  protected var driverAttributes: DriverAttributes = DriverAttributes()

  /** Track when trip started (for travel time calculation).
    */
  private var tripStartTick: Option[Tick] = None

  /** Track trip start distance (for distance calculation).
    */
  private var tripStartDistance: Double = 0.0

  /** Get current distance from state (abstract method to be implemented by vehicle types).
    */
  protected def getCurrentDistance: Double

  /** Accessor methods for protected BaseActor/SimulationBaseActor members. These must be
    * implemented by the concrete class.
    */
  protected def getVehicleStatus: MovableStatusEnum
  protected def setVehicleStatus(status: MovableStatusEnum): Unit
  protected def getActorCurrentTick: Tick
  protected def getActorShardId: String
  protected def getActorEntityId: String
  protected def scheduleNextTick(nextTick: Option[Tick]): Unit
  protected def sendVehicleMessage(
    entityId: String,
    shardId: String,
    data: AnyRef,
    eventType: String,
    actorType: CreationTypeEnum
  ): Unit
  protected def logVehicleInfo(message: String): Unit
  protected def logVehicleWarn(message: String): Unit
  protected def logVehicleDebug(message: String): Unit

  /** Initialize vehicle in Parked state.
    *
    * Override actSpontaneous in subclasses to call this. Vehicles should NOT auto-start; they wait
    * for StartTrip message.
    */
  protected def initializeAsParked(): Unit = {
    setVehicleStatus(Parked)
    logVehicleInfo(s"${getActorEntityId} initialized in Parked state")
  }

  /** Handle StartTrip message from Person.
    *
    * Activates the vehicle, configures it with driver attributes, and begins the trip.
    */
  protected def handleStartTrip(event: ActorInteractionEvent, data: StartTripData): Unit =
    // Guard: wrap all state access in try-catch, defer if not ready
    try {
      val status = getVehicleStatus
      if (status != Parked) {
        logVehicleWarn(
          s"${getActorEntityId} received StartTrip but is not Parked (status: $status)"
        )
        return
      }

      logVehicleInfo(
        s"${getActorEntityId} activated by ${data.personId}: ${data.origin} -> ${data.destination}"
      )

      // Store owner reference (complete Identify with id + classType)
      ownerPersonRef = Some(
        Identify(
          id = data.personId,
          classType = event.actorClassType // Person's shard/classType from event sender
        )
      )
      tripOrigin = Some(data.origin)
      tripDestination = Some(data.destination)
      driverAttributes = data.driverAttributes.validate()
      tripStartTick = Some(data.startTick)
      tripStartDistance = getCurrentDistance

      // Configure vehicle with driver attributes
      applyDriverAttributes(driverAttributes)

      // Activate vehicle (transition from Parked to Start)
      setVehicleStatus(Start)

      // Register with TimeManager (this will trigger route request)
      // The existing actSpontaneous logic will handle Start status
      scheduleNextTick(Some(getActorCurrentTick + 1))
    } catch {
      case _: NullPointerException =>
        logVehicleDebug(s"${getActorEntityId} state not ready for StartTrip, deferring message")
        context.self ! event
    }

  /** Get current trip origin (for route calculation).
    */
  protected def getTripOrigin: Option[String] = tripOrigin

  /** Get current trip destination (for route calculation).
    */
  protected def getTripDestination: Option[String] = tripDestination

  /** Get current trip start tick (for trip metrics).
    */
  protected def getTripStartTick: Option[Tick] = tripStartTick

  /** Get current driver attributes (for speed factor metrics).
    */
  protected def getDriverAttributes: DriverAttributes = driverAttributes

  /** Handle ParkVehicle message (optional explicit parking).
    */
  protected def handleParkVehicle(event: ActorInteractionEvent, data: ParkVehicleData): Unit = {
    logVehicleInfo(s"${getActorEntityId} parking at ${data.parkingNodeId}")

    // Report trip completion (if trip was active)
    if (getVehicleStatus != Parked) {
      reportTripCompletion("parked_by_person", data.parkingNodeId)
    }

    // Deactivate vehicle
    deactivateVehicle()
  }

  /** Report trip completion back to Person.
    */
  protected def reportTripCompletion(reason: String, finalNode: String): Unit =
    ownerPersonRef.foreach {
      personRef =>
        val travelTime = tripStartTick
          .map(
            start => getActorCurrentTick - start
          )
          .getOrElse(0L)
        val distanceTraveled = getCurrentDistance - tripStartDistance

        // Send TripCompleted message to Person using correct shard
        sendVehicleMessage(
          entityId = personRef.id,
          shardId = personRef.classType, // Use Person's actual shard (from Identify)
          data = TripCompletedData(
            vehicleId = getActorEntityId,
            personId = personRef.id,
            distanceTraveled = distanceTraveled,
            travelTime = travelTime,
            finalNode = finalNode,
            completionTick = getActorCurrentTick,
            completionReason = reason
          ),
          eventType = "TripCompleted",
          actorType = LoadBalancedDistributed
        )

        logVehicleInfo(
          s"${getActorEntityId} reported trip completion to ${personRef.id}: ${distanceTraveled}m in $travelTime ticks"
        )
    }

  /** Deactivate vehicle (return to Parked state).
    *
    * Unregister from TimeManager, clear trip state.
    */
  protected def deactivateVehicle(): Unit = {
    setVehicleStatus(Parked)
    ownerPersonRef = None
    tripStartTick = None
    tripStartDistance = 0.0

    logVehicleInfo(s"${getActorEntityId} deactivated (Parked)")

    // Unregister from TimeManager (vehicle is now passive)
    // This prevents vehicle from receiving spontaneous events
    scheduleNextTick(None) // No next tick scheduled
  }

  /** Apply driver attributes to vehicle physics parameters.
    *
    * Subclasses should override this to apply attributes to their specific state (CarState,
    * BicycleState, etc.).
    */
  protected def applyDriverAttributes(attrs: DriverAttributes): Unit =
    logVehicleDebug(
      s"Applying driver attributes: aggressiveness=${attrs.aggressiveness}, " +
        s"maxSpeedFactor=${attrs.maxSpeedFactor}, reactionTime=${attrs.reactionTime}"
    )

    // Subclasses override to apply to their specific micro state
    // For example:
    // - Update microState.desiredVelocity *= attrs.maxSpeedFactor
    // - Update microState.reactionTime = attrs.reactionTime
    // - Update microState.minGap *= attrs.minGapFactor

  /** Override onFinish to report trip completion.
    */
  protected def onFinishPrivateVehicle(nodeId: String): Unit = {
    val reachedDestination = tripDestination.contains(nodeId)
    val reason = if (reachedDestination) "reached_destination" else "trip_ended"

    reportTripCompletion(reason, nodeId)
    deactivateVehicle()
  }

  /** Check if vehicle is active (not parked).
    */
  protected def isActive: Boolean = getVehicleStatus != Parked

  /** Check if vehicle is parked.
    */
  protected def isParked: Boolean = getVehicleStatus == Parked

  /** Handle private vehicle specific interaction events.
    *
    * Call this from subclass actInteractWith before super.actInteractWith.
    */
  protected def handlePrivateVehicleEvent(event: ActorInteractionEvent): Boolean =
    event.data match {
      case d: StartTripData =>
        handleStartTrip(event, d)
        true
      case d: ParkVehicleData =>
        handleParkVehicle(event, d)
        true
      case d: TripCompletedData =>
        // Should not receive this (we send it)
        logVehicleWarn(s"${getActorEntityId} received TripCompletedData (unexpected)")
        true
      case _ =>
        false // Not a private vehicle event
    }
}
