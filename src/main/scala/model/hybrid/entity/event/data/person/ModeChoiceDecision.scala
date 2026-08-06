package org.interscity.htc
package model.hybrid.entity.event.data.person

import core.entity.event.data.BaseEventData

/** Internal Person state for mode choice decision.
  *
  * Not a message, but a data structure to represent mode choice logic.
  *
  * @param availableModes
  *   Modes available to person
  * @param chosenMode
  *   Mode selected by person
  * @param decisionFactors
  *   Factors influencing decision (for analysis)
  */
case class ModeChoiceDecision(
  availableModes: List[String],
  chosenMode: String,
  decisionFactors: Map[String, Double] = Map.empty
) extends BaseEventData
