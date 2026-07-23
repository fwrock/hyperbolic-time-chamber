package org.interscity.htc
package model.hybrid.decision

import model.hybrid.entity.state.plan.{ AtomicLeg, ModeDecisionRequest }
import model.hybrid.util.strategy.TravelTimeModeChoiceStrategy

/** Wraps [[TravelTimeModeChoiceStrategy]] — estimated travel-time scoring across walk, transit
  * (bus/subway) and private vehicles (car/bicycle/motorcycle, congestion-aware via the live A*
  * router) — as a named, registrable engine.
  *
  * Registered under `"travel-time"`.
  *
  * === Modes actually evaluated: all six — the only one of the three engines that considers
  * private vehicles ===
  *
  * Unlike [[RaptorMultiModalEngine]] (bus/subway only) and [[NearestStopUtilityEngine]] (walk +
  * bus/subway only), `TravelTimeModeChoiceStrategy.choose` evaluates walk, bus, subway, car,
  * bicycle and motorcycle candidates directly from the `ownedVehicles` map it is given — so this is
  * the one engine of the three where [[PrivateVehicleCandidates.available]] actually filters
  * anything. `ctx.ownedVehicles` is narrowed to vehicles parked at `originNodeId` before being
  * passed to `strategy.choose`, enforcing the "parked at origin" invariant. This is a real,
  * intentional asymmetry between the three engines (accepted as-is, not a gap to paper over): the
  * other two engines' wrapped implementations were never built to score private vehicles at all,
  * so there is no equivalent scoring logic to reuse for them — see each engine's own doc for the
  * audit trail. Do not add ad hoc private-vehicle scoring to `RaptorMultiModalEngine`/
  * `NearestStopUtilityEngine`; that would mean inventing new scoring logic never audited against
  * production behaviour, which is explicitly out of scope.
  *
  * === Why `validateForScenario` is always `Right` ===
  *
  * `TravelTimeModeChoiceStrategy.choose` never fails — it falls back to an instant walk
  * (`ArrivalLogistics(mode = "walk", instant = true)`) when no candidate scores above
  * `Double.MinValue`. There is no scenario-level dataset whose absence should abort the whole load.
  *
  * === Mode restriction ===
  *
  * As with [[NearestStopUtilityEngine]], `choose` has no `allowedModes`-shaped restriction of its
  * own beyond `weights.includedModes` (already scenario/person-level); `request.allowedModes` is
  * applied as a post-hoc filter on the translated result.
  */
final class TravelTimeEngine extends ModeDecisionEngine {

  private val strategy = new TravelTimeModeChoiceStrategy()

  override val id: String = "travel-time"

  override def validateForScenario(ctx: ScenarioValidationContext): Either[EngineUnavailable, Unit] = Right(())

  override def decide(
    originNodeId: String,
    destinationNodeId: String,
    request: ModeDecisionRequest,
    ctx: DecisionContext
  ): Either[NoViableJourney, List[AtomicLeg]] = {
    val weights = request.weightsOverride.getOrElse(ctx.weights)
    val availableVehicles = PrivateVehicleCandidates.available(originNodeId, ctx.ownedVehicles, ctx.vehicleCurrentNode)

    val result = strategy.choose(originNodeId, destinationNodeId, weights, availableVehicles)

    ArrivalLogisticsTranslation.translate(originNodeId, destinationNodeId, result.logistics) match {
      case Some(leg) if request.allowedModes.contains(leg.mode) =>
        Right(List(leg))
      case Some(leg) =>
        Left(NoViableJourney(
          s"travel-time: resolved mode ${leg.mode} not in allowedModes for $originNodeId -> $destinationNodeId"
        ))
      case None =>
        Left(NoViableJourney(
          s"travel-time: could not translate mode-choice result for $originNodeId -> $destinationNodeId"
        ))
    }
  }
}
