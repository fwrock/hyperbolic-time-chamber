package org.interscity.htc
package model.hybrid.decision

import model.hybrid.entity.state.ArrivalLogistics
import model.hybrid.entity.state.plan.{ AtomicLeg, ModeDecisionRequest }
import model.hybrid.util.ModeChoiceUtil

/** Wraps [[ModeChoiceUtil.chooseBestLogistics]] — the haversine-distance heuristic (nearest stop ×
  * line × nearest alighting stop, plus a direct-walk candidate) — as a named, registrable engine.
  *
  * Registered under `"nearest-stop-utility"`.
  *
  * === Modes actually evaluated: walk + bus/subway only — never private vehicles ===
  *
  * Audited: `ModeChoiceUtil.buildCandidates` only ever builds a walk candidate and bus/subway
  * candidates — it has no notion of a car/bicycle/motorcycle candidate at all. So, unlike
  * [[TravelTimeEngine]], this engine never calls [[PrivateVehicleCandidates.available]] — there is
  * nothing in the wrapped implementation for it to filter.
  *
  * Requesting `ConcreteMode.Car`/`Bicycle`/`Motorcycle` in `allowedModes` does **not** trigger any
  * special-case error handling: those modes are simply never producible candidates, exactly as if
  * they had not been listed. `decide` falls through to the same generic "resolved mode not in
  * allowedModes" `NoViableJourney` it would report for any other unmet preference — it never
  * pretends to have evaluated a private vehicle and failed.
  *
  * === Why `validateForScenario` is always `Right` ===
  *
  * `chooseBestLogistics` degrades gracefully by design (see its own class doc): with no transit map
  * configured it simply returns the walk candidate unchanged. There is no scenario-level
  * precondition whose absence should abort the whole load — "no transit data" is a normal, already
  * handled runtime branch here, not a configuration error.
  *
  * === Mode restriction ===
  *
  * `chooseBestLogistics` has no `includedModes`-style restriction parameter of its own — it always
  * evaluates every candidate it knows how to build. `request.allowedModes` is therefore applied as a
  * post-hoc filter on the translated result rather than steering the underlying candidate search;
  * this is the smallest change that respects `allowedModes` without reimplementing
  * `ModeChoiceUtil`'s candidate evaluation.
  */
final class NearestStopUtilityEngine extends ModeDecisionEngine {

  override val id: String = "nearest-stop-utility"

  override def validateForScenario(ctx: ScenarioValidationContext): Either[EngineUnavailable, Unit] = Right(())

  override def decide(
    originNodeId: String,
    destinationNodeId: String,
    request: ModeDecisionRequest,
    ctx: DecisionContext
  ): Either[NoViableJourney, List[AtomicLeg]] = {
    val weights = request.weightsOverride.getOrElse(ctx.weights)

    val resolved = ModeChoiceUtil.chooseBestLogistics(
      originNodeId      = originNodeId,
      destinationNodeId = destinationNodeId,
      currentLogistics  = ArrivalLogistics(mode = "walk"),
      weights           = weights
    )

    ArrivalLogisticsTranslation.translate(originNodeId, destinationNodeId, resolved) match {
      case Some(leg) if request.allowedModes.contains(leg.mode) =>
        Right(List(leg))
      case Some(leg) =>
        Left(NoViableJourney(
          s"nearest-stop-utility: resolved mode ${leg.mode} not in allowedModes for $originNodeId -> $destinationNodeId"
        ))
      case None =>
        Left(NoViableJourney(
          s"nearest-stop-utility: could not translate mode-choice result for $originNodeId -> $destinationNodeId"
        ))
    }
  }
}
