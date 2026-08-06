package org.interscity.htc
package model.hybrid.decision

import core.types.Tick
import model.hybrid.entity.state.ModeChoiceWeights
import model.hybrid.entity.state.plan.{ AtomicLeg, ModeDecisionRequest }
import org.htc.protobuf.core.entity.actor.Identify

/** Reason a [[ModeDecisionEngine]] cannot be used for the current scenario at all — a structural
  * data-availability gap (e.g. no transit routes file loaded). Checked once at scenario load time
  * via `ScenarioLoadValidator.validateModeDecisionEngines`, never per-decision.
  */
final case class EngineUnavailable(engineId: String, reason: String)

/** Reason a single origin/destination decision produced no usable journey, even though the engine
  * itself is available for the scenario as a whole (e.g. no transit stop within range for this
  * particular origin, or the resolved mode isn't in the request's `allowedModes`).
  */
final case class NoViableJourney(reason: String)

/** Scenario-wide data-availability flags, checked once at load time by
  * [[ModeDecisionEngine.validateForScenario]] — cheaper, and fails faster, than waiting for the
  * first per-Person decision to discover a missing dataset mid-simulation.
  *
  * @param transitRouteDataAvailable mirrors [[model.hybrid.util.TransitRouteUtil.isAvailable]]
  * @param transitMapDataAvailable   mirrors [[model.hybrid.util.TransitMapUtil.isAvailable]]
  */
final case class ScenarioValidationContext(
  transitRouteDataAvailable: Boolean,
  transitMapDataAvailable: Boolean
)

/** Per-decision inputs an engine needs beyond the origin/destination node pair itself.
  *
  * @param entityId the deciding Person's entity id — combined with `currentTick` to seed any
  *   per-decision RNG draw (e.g. [[TravelTimeLogitEngine]]'s logit sampling) deterministically,
  *   instead of drawing from a globally shared generator whose "correct" next value would depend
  *   on cross-actor call order. See `docs/TIME_WARP_DESIGN.md` §8.
  */
final case class DecisionContext(
  weights: ModeChoiceWeights,
  ownedVehicles: Map[String, Identify],
  vehicleCurrentNode: Map[String, String],
  currentTick: Tick,
  entityId: String
)

/** A pluggable mode-choice algorithm.
  *
  * Every engine wraps one of the pre-existing, already-audited routing/scoring implementations —
  * [[model.hybrid.util.RaptorRouter]], [[model.hybrid.util.ModeChoiceUtil]], or
  * [[model.hybrid.util.strategy.TravelTimeModeChoiceStrategy]] — translating its result into
  * [[AtomicLeg]]s rather than re-implementing routing. See each concrete engine's doc for the
  * specific translation and any information gaps it had to bridge.
  */
trait ModeDecisionEngine {

  /** Stable identifier matched against [[ModeDecisionRequest.strategyId]] and used as the
    * `ModeDecisionEngineRegistry` key.
    */
  def id: String

  /** Scenario-wide precondition check, run once at load time — never per-decision. `Left` aborts
    * the whole scenario load; see `ScenarioLoadValidator.validateModeDecisionEngines` for why this
    * is an all-or-nothing check rather than a per-Person quarantine.
    */
  def validateForScenario(ctx: ScenarioValidationContext): Either[EngineUnavailable, Unit]

  /** Resolve one pending decision into a concrete, ordered sequence of [[AtomicLeg]]s.
    *
    * Implementations must never throw — an unreachable/unviable journey is reported as
    * `Left(NoViableJourney(...))`, never an exception.
    */
  def decide(
    originNodeId: String,
    destinationNodeId: String,
    request: ModeDecisionRequest,
    ctx: DecisionContext
  ): Either[NoViableJourney, List[AtomicLeg]]
}
