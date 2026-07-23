package org.interscity.htc
package model.hybrid.actor

import core.actor.SimulationBaseActor
import core.actor.manager.loadbalance.migration.MigrationSnapshot
import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import core.types.Tick
import core.entity.actor.properties.Properties
import core.util.StringPool
import model.hybrid.entity.state.{ PersonState, TripExecutionState }
import model.hybrid.entity.state.plan.{ Activity, AtTick, ConcreteMode, PlanCursor, RemainingQueue, WalkLeg }
import model.hybrid.entity.event.data.person.{ PassengerBoardedVehicleData, PersonScheduleCompleteData, TripCompletedData }
import model.hybrid.entity.event.data.bus.{ BusRequestUnloadPassengerData, BusUnloadPassengerData, PTLineNotOperationalData }
import model.hybrid.entity.event.data.subway.{ SubwayRequestUnloadPassengerData, SubwayUnloadPassengerData }
import org.htc.protobuf.core.entity.event.control.execution.DestructEvent
import model.hybrid.support.person.{ PersonMetricsReporter, PersonPTTripHandler, PersonPlanManager, PersonPrivateVehicleTripHandler, PersonWalkingTripHandler, PlanStepResult }

import org.interscity.htc.core.api.SimulatorSettingsRegistry
import org.interscity.htc.core.metrics.core.ActorMetrics
import core.actor.trace.ActorTrace

/** Person actor - Agent-based person in the simulation.
  *
  * In the person-centric model, Person actors:
  *   - Persist throughout the simulation day
  *   - Walk a [[model.hybrid.entity.state.plan.PlanCursor]] one
  *     [[model.hybrid.entity.state.plan.PlanElement]] at a time
  *   - Resolve [[model.hybrid.entity.state.plan.PendingDecision]]s via a
  *     `model.hybrid.decision.ModeDecisionEngine` when the plan calls for a dynamic mode choice
  *   - Activate private vehicles (Car, Bicycle, Motorcycle) as needed
  *   - Receive trip completion notifications
  *
  * Lifecycle (see `docs/PERSON_AGENT.md` for the full model):
  *   1. Person starts dwelling at the first `Activity` in the plan
  *   2. On departure (per `LatenessPolicy`), the plan cursor advances into the next element
  *   3. A `PendingDecision` is resolved into concrete `AtomicLeg`s via the requested
  *      `ModeDecisionEngine`; an already-concrete `AtomicLeg` (a "fixed" leg) is started directly
  *   4. Each leg is delegated to its mode's handler (walk/PT/private vehicle)
  *   5. On leg completion the cursor advances again — repeat until the next `Activity` or the plan
  *      is exhausted
  *
  * @param properties
  *   Actor properties
  */
