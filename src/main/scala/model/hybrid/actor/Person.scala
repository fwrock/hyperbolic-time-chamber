package org.interscity.htc
package model.hybrid.actor

import core.actor.SimulationBaseActor
import core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import core.types.Tick
import core.entity.actor.properties.Properties
import model.hybrid.entity.state.{Activity, ArrivalLogistics, PersonState}
import model.hybrid.entity.event.data.person.{StartTripData, TripCompletedData}
import model.hybrid.entity.event.data.bus.{BusRequestUnloadPassengerData, BusUnloadPassengerData, RegisterPassengerData}
import model.hybrid.entity.event.data.subway.{RegisterSubwayPassengerData, SubwayRequestUnloadPassengerData, SubwayUnloadPassengerData}
import model.hybrid.util.{CityMapUtil, GPSUtil, ModeChoiceUtil}

import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import org.interscity.htc.core.metrics.core.ActorMetrics
import org.interscity.htc.core.metrics.model.hybrid.{GPSMetrics, PersonMetrics}

import scala.collection.mutable

/** Person actor - Agent-based person in the simulation.
  *
  * In the person-centric model, Person actors:
  *   - Persist throughout the simulation day
  *   - Manage their daily schedule (activities)
  *   - Make mode choices for trips
  *   - Activate private vehicles (Car, Bicycle, Motorcycle) as needed
  *   - Receive trip completion notifications
  *
  * Lifecycle:
  *   1. Person starts at first activity (Home) 2. Wait until activity endTime 3. Read
  *      nextActivity.arrivalLogistics 4. Execute mode choice (decide transport mode) 5. Send
  *      StartTrip to chosen vehicle 6. Wait for TripCompleted 7. Advance to next activity 8. Repeat
  *      until schedule complete
  *
  * @param properties
  *   Actor properties
  */
