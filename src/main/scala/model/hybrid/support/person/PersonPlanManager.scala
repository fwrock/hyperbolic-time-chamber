package org.interscity.htc
package model.hybrid.support.person

import core.entity.event.ActorInteractionEvent
import core.types.Tick
import model.hybrid.decision.{ DecisionContext, ModeDecisionEngineRegistry, NoViableJourney }
import model.hybrid.entity.event.data.bus.PTLineNotOperationalData
import model.hybrid.entity.event.data.person.{ PersonScheduleCompleteData, TripCompletedData }
import model.hybrid.entity.state.{ PTWaitState, PersonState, TripExecutionState }
import model.hybrid.entity.state.plan._
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import org.interscity.htc.core.metrics.model.hybrid.GPSMetrics

/** Orchestrates a person's plan execution: walks [[PlanCursor]] one [[PlanElement]] at a time,
  * dispatching each [[Activity]]/[[AtomicLeg]] to the right handler and resolving
  * [[PendingDecision]]s via `model.hybrid.decision.ModeDecisionEngine`.
  *
  * Replaces the pre-redesign `PersonTripManager` + `PersonModeChoiceHandler` +
  * `PersonActivityManager` (their responsibilities are now unified around a single plan cursor
  * instead of a `dailySchedule`/`currentActivityIndex` pair plus ~15 loose trip-tracking fields —
  * see `PersonState`/`TripExecutionState` docs).
  *
  * All decision points not fully specified by the phase brief are documented at the point they're
  * made below (search for "Documented default").
  *
  * @param personId Person actor ID
  * @param walkingHandler Walking leg handler
  * @param ptHandler Public transport leg handler
  * @param privateVehicleHandler Private vehicle leg handler
  * @param metricsReporter Metrics reporter
  * @param reportFn Reporting function for events
  * @param logDebug Debug logging function
  * @param logWarn Warning logging function
  */
