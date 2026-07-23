package org.interscity.htc
package model.hybrid.decision

import model.hybrid.entity.state.ModeChoiceWeights
import model.hybrid.entity.state.model.TransitStop
import model.hybrid.entity.state.plan.{ ConcreteMode, ModeDecisionRequest, TransitLeg, WalkLeg }
import model.hybrid.util.RaptorRouter
import model.hybrid.util.RaptorRouter.{ RaptorLeg, RaptorResult }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** `RaptorMultiModalEngine.decide`'s success path needs `CityMapUtil.nodesById` to resolve node
  * ids to lat/lon before it can call `RaptorRouter.route`. `CityMapUtil` is a Scala `object` with
  * an eager `lazy val` that loads `city_map.json` from disk on first access and *throws*
  * `IllegalStateException` when the file is absent (no config points at one in this test
  * environment) — and because it's a JVM-wide singleton with no dependency-injection seam, and
  * `build.sbt` does not fork tests, seeding it with a throwaway fixture here would permanently fix
  * that outcome for every other spec sharing this `sbt test` JVM. So instead:
  *
  *   - [[validateForScenario]] and the `NoViableJourney` short-circuit (transit data unavailable)
  *     are tested directly — neither ever touches `CityMapUtil`.
  *   - [[RaptorMultiModalEngine.translateResult]], the pure result-translation adapter, is tested
  *     directly against a synthetic [[RaptorResult]] built from plain [[TransitStop]] values —
  *     exercising the actual translation logic this engine relies on without needing the routing
  *     call itself.
  */
class RaptorMultiModalEngineSpec extends AnyFlatSpec with Matchers {

  private val engine = new RaptorMultiModalEngine()
  private val ctx = DecisionContext(
    weights            = ModeChoiceWeights(),
    ownedVehicles      = Map.empty,
    vehicleCurrentNode = Map.empty,
    currentTick        = 0L
  )

  "validateForScenario" should "return Left when transit route data is unavailable" in {
    val result = engine.validateForScenario(ScenarioValidationContext(transitRouteDataAvailable = false, transitMapDataAvailable = true))

    result shouldBe a[Left[_, _]]
  }

  it should "return Right when transit route data is available" in {
    val result = engine.validateForScenario(ScenarioValidationContext(transitRouteDataAvailable = true, transitMapDataAvailable = true))

    result shouldBe Right(())
  }

  "decide" should "report NoViableJourney when transit route/map data is unavailable, without touching CityMapUtil" in {
    val request = ModeDecisionRequest(allowedModes = Set(ConcreteMode.Bus, ConcreteMode.Subway), strategyId = "raptor")

    val result = engine.decide("n1", "n2", request, ctx)

    result shouldBe a[Left[_, _]]
  }

  it should "report NoViableJourney when allowedModes contains no transit mode" in {
    val request = ModeDecisionRequest(allowedModes = Set(ConcreteMode.Walk), strategyId = "raptor")

    val result = engine.decide("n1", "n2", request, ctx)

    result match {
      case Left(NoViableJourney(reason)) => reason should include("no transit mode allowed")
      case other                         => fail(s"expected Left(NoViableJourney(...)), got $other")
    }
  }

  // ── translateResult: pure adapter, synthetic RaptorResult, no CityMapUtil/TransitMapUtil ──────

  private val originStop = TransitStop(
    id             = "stop-origin",
    actorId        = "busstop-origin",
    actorClassType = "hybrid.actor.BusStop",
    nodeId         = "n-boarding",
    latitude       = -23.55,
    longitude      = -46.63,
    stopType       = "bus",
    lines          = List("L1")
  )
  private val transferStop = TransitStop(
    id             = "stop-transfer",
    actorId        = "busstop-transfer",
    actorClassType = "hybrid.actor.BusStop",
    nodeId         = "n-transfer",
    latitude       = -23.56,
    longitude      = -46.64,
    stopType       = "bus",
    lines          = List("L1", "L2")
  )
  private val destStop = TransitStop(
    id             = "stop-dest",
    actorId        = "busstop-dest",
    actorClassType = "hybrid.actor.BusStop",
    nodeId         = "n-alighting",
    latitude       = -23.57,
    longitude      = -46.65,
    stopType       = "bus",
    lines          = List("L2")
  )
  // Two distinct stops at the SAME physical interchange but different nodeIds, so a transfer
  // between them is a genuine (short) walk, not a same-node no-op.
  private val transferAlightStop = transferStop
  private val transferBoardStop = transferStop.copy(
    id      = "stop-transfer-2",
    actorId = "busstop-transfer-2",
    nodeId  = "n-transfer-2"
  )

  "translateResult" should "build access walk + transit leg + egress walk for a single-leg trip" in {
    val result = RaptorResult(
      legs           = List(RaptorLeg(originStop, "L1", destStop, travelTimeSecs = 300, waitSecs = 60, mode = "bus")),
      totalTimeSecs  = 500,
      accessTimeSecs = 100,
      egressTimeSecs = 100
    )

    val legs = RaptorMultiModalEngine.translateResult("n-origin", "n-destination", result)

    legs shouldBe List(
      WalkLeg("n-origin", "n-boarding"),
      TransitLeg(
        mode          = ConcreteMode.Bus,
        line          = "L1",
        boardingStop  = model.hybrid.entity.state.plan.StopRef("busstop-origin", "hybrid.actor.BusStop", "n-boarding"),
        alightingStop = model.hybrid.entity.state.plan.StopRef("busstop-dest", "hybrid.actor.BusStop", "n-alighting")
      ),
      WalkLeg("n-alighting", "n-destination")
    )
  }

  it should "omit the access/egress walk when the trip starts/ends exactly at the stop's node" in {
    val result = RaptorResult(
      legs           = List(RaptorLeg(originStop, "L1", destStop, travelTimeSecs = 300, waitSecs = 60, mode = "bus")),
      totalTimeSecs  = 300,
      accessTimeSecs = 0,
      egressTimeSecs = 0
    )

    val legs = RaptorMultiModalEngine.translateResult("n-boarding", "n-alighting", result)

    legs.map(_.mode) shouldBe List(ConcreteMode.Bus)
  }

  it should "insert a transfer walk between two legs whose alighting/boarding nodes differ, and map mode subway correctly" in {
    val firstLeg = RaptorLeg(originStop, "L1", transferAlightStop, travelTimeSecs = 200, waitSecs = 60, mode = "bus")
    val secondLeg = RaptorLeg(transferBoardStop, "L2", destStop, travelTimeSecs = 200, waitSecs = 60, mode = "subway")
    val result = RaptorResult(
      legs           = List(firstLeg, secondLeg),
      totalTimeSecs  = 500,
      accessTimeSecs = 0,
      egressTimeSecs = 0
    )

    val legs = RaptorMultiModalEngine.translateResult("n-boarding", "n-alighting", result)

    legs shouldBe List(
      TransitLeg(
        ConcreteMode.Bus,
        "L1",
        model.hybrid.entity.state.plan.StopRef("busstop-origin", "hybrid.actor.BusStop", "n-boarding"),
        model.hybrid.entity.state.plan.StopRef("busstop-transfer", "hybrid.actor.BusStop", "n-transfer")
      ),
      WalkLeg("n-transfer", "n-transfer-2"),
      TransitLeg(
        ConcreteMode.Subway,
        "L2",
        model.hybrid.entity.state.plan.StopRef("busstop-transfer-2", "hybrid.actor.BusStop", "n-transfer-2"),
        model.hybrid.entity.state.plan.StopRef("busstop-dest", "hybrid.actor.BusStop", "n-alighting")
      )
    )
  }
}
