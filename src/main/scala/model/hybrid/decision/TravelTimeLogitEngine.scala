package org.interscity.htc
package model.hybrid.decision

import core.types.Tick
import model.hybrid.entity.state.ArrivalLogistics
import model.hybrid.entity.state.plan.{ AtomicLeg, ModeDecisionRequest }
import model.hybrid.util.strategy.TravelTimeModeChoiceStrategy

import scala.util.Random

/** Probabilistic sibling of [[TravelTimeEngine]] — same candidate scoring
  * (`TravelTimeModeChoiceStrategy.scoredCandidates`), same lazy-RAPTOR-validation flow
  * ([[TravelTimeChoiceResolution]]), but picks among candidates by **sampling** the softmax
  * (multinomial logit) distribution over their utility scores instead of always taking the
  * argmax.
  *
  * Registered under `"travel-time-logit"`.
  *
  * === Why: `TravelTimeEngine`'s `maxByOption` is not what real travellers do ===
  *
  * `TravelTimeEngine` always picks the single highest-scoring candidate — the same person, same
  * trip, same inputs, produces the identical mode choice every single time. Real travel-demand
  * modelling has rejected pure utility-maximisation for exactly this reason since McFadden's random
  * utility theory (1974, Nobel Prize in Economics 2000): when two options score close to each
  * other, real populations split between them — a small utility edge shifts *most* riders, not
  * *all* of them. `ModeChoiceStrategyRegistry`'s own docstring already anticipated this gap
  * (`ModeChoiceStrategyRegistry.register("ml-logit", new MLLogitModeChoiceStrategy())`) but no such
  * class was ever implemented, and that registry is unreachable from the current
  * `PendingDecision`/`ModeDecisionEngineRegistry` pipeline in any case (only `ModeChoiceStrategy`
  * subclasses `TravelTimeEngine`/`TravelTimeLogitEngine` instantiate directly are actually live —
  * see `htc-scenario-qa` findings, 2026-07-23, for the audit that found `ModeChoiceStrategyRegistry`
  * and `UtilityModeChoiceStrategy` both orphaned).
  *
  * === Multinomial logit sampling ===
  *
  * {{{P(i) = exp(scale × score_i) / Σ_j exp(scale × score_j)}}}
  *
  * `scale` (the MNL "scale"/"μ" parameter) controls how close to argmax the sampling behaves:
  * higher scale sharpens the distribution toward the best-scoring candidate (in the limit,
  * identical to `TravelTimeEngine`); lower scale flattens it toward uniform-random regardless of
  * score. Default `1.0` treats `weights.betaMode`/`betaTravelTime`'s existing units as already
  * being on the right scale for a standard (unscaled) logit — recalibrate `scale` instead of
  * `betaMode`/`betaTravelTime` if the sampled mode shares come out too sharp or too flat once
  * checked against ground truth.
  *
  * === Reproducibility is preserved, not sacrificed ===
  *
  * Sampling draws from a `Random` seeded fresh per call from `(ctx.entityId, ctx.currentTick)` —
  * deterministic and reproducible like every other stochastic part of this engine, but *not* the
  * single globally-shared `RandomSeedManager` generator: a shared mutable generator advanced by
  * whichever Person happens to decide next has no stable "correct next value" once decisions can be
  * replayed independently per actor (Time Warp rollback/replay, or simply reordering call sites),
  * whereas a per-`(entityId, tick)` seed reproduces the same draw regardless of what order other
  * actors' decisions happen to run in. See `docs/TIME_WARP_DESIGN.md` §8.
  */
final class TravelTimeLogitEngine(scale: Double = 1.0) extends ModeDecisionEngine {

  private val strategy = new TravelTimeModeChoiceStrategy()

  override val id: String = "travel-time-logit"

  override def validateForScenario(ctx: ScenarioValidationContext): Either[EngineUnavailable, Unit] = Right(())

  override def decide(
    originNodeId: String,
    destinationNodeId: String,
    request: ModeDecisionRequest,
    ctx: DecisionContext
  ): Either[NoViableJourney, List[AtomicLeg]] =
    TravelTimeChoiceResolution.resolve(
      strategy, originNodeId, destinationNodeId, request, ctx, id,
      pick = TravelTimeLogitEngine.sampleLogit(
        _,
        new Random(TravelTimeLogitEngine.seedFor(ctx.entityId, ctx.currentTick)),
        scale
      )
    )
}

object TravelTimeLogitEngine {

  /** Deterministic per-`(entityId, tick)` seed for `sampleLogit`'s `rng`, so the same Person
    * deciding at the same tick always draws the same sample — independent of what order other
    * Persons' decisions happen to run in, and reproducible after a Time Warp rollback replay.
    */
  def seedFor(entityId: String, tick: Tick): Long =
    (entityId.hashCode.toLong * 0x9E3779B97F4A7C15L) ^ tick

  /** Samples one candidate from a multinomial logit distribution over `candidates`' scores. Pure
    * given `rng` (a `scala.util.Random` instance is itself stateful, but deterministic replay only
    * needs the *same starting state*, which `seedFor` already guarantees) — so this is
    * unit-testable by passing a freshly-seeded `Random` and checking either exact behaviour
    * (single candidate, or one candidate scoring `Double.MinValue`) or statistical behaviour over
    * many draws (roughly-equal scores → roughly-even draw counts).
    *
    * `None` for an empty candidate list — mirrors `List.maxByOption`'s `None` on empty input, the
    * same "no viable candidate, caller falls back to instant walk" contract
    * `TravelTimeChoiceResolution.resolve` already relies on.
    */
  def sampleLogit(
    candidates: List[(ArrivalLogistics, Double)],
    rng: Random,
    scale: Double = 1.0
  ): Option[ArrivalLogistics] = candidates match {
    case Nil => None
    case single :: Nil => Some(single._1)
    case _ =>
      val maxScore = candidates.map(_._2).max
      val weighted = candidates.map { case (logistics, score) => (logistics, math.exp((score - maxScore) * scale)) }
      val total = weighted.map(_._2).sum
      val threshold = rng.nextDouble() * total

      val picked = weighted.foldLeft((Option.empty[ArrivalLogistics], 0.0)) {
        case (found @ (Some(_), _), _) => found
        case ((None, cumulative), (logistics, w)) =>
          val next = cumulative + w
          if (threshold <= next) (Some(logistics), next) else (None, next)
      }._1

      // Floating-point safety net: cumulative sum can fall a hair short of `threshold` due to
      // rounding, leaving `picked` empty even though `weighted` is non-empty — take the last
      // candidate rather than silently dropping a real one.
      picked.orElse(weighted.lastOption.map(_._1))
  }
}
