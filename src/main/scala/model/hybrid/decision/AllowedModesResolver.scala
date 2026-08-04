package org.interscity.htc
package model.hybrid.decision

import model.hybrid.entity.state.plan.ConcreteMode

/** Resolves the effective `allowedModes` set for one person's pending decision.
  *
  * Pure and standalone — not yet wired to real `simulation.json`/`persons_*.json` parsing (that
  * integration is a later phase); this is the isolated policy function that integration will call.
  */
object AllowedModesResolver {

  /** @param perScenarioDefault the scenario-wide default allowed-modes set
    * @param perPersonOverride  an optional per-person override; when present it wins outright
    *                           (no merge with the scenario default)
    */
  def resolveAllowedModes(
    perScenarioDefault: Set[ConcreteMode],
    perPersonOverride: Option[Set[ConcreteMode]]
  ): Set[ConcreteMode] =
    perPersonOverride.getOrElse(perScenarioDefault)
}