class PersonPlanManager(
  personId: String,
  walkingHandler: PersonWalkingTripHandler,
  ptHandler: PersonPTTripHandler,
  privateVehicleHandler: PersonPrivateVehicleTripHandler,
  metricsReporter: PersonMetricsReporter,
  reportFn: (Map[String, Any], String) => Unit,
  sendMessageFn: (String, String, Any, String, Any) => Unit,
  logDebug: String => Unit,
  logWarn: String => Unit
) {

  import PlanStepResult._

  /** Documented default: the engine consulted to replan a leg that was never behind a
    * [[PendingDecision]] in the first place (a "fixed" leg already concrete in the original plan)
    * and therefore has no `strategyId`/`allowedModes` of its own to fall back on if its PT portion
    * times out. `"travel-time"` is picked because it is the only one of the three built-in engines
    * that evaluates every mode (see `TravelTimeEngine`'s doc) — the most permissive reasonable
    * default for a situation the original plan never anticipated.
    */
  private val DefaultReplanStrategyId: String = "travel-time"
  private val DefaultReplanAllowedModes: Set[ConcreteMode] = ConcreteMode.values.toSet

  private def nextTripId(state: PersonState): String =
    s"$personId:trip:${state.completedTrips + 1}"

  private def physicalNodeIdBefore(state: PersonState): String =
    state.currentPhysicalNodeId.getOrElse {
      logWarn(s"$personId has no resolvable physical node (empty plan?) — using empty origin")
      ""
    }

  /** Looks (without consuming) for the next [[Activity]] at the head of `remaining` — this is
    * always where a trip's legs lead back to, whether they came from a fixed leg run or an
    * expanded [[PendingDecision]].
    */
  private def peekNextActivity(remaining: RemainingQueue): Option[Activity] =
    remaining.dequeue.collect { case (a: Activity, _) => a }

  private def modeLabel(mode: ConcreteMode): String = mode.toString.toLowerCase

  /** Advance the plan by exactly one element, dispatching Activities/AtomicLegs/PendingDecisions
    * as appropriate. `pendingHint`, when present, carries the `strategyId`/`allowedModes` of the
    * [[PendingDecision]] whose resolution produced the leg now being started — used only when the
    * leg starting is the *first* one of a new contiguous run (see [[TripExecutionState.Traveling]]
    * for why this needs to be threaded through rather than re-derived).
    */
  def step(
    state: PersonState,
    currentTick: Tick,
    pendingHint: Option[(String, Set[ConcreteMode])] = None
  ): PlanStepResult =
    PlanCursor.advance(state.cursor) match {
      case ScheduleComplete(finalCursor) =>
        Finished(state.copy(cursor = finalCursor, tripExecution = TripExecutionState.Idle))

      case Advanced(next) =>
        next.executed.last match {
          case activity: Activity => onArrivedAtActivity(state, next, activity, currentTick)
          case leg: AtomicLeg     => onLegAdvanced(state, next, leg, currentTick, pendingHint)
        }

      case pr: PendingResolutionRequired =>
        resolvePending(pr, state, currentTick)
    }

  private def onArrivedAtActivity(
    state: PersonState,
    next: PlanCursor,
    activity: Activity,
    currentTick: Tick
  ): PlanStepResult = {
    // Flush trip bookkeeping (completedTrips + accumulated distance) exactly once, when the
    // contiguous leg run that led here actually finishes. A plan that starts with back-to-back
    // Activities (no legs at all — e.g. a same-node "instant" transition) never enters Traveling,
    // so this is a no-op in that case.
    val baseState = state.tripExecution match {
      case t: TripExecutionState.Traveling => state.completeTrip(t.accumulatedDistance)
      case TripExecutionState.Idle         => state
    }
    val withCursor = baseState.copy(cursor = next)

    val activitySequence = next.executed.count(_.isInstanceOf[Activity]) - 1
    metricsReporter.reportActivityStart(
      activityType = activity.activityType,
      activitySequence = activitySequence,
      nodeId = activity.nodeId,
      endTime = activity.endTime.toString,
      currentTick = currentTick
    )
    logDebug(s"$personId arrived at ${activity.activityType} (${activity.nodeId})")

    val departureTick = MinimumDwellLatenessPolicy.resolveDepartureTick(activity.endTime, currentTick)
    Awaiting(withCursor, departureTick)
  }

  private def onLegAdvanced(
    state: PersonState,
    next: PlanCursor,
    leg: AtomicLeg,
    currentTick: Tick,
    pendingHint: Option[(String, Set[ConcreteMode])]
  ): PlanStepResult = {
    val (tripId, replanStrategyId, replanAllowedModes, isFirstLegOfTrip) = state.tripExecution match {
      case t: TripExecutionState.Traveling => (t.tripId, t.replanStrategyId, t.replanAllowedModes, false)
      case TripExecutionState.Idle =>
        val (rid, rmodes) = pendingHint.getOrElse((DefaultReplanStrategyId, DefaultReplanAllowedModes))
        (nextTripId(state), rid, rmodes, true)
    }

    if (isFirstLegOfTrip) {
      val destActivityType = peekNextActivity(next.remaining).map(_.activityType).getOrElse("unknown")
      metricsReporter.recordTripStart(destActivityType, modeLabel(leg.mode))
    }
    metricsReporter.reportTripStarted(tripId, modeLabel(leg.mode), currentTick)

    val origin = physicalNodeIdBefore(state)

    leg match {
      case w: WalkLeg =>
        walkingHandler.initiateWalkingTrip(w, currentTick, logWarn) match {
          case Some(arrivalTick) =>
            val traveling = TripExecutionState.Traveling(
              physicalNodeId = w.originNodeId,
              tripId = tripId,
              legStartTick = currentTick,
              ptWait = None,
              replanStrategyId = replanStrategyId,
              replanAllowedModes = replanAllowedModes
            )
            LegStarted(state.copy(cursor = next, tripExecution = traveling), Some(arrivalTick))
          case None =>
            abortCurrentRun(next, state, currentTick, s"no walking route ${w.originNodeId} -> ${w.destinationNodeId}")
        }

      case pv: PrivateVehicleLeg =>
        val destination = peekNextActivity(next.remaining).map(_.nodeId).getOrElse(origin)
        privateVehicleHandler.initiatePrivateVehicleTrip(origin, destination, pv, currentTick)
        val traveling = TripExecutionState.Traveling(
          physicalNodeId = origin,
          tripId = tripId,
          legStartTick = currentTick,
          ptWait = None,
          replanStrategyId = replanStrategyId,
          replanAllowedModes = replanAllowedModes
        )
        // Vehicle controls timing; the vehicle actor guarantees a TripCompleted notification on
        // its own destruction, so Person yields the Time Manager (None) until then.
        LegStarted(state.copy(cursor = next, tripExecution = traveling), None)

      case t: TransitLeg =>
        ptHandler.initiatePTTrip(t, currentTick)
        val timeoutTick = currentTick + state.ptWaitTimeoutTicks
        val traveling = TripExecutionState.Traveling(
          physicalNodeId = origin,
          tripId = tripId,
          legStartTick = currentTick,
          ptWait = Some(PTWaitState(currentTick, timeoutTick, t.alightingStop.nodeId, t.line)),
          replanStrategyId = replanStrategyId,
          replanAllowedModes = replanAllowedModes
        )
        LegStarted(state.copy(cursor = next, tripExecution = traveling), Some(timeoutTick))
    }
  }

  private def resolvePending(pr: PendingResolutionRequired, state: PersonState, currentTick: Tick): PlanStepResult = {
    val originNodeId = physicalNodeIdBefore(state)
    val destinationNodeId = peekNextActivity(pr.cursorWithoutHead.remaining).map(_.nodeId).getOrElse(originNodeId)
    val request = pr.pending.decision

    ModeDecisionEngineRegistry.get(request.strategyId) match {
      case None =>
        // Should not happen if ScenarioLoadValidator.validateModeDecisionEngines ran at load time —
        // kept as a defensive branch, not a silently-swallowed error.
        val reason = s"unknown strategyId ${request.strategyId}"
        logWarn(s"$personId: unknown mode-decision strategyId '${request.strategyId}' — should have failed scenario load validation")
        abortPendingDecision(pr, state, currentTick, reason)

      case Some(engine) =>
        val ctx = DecisionContext(
          weights = state.modeChoiceWeights,
          ownedVehicles = state.ownedVehicles,
          vehicleCurrentNode = state.vehicleCurrentNode,
          currentTick = currentTick,
          entityId = personId
        )
        engine.decide(originNodeId, destinationNodeId, request, ctx) match {
          case Right(legs) =>
            val expanded = PlanCursor.expandPending(pr, legs)
            step(state.copy(cursor = expanded), currentTick, pendingHint = Some((request.strategyId, request.allowedModes)))
          case Left(NoViableJourney(reason)) =>
            // Unified with the PT-timeout replan failure policy (see replanAfterPTTimeout): a
            // PendingDecision with no viable journey aborts just this trip and resumes at the
            // next Activity, exactly like any other leg-initiation failure — it does not leave
            // the person permanently unscheduled. (Earlier revisions of this method treated the
            // two as different failure modes; that asymmetry was a deliberate follow-up question
            // in the phase brief, since resolved by the user in favor of this uniform behavior.)
            logWarn(s"$personId: no viable journey for pending decision ($reason) — aborting this trip, resuming at the next activity")
            abortPendingDecision(pr, state, currentTick, reason)
        }
    }
  }

  /** A [[PendingDecision]] that could not be resolved at all (unknown strategyId, or
    * `NoViableJourney`) is treated the same as any other leg-initiation failure: no legs exist yet
    * for this trip, so there is nothing to drop but the pending decision itself —
    * `pr.cursorWithoutHead` already has it removed — and stepping resumes directly at whatever
    * comes next (normally the following `Activity`).
    */
  private def abortPendingDecision(pr: PendingResolutionRequired, state: PersonState, currentTick: Tick, reason: String): PlanStepResult = {
    reportFn(
      Map("event_type" -> "trip_aborted", "person_id" -> personId, "reason" -> reason, "tick" -> currentTick),
      "person_trip_aborted"
    )
    step(state.copy(cursor = pr.cursorWithoutHead, tripExecution = TripExecutionState.Idle), currentTick)
  }

  /** Called when a person, self-woken while [[TripExecutionState.Traveling]] with a live
    * [[PTWaitState]], has reached that wait's `timeoutTick` without the vehicle ever loading them.
    * Re-consults the engine that produced the current trip's legs (or the documented default, see
    * `DefaultReplanStrategyId`) for a fresh route from the person's current physical position to
    * the next `Activity`, discarding only the remaining legs of the current run.
    */
  def replanAfterPTTimeout(state: PersonState, currentTick: Tick): PlanStepResult =
    state.tripExecution match {
      case t @ TripExecutionState.Traveling(physicalNodeId, _, _, Some(ptWait), replanStrategyId, replanAllowedModes, _) =>
        val waited = currentTick - ptWait.waitingSinceTick
        logWarn(s"$personId PT wait for line ${ptWait.line} timed out after $waited ticks — triggering replan")

        val (_, afterCurrentRun) = state.cursor.remaining.dropCurrentLegRun
        val destinationNodeId = peekNextActivity(afterCurrentRun).map(_.nodeId).getOrElse(physicalNodeId)
        val stateAtActivityBoundary = state.copy(cursor = state.cursor.copy(remaining = afterCurrentRun))

        ModeDecisionEngineRegistry.get(replanStrategyId) match {
          case None =>
            reportFn(
              Map(
                "event_type" -> "pt_timeout_replan_failed",
                "person_id" -> personId,
                "line" -> ptWait.line,
                "reason" -> s"unknown replan strategyId $replanStrategyId",
                "tick" -> currentTick
              ),
              "person_pt_replan_failed"
            )
            abortToNextActivity(stateAtActivityBoundary, currentTick)

          case Some(engine) =>
            val ctx = DecisionContext(
              weights = state.modeChoiceWeights,
              ownedVehicles = state.ownedVehicles,
              vehicleCurrentNode = state.vehicleCurrentNode,
              currentTick = currentTick,
              entityId = personId
            )
            val request = ModeDecisionRequest(allowedModes = replanAllowedModes, strategyId = replanStrategyId)
            engine.decide(physicalNodeId, destinationNodeId, request, ctx) match {
              case Right(legs) =>
                reportFn(
                  Map(
                    "event_type" -> "pt_timeout_replanned",
                    "person_id" -> personId,
                    "line" -> ptWait.line,
                    "new_leg_count" -> legs.size,
                    "tick" -> currentTick
                  ),
                  "person_pt_replanned"
                )
                val replanned = PlanCursor.expandReplan(state.cursor, legs)
                step(state.copy(cursor = replanned), currentTick, pendingHint = Some((replanStrategyId, replanAllowedModes)))
              case Left(NoViableJourney(reason)) =>
                reportFn(
                  Map(
                    "event_type" -> "pt_timeout_replan_failed",
                    "person_id" -> personId,
                    "line" -> ptWait.line,
                    "reason" -> reason,
                    "tick" -> currentTick
                  ),
                  "person_pt_replan_failed"
                )
                abortToNextActivity(stateAtActivityBoundary, currentTick)
            }
        }

      case other =>
        logWarn(s"$personId replanAfterPTTimeout called without an active PT wait (tripExecution=$other) — resuming normal stepping")
        step(state, currentTick)
    }

  /** Called when a person self-wakes while [[TripExecutionState.Traveling]] with no PT wait —
    * i.e. a walking leg has just arrived (private-vehicle legs never self-schedule; see
    * `onLegAdvanced`).
    */
  def continueAfterWalkArrival(state: PersonState, currentTick: Tick): PlanStepResult =
    (state.cursor.executed.lastOption, state.tripExecution) match {
      case (Some(w: WalkLeg), t: TripExecutionState.Traveling) =>
        walkingHandler.reportWalkingCompleted(currentTick - t.legStartTick, currentTick)
        step(state.copy(tripExecution = t.copy(physicalNodeId = w.destinationNodeId)), currentTick)
      case _ =>
        logWarn(s"$personId woke mid-travel but the last executed element was not a WalkLeg — resuming stepping anyway")
        step(state, currentTick)
    }

  /** Called on [[TripCompletedData]] from a private vehicle. */
  def handleVehicleTripCompleted(state: PersonState, data: TripCompletedData, currentTick: Tick): PlanStepResult =
    (state.cursor.executed.lastOption, state.tripExecution) match {
      case (Some(pv: PrivateVehicleLeg), t: TripExecutionState.Traveling) =>
        val mode = modeLabel(pv.mode)
        val activityType = peekNextActivity(state.cursor.remaining).map(_.activityType).getOrElse("unknown")

        metricsReporter.recordTripEnd(activityType, mode, data.completionReason)
        if (data.wasTeleported) GPSMetrics.teleportCount.labels(mode).inc()

        metricsReporter.reportTripAndLegMetrics(
          mode = mode,
          tripId = t.tripId,
          departureTime = t.legStartTick,
          arrivalTick = currentTick,
          travelTime = data.travelTime,
          distance = Some(data.distanceTraveled),
          waitTime = Some(0L),
          currentTick = currentTick
        )
        metricsReporter.reportTripCompleted(
          vehicleId = data.vehicleId,
          distanceTraveled = data.distanceTraveled,
          travelTime = data.travelTime,
          completionReason = data.completionReason,
          totalDistance = state.totalDistanceTraveled + t.accumulatedDistance + data.distanceTraveled,
          completedTrips = state.completedTrips + 1,
          currentTick = currentTick
        )

        val updatedTraveling = t.copy(
          physicalNodeId = data.finalNode,
          accumulatedDistance = t.accumulatedDistance + data.distanceTraveled
        )
        var updatedState = state.copy(tripExecution = updatedTraveling)
        updatedState = privateVehicleHandler.updateVehicleLocation(pv.mode, data.finalNode, updatedState)
        step(updatedState, currentTick)

      case _ =>
        logWarn(
          s"$personId received TripCompleted from ${data.vehicleId} while not on a matching private vehicle leg " +
            "— likely stale after a replan/timeout, ignoring"
        )
        step(state, currentTick)
    }

  /** Called on a bus/subway unload request. Always replies via [[PersonPTTripHandler]] — see that
    * method's doc for why this is consistency-critical from the vehicle's perspective.
    *
    * @return the updated state, and `PlanStepResult` to dispatch (`None` when the person is
    *         staying on board and Person's own spontaneous scheduling is unaffected).
    */
  def handlePTUnloadRequest(
    event: ActorInteractionEvent,
    nodeId: String,
    mode: ConcreteMode,
    state: PersonState,
    currentTick: Tick
  ): Option[PlanStepResult] =
    state.tripExecution match {
      case t: TripExecutionState.Traveling if t.ptWait.isDefined =>
        val ptWait = t.ptWait.get
        val isArrival = ptHandler.handlePTUnloadRequest(event, nodeId, mode, ptWait.alightingNodeId)
        if (!isArrival) {
          None
        } else {
          val travelTime = currentTick - t.legStartTick
          metricsReporter.reportTripAndLegMetrics(
            mode = modeLabel(mode),
            tripId = t.tripId,
            departureTime = t.legStartTick,
            arrivalTick = currentTick,
            travelTime = travelTime,
            distance = None,
            waitTime = Some(0L),
            currentTick = currentTick
          )
          reportFn(
            Map(
              "event_type" -> "pt_trip_completed",
              "person_id" -> personId,
              "line" -> ptWait.line,
              "alighting_node" -> nodeId,
              "travel_time" -> travelTime,
              "tick" -> currentTick
            ),
            "person_pt_trip_completed"
          )
          val updated = state.copy(tripExecution = t.copy(physicalNodeId = nodeId, ptWait = None))
          Some(step(updated, currentTick))
        }
      case _ =>
        logWarn(s"$personId received PT unload request from ${event.actorRefId} while not waiting on a PT leg — ignoring (already handled)")
        None
    }

  /** Called on [[PTLineNotOperationalData]]: the currently boarded/awaited PT line was ejected by
    * the operator. The trip is aborted the same way a leg-initiation failure is (see
    * [[abortCurrentRun]]) — there is no salvageable position to replan from mid-line-cancellation.
    */
  def handlePTLineNotOperational(data: PTLineNotOperationalData, state: PersonState, currentTick: Tick): PlanStepResult =
    abortCurrentRun(state.cursor, state, currentTick, s"PT line ${data.line} not operational")

  /** Drops the remaining legs of the current contiguous run and resumes stepping — used both when
    * a leg fails to even start (route not found, in [[onLegAdvanced]]) and when an in-flight PT
    * leg is cancelled outright ([[handlePTLineNotOperational]]).
    */
  private def abortCurrentRun(cursor: PlanCursor, state: PersonState, currentTick: Tick, reason: String): PlanStepResult = {
    logWarn(s"$personId aborting current trip: $reason")
    reportFn(
      Map("event_type" -> "trip_aborted", "person_id" -> personId, "reason" -> reason, "tick" -> currentTick),
      "person_trip_aborted"
    )
    val (_, rest) = cursor.remaining.dropCurrentLegRun
    step(state.copy(cursor = cursor.copy(remaining = rest), tripExecution = TripExecutionState.Idle), currentTick)
  }

  private def abortToNextActivity(state: PersonState, currentTick: Tick): PlanStepResult =
    step(state.copy(tripExecution = TripExecutionState.Idle), currentTick)

  /** Send schedule complete notification to all owned vehicles. */
  def notifyVehiclesScheduleComplete(state: PersonState): Unit =
    state.ownedVehicles.foreach {
      case (_, vehicleRef) =>
        sendMessageFn(
          vehicleRef.id,
          vehicleRef.classType,
          PersonScheduleCompleteData(personId = personId),
          "PersonScheduleComplete",
          LoadBalancedDistributed
        )
    }
}
