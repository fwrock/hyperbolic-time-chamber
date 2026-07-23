package org.interscity.htc
package model.hybrid.decision

import model.hybrid.entity.state.model.TransitStop
import model.hybrid.entity.state.plan.{ AtomicLeg, ConcreteMode, ModeDecisionRequest, StopRef, TransitLeg, WalkLeg }
import model.hybrid.util.{ CityMapUtil, RaptorRouter, TransitMapUtil, TransitRouteUtil }

/** Wraps [[RaptorRouter.route]] — round-based, frequency-aware, multi-leg transit routing — as a
  * named, registrable engine.
  *
  * Registered under `"raptor"`.
  *
  * === Modes actually evaluated: bus/subway transit only — never a standalone walk, never private
  * vehicles ===
  *
  * `RaptorRouter.route` only ever searches for a transit (bus/subway) journey; `decide` collects
  * `Bus`/`Subway` out of `request.allowedModes` into the stop types passed to the router and
  * ignores every other entry. This means:
  *
  *   - `ConcreteMode.Walk` is never evaluated as a standalone trip — [[WalkLeg]]s only ever appear
  *     here as the access/egress/transfer legs this engine inserts around a transit journey (see
  *     [[translateResult]]), never as the sole leg of the returned list.
  *   - `ConcreteMode.Car`/`Bicycle`/`Motorcycle` are never evaluated at all — `RaptorRouter` has no
  *     private-vehicle concept, so nothing is fed to it for these modes.
  *
  * Requesting one of the modes above in `allowedModes` (e.g. `allowedModes = Set(Car, Bus)`) does
  * **not** trigger any special-case error handling: the unsupported mode is simply never a
  * producible candidate, exactly as if it had not been listed. `decide` only reports
  * `NoViableJourney` for the ordinary reasons — no transit mode (`Bus`/`Subway`) present in
  * `allowedModes` at all, transit data unavailable, or no feasible path found — never specifically
  * because a private-vehicle or walk-only mode was requested alongside a viable transit option.
  *
  * === Result translation ===
  *
  * [[RaptorRouter.RaptorResult]] carries full [[TransitStop]] objects at both ends of every leg,
  * so — unlike the legacy `ArrivalLogistics` shape handled by [[ArrivalLogisticsTranslation]] for
  * [[NearestStopUtilityEngine]]/[[TravelTimeEngine]] — translating it to [[AtomicLeg]]s is
  * lossless: no stop lookup/reconstruction is needed. [[RaptorMultiModalEngine.translateResult]]
  * mirrors `UtilityModeChoiceStrategy.buildFullLegChain`, inserting a [[WalkLeg]] access walk
  * (origin → first boarding stop), one [[TransitLeg]] per RAPTOR leg, a [[WalkLeg]] transfer
  * between consecutive legs whose nodes differ, and a [[WalkLeg]] egress walk (last alighting stop
  * → destination) — each walk omitted when the two nodes already coincide.
  *
  * === Never evaluates private vehicles ===
  *
  * `RaptorRouter` is transit-only by construction (no concept of a car/bicycle/motorcycle
  * candidate), so this engine never calls [[PrivateVehicleCandidates.available]] — see
  * [[TravelTimeEngine]] for the one engine of the three where that filter is actually exercised.
  *
  * === Testing limitation (documented, not worked around) ===
  *
  * `decide`'s success path requires `CityMapUtil.nodesById` to resolve `originNodeId`/
  * `destinationNodeId` to lat/lon before calling `RaptorRouter.route`. `CityMapUtil` is a Scala
  * `object` with an eager `lazy val` that loads `city_map.json` from disk on first access and
  * *throws* `IllegalStateException` if the file is missing — there is no dependency-injection seam,
  * and because it is a JVM-wide singleton, seeding it with a throwaway fixture from one spec would
  * permanently fix that outcome for every other spec sharing the same `sbt test` JVM (this project's
  * `build.sbt` does not fork tests). So `decide()`'s full success path is intentionally not covered
  * by a unit test here; [[translateResult]] is extracted specifically so the actual translation
  * logic can still be exercised directly with a synthetic [[RaptorRouter.RaptorResult]], and the
  * `NoViableJourney` short-circuit (transit data unavailable) is tested since it never touches
  * `CityMapUtil`.
  */
final class RaptorMultiModalEngine extends ModeDecisionEngine {

  override val id: String = "raptor"

