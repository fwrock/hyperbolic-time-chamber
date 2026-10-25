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
  * dependency, so plain ScalaTest can inject route lengths without loading the process-wide map.
  */
class PersonPlanManagerSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private val sentMessages = mutable.Buffer.empty[(Long, String, Any, String, Any)]
  private val reports = mutable.Buffer.empty[(Map[String, Any], String)]

  private def resetCapture(): Unit = {
    sentMessages.clear()
    reports.clear()
  }

  override def beforeEach(): Unit = resetCapture()

  private val noop: String => Unit = _ => ()

  private def newManager(personId: String = "1", deferDecisions: Boolean = false,
    linkLengths: Map[Long, Double] = Map.empty): PersonPlanManager = {
    val metricsReporter = new PersonMetricsReporter(personId, (m, l) => reports += ((m, l)), noop)
    val walkingHandler = new PersonWalkingTripHandler(personId, (m, l) => reports += ((m, l)), noop, noop,
      linkLengthFn = linkLengths.get)
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
      logWarn = noop,
      deferDecisionsFn = () => deferDecisions
    )
  }

  private def stateWithPlan(plan: List[PlanElement], ptWaitTimeoutTicks: Long = 50L, ownedVehicles: Map[String, Identify] = Map.empty): PersonState =
    PersonState(
      cursor = PlanCursor(executed = Nil, remaining = RemainingQueue(plan)),
      originalPlan = plan,
      ownedVehicles = ownedVehicles,
      ptWaitTimeoutTicks = ptWaitTimeoutTicks
    )

  "Walking distance" should "preserve access, transfer, ride and egress distances across checkpoint restoration" in {
    val manager = newManager(linkLengths = Map(11L -> 140.0, 12L -> 280.0, 13L -> 70.0))
    def walk(from: Long, to: Long, link: Long) = WalkLeg(from, to, Some(List(link -> to)))
    def transit(mode: ConcreteMode, from: Long, to: Long) =
      TransitLeg(mode, "L1", StopRef(801L, "stop", from), StopRef(802L, "stop", to))
    val plan = List(Activity("home", 1L, AtTick(10)), walk(1, 2, 11),
      transit(ConcreteMode.Bus, 2, 3), walk(3, 4, 12), transit(ConcreteMode.Subway, 4, 5),
      walk(5, 6, 13), Activity("work", 6L, AtTick(1000)))
    val home = manager.step(stateWithPlan(plan), 0).asInstanceOf[PlanStepResult.Awaiting].state
    val access = manager.step(home, 10).asInstanceOf[PlanStepResult.LegStarted].state
    val restored = core.util.JsonUtil.fromJson[PersonState](core.util.JsonUtil.toJson(access))
    // Arrival consumes the checkpointed measurement even when live map data is unavailable.
    val bus = newManager().continueAfterWalkArrival(restored, 110).asInstanceOf[PlanStepResult.LegStarted].state
    bus.tripExecution.asInstanceOf[TripExecutionState.Traveling].accumulatedDistance shouldBe 140.0
    def alight(state: PersonState, mode: ConcreteMode, node: Long, tick: Long, baseline: Double, end: Double): PersonState = {
      val traveling = state.tripExecution.asInstanceOf[TripExecutionState.Traveling]
      val boarded = state.copy(tripExecution = traveling.copy(ptWait = traveling.ptWait.map(_.copy(
        boardedVehicleId = Some(16L), boardedVehicleClassType = Some(s"hybrid.actor.$mode"),
        boardingDistance = Some(baseline), boardingTick = Some(traveling.legStartTick + 1)))))
      val data = if (mode == ConcreteMode.Bus)
        model.hybrid.entity.event.data.bus.BusRequestUnloadPassengerData(node, null, end)
      else model.hybrid.entity.event.data.subway.SubwayRequestUnloadPassengerData(node, null, end)
      val event = core.entity.event.ActorInteractionEvent(tick, 0L, 16L, "", "", s"hybrid.actor.$mode", data = data, resourceId = "")
      manager.handlePTUnloadRequest(event, node, mode, boarded, tick).get match {
        case PlanStepResult.LegStarted(s, _) => s
        case PlanStepResult.Awaiting(s, _) => s
        case other => fail(s"Unexpected result $other")
      }
    }
    val transfer = alight(bus, ConcreteMode.Bus, 3, 160, 2000, 2400)
    transfer.tripExecution.asInstanceOf[TripExecutionState.Traveling].accumulatedDistance shouldBe 540.0
    val subway = manager.continueAfterWalkArrival(transfer, 360).asInstanceOf[PlanStepResult.LegStarted].state
    val egress = alight(subway, ConcreteMode.Subway, 5, 450, 20000, 20700)
    egress.totalDistanceTraveled shouldBe 0.0
    egress.tripExecution.asInstanceOf[TripExecutionState.Traveling].accumulatedDistance shouldBe 1520.0
    val arrived = manager.continueAfterWalkArrival(egress, 500).asInstanceOf[PlanStepResult.Awaiting].state
    arrived.totalDistanceTraveled shouldBe 1590.0
    arrived.completedTrips shouldBe 1
    reports.filter(_._2 == "person_walking_completed").map(_._1("distance")) shouldBe Seq(140.0, 280.0, 70.0)
  }

  it should "ignore early or duplicate wakes and leave unfinished walking distance uncredited" in {
    val manager = newManager(linkLengths = Map(11L -> 140.0, 12L -> 280.0))
    val plan = List(Activity("home", 1L, AtTick(10)), WalkLeg(1, 2, Some(List(11L -> 2L))),
      WalkLeg(2, 3, Some(List(12L -> 3L))), Activity("work", 3L, AtTick(1000)))
    val home = manager.step(stateWithPlan(plan), 0).asInstanceOf[PlanStepResult.Awaiting].state
    val first = manager.step(home, 10).asInstanceOf[PlanStepResult.LegStarted].state
    manager.continueAfterWalkArrival(first, 109) shouldBe PlanStepResult.LegStarted(first, Some(110L))
    val second = manager.continueAfterWalkArrival(first, 110).asInstanceOf[PlanStepResult.LegStarted].state
    manager.continueAfterWalkArrival(second, 110) shouldBe PlanStepResult.LegStarted(second, Some(310L))
    second.totalDistanceTraveled shouldBe 0.0
    second.tripExecution.asInstanceOf[TripExecutionState.Traveling].accumulatedDistance shouldBe 140.0
    reports.count(_._2 == "person_walking_completed") shouldBe 1
    val arrived = manager.continueAfterWalkArrival(second, 310).asInstanceOf[PlanStepResult.Awaiting].state
    arrived.totalDistanceTraveled shouldBe 420.0
    arrived.completedTrips shouldBe 1
    manager.continueAfterWalkArrival(arrived, 310) shouldBe PlanStepResult.LegStarted(arrived, None)
    reports.count(_._2 == "person_walking_completed") shouldBe 2
  }

  it should "retain only completed distance when the next walking route fails" in {
    val manager = newManager(linkLengths = Map(11L -> 140.0))
    val plan = List(Activity("home", 1L, AtTick(10)), WalkLeg(1, 2, Some(List(11L -> 2L))),
      WalkLeg(2, 3, Some(List(12L -> 3L))), Activity("work", 3L, AtTick(1000)))
    val home = manager.step(stateWithPlan(plan), 0).asInstanceOf[PlanStepResult.Awaiting].state
    val walking = manager.step(home, 10).asInstanceOf[PlanStepResult.LegStarted].state
    val aborted = manager.continueAfterWalkArrival(walking, 110).asInstanceOf[PlanStepResult.Awaiting].state
    aborted.totalDistanceTraveled shouldBe 140.0
    aborted.completedTrips shouldBe 0
    aborted.tripExecution shouldBe TripExecutionState.Idle
    reports.count(_._2 == "person_trip_aborted") shouldBe 1
  }

  it should "retain an access walk through both successful and failed PT timeout replans" in {
    val manager = newManager(linkLengths = Map(11L -> 140.0, 12L -> 280.0))
    val plan = List(Activity("home", 1L, AtTick(10)), WalkLeg(1, 2, Some(List(11L -> 2L))),
      TransitLeg(ConcreteMode.Bus, "L1", StopRef(801L, "stop", 2L), StopRef(802L, "stop", 3L)),
      Activity("work", 3L, AtTick(1000)))
    val home = manager.step(stateWithPlan(plan), 0).asInstanceOf[PlanStepResult.Awaiting].state
    val walk = manager.step(home, 10).asInstanceOf[PlanStepResult.LegStarted].state
    val waiting = manager.continueAfterWalkArrival(walk, 110).asInstanceOf[PlanStepResult.LegStarted].state
    val failed = manager.replanAfterPTTimeout(waiting, 160, Some(Left(NoViableJourney("no service"))))
      .asInstanceOf[PlanStepResult.Awaiting].state
    failed.totalDistanceTraveled shouldBe 140.0
    failed.completedTrips shouldBe 0
    val replanned = manager.replanAfterPTTimeout(waiting, 160,
      Some(Right(List(WalkLeg(2, 3, Some(List(12L -> 3L))))))).asInstanceOf[PlanStepResult.LegStarted].state
    val arrived = manager.continueAfterWalkArrival(replanned, 360).asInstanceOf[PlanStepResult.Awaiting].state
    arrived.totalDistanceTraveled shouldBe 420.0
    arrived.completedTrips shouldBe 1
  }

  for (mode <- Seq(ConcreteMode.Bus, ConcreteMode.Subway)) {
    "PT ride distance" should s"use a checkpointed boarding baseline for $mode and ignore another vehicle" in {
      val leg = TransitLeg(mode, "L1", StopRef(801L, "stop", 1L), StopRef(802L, "stop", 3L))
      val manager = newManager()
      val plan = List(Activity("home", 1L, AtTick(10L)), leg, Activity("work", 3L, AtTick(500L)))
      val first = manager.step(stateWithPlan(plan), 0L).asInstanceOf[PlanStepResult.Awaiting].state
      val waiting = manager.step(first, 10L).asInstanceOf[PlanStepResult.LegStarted].state
      val traveling = waiting.tripExecution.asInstanceOf[TripExecutionState.Traveling]
      val boarded = waiting.copy(tripExecution = traveling.copy(ptWait = traveling.ptWait.map(_.copy(
        boardedVehicleId = Some(16L), boardedVehicleClassType = Some(s"hybrid.actor.$mode"),
        boardingDistance = Some(1200.0), boardingTick = Some(20L)))))
      val restored = core.util.JsonUtil.fromJson[PersonState](core.util.JsonUtil.toJson(boarded))
      def request(vehicle: Long, distance: Double, classType: String = s"hybrid.actor.$mode"): core.entity.event.ActorInteractionEvent = {
        val data = if (mode == ConcreteMode.Bus)
          model.hybrid.entity.event.data.bus.BusRequestUnloadPassengerData(3L, null, distance)
        else model.hybrid.entity.event.data.subway.SubwayRequestUnloadPassengerData(3L, null, distance)
        core.entity.event.ActorInteractionEvent(100L, 0L, vehicle, "", "", classType, data = data, resourceId = "")
      }
      manager.handlePTUnloadRequest(request(99L, 1600.0), 3L, mode, restored, 100L) shouldBe None
      manager.handlePTUnloadRequest(request(16L, 1600.0, "other.Model"), 3L, mode, restored, 100L) shouldBe None
      val result = manager.handlePTUnloadRequest(request(16L, 1600.0), 3L, mode, restored, 100L).get
        .asInstanceOf[PlanStepResult.Awaiting].state
      result.totalDistanceTraveled shouldBe 400.0
      result.completedTrips shouldBe 1
      reports.find(_._2 == "person_pt_trip_completed").get._1("distance_traveled") shouldBe 400.0
      intercept[IllegalArgumentException] {
        manager.handlePTUnloadRequest(request(16L, 1000.0), 3L, mode, restored, 100L)
      }.getMessage should include ("odometer moved backwards")
    }
  }

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
        originNodeId: Long,
        destinationNodeId: Long,
        request: ModeDecisionRequest,
        ctx: DecisionContext
      ): Either[NoViableJourney, List[AtomicLeg]] =
        Right(List(WalkLeg(originNodeId, destinationNodeId, precomputedRoute = Some(Nil))))
    })

    val vehicle = Identify(id = 501L, classType = "hybrid.actor.Car")
    val plan: List[PlanElement] = List(
      Activity("home", 1L, AtTick(100)),
      PrivateVehicleLeg(ConcreteMode.Car, vehicle),
      Activity("work", 2L, AtTick(500)),
      PendingDecision(ModeDecisionRequest(allowedModes = Set(ConcreteMode.Walk), strategyId = testEngineId)),
      Activity("home", 3L, AtTick(900))
    )
    val manager = newManager()
    val state0 = stateWithPlan(plan, ownedVehicles = Map("car" -> vehicle))

    // Advance into the first Activity.
    val PlanStepResult.Awaiting(state1, wake1) = manager.step(state0, currentTick = 0): @unchecked
    wake1 shouldBe 100L
    state1.cursor.executed shouldBe List(Activity("home", 1L, AtTick(100)))

    // Departure: start the fixed car leg.
    val PlanStepResult.LegStarted(state2, nextTick2) = manager.step(state1, currentTick = 100): @unchecked
    nextTick2 shouldBe None // vehicle-controlled timing
    state2.tripExecution shouldBe a[TripExecutionState.Traveling]
    sentMessages.exists { case (id, _, _, eventType, _) => id == 501L && eventType == "StartTrip" } shouldBe true

    // Vehicle reports trip completion with real distance traveled.
    val completed = TripCompletedData(
      vehicleId = 501L,
      personId = 1L,
      distanceTraveled = 1234.0,
      travelTime = 50L,
      finalNode = 2L,
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
      case WalkLeg(2L, 3L, _) => true
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
        originNodeId: Long,
        destinationNodeId: Long,
        request: ModeDecisionRequest,
        ctx: DecisionContext
      ): Either[NoViableJourney, List[AtomicLeg]] = {
        decideCallCount += 1
        if (decideCallCount == 1)
          Right(List(TransitLeg(
            ConcreteMode.Bus,
            "L1",
            boardingStop = StopRef(801L, "hybrid.actor.BusStop", originNodeId),
            alightingStop = StopRef(802L, "hybrid.actor.BusStop", destinationNodeId)
          )))
        else
          Right(List(WalkLeg(originNodeId, destinationNodeId, precomputedRoute = Some(Nil))))
      }
    })

    val plan: List[PlanElement] = List(
      Activity("home", 1L, AtTick(10)),
      PendingDecision(ModeDecisionRequest(allowedModes = Set(ConcreteMode.Bus, ConcreteMode.Walk), strategyId = testEngineId)),
      Activity("work", 5L, AtTick(1000))
    )
    val manager = newManager()
    val state0 = stateWithPlan(plan, ptWaitTimeoutTicks = 50L)

    val PlanStepResult.Awaiting(state1, wake1) = manager.step(state0, currentTick = 0): @unchecked
    wake1 shouldBe 10L

    // Departure resolves the PendingDecision into a bus leg and registers at the stop.
    val PlanStepResult.LegStarted(state2, nextTick2) = manager.step(state1, currentTick = 10): @unchecked
    nextTick2 shouldBe Some(60L) // 10 + ptWaitTimeoutTicks
    val traveling2 = state2.tripExecution.asInstanceOf[TripExecutionState.Traveling]
    traveling2.ptWait.map(_.alightingNodeId) shouldBe Some(5L)
    traveling2.replanStrategyId shouldBe testEngineId
    sentMessages.exists { case (id, _, _, eventType, _) => id == 801L && eventType == "RegisterPassenger" } shouldBe true

    // The bus never arrives: timeout fires, triggers a replan to a walk leg reaching the same
    // destination — proving the rest of the trip survives rather than being silently skipped.
    val PlanStepResult.LegStarted(state3, nextTick3) = manager.replanAfterPTTimeout(state2, currentTick = 60): @unchecked
    nextTick3 shouldBe Some(60L)
    decideCallCount shouldBe 2
    state3.cursor.executed.exists {
      case WalkLeg(1L, 5L, _) => true
      case _                      => false
    } shouldBe true
    state3.tripExecution.asInstanceOf[TripExecutionState.Traveling].ptWait shouldBe None

    // Walk arrival reaches the originally intended activity — the trip was rerouted, not lost.
    val PlanStepResult.Awaiting(state4, wake4) = manager.continueAfterWalkArrival(state3, currentTick = 60): @unchecked
    wake4 shouldBe 1000L
    state4.completedTrips shouldBe 1
    state4.cursor.executed.last shouldBe Activity("work", 5L, AtTick(1000))
  }

  // ---------------------------------------------------------------------------------------------
  // (c) ScheduleComplete at the end of the day.
  // ---------------------------------------------------------------------------------------------

  it should "report ScheduleComplete once the plan cursor has nothing left to run" in {
    val manager = newManager()
    val plan: List[PlanElement] = List(Activity("home", 1L, AtTick(5)))
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
        originNodeId: Long,
        destinationNodeId: Long,
        request: ModeDecisionRequest,
        ctx: DecisionContext
      ): Either[NoViableJourney, List[AtomicLeg]] = Left(NoViableJourney("no candidate available"))
    })

    val plan: List[PlanElement] = List(
      Activity("home", 1L, AtTick(10)),
      PendingDecision(ModeDecisionRequest(allowedModes = Set(ConcreteMode.Walk), strategyId = testEngineId)),
      Activity("work", 2L, AtTick(500))
    )
    val manager = newManager()
    val state0 = stateWithPlan(plan)

    val PlanStepResult.Awaiting(state1, _) = manager.step(state0, currentTick = 0): @unchecked
    val result = manager.step(state1, currentTick = 10)

    val awaiting = result.asInstanceOf[PlanStepResult.Awaiting]
    awaiting.wakeTick shouldBe 500L
    awaiting.state.cursor.executed.last shouldBe Activity("work", 2L, AtTick(500))
    awaiting.state.tripExecution shouldBe TripExecutionState.Idle
    reports.exists { case (_, label) => label == "person_trip_aborted" } shouldBe true
  }

  it should "defer a pending decision to the routing LP and apply exactly the supplied reply" in {
    val engineId = "test-deferred-engine"
    var localCalls = 0
    ModeDecisionEngineRegistry.register(engineId, new ModeDecisionEngine {
      override val id: String = engineId
      override def validateForScenario(ctx: ScenarioValidationContext) = Right(())
      override def decide(originNodeId: Long, destinationNodeId: Long, request: ModeDecisionRequest,
        ctx: DecisionContext) = {
        localCalls += 1
        Left(NoViableJourney("must execute in routing service"))
      }
    })
    val plan: List[PlanElement] = List(
      Activity("home", 1L, AtTick(10)),
      PendingDecision(ModeDecisionRequest(Set(ConcreteMode.Walk), engineId)),
      Activity("work", 2L, AtTick(100))
    )
    val manager = newManager(deferDecisions = true)
    val PlanStepResult.Awaiting(atHome, _) = manager.step(stateWithPlan(plan), 0L): @unchecked
    val requested = manager.step(atHome, 10L).asInstanceOf[PlanStepResult.DecisionRequested]

    requested.query.originNodeId shouldBe 1L
    requested.query.destinationNodeId shouldBe 2L
    requested.kind shouldBe PersonDecisionKind.PendingPlan
    localCalls shouldBe 0

    val completed = manager.completeRoutingDecision(
      requested.state,
      10L,
      requested.kind,
      Right(List(WalkLeg(1L, 2L, precomputedRoute = Some(Nil))))
    )
    completed shouldBe a[PlanStepResult.LegStarted]
    localCalls shouldBe 0
  }
}
