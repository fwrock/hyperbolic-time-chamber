package org.interscity.htc
package model.hybrid.util.strategy

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.model.hybrid.entity.state.{ ArrivalLogistics, ModeChoiceWeights }

/** Result returned by [[ModeChoiceStrategy.choose]].
  *
  * For single-leg trips `pendingLegs` is empty. For multi-leg transit trips (e.g. a route with one
  * transfer found by the RAPTOR algorithm), `pendingLegs` contains the subsequent legs in order.
  * [[model.hybrid.actor.Person]] stores them in `PersonState.pendingTransferLegs` and executes
  * them sequentially after each transit leg completes.
  *
  * @param logistics
  *   The first (or only) leg to execute immediately.
  * @param pendingLegs
  *   Zero or more subsequent legs to execute after `logistics` completes, in order.
  */
case class ModeChoiceResult(
  logistics: ArrivalLogistics,
  pendingLegs: List[ArrivalLogistics] = Nil
)

/** Strategy interface for trip mode selection.
  *
  * Implementations receive only the origin and destination node IDs plus the configured utility
  * weights, and return a fully resolved [[ArrivalLogistics]] (mode, line, boarding/alighting stop
  * IDs, etc.).
  *
  * === Usage ===
  *
  * When dynamic mode choice is enabled for a person,
  * [[model.hybrid.actor.Person]] delegates trip resolution to the strategy registered under
  * [[model.hybrid.entity.state.PersonState.modeChoiceStrategyType]] for every non-fixed leg
  * (`arrivalLogistics.fixedMode = false`).
  *
  * === Built-in strategies ===
  *
  *   - `"utility"` → [[UtilityModeChoiceStrategy]] – additive utility function over bus / subway /
  *     walk (default)
  *
  * === Custom strategies ===
  *
  * Register additional implementations before the simulation starts:
  * {{{
  *   ModeChoiceStrategyRegistry.register("my-strategy", new MyModeChoiceStrategy())
  * }}}
  *
  * The strategy type is declared per-person in the scenario JSON:
  * {{{
  *   {
  *     "modeChoiceStrategyType": "utility"
  *   }
  * }}}
  *
  * Any requested schedule mode (for example `"auto"`, `"car"`, `"bus"`) can be provided in
  * activity logistics; the strategy returns the final concrete mode used at runtime.
  *
  * Typical flexible leg:
  * {{{
  *   { "mode": "auto", "fixedMode": false }
  * }}}
  */
trait ModeChoiceStrategy {

  /** Choose the best [[ArrivalLogistics]] for a trip from `originNodeId` to `destinationNodeId`.
    *
    * Implementations must always return a usable logistics object (never throw). When no transit
    * option is reachable, fall back to walking.
    *
    * The returned `mode` must be a concrete mode (never `"auto"`).
    *
    * @param originNodeId
    *   Road-network node ID of the trip origin.
    * @param destinationNodeId
    *   Road-network node ID of the trip destination.
    * @param weights
    *   Utility weights and distance thresholds from [[model.hybrid.entity.state.PersonState]].
    *   `weights.includedModes` controls which modes are evaluated.
    * @param ownedVehicles
    *   Private vehicles owned by the person (mode key → actor reference). Used to validate and
    *   populate the vehicle reference in the returned logistics when a private mode is chosen.
    *   Implementations must not select a private vehicle mode if the person does not own one.
    * @return
    *   Fully resolved [[ArrivalLogistics]] with a concrete mode.
    */
  def choose(
    originNodeId: String,
    destinationNodeId: String,
    weights: ModeChoiceWeights,
    ownedVehicles: Map[String, Identify] = Map.empty
  ): ModeChoiceResult
}