  override def validateForScenario(ctx: ScenarioValidationContext): Either[EngineUnavailable, Unit] =
    if (!ctx.transitRouteDataAvailable)
      Left(EngineUnavailable(
        id,
        "no transit routes file loaded (TransitRouteUtil.isAvailable = false) — RAPTOR requires route/headway data"
      ))
    else Right(())

  override def decide(
    originNodeId: String,
    destinationNodeId: String,
    request: ModeDecisionRequest,
    ctx: DecisionContext
  ): Either[NoViableJourney, List[AtomicLeg]] =
    {
      val includedStopTypes = request.allowedModes.collect {
        case ConcreteMode.Bus    => "bus"
        case ConcreteMode.Subway => "subway"
      }
      if (includedStopTypes.isEmpty)
        Left(NoViableJourney(s"raptor: no transit mode allowed for $originNodeId -> $destinationNodeId"))
      else if (!TransitRouteUtil.isAvailable || !TransitMapUtil.isAvailable)
        Left(NoViableJourney(s"raptor: transit route/map data unavailable for $originNodeId -> $destinationNodeId"))
      else {
        val weights = request.weightsOverride.getOrElse(ctx.weights)
        for {
          originNode <- CityMapUtil.nodesById
            .get(originNodeId)
            .toRight(NoViableJourney(s"raptor: unknown origin node $originNodeId"))
          destNode <- CityMapUtil.nodesById
            .get(destinationNodeId)
            .toRight(NoViableJourney(s"raptor: unknown destination node $destinationNodeId"))
          result <- RaptorRouter
            .route(
              originLat         = originNode.latitude,
              originLon         = originNode.longitude,
              destLat           = destNode.latitude,
              destLon           = destNode.longitude,
              includedStopTypes = includedStopTypes,
              maxAccessDistM    = weights.maxAccessDistanceM,
              maxTransferWalkM  = weights.maxAccessDistanceM
            )
            .toRight(NoViableJourney(s"raptor: no feasible transit path for $originNodeId -> $destinationNodeId"))
        } yield RaptorMultiModalEngine.translateResult(originNodeId, destinationNodeId, result)
      }
    }
}

object RaptorMultiModalEngine {

  private def stopRef(stop: TransitStop): StopRef = StopRef(stop.actorId, stop.actorClassType, stop.nodeId)

  private def concreteMode(rawMode: String): ConcreteMode =
    if (rawMode == "subway") ConcreteMode.Subway else ConcreteMode.Bus

  /** Pure translation from a RAPTOR result to a flat, ordered [[AtomicLeg]] sequence.
    *
    * Preconditions (guaranteed by every `Some`/non-empty [[RaptorRouter.RaptorResult]] the router
    * itself ever returns — see `RaptorRouter.route`'s own `if (legs.isEmpty) None` check):
    * `result.legs` is non-empty.
    */
  def translateResult(
    originNodeId: String,
    destinationNodeId: String,
    result: RaptorRouter.RaptorResult
  ): List[AtomicLeg] = {
    val legs = result.legs

    val accessWalk: List[AtomicLeg] = {
      val firstBoardingNodeId = legs.head.boardingStop.nodeId
      if (firstBoardingNodeId != originNodeId) List(WalkLeg(originNodeId, firstBoardingNodeId)) else Nil
    }

    val transitWithTransfers: List[AtomicLeg] = legs.zipWithIndex.flatMap {
      case (leg, idx) =>
        val transit: AtomicLeg = TransitLeg(
          mode          = concreteMode(leg.mode),
          line          = leg.line,
          boardingStop  = stopRef(leg.boardingStop),
          alightingStop = stopRef(leg.alightingStop)
        )
        val transferWalk: List[AtomicLeg] =
          if (idx < legs.size - 1) {
            val nextBoardingNodeId = legs(idx + 1).boardingStop.nodeId
            if (leg.alightingStop.nodeId != nextBoardingNodeId)
              List(WalkLeg(leg.alightingStop.nodeId, nextBoardingNodeId))
            else Nil
          } else Nil
        transit :: transferWalk
    }

    val egressWalk: List[AtomicLeg] = {
      val lastAlightingNodeId = legs.last.alightingStop.nodeId
      if (lastAlightingNodeId != destinationNodeId) List(WalkLeg(lastAlightingNodeId, destinationNodeId)) else Nil
    }

    accessWalk ++ transitWithTransfers ++ egressWalk
  }
}
