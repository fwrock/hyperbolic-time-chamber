package org.interscity.htc
package model.hybrid.entity.state.plan

import model.hybrid.entity.state.ModeChoiceWeights

/** Request payload carried by a [[PendingDecision]], resolved by a
  * `model.hybrid.decision.ModeDecisionEngine` (Phase 2).
  *
  * The [[PendingDecision]] case class itself lives in `PlanElement.scala`, not here — it is a
  * direct subtype of the sealed `PlanElement` trait, and Scala 3 requires direct subtypes of a
  * sealed type to be declared in the same source file as the type itself.
  *
  * @param allowedModes    modes the decision strategy is permitted to consider
  * @param strategyId      identifier of the mode-choice strategy to apply — matched against
  *                        `model.hybrid.decision.ModeDecisionEngine.id` via
  *                        `model.hybrid.decision.ModeDecisionEngineRegistry`
  * @param weightsOverride per-decision override of the scenario's default
  *                        [[ModeChoiceWeights]]; `None` means "use the scenario/person default"
  */
final case class ModeDecisionRequest(
  allowedModes: Set[ConcreteMode],
  strategyId: String,
  weightsOverride: Option[ModeChoiceWeights] = None
)
