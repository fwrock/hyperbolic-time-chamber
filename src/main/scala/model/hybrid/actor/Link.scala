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

import scala.collection.mutable

class Link(
            private val properties: Properties
          ) extends SimulationBaseActor[LinkState](
  properties = properties
) {

  private def cost: Double = {
    val speedFactor =
      if (state.currentSpeed > 0) state.length / state.currentSpeed else Double.MaxValue
    state.length * state.congestionFactor + speedFactor
  }

  private val carFollowingModel: CarFollowingModel = KraussModel()
  private var lastCostPublishTick: Tick = 0

  private var summaryTick: Tick = Long.MinValue
  private var tickLoaded: Int = 0
  private var tickInserted: Int = 0
  private var tickArrived: Int = 0
  private var tickTravelTimeSum: Double = 0.0
  private var tickProcessingDurationMs: Long = 0L
  private var cumulativeLoadedVehicles: Long = 0L
  private var cumulativeLoaded: Long = 0L
  private var cumulativeArrived: Long = 0L
  private var cumulativeDiscarded: Long = 0L

  private val vehicleEntryTick: mutable.Map[String, Tick] = mutable.Map.empty
  private val vehicleWaitingSeconds: mutable.Map[String, Double] = mutable.Map.empty

  private var microTickScheduled: Boolean = false

  private val costPublishInterval: Int = {
    try { com.typesafe.config.ConfigFactory.load().getInt("htc.routing.link-cost.publish-interval") }
    catch { case _: Exception => 10 }
  }

  private val cacheTtl: Int = {
    try { com.typesafe.config.ConfigFactory.load().getInt("htc.routing.link-cost.cache-ttl") }
    catch { case _: Exception => 60 }
  }

  override def onInitialize(event: InitializeEvent): Unit = {
    super.onInitialize(event)

    if (state.isMicroMode) {
      initializeMicroMode()
    }

    publishDynamicCost()
    logDebug(s"Link initialized: mode=${state.simulationMode}, lanes=${state.lanes}, length=${state.length}m")
  }

  private def initializeMicroMode(): Unit = {
    logDebug(s"Initializing MICRO mode for link ${state.from} -> ${state.to}")

    if (state.vehiclesByLane.isEmpty) {
      state = state.initializeMicroLanes()
    }

    logDebug(s"✓ MICRO mode initialized")
    logDebug(s"  - linkId: ${getEntityId}")
    logDebug(s"  - lanes: ${state.lanes}")
    logDebug(s"  - length: ${state.length}m")
    logDebug(s"  - timeStep: ${state.microTimeStep}s")
    logDebug(s"  - ticksPerGlobalTick: ${state.microTicksPerGlobalTick}")
  }

  override def actInteractWith(event: ActorInteractionEvent): Unit = {
    event.data match {
      case d: EnterLinkData => handleEnterLink(event, d)
      case d: LeaveLinkData => handleLeaveLink(event, d)
      case _ =>
        logWarn(s"Event not handled: ${event.data.getClass.getSimpleName}")
    }
  }

  override protected def actSpontaneous(event: SpontaneousEvent): Unit = {
    if (!state.isMicroMode) {
      microTickScheduled = false
      onFinishSpontaneous(None)
      return
    }

    val vehicleCount = state.totalVehiclesInMicro
    val hasVehicles = vehicleCount > 0

    if (!hasVehicles) {
      microTickScheduled = false
      onFinishSpontaneous(None)
      return
    }

    handleGlobalTick(currentTick)

    val hasVehiclesAfterTick = state.totalVehiclesInMicro > 0
    if (hasVehiclesAfterTick) {
      onFinishSpontaneous(Some(currentTick + 1))
    } else {
      microTickScheduled = false
      onFinishSpontaneous(None)
    }
  }

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

  private def findVehicleLane(actorId: String): Option[Int] = {
    state.vehiclesByLane.collectFirst {
      case (laneId, queue) if queue.exists(_.actorId == actorId) => laneId
    }
  }

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

  private def sendLeaveLinkMicro(event: ActorInteractionEvent, data: LeaveLinkData, entryTick: Long): Unit = {
    state.vehiclesByLane.foreach { case (_, queue) =>
      queue.dequeueAll(_.actorId == data.actorId)
    }

    // BUGFIX: Send accumulated waiting time from Link to Car
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

  private def findLeastOccupiedLane(): Int = {
    if (state.vehiclesByLane.isEmpty) 0
    else state.vehiclesByLane.minBy(_._2.size)._1
  }

  private def handleGlobalTick(tick: Tick): Unit = {
    val processingStartedAt = System.nanoTime()
    ensureSummaryTick(tick)

    if (tick - lastCostPublishTick >= costPublishInterval) {
      publishDynamicCost()
      lastCostPublishTick = tick
    }

    if (state.isMicroMode) {
      if (state.totalVehiclesInMicro == 0) return

      for (subTick <- 0 until state.microTicksPerGlobalTick) {
        executeSubTick(subTick, tick)
      }
    }

    tickProcessingDurationMs = (System.nanoTime() - processingStartedAt) / 1000000L
    emitSumoSummaryStep(tick)
  }

  private def executeSubTick(subTick: Int, tick: Tick): Unit = {
    state.vehiclesByLane.foreach { case (laneId, vehicles) =>
      if (vehicles.nonEmpty) {
        processMicroLane(laneId, vehicles, subTick, tick)
      }
    }
  }

  private def processMicroLane(
                                laneId: Int,
                                vehicles: mutable.Queue[VehicleInLane],
                                subTick: Int,
                                tick: Tick,
                              ): Unit = {
    for (i <- vehicles.indices) {
      val vehicle = vehicles(i)
      val leader = if (i > 0) Some(vehicles(i - 1)) else None

      val (rawGap, leaderVel) = leader match {
        case Some(l) =>
          (l.position - vehicle.position - vehicle.vehicleLength, l.velocity)
        case None =>
          (state.length - vehicle.position, state.speedLimit / 3.6)
      }
      val gap = math.max(0.1, rawGap)

      val targetVel = leader match {
        case Some(l) if gap < 50.0 => math.min(l.velocity, math.sqrt(2.0 * 4.5 * gap))
        case _ => state.speedLimit / 3.6
      }

      val safeTargetVel = if (targetVel.isNaN || targetVel.isInfinite) 0.0 else targetVel
      val velChange = (safeTargetVel - vehicle.velocity) * 0.5 * state.microTimeStep
      val rawNewVelocity = vehicle.velocity + velChange
      val cappedVelocity = math.max(0.0, math.min(if (rawNewVelocity.isNaN || rawNewVelocity.isInfinite) 0.0 else rawNewVelocity, state.speedLimit / 3.6))

      // Limita a posição ao fim do link
      val rawNewPosition = vehicle.position + cappedVelocity * state.microTimeStep
      val newPosition = math.min(rawNewPosition, state.length)

      // Anula a velocidade real do carro se ele estiver engarrafado ou aguardando sinal
      val actualVelocity = if (newPosition >= state.length) 0.0 else cappedVelocity
      val newAcceleration = velChange / state.microTimeStep

      if (actualVelocity < 0.1) {
        vehicleWaitingSeconds.update(
          vehicle.actorId,
          vehicleWaitingSeconds.getOrElse(vehicle.actorId, 0.0) + state.microTimeStep
        )
      }

      vehicles(i) = vehicle.copy(
        position = newPosition,
        velocity = actualVelocity,
        acceleration = newAcceleration
      )

      val updateMicro = MicroUpdateData(
        subTick = subTick,
        position = newPosition,
        velocity = actualVelocity,
        acceleration = newAcceleration,
        currentLane = laneId,
        leaderVehicle = leader.map(_.actorId),
        gapToLeader = gap,
        leaderVelocity = leaderVel,
        safeVelocity = actualVelocity
      )

      if (newPosition >= state.length || subTick >= state.microTicksPerGlobalTick - 1) {
        sendMessageTo(
          entityId = vehicle.actorId,
          shardId = vehicle.shardId,
          data = updateMicro,
          eventType = "MicroUpdate",
          actorType = LoadBalancedDistributed
        )
      }
    }

    val ordered = vehicles.sortBy(v => -v.position)
    vehicles.clear()
    vehicles ++= ordered
  }

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

  private def onVehicleInserted(): Unit = {
    tickInserted += 1
    cumulativeLoaded += 1
  }

  private def onVehicleArrived(travelTime: Double): Unit = {
    tickArrived += 1
    cumulativeArrived += 1
    if (travelTime > 0) {
      tickTravelTimeSum += travelTime
    }
  }

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

  // When the simulation terminates, forceDestructActiveActors only covers actors still in
  // scheduledActors/runningEvents. MICRO-mode vehicles called onFinishSpontaneous(None) on
  // entry, removing themselves from TM tracking. By forwarding the DestructEvent to all
  // registered vehicles here, we ensure Car.onDestruct fires for each one, which calls
  // finishJourney and logs the journey_completed event.
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