class Person(
  private val properties: Properties
) extends SimulationBaseActor[PersonState](
      properties = properties
    ) {

  /** Person should only re-register on the TM after migration if it was actually registered at
    * migration time. During vehicle trips (and PT trips), Person calls onFinishSpontaneous(None)
    * and yields TM ownership to the vehicle. Walking trips keep Person on TM (scheduled to wake at
    * arrival tick), so those are allowed.
    */
  override protected def shouldRegisterOnTimeManagerAfterMigration(): Boolean =
    state != null &&
      state.isSetScheduleOnTimeManager &&
      state.currentTripVehicleId.forall(_ == "walking")

  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    if (state == null) {
      logWarn(
        s"${getEntityId} actSpontaneous called with null state at tick=$currentTick — unscheduling"
      )
      onFinishSpontaneous(None)
      return
    }
    if (state.isScheduleComplete) {
      logDebug(
        s"${getEntityId} completed daily schedule (${state.completedTrips} trips, ${state.totalDistanceTraveled}m)"
      )
      PersonMetrics.completeSchedule.inc()
      ActorMetrics.spontaneousEventAfterCompletion.labels(
        getClass.getSimpleName, "spontaneous"
      ).inc()
      onFinishSpontaneous(None)
      return
    }

    if (state.currentTripVehicleId.isDefined) {
      if (state.currentTripVehicleId.contains("walking")) {
        advanceToNextActivity()
        return
      }

      logDebug(
        s"${getEntityId} unexpected spontaneous event during vehicle trip with ${state.currentTripVehicleId.get}"
      )
      onFinishSpontaneous(None)
      return
    }

    state.currentActivity match {
      case Some(activity) =>
        if (isActivityEndTime(activity)) {
          logDebug(
            s"${getEntityId} completing activity ${activity.activityType} at ${activity.nodeId}"
          )
          startNextTrip()
        } else {
          val endTick = currentTick + getTickUntilActivityEnd(activity)
          onFinishSpontaneous(Some(endTick))
        }

      case None =>
        advanceToNextActivity()
    }
  }

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: TripCompletedData                => handleTripCompleted(event, d)
      case d: BusRequestUnloadPassengerData    => handlePTUnloadRequest(event, d.nodeId, "bus")
      case d: SubwayRequestUnloadPassengerData => handlePTUnloadRequest(event, d.nodeId, "subway")
      case _ =>
        logWarn(s"Person event not handled: ${event.eventType}")
    }

  /** Check if current activity's end time has been reached.
    */
  private def isActivityEndTime(activity: Activity): Boolean =
    effectiveEndTick(activity) match {
      case Some(endTick) =>
        currentTick >= endTick
      case None =>
        logWarn(s"Cannot parse endTime: ${activity.endTime}, assuming time reached")
        true
    }

  /** Calculate ticks until activity end time.
    */
  private def getTickUntilActivityEnd(activity: Activity): Long =
    effectiveEndTick(activity)
      .map(endTick => Math.max(1L, endTick - currentTick))
      .getOrElse(1L)

  private def parseTick(value: String): Option[Long] =
    try Some(value.toLong)
    catch {
      case _: NumberFormatException => None
    }

  private def effectiveEndTick(activity: Activity): Option[Long] =
    parseTick(activity.endTime).map(_ + state.scheduleDelayOffsetTicks)

  private def plannedStartTickForActivity(index: Int): Option[Long] = {
    val previousIndex = index - 1
    if (previousIndex >= 0 && previousIndex < state.dailySchedule.length)
      parseTick(state.dailySchedule(previousIndex).endTime)
    else
      None
  }

  private def updateScheduleDelayOnArrival(arrivedActivityIndex: Int): Unit =
    plannedStartTickForActivity(arrivedActivityIndex).foreach { plannedStartTick =>
      val observedDelay = Math.max(0L, currentTick - plannedStartTick)
      val activityType = state.dailySchedule
        .lift(arrivedActivityIndex)
        .map(_.activityType)
        .getOrElse("unknown")

      PersonMetrics.personArrivalDelayTicks
        .labels(activityType)
        .observe(observedDelay.toDouble)

      if (observedDelay > 0L)
        PersonMetrics.personDelayedArrival.labels(activityType).inc()

      val firstTripDelay =
        if (arrivedActivityIndex == 1) Some(observedDelay)
        else state.firstTripDelayTicks

      if (observedDelay > state.scheduleDelayOffsetTicks) {
        state = state.copy(
          scheduleDelayOffsetTicks = observedDelay,
          firstTripDelayTicks = firstTripDelay
        )
        logDebug(
          s"${getEntityId} updated schedule delay offset to ${state.scheduleDelayOffsetTicks}s " +
            s"after arriving at activity $arrivedActivityIndex"
        )
      } else if (arrivedActivityIndex == 1 && state.firstTripDelayTicks.isEmpty) {
        state = state.copy(firstTripDelayTicks = firstTripDelay)
      }
    }

  /** Start trip to next activity.
    */
  private def startNextTrip(): Unit =
    state.nextActivity match {
      case Some(nextActivity) =>
        nextActivity.arrivalLogistics match {
          case Some(logistics) =>
            val originNodeId = state.currentActivity.map(_.nodeId).getOrElse("")
            val effectiveLogistics =
              executeModeChoice(originNodeId, nextActivity.nodeId, logistics)
            if (effectiveLogistics.instant) {
              logDebug(
                s"${getEntityId} instant transition to ${nextActivity.nodeId} (instant=true)"
              )
              advanceToNextActivity()
            } else {
              PersonMetrics.personTripStart.labels(nextActivity.activityType, effectiveLogistics.mode).inc()
              initiateTrip(nextActivity, effectiveLogistics)
            }

          case None =>
            logDebug(s"${getEntityId} instant arrival at ${nextActivity.nodeId} (no logistics)")
            advanceToNextActivity()
        }

      case None =>
        logDebug(s"${getEntityId} has no more activities")
        onFinishSpontaneous(None)
    }

  /** Execute mode choice logic.
    *
    * When `state.enableDynamicModeChoice` is `true` and the logistics leg is not fixed (no vehicle
    * reference, `fixedMode = false`), delegates to [[ModeChoiceUtil.chooseBestLogistics]] which
    * scores all reachable transit options and walking. Otherwise returns the original logistics
    * unchanged — full backward compatibility with static schedules.
    */
  private def executeModeChoice(
    originNodeId: String,
    destinationNodeId: String,
    logistics: ArrivalLogistics
  ): ArrivalLogistics =
    if (!state.enableDynamicModeChoice) {
      logDebug(s"${getEntityId} chose mode: ${logistics.mode} (static)")
      logistics
    } else {
      val effective = ModeChoiceUtil.chooseBestLogistics(
        originNodeId,
        destinationNodeId,
        logistics,
        state.modeChoiceWeights
      )
      if (effective.mode != logistics.mode)
        logDebug(
          s"${getEntityId} dynamic mode: ${logistics.mode} → ${effective.mode} " +
            s"($originNodeId → $destinationNodeId)"
        )
      else
        logDebug(s"${getEntityId} chose mode: ${effective.mode}")
      effective
    }

  /** Initiate trip to next activity.
    */
  private def initiateTrip(nextActivity: Activity, logistics: ArrivalLogistics): Unit =
    state.currentActivity match {
      case Some(currentActivity) =>
        logistics.mode.toLowerCase match {
          case "car" | "bicycle" | "motorcycle" =>
            initiatePrivateVehicleTrip(currentActivity.nodeId, nextActivity.nodeId, logistics)

          case "walk" =>
            initiateWalkingTrip(currentActivity.nodeId, nextActivity.nodeId)

          case "transit" | "bus" | "subway" | "pt" | "mixed" =>
            // TODO: implement PT when bus/subway routes are active in the scenario.
            // Currently behaves like car_passenger: teleports to destination using scheduled time.
            logDebug(
              s"${getEntityId} PT mode '${logistics.mode}' not yet active, advancing to next activity using scheduled time"
            )
            advanceToNextActivity()

          case _ =>
            // TODO: model unsupported modes properly when needed.
            logDebug(
              s"Mode '${logistics.mode}' not yet implemented, advancing to next activity using scheduled time"
            )
            advanceToNextActivity()
        }

      case None =>
        logWarn(s"${getEntityId} has no current activity")
        advanceToNextActivity()
    }

  /** Initiate walking trip (mesoscopic).
    *
    * Calculates route using road network, computes walking time based on distance and walking speed
    * (1.4 m/s typical), and schedules arrival.
    */
  private def initiateWalkingTrip(origin: String, destination: String): Unit =
    GPSUtil.calcRouteALT(originId = origin, destinationId = destination) match {
      case Some((routeCost, routeQueue)) =>
        val totalDistance = calculateRouteDistance(routeQueue)

        val walkingSpeed = 1.4 // m/s

        val walkingTimeSeconds = totalDistance / walkingSpeed
        val walkingTimeTicks = math.ceil(walkingTimeSeconds).toLong

        val arrivalTick = currentTick + walkingTimeTicks

        state = state.copy(
          currentTripVehicleId = Some("walking"),
          currentTripStartTick = Some(currentTick),
          currentTripMode = Some("walk")
        )

        logDebug(
          s"${getEntityId} walking from $origin to $destination: " +
            s"${totalDistance.toInt}m, ${walkingTimeTicks}s, arriving at tick $arrivalTick"
        )

        report(
          data = Map(
            "event_type" -> "walking_trip_start",
            "person_id" -> getEntityId,
            "origin" -> origin,
            "destination" -> destination,
            "distance" -> totalDistance,
            "walking_time_ticks" -> walkingTimeTicks,
            "arrival_tick" -> arrivalTick,
            "walking_speed" -> walkingSpeed,
            "tick" -> currentTick
          ),
          label = "person_walking_start"
        )

        onFinishSpontaneous(Some(arrivalTick))

      case None =>
        logError(s"${getEntityId} cannot find walking route from $origin to $destination")
        GPSMetrics.gpsCannotFindRoute.labels("person_walking").inc()
        advanceToNextActivity()
    }

  /** Initiate public transport trip (Bus or Subway).
    *
    * Person registers at the boarding stop (BusStop/SubwayStation) for the specified line, then
    * unregisters from the TimeManager. The PT vehicle carries the Person and periodically asks "do
    * you want to alight here?" via BusRequestUnloadPassengerData /
    * SubwayRequestUnloadPassengerData. Person responds and, when at the alighting node, advances to
    * next activity.
    *
    * Required ArrivalLogistics fields for PT:
    *   - line: bus/subway line label
    *   - boardingStopId: BusStop/SubwayStation actor ID
    *   - boardingStopClassType: actor class type for shard routing
    *   - alightingNodeId: node where Person should alight
    */
  private def initiatePTTrip(
    origin: String,
    destination: String,
    logistics: ArrivalLogistics
  ): Unit =
    (
      logistics.line,
      logistics.boardingStopId,
      logistics.boardingStopClassType,
      logistics.alightingNodeId
    ) match {
      case (Some(line), Some(stopId), Some(stopClassType), Some(alightingNode)) =>
        val registrationData = logistics.mode.toLowerCase match {
          case "subway" => RegisterSubwayPassengerData(line = line)
          case _        => RegisterPassengerData(label = line)
        }

        sendMessageTo(
          entityId = stopId,
          shardId = stopClassType,
          data = registrationData,
          eventType = "RegisterPassenger",
          actorType = LoadBalancedDistributed
        )

        state = state.copy(
          currentTripVehicleId = Some(s"pt:${logistics.mode}:$line"),
          currentTripStartTick = Some(currentTick),
          currentTripMode = Some(logistics.mode),
          ptAlightingNodeId = Some(alightingNode),
          ptLine = Some(line)
        )

        logDebug(s"${getEntityId} registered at $stopId for $line, alighting at $alightingNode")

        report(
          data = Map(
            "event_type" -> "pt_trip_start",
            "person_id" -> getEntityId,
            "mode" -> logistics.mode,
            "line" -> line,
            "origin" -> origin,
            "destination" -> destination,
            "boarding_stop" -> stopId,
            "alighting_node" -> alightingNode,
            "tick" -> currentTick
          ),
          label = "person_pt_trip_start"
        )

        onFinishSpontaneous(None)

      case _ =>
        // TODO: handle gracefully when PT routing data is partially available.
        logDebug(
          s"${getEntityId} PT trip missing routing info (line=${logistics.line}, " +
            s"boardingStop=${logistics.boardingStopId}, alightingNode=${logistics.alightingNodeId}). " +
            s"Advancing to next activity using scheduled time."
        )
        advanceToNextActivity()
    }

  /** Handle unload request from PT vehicle (Bus or Subway).
    *
    * The vehicle asks "are you getting off at this node?" Person checks if the node matches its
    * alighting destination and responds accordingly. If alighting, Person completes the trip and
    * re-registers with the TimeManager.
    *
    * @param event
    *   the interaction event from the vehicle
    * @param nodeId
    *   the node ID the vehicle is currently at
    * @param ptType
    *   "bus" or "subway" for logging and response routing
    */
  private def handlePTUnloadRequest(
    event: ActorInteractionEvent,
    nodeId: String,
    ptType: String
  ): Unit = {
    val isArrival = state.ptAlightingNodeId.contains(nodeId)

    val responseData = ptType match {
      case "subway" => SubwayUnloadPassengerData(isArrival = isArrival)
      case _        => BusUnloadPassengerData(isArrival = isArrival)
    }

    sendMessageTo(
      entityId = event.actorRefId,
      shardId = event.actorClassType,
      data = responseData,
      eventType = "UnloadPassengerResponse"
    )

    if (isArrival) {
      val travelTime = state.currentTripStartTick
        .map(
          start => currentTick - start
        )
        .getOrElse(0L)

      logDebug(s"${getEntityId} alighting from $ptType at node $nodeId after ${travelTime}s")

      report(
        data = Map(
          "event_type" -> "pt_trip_completed",
          "person_id" -> getEntityId,
          "pt_type" -> ptType,
          "vehicle_id" -> event.actorRefId,
          "line" -> state.ptLine.getOrElse("unknown"),
          "alighting_node" -> nodeId,
          "travel_time" -> travelTime,
          "tick" -> currentTick
        ),
        label = "person_pt_trip_completed"
      )

      state = state.completeTrip(0.0)
      advanceToNextActivity()
    } else {
      logDebug(
        s"${getEntityId} staying on $ptType at node $nodeId (alighting at ${state.ptAlightingNodeId.getOrElse("?")})"
      )
    }
  }

  /** Calculate total route distance by summing link lengths.
    */
  private def calculateRouteDistance(routeQueue: mutable.Queue[(String, String)]): Double = {
    var totalDistance = 0.0

    val routeCopy = routeQueue.clone()

    while (routeCopy.nonEmpty) {
      val (linkEdgeGraphId, _) = routeCopy.dequeue()

      CityMapUtil.edgeLabelsById.get(linkEdgeGraphId) match {
        case Some(edgeLabel) =>
          totalDistance += edgeLabel.length
        case None =>
          logWarn(s"Edge label $linkEdgeGraphId not found")
      }
    }

    totalDistance
  }

  /** Initiate private vehicle trip.
    */
  private def initiatePrivateVehicleTrip(
    origin: String,
    destination: String,
    logistics: ArrivalLogistics
  ): Unit =
    logistics.vehicle match {
      case Some(vehicleRef) =>
        state.ownedVehicles.get(logistics.mode.toLowerCase) match {
          case Some(ownedVehicleRef) if ownedVehicleRef.id == vehicleRef.id =>
            val startTripData = StartTripData(
              personId = getEntityId,
              origin = origin,
              destination = destination,
              driverAttributes = logistics.driverAttributes,
              startTick = currentTick
            )

            sendMessageTo(
              entityId = vehicleRef.id,
              shardId = vehicleRef.classType,
              data = startTripData,
              eventType = "StartTrip",
              actorType = LoadBalancedDistributed
            )

            // Update state
            state = state.copy(
              currentTripVehicleId = Some(vehicleRef.id),
              currentTripStartTick = Some(currentTick),
              currentTripMode = Some(logistics.mode)
            )

            logDebug(s"${getEntityId} started trip with ${vehicleRef.id}: $origin -> $destination")

            onFinishSpontaneous(None)

          case _ =>
            logError(
              s"${getEntityId} does not own vehicle ${vehicleRef.id} for mode ${logistics.mode}"
            )
            advanceToNextActivity()
        }

      case None =>
        logError(s"${getEntityId} no vehicle specified for mode ${logistics.mode}")
        advanceToNextActivity()
    }

  /** Handle trip completion from vehicle.
    */
  private def handleTripCompleted(event: ActorInteractionEvent, data: TripCompletedData): Unit = {
    logDebug(
      s"${getEntityId} received trip completion from ${data.vehicleId}: " +
        s"${data.distanceTraveled}m in ${data.travelTime} ticks, reason: ${data.completionReason}"
    )

    val currentActivityType = state.currentActivity.map(_.activityType).getOrElse("unknown")
    val currentMode = state.currentTripMode.getOrElse("unknown")

    PersonMetrics.personTripEnd.labels(currentActivityType, currentMode).inc()
    PersonMetrics.personCompleteTripReason.labels(currentActivityType, currentMode, data.completionReason).inc()
    state = state.completeTrip(data.distanceTraveled)

    report(
      data = Map(
        "event_type" -> "trip_completed",
        "person_id" -> getEntityId,
        "vehicle_id" -> data.vehicleId,
        "distance_traveled" -> data.distanceTraveled,
        "travel_time" -> data.travelTime,
        "completion_reason" -> data.completionReason,
        "total_distance" -> state.totalDistanceTraveled,
        "completed_trips" -> state.completedTrips,
        "tick" -> currentTick
      ),
      label = "person_trip_completed"
    )

    advanceToNextActivity()
  }

  /** Advance to next activity in schedule.
    */
  private def advanceToNextActivity(): Unit = {
    if (state.currentTripVehicleId.contains("walking")) {
      state.currentTripStartTick match {
        case Some(startTick) =>
          val travelTime = currentTick - startTick

          val currentActivityType = state.currentActivity.map(_.activityType).getOrElse("unknown")
          val currentMode = state.currentTripVehicleId.getOrElse("unknown")

          PersonMetrics.personTripEnd.labels(currentActivityType, currentMode).inc()
          PersonMetrics.personCompleteTripReason.labels(currentActivityType, currentMode, "completed").inc()

          report(
            data = Map(
              "event_type" -> "walking_trip_completed",
              "person_id" -> getEntityId,
              "travel_time" -> travelTime,
              "arrival_tick" -> currentTick,
              "tick" -> currentTick
            ),
            label = "person_walking_completed"
          )

          logDebug(s"${getEntityId} completed walking trip in ${travelTime}s")
        case None =>
      }

      state = state.completeTrip(0.0)
    }

    state = state.advanceActivity()

    // Replan activity timing using observed arrival in the simulator timeline.
    updateScheduleDelayOnArrival(state.currentActivityIndex)

    state.currentActivity match {
      case Some(activity) =>
        logDebug(s"${getEntityId} arrived at ${activity.activityType} (${activity.nodeId})")

        report(
          data = Map(
            "event_type" -> "activity_start",
            "person_id" -> getEntityId,
            "activity_type" -> activity.activityType,
            "activity_sequence" -> activity.sequence,
            "node_id" -> activity.nodeId,
            "end_time" -> activity.endTime,
            "tick" -> currentTick
          ),
          label = "person_activity_start"
        )

        val endTick =
          effectiveEndTick(activity)
            .map(effectiveTick => Math.max(currentTick + 1, effectiveTick))
            .getOrElse(currentTick + 1)
        onFinishSpontaneous(Some(endTick))

      case None =>
        logDebug(s"${getEntityId} completed all activities")
        PersonMetrics.completeSchedule.inc()

        report(
          data = Map(
            "event_type" -> "schedule_complete",
            "person_id" -> getEntityId,
            "total_trips" -> state.completedTrips,
            "total_distance" -> state.totalDistanceTraveled,
            "tick" -> currentTick
          ),
          label = "person_schedule_complete"
        )

        onFinishSpontaneous(None)
    }
  }
}

/** Person companion object.
  */
object Person {
  def apply(properties: Properties): Person =
    new Person(properties)
}
