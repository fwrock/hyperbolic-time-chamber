package org.interscity.htc
package model.hybrid.actor

import core.actor.SimulationBaseActor
import core.types.Tick
import core.entity.event.SpontaneousEvent

import org.interscity.htc.core.entity.event.ActorInteractionEvent
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.control.load.InitializeEvent
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import org.interscity.htc.core.util.IdentifyUtil
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.control.execution.DestructEvent
import core.entity.event.EntityEnvelopeEvent
import core.util.{IdUtil, StringUtil}
import org.interscity.htc.model.hybrid.entity.state.LinkState
import org.interscity.htc.model.hybrid.entity.state.enumeration.SimulationModeEnum
import org.interscity.htc.model.hybrid.entity.event.data.*
import org.interscity.htc.model.hybrid.micro.model.{CarFollowingModel, KraussModel}
import org.interscity.htc.model.hybrid.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.hybrid.entity.state.model.{DynamicLinkCost, LinkRegister, VehicleInLane}
import org.interscity.htc.model.hybrid.entity.event.data.*
import org.interscity.htc.model.hybrid.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.hybrid.util.DynamicWeightCache
import org.interscity.htc.model.hybrid.micro.strategy.{MicroSimulationStrategy, DefaultMicroSimulationStrategy, LaneChangeStrategy, NoLaneChangeStrategy}

import scala.collection.mutable

/**
 * Hybrid link actor representing a road segment that can operate in MESO or MICRO simulation mode.
 *
 * <h2>Overview</h2>
 * The Link actor manages vehicle flow through a road segment, supporting both:
 * <ul>
 * <li><b>MESO mode</b>: Aggregate flow calculations using speed-density relationships</li>
 * <li><b>MICRO mode</b>: Individual vehicle dynamics with car-following and lane management</li>
 * </ul>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 * <li>Vehicle entry/exit management</li>
 * <li>Mode-specific traffic simulation (delegated to strategies)</li>
 * <li>Dynamic cost calculation for routing</li>
 * <li>Performance metrics and reporting</li>
 * <li>Inter-actor communication (vehicles, nodes, signals)</li>
 * </ul>
 *
 * <h2>Micro Simulation</h2>
 * In MICRO mode, the link:
 * <ul>
 * <li>Maintains per-lane vehicle queues</li>
 * <li>Executes sub-tick simulations (typically 10 sub-ticks per global tick)</li>
 * <li>Delegates car-following logic to [[MicroSimulationStrategy]]</li>
 * <li>Delegates lane-change logic to [[LaneChangeStrategy]]</li>
 * <li>Publishes dynamic costs to Kafka for routing</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * Link behavior is configured via:
 * <ul>
 * <li>`htc.routing.link-cost.publish-interval`: Cost publish frequency (ticks)</li>
 * <li>`htc.routing.link-cost.cache-ttl`: Cost cache TTL (ticks)</li>
 * </ul>
 *
 * @param properties Actor properties including entity ID, shard configuration
 * @see LinkState for link state model
 * @see MicroSimulationStrategy for micro simulation algorithms
 * @see LaneChangeStrategy for lane change behavior
 */