class Person(
  private val properties: Properties
) extends SimulationBaseActor[PersonState](
      properties = properties
    ) {

  // Truncation is deferred to the first actSpontaneous so that SimulationManager has already
  // published htc.simulation.duration to SimulatorSettingsRegistry before we run.
  private var scheduleAlreadyTruncated: Boolean = false

  // PT vehicle reference stored when Bus/Subway sends PassengerBoardedVehicleData.
  // Used by onDestruct to send isArrival=false if Person dies while still on board.
  private var currentPTVehicleRef: Option[(String, String)] = None  // (vehicleId, vehicleClassType)

  // ============================================================================
  // Support Classes (lazy initialization - created only when needed)
  // ============================================================================

  // Wrapper to adapt sendMessageTo signature from AnyRef to Any
  private def sendMessage(entityId: String, shardId: String, data: Any, eventType: String, actorType: Any): Unit = {
    sendMessageTo(entityId, shardId, data.asInstanceOf[AnyRef], eventType, actorType.asInstanceOf[org.interscity.htc.core.enumeration.CreationTypeEnum])
  }

  private lazy val metricsReporter = new PersonMetricsReporter(
    personId = getEntityId,
    reportFn = report,
    logInfo = logInfo
  )

  private lazy val walkingHandler = new PersonWalkingTripHandler(
    personId = getEntityId,
    reportFn = report,
    logDebug = logDebug,
    logError = logError
  )

  private lazy val ptHandler = new PersonPTTripHandler(
    personId = getEntityId,
    reportFn = report,
    sendMessageFn = sendMessage,
    logDebug = logDebug
  )

  private lazy val privateVehicleHandler = new PersonPrivateVehicleTripHandler(
    personId = getEntityId,
    sendMessageFn = sendMessage,
    logDebug = logDebug
  )

  private lazy val planManager = new PersonPlanManager(
    personId = getEntityId,
    walkingHandler = walkingHandler,
    ptHandler = ptHandler,
    privateVehicleHandler = privateVehicleHandler,
    metricsReporter = metricsReporter,
    reportFn = report,
    sendMessageFn = sendMessage,
    logDebug = logDebug,
    logWarn = logWarn
  )

  /** Person should only re-register on the TM after migration if it was actually registered at
    * migration time. During private-vehicle trips, Person calls onFinishSpontaneous(None) and
    * yields TM ownership to the vehicle; a PT wait similarly has no self-scheduled wake worth
    * re-registering for after a migration (shard rebalancing is disabled anyway — see CLAUDE.md).
    * Walking legs keep Person on TM (scheduled to wake at arrival tick), same as dwelling at an
    * Activity, so those are allowed.
    */
  override protected def shouldRegisterOnTimeManagerAfterMigration(): Boolean =
    state != null && state.isSetScheduleOnTimeManager && (
      state.tripExecution match {
        case TripExecutionState.Idle => true
        case _: TripExecutionState.Traveling =>
          state.cursor.executed.lastOption match {
            case Some(_: WalkLeg) => true
            case _                => false
          }
      }
    )

  /** Runs once, immediately after JSON deserialization (see `BaseActor.onFinishInitialize`):
    *
    *   1. Seeds `cursor` from `originalPlan` on a fresh scenario load — the native scenario JSON
    *      only ever carries `originalPlan`, never a `cursor` (there is nothing to resume yet), so
    *      `PersonState`'s default empty `cursor` needs this one-time seed. A migration-restored
    *      state (deserialized straight from a `MigrationSnapshot`, bypassing this hook) already
    *      carries its real in-progress `cursor` and is left untouched.
    *   2. Interns repeated string fields (activity types, node IDs, line names, stop IDs) so that
    *      all Person actors at city scale share canonical String instances instead of holding
    *      independent copies from JSON deserialization.
    */
  override protected def internStateStrings(s: PersonState): PersonState = {
    val seeded =
      if (s.cursor.executed.isEmpty && s.cursor.remaining.isEmpty && s.originalPlan.nonEmpty)
        s.copy(cursor = PlanCursor(executed = Nil, remaining = RemainingQueue(s.originalPlan)))
      else s
    seeded.withInternedStrings
  }

  /** Truncate plan elements whose Activity endTime exceeds the simulation duration.
    *
    * Called lazily on the first actSpontaneous (not onStart) so that SimulationManager has
    * already published htc.simulation.duration to SimulatorSettingsRegistry. Only ever rebuilds
    * the cursor from `originalPlan` — `originalPlan` itself is never mutated (kept for
    * provenance), and this only runs before the cursor has advanced at all.
    */
  private def applyScheduleTruncationIfNeeded(): Unit =
    if (!scheduleAlreadyTruncated) {
      scheduleAlreadyTruncated = true
      if (state != null) state = truncateIfEnabled(state)
    }

  private def truncateIfEnabled(s: PersonState): PersonState = {
    val truncate = SimulatorSettingsRegistry
      .get("htc.person.truncate-schedule-beyond-duration")
      .orElse(sys.env.get("HTC_PERSON_TRUNCATE_SCHEDULE"))
      .orElse(scala.util.Try(config.getString("htc.person.truncate-schedule-beyond-duration")).toOption)
      .exists(_.equalsIgnoreCase("true"))

    if (!truncate || s.originalPlan.isEmpty) return s

    SimulatorSettingsRegistry
      .get("htc.simulation.duration")
      .flatMap(v => scala.util.Try(v.toLong).toOption) match {
      case None =>
        logWarn(s"${getEntityId} htc.simulation.duration not set in registry — schedule truncation skipped")
        s
      case Some(duration) =>
        val cutIndex = s.originalPlan.indexWhere {
          case Activity(_, _, AtTick(t)) => t > duration
          case _                         => false
        }
        if (cutIndex < 0) {
          s
        } else {
          val truncated = s.originalPlan.take(cutIndex)
          logDebug(
            s"${getEntityId} truncated plan to ${truncated.size}/${s.originalPlan.size} elements (duration=$duration)"
          )
          report(
            data = Map(
              "event_type" -> "schedule_truncated",
              "person_id" -> getEntityId,
              "remaining_count" -> truncated.size,
              "original_count" -> s.originalPlan.size,
              "simulation_duration" -> duration
            ),
            label = "person_schedule_truncated"
          )
          s.copy(cursor = PlanCursor(executed = Nil, remaining = RemainingQueue(truncated)))
        }
    }
  }

  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    applyScheduleTruncationIfNeeded()
    if (state == null) {
      logWarn(s"${getEntityId} actSpontaneous called with null state at tick=$currentTick — unscheduling")
      onFinishSpontaneous(None)
      return
    }

    if (state.isScheduleComplete)
      ActorMetrics.spontaneousEventAfterCompletion.labels(getClass.getSimpleName, "spontaneous").inc()

    state.tripExecution match {
      case t: TripExecutionState.Traveling if t.ptWait.isDefined =>
        dispatch(planManager.replanAfterPTTimeout(state, currentTick))
      case _: TripExecutionState.Traveling =>
        dispatch(planManager.continueAfterWalkArrival(state, currentTick))
      case TripExecutionState.Idle =>
        dispatch(planManager.step(state, currentTick))
    }
  }

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: TripCompletedData =>
        dispatch(planManager.handleVehicleTripCompleted(state, d, currentTick))

      case d: PassengerBoardedVehicleData =>
        currentPTVehicleRef = Some((d.vehicleId, d.vehicleClassType))

      case d: BusRequestUnloadPassengerData =>
        planManager.handlePTUnloadRequest(event, d.nodeId, ConcreteMode.Bus, state, currentTick).foreach {
          result =>
            currentPTVehicleRef = None
            dispatch(result)
        }

      case d: SubwayRequestUnloadPassengerData =>
        planManager.handlePTUnloadRequest(event, d.nodeId, ConcreteMode.Subway, state, currentTick).foreach {
          result =>
            currentPTVehicleRef = None
            dispatch(result)
        }

      case d: PTLineNotOperationalData =>
        dispatch(planManager.handlePTLineNotOperational(d, state, currentTick))

      case _ =>
        logWarn(s"Person event not handled: ${event.eventType}")
    }

  /** Applies one [[PlanStepResult]] to actor state and resolves the corresponding
    * spontaneous-event obligation. See [[PlanStepResult]]'s own doc for what each case means.
    */
  private def dispatch(result: PlanStepResult): Unit = result match {
    case PlanStepResult.Awaiting(newState, wakeTick) =>
      state = newState
      onFinishSpontaneous(Some(wakeTick))

    case PlanStepResult.LegStarted(newState, Some(nextTick)) =>
      state = newState
      onFinishSpontaneous(Some(nextTick))

    case PlanStepResult.LegStarted(newState, None) =>
      state = newState
      onFinishSpontaneous(None)

    case PlanStepResult.Finished(newState) =>
      state = newState
      logDebug(s"${getEntityId} completed daily schedule (${state.completedTrips} trips, ${state.totalDistanceTraveled}m)")
      metricsReporter.reportScheduleComplete(
        totalTrips = state.completedTrips,
        totalDistance = state.totalDistanceTraveled,
        currentTick = currentTick
      )
      planManager.notifyVehiclesScheduleComplete(state)
      onFinishSpontaneous(None, destruct = true)
  }

  /** Carries the boarding barrier's reply-linkage field (`currentPTVehicleRef`) across a shard
    * migration.
    *
    * `currentPTVehicleRef` is an actor-local `var`, not part of `PersonState`, so the default
    * `BaseActor.buildMigrationSnapshot` (which only serializes `state`) would silently drop it on
    * migration. `onDestruct` depends on it to answer the boarded Bus/Subway's unload barrier
    * (`expectedUnloadResponses`) on every path (commit `531ca55`) — losing it on migration reopens
    * that barrier deadlock. Follows the same override pattern `SimulationBaseActor` already uses
    * for `currentTick`/`lamportClock`/`relationships`.
    */
  override protected def buildMigrationSnapshot(): MigrationSnapshot = {
    val base = super.buildMigrationSnapshot()
    base.copy(
      currentPTVehicleRefId = currentPTVehicleRef.map(_._1).getOrElse(""),
      currentPTVehicleRefClassType = currentPTVehicleRef.map(_._2).getOrElse("")
    )
  }

  /** Restores `currentPTVehicleRef` from a migration snapshot. See [[buildMigrationSnapshot]] for
    * why this must be carried explicitly.
    */
  override protected def applyMigrationSnapshot(snapshot: MigrationSnapshot): Unit = {
    super.applyMigrationSnapshot(snapshot)

    currentPTVehicleRef =
      if (snapshot.currentPTVehicleRefId.nonEmpty)
        Some((snapshot.currentPTVehicleRefId, snapshot.currentPTVehicleRefClassType))
      else None
  }

  /** If Person dies while on board a PT vehicle (Bus or Subway), send isArrival=false so the
    * vehicle's unload counter completes and it is not stuck waiting forever.
    *
    * This covers the race between a DestructEvent and the vehicle's unload request arriving in
    * Person's mailbox — whichever order they appear, the vehicle always gets a response.
    */
  override def onDestruct(event: DestructEvent): Unit =
    currentPTVehicleRef.foreach { case (vehicleId, vehicleClassType) =>
      val responseData: AnyRef =
        if (vehicleClassType == "hybrid.actor.Subway") SubwayUnloadPassengerData(isArrival = false)
        else BusUnloadPassengerData(isArrival = false)
      sendMessageTo(vehicleId, vehicleClassType, responseData, "UnloadPassengerResponse")
      logDebug(s"${getEntityId} sent isArrival=false to $vehicleId on destruct (still on board PT vehicle)")
    }
}

/** Person companion object.
  */
object Person {
  def apply(properties: Properties): Person =
    new Person(properties)
}
