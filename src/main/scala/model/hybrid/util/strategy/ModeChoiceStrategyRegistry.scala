package org.interscity.htc
package model.hybrid.util.strategy

import scala.collection.concurrent.TrieMap

/** Thread-safe registry that maps strategy-type names to [[ModeChoiceStrategy]] instances.
  *
  * Built-in strategy (always available):
  *   - `"utility"` → [[UtilityModeChoiceStrategy]] – additive utility over haversine distances
  *     (fast, no routing calls, does not consider congestion)
  *   - `"travel-time"` → [[TravelTimeModeChoiceStrategy]] – penalises estimated travel time in
  *     seconds; calls the real A* router with live [[DynamicWeightCache]] data for private vehicles
  *     and walking, so congestion is taken into account
  *
  * === Registering a custom strategy ===
  *
  * Custom implementations must be registered before the simulation starts (e.g. in a plugin
  * initialiser or in `Main` before actors are spawned):
  * {{{
  *   ModeChoiceStrategyRegistry.register("ml-logit", new MLLogitModeChoiceStrategy())
  * }}}
  *
  * === Selecting a strategy per person ===
  *
  * Set `modeChoiceStrategyType` in the person's JSON input:
  * {{{
  *   {
  *     "modeChoiceStrategyType": "utility"
  *   }
  * }}}
  *
  * If the named strategy is not found, the `"utility"` strategy is used as default.
  */
object ModeChoiceStrategyRegistry {

  private val registry: TrieMap[String, ModeChoiceStrategy] = TrieMap(
    "utility"      -> new UtilityModeChoiceStrategy(),
    "travel-time"  -> new TravelTimeModeChoiceStrategy()
  )

  /** Returns the strategy registered under `strategyType`.
    *
    * Falls back to `"utility"` when `strategyType` is not registered.
    *
    * @param strategyType
    *   The strategy name declared in the person JSON (e.g. `"utility"`, `"ml-logit"`).
    */
  def get(strategyType: String): ModeChoiceStrategy =
    registry.getOrElse(strategyType, registry("utility"))

  /** Register a custom strategy at runtime.
    *
    * Must be called before actors are created. The strategy instance must be thread-safe if it is
    * shared across multiple Person actors (which is the case for singleton-style strategies).
    *
    * @param name
    *   The strategy type name used in person JSON (e.g. `"my-custom-strategy"`).
    * @param strategy
    *   Strategy instance.
    */
  def register(name: String, strategy: ModeChoiceStrategy): Unit =
    registry.put(name, strategy)

  /** Returns the names of all registered strategies. */
  def registeredNames: Iterable[String] = registry.keys
}
