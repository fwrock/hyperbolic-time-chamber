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
  * bus/subway only), `TravelTimeModeChoiceStrategy.scoredCandidates` evaluates walk, bus, subway,
  * car, bicycle and motorcycle candidates directly from the `ownedVehicles` map it is given — so
  * this is the one engine family where [[PrivateVehicleCandidates.available]] actually filters
  * anything. `ctx.ownedVehicles` is narrowed to vehicles parked at `originNodeId` before being
  * passed to the strategy, enforcing the "parked at origin" invariant. This is a real, intentional
  * asymmetry between the three engines (accepted as-is, not a gap to paper over): the other two
  * engines' wrapped implementations were never built to score private vehicles at all, so there is
  * no equivalent scoring logic to reuse for them — see each engine's own doc for the audit trail.
  * Do not add ad hoc private-vehicle scoring to `RaptorMultiModalEngine`/`NearestStopUtilityEngine`;
  * that would mean inventing new scoring logic never audited against production behaviour, which
  * is explicitly out of scope.
  *
  * === Deterministic argmax — see [[TravelTimeLogitEngine]] for the probabilistic alternative ===
  *
  * `pick` here is a plain `maxByOption` over `scoredCandidates` — the same real person, the same
  * trip, the same inputs, always produces the exact same mode choice. That's a simplification of
  * real travel behaviour (random utility maximisation — McFadden 1974 — says two options close in
  * utility split ridership probabilistically, not 100/0), kept here deliberately as the
  * "reproducible baseline" engine. Use `"travel-time-logit"` when mode-share realism matters more
  * than bitwise-identical reruns of the same scenario.
  *
  * === Lazy RAPTOR validation for the winning PT candidate (added 2026-07-23) ===
  *
  * `scoredCandidates` scores bus/subway with a cheap, single-leg, haversine-based approximation —
  * deliberately, so evaluating five-plus candidates per person per trip departure never pays for a
  * full transit-network search up front (see `TravelTimeModeChoiceStrategy`'s own doc for the
  * complexity trade-off, and `htc-scenario-qa`'s 2026-07-23 findings for why running RAPTOR for
  * every candidate, every decision, was rejected as too expensive at scale). But an approximation
  * that never validates feasibility can rank a physically-impossible PT trip above every real
  * alternative — `TransitRouteUtil.reachableStopIdsAfter` (added the same day) already rules out
  * the worst case (an alighting stop the line never reaches from the boarding stop in that
  * direction), but it still isn't a real routed itinerary (no transfers, no headway-aware timing).
  *
  * So: only when the pick is bus/subway do we pay for one real `RaptorRouter.route` call, to either
  * (a) replace the single guessed leg with the real, possibly multi-leg, transfer-aware itinerary
  * [[RaptorMultiModalEngine.translateResult]] already knows how to build, or (b) discover the
  * approximation was a false positive (RAPTOR finds no path at all) and re-pick once more with
  * bus/subway masked out of contention, so the person falls back to their next-best real
  * alternative instead of being committed to a trip no scheduled vehicle will ever complete. Every
  * other candidate never touches RAPTOR at all. See [[TravelTimeChoiceResolution]] for the shared
  * implementation (also used by [[TravelTimeLogitEngine]]).
  *
  * === Why `validateForScenario` is always `Right` ===
  *
  * `scoredCandidates` never fails to produce a result — resolution falls back to an instant walk
  * when no candidate scores above `Double.MinValue`. There is no scenario-level dataset whose
  * absence should abort the whole load — RAPTOR validation degrades gracefully to the old
  * single-leg approximation when `TransitRouteUtil.isAvailable` is false, exactly like every other
  * `TransitRouteUtil.isAvailable` check in this codebase.
  *
  * === Mode restriction ===
  *
  * As with [[NearestStopUtilityEngine]], candidate scoring has no `allowedModes`-shaped
  * restriction of its own beyond `weights.includedModes` (already scenario/person-level);
  * `request.allowedModes` is applied as a post-hoc filter on the translated result for the
  * non-PT-winning path. The RAPTOR path instead narrows `includedStopTypes` *before* routing
  * (mirroring [[RaptorMultiModalEngine.decide]]) — a result from `RaptorRouter.route` can never
  * disagree with `allowedModes` by construction, so no post-hoc filter is needed there.
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
  ): Either[NoViableJourney, List[AtomicLeg]] =
    TravelTimeChoiceResolution.resolve(
      strategy, originNodeId, destinationNodeId, request, ctx, id,
      pick = _.maxByOption(_._2).map(_._1)
    )
}

object TravelTimeEngine {

  /** Re-exported for `TravelTimeEngineSpec`'s existing coverage of the pure stop-type narrowing
    * logic — the implementation now lives in [[TravelTimeChoiceResolution]], shared with
    * [[TravelTimeLogitEngine]].
    */
  def raptorIncludedStopTypes(
    weights: model.hybrid.entity.state.ModeChoiceWeights,
    allowedModes: Set[model.hybrid.entity.state.plan.ConcreteMode]
  ): Set[String] = TravelTimeChoiceResolution.raptorIncludedStopTypes(weights, allowedModes)
}
