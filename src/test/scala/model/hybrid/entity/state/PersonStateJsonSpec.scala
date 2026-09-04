package org.interscity.htc
package model.hybrid.entity.state

import core.util.JsonUtil
import org.interscity.htc.model.hybrid.entity.state.plan._
import org.htc.protobuf.core.entity.actor.Identify

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Locks in the Jackson polymorphism setup (`@JsonTypeInfo`/`@JsonSubTypes`) added to the
  * `PlanElement`/`EndTimeSpec`/`TripExecutionState` hierarchies for this phase.
  *
  * This matters because `PersonState` is deserialized generically by
  * `core.util.JsonUtil.convertValue`/`core.actor.BaseActor.onInitialize` (scenario load) and
  * re-serialized/deserialized via `JsonUtil.toJson`/`fromJson` on every migration snapshot — there
  * is no per-actor custom decoder to catch a broken discriminator at compile time. Without the
  * annotations, Jackson has no way to tell a `WalkLeg` from a `TransitLeg` inside a
  * `List[PlanElement]`, and would fail exactly the way `PersonMigrationSnapshotSpec` caught the
  * missing `TripExecutionState.Idle` subtype during this phase's development.
  */
class PersonStateJsonSpec extends AnyFlatSpec with Matchers {

  private val fullPlan: List[PlanElement] = List(
    Activity("home", 1L, AtTick(100)),
    WalkLeg(1L, 3L, precomputedRoute = Some(List((11L, 2L)))),
    TransitLeg(
      ConcreteMode.Subway,
      "Line-4",
      boardingStop = StopRef(101L, "hybrid.actor.SubwayStation", 3L),
      alightingStop = StopRef(102L, "hybrid.actor.SubwayStation", 8L)
    ),
    PrivateVehicleLeg(ConcreteMode.Car, Identify(id = 201L, classType = "hybrid.actor.Car")),
    PendingDecision(ModeDecisionRequest(allowedModes = Set(ConcreteMode.Bus, ConcreteMode.Walk), strategyId = "travel-time")),
    Activity("work", 8L, Duration(3600L))
  )

  "PersonState JSON round trip" should "preserve every PlanElement/EndTimeSpec leaf through toJson/fromJson" in {
    val state = PersonState(
      originalPlan = fullPlan,
      cursor = PlanCursor(executed = List(fullPlan.head.asInstanceOf[Activity]), remaining = RemainingQueue(fullPlan.tail))
    )

    val json = JsonUtil.toJson(state)
    val restored = JsonUtil.fromJson[PersonState](json)

    restored.originalPlan shouldBe fullPlan
    restored.cursor shouldBe state.cursor
  }

  it should "preserve a Traveling tripExecution with an active PT wait" in {
    val state = PersonState(
      tripExecution = TripExecutionState.Traveling(
        physicalNodeId = 3L,
        tripId = "person-1:trip:1",
        legStartTick = 42L,
        ptWait = Some(PTWaitState(waitingSinceTick = 42L, timeoutTick = 142L, alightingNodeId = 8L, line = "Line-4")),
        replanStrategyId = "travel-time",
        replanAllowedModes = Set(ConcreteMode.Bus, ConcreteMode.Subway)
      )
    )

    val restored = JsonUtil.fromJson[PersonState](JsonUtil.toJson(state))

    restored.tripExecution shouldBe state.tripExecution
  }

  it should "preserve the Idle tripExecution (the case that requires an explicit JsonSubTypes entry for a case object)" in {
    val restored = JsonUtil.fromJson[PersonState](JsonUtil.toJson(PersonState()))

    restored.tripExecution shouldBe TripExecutionState.Idle
  }

  "JsonUtil.convertValue" should "deserialize a scenario-shaped payload (originalPlan set, cursor/tripExecution at their scenario-load defaults) via the same path core.actor.BaseActor.onInitialize uses" in {
    val scenarioState = PersonState(originalPlan = fullPlan)
    val json = JsonUtil.toJson(scenarioState)

    val restored = JsonUtil.convertValue[PersonState](json)

    restored.originalPlan shouldBe fullPlan
    restored.cursor shouldBe PlanCursor(executed = Nil, remaining = RemainingQueue(Nil))
    restored.tripExecution shouldBe TripExecutionState.Idle
  }
}
