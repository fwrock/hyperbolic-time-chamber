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

  /** True once this vehicle has ever been activated by a Person actor.
    * Once set, it is NEVER cleared — even between trips.
    * A person-centric vehicle must NOT selfDestruct() after completing a trip;
    * it returns to Parked and waits for the next StartTrip from its owner Person.
    */
  private var personCentric: Boolean = false

  /** Whether this vehicle is managed by a Person actor.
    * Determines lifecycle: person-centric → survive between trips; standalone → selfDestruct on finish.
    */
  protected def isPersonCentric: Boolean = personCentric

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

  /** Register this vehicle with the TimeManager pool for the first time.
    * Must use scheduleEvent (TM pool router) instead of onFinishSpontaneous
    * because passive vehicles (scheduleOnTimeManager=false) have null currentTimeManager.
    */
  protected def registerOnTimeManager(tick: Tick): Unit

  /** Initialize vehicle in Parked state.
    *
    * Override actSpontaneous in subclasses to call this. Vehicles should NOT auto-start; they wait
    * for StartTrip message.
    */
  protected def initializeAsParked(): Unit = {
    setVehicleStatus(Parked)
    logVehicleDebug(s"${getActorEntityId} initialized in Parked state")
  }

  /** Hook called at the beginning of each new trip (before activation).
    * Subclasses must override to reset all per-trip variables (metrics, SUMO stats, link tracking).
    * This is critical for person-centric vehicles that serve multiple trips without being destroyed.
    */
  protected def resetTripState(): Unit = {}

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

      // Mark this vehicle as person-centric (permanent — survives between trips)
      personCentric = true

      // Reset all per-trip state so metrics/route tracking start fresh for this trip
      resetTripState()

      logVehicleDebug(
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

      // Register with TimeManager for the first time.
      // Use registerOnTimeManager (routed via TM pool) instead of scheduleNextTick /
      // onFinishSpontaneous, because the latter sends to currentTimeManager which
      // is null for passive vehicles that never received a SpontaneousEvent
      // (i.e., those created with scheduleOnTimeManager = false).
      registerOnTimeManager(getActorCurrentTick + 1)
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
        logVehicleDebug(s"${getActorEntityId} parking at ${data.parkingNodeId}")

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

            logVehicleDebug(
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

        logVehicleDebug(s"${getActorEntityId} deactivated (Parked)")

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

  /** Private vehicles should only re-register on the TM after migration when they are
    * NOT parked.  When parked they are waiting passively for a StartTrip message from
    * Person; re-registering would fire a spurious spontaneous tick which is immediately
    * discarded by the Parked guard in actSpontaneous — wasting a TM slot.
    */
  override protected def shouldRegisterOnTimeManagerAfterMigration(): Boolean = !isParked

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
        logVehicleWarn(s"${getActorEntityId} received TripCompletedData (unexpected)")
        true
      case _ =>
        false // Not a private vehicle event
    }
}
