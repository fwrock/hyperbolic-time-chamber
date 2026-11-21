package org.interscity.htc
package model.mobility.entity.state.plan

import model.mobility.entity.state.ModeChoiceWeights

/** Request payload carried by a [[PendingDecision]], resolved by a
  * `model.mobility.decision.ModeDecisionEngine` (Phase 2).
  *
  * The [[PendingDecision]] case class itself lives in `PlanElement.scala`, not here — it is a
  * direct subtype of the sealed `PlanElement` trait, and Scala 3 requires direct subtypes of a
  * sealed type to be declared in the same source file as the type itself.
  *
  * @param allowedModes    modes the decision strategy is permitted to consider
  * @param strategyId      identifier of the mode-choice strategy to apply — matched against
  *                        `model.mobility.decision.ModeDecisionEngine.id` via
  *                        `model.mobility.decision.ModeDecisionEngineRegistry`
  * @param weightsOverride per-decision override of the scenario's default
  *                        [[ModeChoiceWeights]]; `None` means "use the scenario/person default"
  */
final case class ModeDecisionRequest(
  allowedModes: Set[ConcreteMode],
  strategyId: String,
  weightsOverride: Option[ModeChoiceWeights] = None
)
