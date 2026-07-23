package org.interscity.htc
package model.hybrid.decision

import model.hybrid.entity.state.plan.PendingDecision

/** A scenario failed its pre-flight mode-decision-engine check. */
final case class ScenarioLoadError(message: String)

/** Fail-fast pre-flight check, meant to run once at scenario load — not yet wired to the real Load
  * Data Manager (that integration is a later phase). Operates directly over the [[PendingDecision]]s
  * found across the loaded plans rather than a formal `PersonPlan` type, since none exists yet.
  */
object ScenarioLoadValidator {

  /** Every distinct `strategyId` referenced by any `allPendingDecisions` entry must:
    *   - resolve to a registered [[ModeDecisionEngine]] (`ModeDecisionEngineRegistry.get`), and
    *   - pass that engine's [[ModeDecisionEngine.validateForScenario]] against `scenarioCtx`.
    *
    * Aborts the whole scenario on the first problem found — this is a scenario-wide configuration
    * error (a bad `strategyId`, or a missing dataset an engine needs), not something to quarantine
    * person-by-person.
    */
  def validateModeDecisionEngines(
    allPendingDecisions: Iterable[PendingDecision],
    scenarioCtx: ScenarioValidationContext
  ): Either[ScenarioLoadError, Unit] = {
    val distinctStrategyIds = allPendingDecisions.map(_.decision.strategyId).toSet

    distinctStrategyIds.foldLeft[Either[ScenarioLoadError, Unit]](Right(())) { (acc, strategyId) =>
      acc.flatMap { _ =>
        ModeDecisionEngineRegistry.get(strategyId) match {
          case None =>
            Left(ScenarioLoadError(s"Unknown mode-decision strategyId '$strategyId' — no engine registered"))
          case Some(engine) =>
            engine.validateForScenario(scenarioCtx) match {
              case Left(EngineUnavailable(engineId, reason)) =>
                Left(ScenarioLoadError(s"Engine '$engineId' unavailable for this scenario: $reason"))
              case Right(()) => Right(())
            }
        }
      }
    }
  }
}
