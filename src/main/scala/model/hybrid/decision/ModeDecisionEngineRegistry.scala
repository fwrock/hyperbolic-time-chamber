package org.interscity.htc
package model.hybrid.decision

import scala.collection.concurrent.TrieMap

/** Thread-safe registry mapping engine ids — matched against
  * [[model.hybrid.entity.state.plan.ModeDecisionRequest.strategyId]] — to [[ModeDecisionEngine]]
  * instances.
  *
  * Deliberately has **no fallback** for an unknown id — unlike
  * [[model.hybrid.util.strategy.ModeChoiceStrategyRegistry]] (which silently falls back to
  * `"utility"`), [[get]] returns `None` for an unregistered id. Silently substituting a different
  * engine than the one a scenario explicitly requested would defeat the fail-fast intent of
  * `ScenarioLoadValidator.validateModeDecisionEngines`: a typo'd `strategyId` must surface as a
  * scenario-load error, not silently run under a different algorithm.
  */
object ModeDecisionEngineRegistry {

  private val engines: TrieMap[String, ModeDecisionEngine] = TrieMap(
    "raptor"               -> new RaptorMultiModalEngine(),
    "nearest-stop-utility" -> new NearestStopUtilityEngine(),
    "travel-time"          -> new TravelTimeEngine()
  )

  /** `None` for an unregistered id — no silent fallback, see class doc. */
  def get(id: String): Option[ModeDecisionEngine] = engines.get(id)

  /** Register a custom engine at runtime. Must be called before scenario load validation runs. */
  def register(id: String, engine: ModeDecisionEngine): Unit = engines.put(id, engine)

  def all: Iterable[ModeDecisionEngine] = engines.values
}