class Link(
            private val properties: Properties
          ) extends SimulationBaseActor[LinkState](
  properties = properties
) {

  /**
   * Calculates the current cost of traversing this link.
   * Cost combines distance, congestion, and travel time factors.
   *
   * @return Current link cost (higher = less desirable for routing)
   */
  private def cost: Double = {
    val speedFactor =
      if (state.currentSpeed > 0) state.length / state.currentSpeed else Double.MaxValue
    state.length * state.congestionFactor + speedFactor
  }

  // Strategy pattern for microscopic simulation
  private val microSimulationStrategy: MicroSimulationStrategy = DefaultMicroSimulationStrategy()
  private val laneChangeStrategy: LaneChangeStrategy = NoLaneChangeStrategy()

  private var lastCostPublishTick: Tick = 0

  // === Metrics tracking ===
  /** Current tick being summarized for metrics */
  private var summaryTick: Tick = Long.MinValue
  /** Vehicles loaded in current tick */
  private var tickLoaded: Int = 0
  /** Vehicles inserted in current tick */
  private var tickInserted: Int = 0
  /** Vehicles that arrived in current tick */
  private var tickArrived: Int = 0
  /** Sum of travel times in current tick (seconds) */
  private var tickTravelTimeSum: Double = 0.0
  /** Processing duration for current tick (milliseconds) */
  private var tickProcessingDurationMs: Long = 0L
  /** Total vehicles ever loaded into this link */
  private var cumulativeLoadedVehicles: Long = 0L
  private var cumulativeLoaded: Long = 0L
  private var cumulativeArrived: Long = 0L
  private var cumulativeDiscarded: Long = 0L

  /** Tracks when each vehicle entered the link (for travel time calculation) */
  private val vehicleEntryTick: mutable.Map[String, Tick] = mutable.Map.empty
  /** Accumulated waiting time per vehicle (seconds) */
  private val vehicleWaitingSeconds: mutable.Map[String, Double] = mutable.Map.empty

  /** Flag indicating if micro-tick simulation is scheduled */
  private var microTickScheduled: Boolean = false

  /**
   * Grace-period counter for MICRO links.
   *
   * When vehiclesByLane becomes empty, the link stays alive for
   * MICRO_GRACE_TICKS additional ticks before deregistering from the TM.
   * This prevents a race condition where:
   *   1. The last vehicle in batch-N sends LeaveLinkData and empties vehiclesByLane.
   *   2. The link's next actSpontaneous sees vehicleCount=0 and calls
   *      onFinishSpontaneous(None), stopping the link.
   *   3. Vehicles from batch-(N+1) arrive (tens to hundreds of ticks later due
   *      to queue buildup in earlier links) and call scheduleEvent(currentTick+1),
   *      but currentTick is now stale and the TM may have advanced or terminated.
   *
   * With a grace period the link remains scheduled in the TM, so batch-(N+1)
   * vehicles always find a live link that will process them on the next tick.
   * The counter is reset to 0 whenever new vehicles are present.
   */
  private var emptyGraceTick: Int = 0
  // Safety-net grace period: keep the link scheduled in the TM for a few extra ticks
  // after vehiclesByLane empties. The real fix is in LocalTimeManagerBase.finishEvent
  // (actor added to scheduledActors atomically), but this guard prevents edge cases where
  // the TM advances past the scheduled tick before the actor is picked up.
  private val MICRO_GRACE_TICKS: Int = 5

  /** Configuration: interval between dynamic cost publications (ticks) */
  private val costPublishInterval: Int = {
    try { com.typesafe.config.ConfigFactory.load().getInt("htc.routing.link-cost.publish-interval") }
    catch { case _: Exception => 10 }
  }

  /** Configuration: TTL for cached dynamic costs (ticks) */
  private val cacheTtl: Int = {
    try { com.typesafe.config.ConfigFactory.load().getInt("htc.routing.link-cost.cache-ttl") }
    catch { case _: Exception => 60 }
  }

  /**
   * Initializes the link actor on first load.
   *
   * - Sets up MICRO mode if configured
   * - Publishes initial dynamic cost
   * - Logs initialization status
   *
   * @param event InitializeEvent from the system
   */
  override def onInitialize(event: InitializeEvent): Unit = {
    super.onInitialize(event)

    if (state.isMicroMode) {
      initializeMicroMode()
    }

    publishDynamicCost()
    logDebug(s"Link initialized: mode=${state.simulationMode}, lanes=${state.lanes}, length=${state.length}m")
  }

  /**
   * Initializes microscopic simulation mode.
   *
   * - Initializes lane structures
   * - Configures simulation strategies
   * - Sets up micro time management
   */
  private def initializeMicroMode(): Unit = {
    logDebug(s"Initializing MICRO mode for link ${state.from} -> ${state.to}")

    if (state.vehiclesByLane.isEmpty) {
      state = state.initializeMicroLanes()
    }

    // Initialize strategies with link parameters
    microSimulationStrategy.initialize(
      linkLength = state.length,
      speedLimit = state.speedLimit,
      lanes = state.lanes,
      microTimeStep = state.microTimeStep
    )

    laneChangeStrategy.initialize(
      linkLength = state.length,
      speedLimit = state.speedLimit,
      lanes = state.lanes
    )

    logDebug(s"✓ MICRO mode initialized")
    logDebug(s"  - linkId: ${getEntityId}")
    logDebug(s"  - lanes: ${state.lanes}")
    logDebug(s"  - length: ${state.length}m")
    logDebug(s"  - timeStep: ${state.microTimeStep}s")
    logDebug(s"  - ticksPerGlobalTick: ${state.microTicksPerGlobalTick}")
  }

  /**
   * Handles interaction events from other actors (vehicles, nodes).
   *
   * Processes:
   * - EnterLinkData: Vehicle entering the link
   * - LeaveLinkData: Vehicle leaving the link
   *
   * @param event Interaction event with data payload
   */
  override def actInteractWith(event: ActorInteractionEvent): Unit = {
    event.data match {
      case d: EnterLinkData => handleEnterLink(event, d)
      case d: LeaveLinkData => handleLeaveLink(event, d)
      case _ =>
        logWarn(s"Event not handled: ${event.data.getClass.getSimpleName}")
    }
  }

  /**
   * Handles spontaneous (time-triggered) events for MICRO mode simulation.
   *
   * In MICRO mode:
   * - Executes sub-tick simulation for all vehicles
   * - Schedules next tick if vehicles remain
   * - Stops scheduling when link is empty
   *
   * In MESO mode: No-op (vehicles manage their own timing)
   *
   * @param event Spontaneous event from time manager
   */
  override protected def actSpontaneous(event: SpontaneousEvent): Unit = {
    if (!state.isMicroMode) {
      microTickScheduled = false
      onFinishSpontaneous(None)
      return
    }

    val vehicleCount = state.totalVehiclesInMicro
    val hasVehicles = vehicleCount > 0

    if (!hasVehicles) {
      emptyGraceTick += 1
      if (emptyGraceTick <= MICRO_GRACE_TICKS) {
        // Stay alive during grace period so late-arriving vehicles can enter and
        // find a scheduled link without needing a TM reschedule from actInteractWith.
        onFinishSpontaneous(Some(currentTick + 1))
      } else {
        emptyGraceTick = 0
        microTickScheduled = false
        onFinishSpontaneous(None)
      }
      return
    }

    emptyGraceTick = 0  // reset whenever vehicles are present
    microTickScheduled = true
    handleGlobalTick(currentTick)

    val hasVehiclesAfterTick = state.totalVehiclesInMicro > 0
    if (hasVehiclesAfterTick) {
      emptyGraceTick = 0
      onFinishSpontaneous(Some(currentTick + 1))
    } else {
      // Apply the same grace period when the last vehicle exits DURING tick processing.
      // Without this, the link calls onFinishSpontaneous(None) immediately, but an
      // incoming vehicle (whose EnterLinkData message is already in-flight) will call
      // scheduleEvent() AFTER the TM has already reported hasScheduled=false to GlobalTM,
      // causing GlobalTM to terminate prematurely while vehicles are still in transit.
      emptyGraceTick += 1
      if (emptyGraceTick <= MICRO_GRACE_TICKS) {
        // Stay alive: an incoming vehicle may arrive within the grace window
        onFinishSpontaneous(Some(currentTick + 1))
      } else {
        emptyGraceTick = 0
        microTickScheduled = false
        onFinishSpontaneous(None)
      }
    }
  }

  /**
   * Handles a vehicle entering the link.
   *
   * Dispatches to mode-specific handler (MESO or MICRO) and emits metrics.
   *
   * @param event Actor interaction event
   * @param data Vehicle entry data (ID, size, type, etc.)
   */
  private def handleEnterLink(event: ActorInteractionEvent, data: EnterLinkData): Unit = {
    ensureSummaryTick(currentTick)
    logDebug(s"Vehicle ${data.actorId} entering link (mode=${state.simulationMode})")

    report(
      data = Map(
        "event_type" -> "vehicle_entered_link",
        "link_id" -> getEntityId,
        "vehicle_id" -> data.actorId,
        "vehicle_type" -> data.actorType,
        "link_length" -> state.length,
        "simulation_mode" -> state.simulationMode.toString,
        "current_congestion" -> state.congestionFactor,
        "vehicles_in_link" -> state.registered.size,
        "tick" -> currentTick
      ),
      label = "link_vehicle_entered"
    )

    if (state.isMicroMode) {
      handleEnterLinkMicro(event, data)
    } else {
      handleEnterLinkMeso(event, data)
    }
  }

  /**
   * Handles vehicle entry in MESO mode.
   *
   * - Checks for duplicate registration
   * - Adds vehicle to registered set
   * - Sends link info back to vehicle
   *
   * @param event Actor interaction event
   * @param data Vehicle entry data
   */
  private def handleEnterLinkMeso(event: ActorInteractionEvent, data: EnterLinkData): Unit = {
    if (state.registered.exists(_.actorId == data.actorId)) {
      val duplicateInfo = LinkInfoData(
        linkLength = state.length,
        linkCapacity = state.capacity,
        linkNumberOfCars = state.registered.size,
        linkFreeSpeed = state.freeSpeed,
        linkLanes = state.lanes
      )

      sendMessageTo(
        entityId = event.actorRefId,
        shardId = event.shardRefId,
        data = duplicateInfo,
        eventType = EventTypeEnum.ReceiveEnterLinkInfo.toString,
        actorType = LoadBalancedDistributed
      )
      return
    }

    tickLoaded += 1
    cumulativeLoadedVehicles += 1
    vehicleEntryTick.put(data.actorId, currentTick)
    vehicleWaitingSeconds.getOrElseUpdate(data.actorId, 0.0)

    state.registered.add(
      LinkRegister(
        actorId = data.actorId,
        shardId = data.shardId,
        actorType = data.actorType,
        actorSize = data.actorSize,
        actorCreationType = data.actorCreationType
      )
    )
    onVehicleInserted()

    val linkInfo = LinkInfoData(
      linkLength = state.length,
      linkCapacity = state.capacity,
      linkNumberOfCars = state.registered.size,
      linkFreeSpeed = state.freeSpeed,
      linkLanes = state.lanes
    )

    sendMessageTo(
      entityId = event.actorRefId,
      shardId = event.shardRefId,
      data = linkInfo,
      eventType = EventTypeEnum.ReceiveEnterLinkInfo.toString,
      actorType = LoadBalancedDistributed
    )
  }

  /**
   * Handles vehicle entry in MICRO mode.
   *
   * - Checks if vehicle already in a lane (duplicate entry)
   * - Assigns vehicle to least occupied lane
   * - Creates VehicleInLane instance with initial state
   * - Schedules micro-tick simulation if not already running
   *
   * @param event Actor interaction event
   * @param data Vehicle entry data
   */
  private def handleEnterLinkMicro(event: ActorInteractionEvent, data: EnterLinkData): Unit = {
    findVehicleLane(data.actorId) match {
      case Some(existingLane) =>
        sendMicroEnterAck(event, existingLane)
        if (!microTickScheduled) {
          microTickScheduled = true
          scheduleEvent(currentTick + 1)
        }
        return
      case None =>
    }

    tickLoaded += 1
    cumulativeLoadedVehicles += 1
    vehicleEntryTick.put(data.actorId, event.tick)
    vehicleWaitingSeconds.getOrElseUpdate(data.actorId, 0.0)

    if (!state.registered.exists(_.actorId == data.actorId)) {
      state.registered.add(
        LinkRegister(
          actorId = data.actorId,
          shardId = data.shardId,
          actorType = data.actorType,
          actorSize = data.actorSize,
          actorCreationType = data.actorCreationType
        )
      )
    }

    if (state.vehiclesByLane.isEmpty) {
      state = state.initializeMicroLanes()
    }

    val assignedLane = findLeastOccupiedLane()
    val entryTick = event.tick
    val vehicle = VehicleInLane(
      actorId = data.actorId,
      shardId = data.shardId,
      position = 0.0,
      velocity = 0.0,
      acceleration = 0.0,
      vehicleLength = data.actorSize,
      entryTick = entryTick
    )

    state.vehiclesByLane.get(assignedLane).foreach { queue =>
      val insertIdx = queue.indexWhere(_.position < vehicle.position)
      if (insertIdx >= 0) queue.insert(insertIdx, vehicle) else queue.enqueue(vehicle)
    }
    onVehicleInserted()

    sendMicroEnterAck(event, assignedLane)

    if (!microTickScheduled) {
      microTickScheduled = true
      // CRITICAL: Use currentTick (the link's own tick, always >= event.tick) rather than
      // entryTick (event.tick, the car's tick when it sent the message).
      // In MICRO mode cars stop scheduling their own spontaneous events, so their currentTick
      // lags behind. When they send EnterLinkData, event.tick may already be < link's currentTick.
      // scheduleEvent with a past tick is silently ignored by the TimeManager (nextTick filters
      // ticks < localTickOffset), causing the link to never run its micro simulation.
      scheduleEvent(currentTick + 1)
    }
  }

  /**
   * Sends MICRO mode entry acknowledgment to vehicle.
   *
   * Includes lane assignment and link parameters for micro simulation.
   *
   * @param event Actor interaction event
   * @param lane Assigned lane ID
   */
  private def sendMicroEnterAck(event: ActorInteractionEvent, lane: Int): Unit = {
    val microEnterData = MicroEnterLinkData(
      linkId = getEntityId,
      mode = SimulationModeEnum.MICRO,
      assignedLane = lane,
      linkLength = state.length,
      speedLimit = state.speedLimit,
      numberOfLanes = state.lanes,
      microTimeStep = state.microTimeStep,
      ticksPerGlobalTick = state.microTicksPerGlobalTick
    )

    sendMessageTo(
      entityId = event.actorRefId,
      shardId = event.shardRefId,
      data = microEnterData,
      eventType = "MicroEnterLink",
      actorType = LoadBalancedDistributed
    )
  }

  /**
   * Finds which lane a vehicle is currently in.
   *
   * @param actorId Vehicle actor ID
   * @return Optional lane ID if vehicle is found
   */
  private def findVehicleLane(actorId: String): Option[Int] = {
    state.vehiclesByLane.collectFirst {
      case (laneId, queue) if queue.exists(_.actorId == actorId) => laneId
    }
  }

  /**
   * Handles a vehicle leaving the link.
   *
   * - Removes vehicle from registered set
   * - Cleans up tracking maps
   * - Dispatches to mode-specific handler (MESO or MICRO)
   * - Emits exit metrics
   *
   * @param event Actor interaction event
   * @param data Vehicle exit data
   */
  private def handleLeaveLink(event: ActorInteractionEvent, data: LeaveLinkData): Unit = {
    ensureSummaryTick(currentTick)
    logDebug(s"Vehicle ${data.actorId} leaving link")

    val wasRegistered = state.registered.exists(_.actorId == data.actorId)
    val vehiclesRemaining = math.max(0, state.registered.size - (if (wasRegistered) 1 else 0))

    report(
      data = Map(
        "event_type" -> "vehicle_left_link",
        "link_id" -> getEntityId,
        "vehicle_id" -> data.actorId,
        "vehicle_type" -> data.actorType.toString,
        "link_length" -> state.length,
        "vehicles_remaining" -> vehiclesRemaining,
        "tick" -> currentTick
      ),
      label = "link_vehicle_left"
    )

    state.registered.filterInPlace(_.actorId != data.actorId)
    val entryTick = vehicleEntryTick.get(data.actorId) match {
      case Some(tick) => tick
      case None => -1
    }
    vehicleEntryTick.remove(data.actorId)
    vehicleWaitingSeconds.remove(data.actorId)
    if (wasRegistered) {
      onVehicleArrived(travelTime = 0.0)
    }

    if (state.isMicroMode) {
      sendLeaveLinkMicro(event, data, entryTick)
    } else {
      sendLeaveLinkDataMeso(event)
    }
  }

  /**
   * Sends vehicle exit acknowledgment in MICRO mode.
   *
   * - Removes vehicle from lane queues
   * - Calculates travel time and waiting time
   * - Sends MicroLeaveLinkData with journey stats
   *
   * @param event Actor interaction event
   * @param data Vehicle exit data
   * @param entryTick Tick when vehicle entered (for travel time)
   */
  private def sendLeaveLinkMicro(event: ActorInteractionEvent, data: LeaveLinkData, entryTick: Long): Unit = {
    // Check whether the vehicle was already proactively sent MicroLeaveLinkData in handleGlobalTick.
    // In the correct flow: Link sends MicroLeaveLinkData → car requests signal → leavingLink() sends
    // LeaveLinkData back here. The vehicle was already removed from vehiclesByLane at exit time, so
    // there is nothing left to do except cleanup (state.registered was already cleaned in handleLeaveLink).
    val stillInLanes = state.vehiclesByLane.values.exists(q => q.exists(_.actorId == data.actorId))

    if (!stillInLanes) {
      // Normal path: vehicle exited proactively. Just ensure lane queues are clean (no-op if already empty).
      logDebug(s"${data.actorId}: LeaveLinkData received after proactive MicroLeaveLink — cleanup only.")
      if (state.totalVehiclesInMicro == 0 && microTickScheduled) {
        microTickScheduled = false
      }
      return
    }

    // Fallback path (should not normally occur): vehicle sent LeaveLinkData before Link sent MicroLeaveLinkData.
    // Send MicroLeaveLinkData now as a fallback so the car can still process stats.
    state.vehiclesByLane.foreach { case (_, queue) =>
      queue.dequeueAll(_.actorId == data.actorId)
    }

    val accumulatedWaitingTime = vehicleWaitingSeconds.getOrElse(data.actorId, 0.0)
    vehicleWaitingSeconds.remove(data.actorId)

    val microLeaveData = MicroLeaveLinkData(
      linkId = getEntityId,
      finalPosition = state.length,
      finalVelocity = state.currentSpeed,
      travelTime = math.max(1L, currentTick - entryTick + 1),
      distanceTraveled = state.length,
      averageSpeed = state.currentSpeed,
      waitingTimeSeconds = accumulatedWaitingTime
    )

    sendMessageTo(
      entityId = event.actorRefId,
      shardId = event.shardRefId,
      data = microLeaveData,
      eventType = "MicroLeaveLink",
      actorType = LoadBalancedDistributed
    )

    if (state.totalVehiclesInMicro == 0 && microTickScheduled) {
      microTickScheduled = false
    }
  }

  /**
   * Sends vehicle exit acknowledgment in MESO mode.
   *
   * Sends link info back to vehicle for next routing decision.
   *
   * @param event Actor interaction event
   */
  private def sendLeaveLinkDataMeso(event: ActorInteractionEvent): Unit = {
    val linkInfo = LinkInfoData(
      linkLength = state.length,
      linkCapacity = state.capacity,
      linkNumberOfCars = state.registered.size,
      linkFreeSpeed = state.freeSpeed,
      linkLanes = state.lanes
    )

    sendMessageTo(
      entityId = event.actorRefId,
      shardId = event.shardRefId,
      data = linkInfo,
      eventType = EventTypeEnum.ReceiveLeaveLinkInfo.toString,
      actorType = LoadBalancedDistributed
    )
  }

  /**
   * Finds the least occupied lane for vehicle entry.
   *
   * @return Lane ID with fewest vehicles, or 0 if no lanes
   */
  private def findLeastOccupiedLane(): Int = {
    microSimulationStrategy.selectEntryLane(
      vehiclesByLane = scala.collection.mutable.Map.from(state.vehiclesByLane),
      vehicleId = "", // Not used in default implementation
      vehicleLength = 4.5 // Default car length
    )
  }

  /**
   * Handles a global tick in MICRO mode.
   *
   * - Publishes dynamic cost if interval elapsed
   * - Delegates micro simulation to strategy
   * - Sends updates to vehicle actors
   * - Emits summary metrics
   *
   * @param tick Current global tick
   */
  private def handleGlobalTick(tick: Tick): Unit = {
    val processingStartedAt = System.nanoTime()
    ensureSummaryTick(tick)

    // Publish dynamic cost periodically
    if (tick - lastCostPublishTick >= costPublishInterval) {
      publishDynamicCost()
      lastCostPublishTick = tick
    }

    // Execute micro simulation via strategy
    if (state.isMicroMode) {
      if (state.totalVehiclesInMicro == 0) return

      // Convert immutable Map to mutable for strategy (it will mutate the queues)
      val mutableLanes = scala.collection.mutable.Map.from(state.vehiclesByLane)
      
      val updates = microSimulationStrategy.executeSubTick(
        vehiclesByLane = mutableLanes,
        subTick = 0, // Strategy handles internal sub-tick iteration
        tick = tick,
        linkLength = state.length,
        speedLimit = state.speedLimit,
        microTimeStep = state.microTimeStep,
        microTicksPerGlobalTick = state.microTicksPerGlobalTick,
        vehicleWaitingSeconds = vehicleWaitingSeconds
      )

      // Strategy mutates state.vehiclesByLane, no need to copy back
      // (queues are shared references)
      
      // Send updates to vehicles: proactive MicroLeaveLinkData for those that reached the end,
      // regular MicroUpdateData for others. This makes the Link the authority on exit, preventing
      // the ghost-restart race condition that occurred when vehicles detected exit themselves.
      updates.foreach { update =>
        if (update.reachedEnd) sendMicroLeaveLinkToVehicle(update)
        else sendMicroUpdateToVehicle(update)
      }
    }

    tickProcessingDurationMs = (System.nanoTime() - processingStartedAt) / 1000000L
    emitSumoSummaryStep(tick)
  }

  /**
   * Sends a proactive MicroLeaveLinkData to a vehicle that has reached the end of the link.
   * This is the correct trigger for the vehicle to request signal state — the Link is the
   * authority on when a vehicle exits, not the vehicle itself.
   *
   * The vehicle is removed from vehiclesByLane here; when its LeaveLinkData arrives later
   * (after signal is green), sendLeaveLinkMicro will skip sending MicroLeaveLinkData again.
   *
   * @param update Vehicle update with reachedEnd=true
   */
  private def sendMicroLeaveLinkToVehicle(update: model.hybrid.micro.strategy.MicroVehicleUpdate): Unit = {
    // Remove vehicle from lane queues immediately — it has physically exited the link
    state.vehiclesByLane.foreach { case (_, queue) =>
      queue.dequeueAll(_.actorId == update.vehicleId)
    }

    val accumulatedWaitingTime = vehicleWaitingSeconds.getOrElse(update.vehicleId, 0.0)
    vehicleWaitingSeconds.remove(update.vehicleId)

    val entryTick = vehicleEntryTick.getOrElse(update.vehicleId, currentTick)
    val elapsedTicks = math.max(1L, currentTick - entryTick + 1)
    val avgSpeed = if (elapsedTicks > 0 && state.microTimeStep > 0)
      state.length / (elapsedTicks * state.microTimeStep)
    else update.velocity

    val microLeaveData = MicroLeaveLinkData(
      linkId = getEntityId,
      finalPosition = update.position,
      finalVelocity = update.velocity,
      travelTime = elapsedTicks,
      distanceTraveled = state.length,
      averageSpeed = avgSpeed,
      waitingTimeSeconds = accumulatedWaitingTime
    )

    sendMessageTo(
      entityId = update.vehicleId,
      shardId = update.shardId,
      data = microLeaveData,
      eventType = "MicroLeaveLink",
      actorType = LoadBalancedDistributed
    )

    if (state.totalVehiclesInMicro == 0 && microTickScheduled) {
      microTickScheduled = false
    }
  }

  /**
   * Sends a micro-simulation update to a vehicle actor.
   *
   * @param update Vehicle update containing position, velocity, etc.
   */
  private def sendMicroUpdateToVehicle(update: model.hybrid.micro.strategy.MicroVehicleUpdate): Unit = {
    val microUpdateData = MicroUpdateData(
      subTick = update.subTick,
      position = update.position,
      velocity = update.velocity,
      acceleration = update.acceleration,
      currentLane = update.currentLane,
      leaderVehicle = update.leaderVehicle,
      gapToLeader = update.gapToLeader,
      leaderVelocity = update.leaderVelocity,
      safeVelocity = update.safeVelocity
    )

    sendMessageTo(
      entityId = update.vehicleId,
      shardId = update.shardId,
      data = microUpdateData,
      eventType = "MicroUpdate",
      actorType = LoadBalancedDistributed
    )
  }

  /**
   * Ensures metrics are reset for a new tick.
   *
   * @param tick Current tick
   */
  private def ensureSummaryTick(tick: Tick): Unit = {
    if (summaryTick != tick) {
      summaryTick = tick
      tickLoaded = 0
      tickInserted = 0
      tickArrived = 0
      tickTravelTimeSum = 0.0
      tickProcessingDurationMs = 0L
    }
  }

  /**
   * Records a vehicle insertion into the link.
   * Updates per-tick and cumulative metrics.
   */
  private def onVehicleInserted(): Unit = {
    tickInserted += 1
    cumulativeLoaded += 1
  }

  /**
   * Records a vehicle arrival (exit from link).
   * Updates per-tick and cumulative metrics.
   *
   * @param travelTime Travel time through the link (seconds)
   */
  private def onVehicleArrived(travelTime: Double): Unit = {
    tickArrived += 1
    cumulativeArrived += 1
    if (travelTime > 0) {
      tickTravelTimeSum += travelTime
    }
  }

  /**
   * Emits SUMO-style summary metrics for this tick.
   *
   * Includes:
   * - Vehicle counts (loaded, inserted, running, arrived)
   * - Speed metrics (mean speed, relative speed)
   * - Travel and waiting times
   * - Halting vehicles (MICRO mode only)
   *
   * @param tick Current tick
   */
  private def emitSumoSummaryStep(tick: Tick): Unit = {
    val running = state.totalVehicles
    val halting = if (state.isMicroMode) {
      state.vehiclesByLane.valuesIterator.map(_.count(_.velocity <= 0.1)).sum
    } else {
      0
    }
    val meanSpeed = if (state.isMicroMode) {
      val allVehicles = state.vehiclesByLane.valuesIterator.flatMap(_.iterator).toVector
      if (allVehicles.nonEmpty) allVehicles.map(_.velocity).sum / allVehicles.size else 0.0
    } else {
      state.currentSpeed
    }
    val meanSpeedRelative = if (state.freeSpeed > 0) meanSpeed / state.freeSpeed else 0.0
    val runningVehicleIds = state.registered.iterator.map(_.actorId).toVector
    val meanTravelTime =
      if (runningVehicleIds.nonEmpty)
        runningVehicleIds.map(id => math.max(0L, tick - vehicleEntryTick.getOrElse(id, tick)).toDouble).sum / runningVehicleIds.size
      else 0.0
    val meanWaitingTime =
      if (runningVehicleIds.nonEmpty)
        runningVehicleIds.map(id => vehicleWaitingSeconds.getOrElse(id, 0.0)).sum / runningVehicleIds.size
      else 0.0
    val waiting = math.max(0L, cumulativeLoadedVehicles - cumulativeLoaded).toInt

    report(
      data = Map(
        "event_type" -> "sumo_summary_step",
        "scope" -> "link",
        "link_id" -> getEntityId,
        "mode" -> state.simulationMode.toString,
        "time" -> tick,
        "loaded" -> cumulativeLoadedVehicles,
        "inserted" -> tickInserted,
        "running" -> running,
        "waiting" -> waiting,
        "ended" -> cumulativeArrived,
        "arrived" -> tickArrived,
        "collisions" -> 0,
        "teleports" -> 0,
        "halting" -> halting,
        "stopped" -> 0,
        "meanWaitingTime" -> meanWaitingTime,
        "meanTravelTime" -> meanTravelTime,
        "meanSpeed" -> meanSpeed,
        "meanSpeedRelative" -> meanSpeedRelative,
        "discarded" -> cumulativeDiscarded,
        "duration" -> tickProcessingDurationMs,
        "tick" -> tick
      ),
      label = "sumo_summary_step"
    )
  }

  /**
   * Publishes current dynamic cost to Kafka for routing updates.
   *
   * Cost is based on:
   * - Current speed vs. free-flow speed
   * - Vehicle count vs. capacity
   * - Congestion factor
   * - Link length
   */
  private def publishDynamicCost(): Unit = {
    val dynamicCost = DynamicLinkCost.fromLinkState(
      linkId = getEntityId,
      length = state.length,
      currentSpeed = state.currentSpeed,
      freeFlowSpeed = state.freeSpeed,
      vehicleCount = state.registered.size,
      capacity = state.capacity,
      congestionFactor = state.congestionFactor,
      tick = currentTick
    )

    DynamicWeightCache.publishCost(dynamicCost, cacheTtl) match {
      case scala.util.Success(_) => // Silencioso no sucesso para evitar log spam
      case scala.util.Failure(e) =>
        logWarn(s"Failed to publish dynamic cost to Kafka: ${e.getMessage}")
    }
  }

  /**
   * Handles link destruction on simulation termination.
   *
   * Forwards DestructEvent to all registered vehicles to ensure proper cleanup.
   * This is critical for MICRO mode where vehicles may have stopped scheduling
   * their own events and rely on the link for lifecycle management.
   *
   * @param event DestructEvent from the system
   */
  override def onDestruct(event: DestructEvent): Unit = {
    state.registered.foreach { reg =>
      val shardRef = getShardRef(IdUtil.format(StringUtil.getModelClassName(reg.shardId)))
      shardRef ! EntityEnvelopeEvent(
        IdUtil.format(reg.actorId),
        DestructEvent(actorRef = self.path.toString)
      )
    }
  }
}

object Link {
  def apply(properties: Properties): Link = {
    new Link(properties)
  }
}