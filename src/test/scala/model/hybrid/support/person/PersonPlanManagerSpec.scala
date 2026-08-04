package org.interscity.htc
package model.hybrid.support.person

import core.types.Tick
import model.hybrid.decision.{ DecisionContext, ModeDecisionEngine, ModeDecisionEngineRegistry, NoViableJourney, ScenarioValidationContext }
import model.hybrid.entity.event.data.person.TripCompletedData
import model.hybrid.entity.state.plan._
import model.hybrid.entity.state.{ PersonState, TripExecutionState }
import org.htc.protobuf.core.entity.actor.Identify

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** Integration coverage for [[PersonPlanManager]] — the orchestration that replaced the
  * pre-redesign `PersonTripManager`/`PersonModeChoiceHandler`/`PersonActivityManager`.
  *
  * Deliberately exercises the manager directly (no `Person` actor, no Pekko `TestKit`) per this
  * repo's testing convention: handlers are stateless, lambda-injected classes with no Pekko
  * dependency, so plain ScalaTest is both cheaper and less brittle than standing up an actor. Every
  * `WalkLeg` here uses `precomputedRoute = Some(Nil)` specifically to avoid touching
  * `CityMapUtil` (a JVM-wide singleton that throws if `city_map.json` isn't present — see
  * `RaptorMultiModalEngineSpec`'s doc for the same constraint).
  */
class PersonPlanManagerSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private val sentMessages = mutable.Buffer.empty[(String, String, Any, String, Any)]
  private val reports = mutable.Buffer.empty[(Map[String, Any], String)]

  private def resetCapture(): Unit = {
    sentMessages.clear()
    reports.clear()
  }

  override def beforeEach(): Unit = resetCapture()

  private val noop: String => Unit = _ => ()

  private def newManager(personId: String = "person-1"): PersonPlanManager = {
    val metricsReporter = new PersonMetricsReporter(personId, (m, l) => reports += ((m, l)), noop)
    val walkingHandler = new PersonWalkingTripHandler(personId, (m, l) => reports += ((m, l)), noop, noop)
    val ptHandler = new PersonPTTripHandler(
      personId,
      (m, l) => reports += ((m, l)),
      (e, s, d, t, a) => sentMessages += ((e, s, d, t, a)),
      noop
    )
    val privateVehicleHandler = new PersonPrivateVehicleTripHandler(
      personId,
      (e, s, d, t, a) => sentMessages += ((e, s, d, t, a)),
      noop
    )
    new PersonPlanManager(
      personId = personId,
      walkingHandler = walkingHandler,
      ptHandler = ptHandler,
      privateVehicleHandler = privateVehicleHandler,
      metricsReporter = metricsReporter,
      reportFn = (m, l) => reports += ((m, l)),
      sendMessageFn = (e, s, d, t, a) => sentMessages += ((e, s, d, t, a)),
      logDebug = noop,
      logWarn = noop
    )
  }

  private def stateWithPlan(plan: List[PlanElement], ptWaitTimeoutTicks: Long = 50L, ownedVehicles: Map[String, Identify] = Map.empty): PersonState =
    PersonState(
      cursor = PlanCursor(executed = Nil, remaining = RemainingQueue(plan)),
      originalPlan = plan,
      ownedVehicles = ownedVehicles,
      ptWaitTimeoutTicks = ptWaitTimeoutTicks
    )

  // ---------------------------------------------------------------------------------------------
  // (a) A full day: a fixed (already-resolved) private-vehicle leg, an activity, then a
  //     PendingDecision resolved dynamically into a walk leg, ending at a final activity.
  // ---------------------------------------------------------------------------------------------

  "PersonPlanManager" should "run a full day: fixed car leg -> activity -> dynamically-resolved walk leg -> activity -> ScheduleComplete" in {
    val testEngineId = "test-walk-engine-a"
    ModeDecisionEngineRegistry.register(testEngineId, new ModeDecisionEngine {
      override val id: String = testEngineId
      override def validateForScenario(ctx: ScenarioValidationContext): Either[model.hybrid.decision.EngineUnavailable, Unit] = Right(())
      override def decide(
        originNodeId: String,
        destinationNodeId: String,
        request: ModeDecisionRequest,
        ctx: DecisionContext
      ): Either[NoViableJourney, List[AtomicLeg]] =
        Right(List(WalkLeg(originNodeId, destinationNodeId, precomputedRoute = Some(Nil))))
    })

    val vehicle = Identify(id = "car-1", classType = "hybrid.actor.Car")
    val plan: List[PlanElement] = List(
      Activity("home", "n1", AtTick(100)),
      PrivateVehicleLeg(ConcreteMode.Car, vehicle),
      Activity("work", "n2", AtTick(500)),
      PendingDecision(ModeDecisionRequest(allowedModes = Set(ConcreteMode.Walk), strategyId = testEngineId)),
      Activity("home", "n3", AtTick(900))
    )
    val manager = newManager()
    val state0 = stateWithPlan(plan, ownedVehicles = Map("car" -> vehicle))

    // Advance into the first Activity.
    val PlanStepResult.Awaiting(state1, wake1) = manager.step(state0, currentTick = 0): @unchecked
    wake1 shouldBe 100L
    state1.cursor.executed shouldBe List(Activity("home", "n1", AtTick(100)))

    // Departure: start the fixed car leg.
    val PlanStepResult.LegStarted(state2, nextTick2) = manager.step(state1, currentTick = 100): @unchecked
    nextTick2 shouldBe None // vehicle-controlled timing
    state2.tripExecution shouldBe a[TripExecutionState.Traveling]
    sentMessages.exists { case (id, _, _, eventType, _) => id == "car-1" && eventType == "StartTrip" } shouldBe true

    // Vehicle reports trip completion with real distance traveled.
    val completed = TripCompletedData(
      vehicleId = "car-1",
      personId = "person-1",
      distanceTraveled = 1234.0,
      travelTime = 50L,
      finalNode = "n2",
      completionTick = 150L,
      completionReason = "reached_destination"
    )
    val PlanStepResult.Awaiting(state3, wake3) = manager.handleVehicleTripCompleted(state2, completed, currentTick = 150): @unchecked
    wake3 shouldBe 500L
    state3.completedTrips shouldBe 1
    state3.totalDistanceTraveled shouldBe 1234.0
    state3.tripExecution shouldBe TripExecutionState.Idle

    // Departure at the second activity resolves the PendingDecision via the registered engine.
    val PlanStepResult.LegStarted(state4, nextTick4) = manager.step(state3, currentTick = 500): @unchecked
    nextTick4 shouldBe Some(500L) // zero-distance precomputed walk arrives instantly
    state4.cursor.executed.exists {
      case WalkLeg("n2", "n3", _) => true
      case _                      => false
    } shouldBe true

    // Walk arrival advances to the final activity.
    val PlanStepResult.Awaiting(state5, wake5) = manager.continueAfterWalkArrival(state4, currentTick = 500): @unchecked
    wake5 shouldBe 900L
    state5.completedTrips shouldBe 2

    // End of day.
    val finished = manager.step(state5, currentTick = 900)
    finished shouldBe a[PlanStepResult.Finished]
    finished.asInstanceOf[PlanStepResult.Finished].state.isScheduleComplete shouldBe true
  }

  // ---------------------------------------------------------------------------------------------
  // (b) PT wait timeout triggers a replan that reaches the same destination — the trip is not
  //     silently dropped.
  // ---------------------------------------------------------------------------------------------

  it should "replan (not drop) a trip when a PT wait times out, reaching the same destination via a walk leg" in {
    val testEngineId = "test-pt-engine-b"
    var decideCallCount = 0
    ModeDecisionEngineRegistry.register(testEngineId, new ModeDecisionEngine {
      override val id: String = testEngineId
      override def validateForScenario(ctx: ScenarioValidationContext): Either[model.hybrid.decision.EngineUnavailable, Unit] = Right(())
      override def decide(
        originNodeId: String,
        destinationNodeId: String,
        request: ModeDecisionRequest,
        ctx: DecisionContext
      ): Either[NoViableJourney, List[AtomicLeg]] = {
        decideCallCount += 1
        if (decideCallCount == 1)
          Right(List(TransitLeg(
            ConcreteMode.Bus,
            "L1",
            boardingStop = StopRef("stop-1", "hybrid.actor.BusStop", originNodeId),
            alightingStop = StopRef("stop-2", "hybrid.actor.BusStop", destinationNodeId)
          )))
        else
          Right(List(WalkLeg(originNodeId, destinationNodeId, precomputedRoute = Some(Nil))))
      }
    })

    val plan: List[PlanElement] = List(
      Activity("home", "n1", AtTick(10)),
      PendingDecision(ModeDecisionRequest(allowedModes = Set(ConcreteMode.Bus, ConcreteMode.Walk), strategyId = testEngineId)),
      Activity("work", "n5", AtTick(1000))
    )
    val manager = newManager()
    val state0 = stateWithPlan(plan, ptWaitTimeoutTicks = 50L)

    val PlanStepResult.Awaiting(state1, wake1) = manager.step(state0, currentTick = 0): @unchecked
    wake1 shouldBe 10L

    // Departure resolves the PendingDecision into a bus leg and registers at the stop.
    val PlanStepResult.LegStarted(state2, nextTick2) = manager.step(state1, currentTick = 10): @unchecked
    nextTick2 shouldBe Some(60L) // 10 + ptWaitTimeoutTicks
    val traveling2 = state2.tripExecution.asInstanceOf[TripExecutionState.Traveling]
    traveling2.ptWait.map(_.alightingNodeId) shouldBe Some("n5")
    traveling2.replanStrategyId shouldBe testEngineId
    sentMessages.exists { case (id, _, _, eventType, _) => id == "stop-1" && eventType == "RegisterPassenger" } shouldBe true

    // The bus never arrives: timeout fires, triggers a replan to a walk leg reaching the same
    // destination — proving the rest of the trip survives rather than being silently skipped.
    val PlanStepResult.LegStarted(state3, nextTick3) = manager.replanAfterPTTimeout(state2, currentTick = 60): @unchecked
    nextTick3 shouldBe Some(60L)
    decideCallCount shouldBe 2
    state3.cursor.executed.exists {
      case WalkLeg("n1", "n5", _) => true
      case _                      => false
    } shouldBe true
    state3.tripExecution.asInstanceOf[TripExecutionState.Traveling].ptWait shouldBe None

    // Walk arrival reaches the originally intended activity — the trip was rerouted, not lost.
    val PlanStepResult.Awaiting(state4, wake4) = manager.continueAfterWalkArrival(state3, currentTick = 60): @unchecked
    wake4 shouldBe 1000L
    state4.completedTrips shouldBe 1
    state4.cursor.executed.last shouldBe Activity("work", "n5", AtTick(1000))
  }

  // ---------------------------------------------------------------------------------------------
  // (c) ScheduleComplete at the end of the day.
  // ---------------------------------------------------------------------------------------------

  it should "report ScheduleComplete once the plan cursor has nothing left to run" in {
    val manager = newManager()
    val plan: List[PlanElement] = List(Activity("home", "n1", AtTick(5)))
    val state0 = stateWithPlan(plan)

    val PlanStepResult.Awaiting(state1, wake1) = manager.step(state0, currentTick = 0): @unchecked
    wake1 shouldBe 5L

    val result = manager.step(state1, currentTick = 5)
    result shouldBe a[PlanStepResult.Finished]
    val finishedState = result.asInstanceOf[PlanStepResult.Finished].state
    finishedState.isScheduleComplete shouldBe true
    finishedState.tripExecution shouldBe TripExecutionState.Idle
  }

  it should "abort just the trip and resume at the next activity when a PendingDecision has no viable journey" in {
    val testEngineId = "test-no-viable-engine"
    ModeDecisionEngineRegistry.register(testEngineId, new ModeDecisionEngine {
      override val id: String = testEngineId
      override def validateForScenario(ctx: ScenarioValidationContext): Either[model.hybrid.decision.EngineUnavailable, Unit] = Right(())
      override def decide(
        originNodeId: String,
        destinationNodeId: String,
        request: ModeDecisionRequest,
        ctx: DecisionContext
      ): Either[NoViableJourney, List[AtomicLeg]] = Left(NoViableJourney("no candidate available"))
    })

    val plan: List[PlanElement] = List(
      Activity("home", "n1", AtTick(10)),
      PendingDecision(ModeDecisionRequest(allowedModes = Set(ConcreteMode.Walk), strategyId = testEngineId)),
      Activity("work", "n2", AtTick(500))
    )
    val manager = newManager()
    val state0 = stateWithPlan(plan)

    val PlanStepResult.Awaiting(state1, _) = manager.step(state0, currentTick = 0): @unchecked
    val result = manager.step(state1, currentTick = 10)

    // Unified with the PT-timeout replan failure policy: no viable journey aborts just this trip
    // and resumes at the next Activity — it does not leave the person permanently unscheduled.
    val awaiting = result.asInstanceOf[PlanStepResult.Awaiting]
    awaiting.wakeTick shouldBe 500L
    awaiting.state.cursor.executed.last shouldBe Activity("work", "n2", AtTick(500))
    awaiting.state.tripExecution shouldBe TripExecutionState.Idle
    reports.exists { case (_, label) => label == "person_trip_aborted" } shouldBe true
  }
}
