package org.interscity.htc
package core.entity.event.control.load

import core.entity.event.BaseEvent

import org.interscity.htc.core.entity.event.data.DefaultBaseEventData
import org.interscity.htc.model.hybrid.decision.ScenarioLoadError

/** Sent by `LoadDataManager` (EAGER sources) or `ProgressiveLoadDataManager` (PROGRESSIVE sources)
  * to `SimulationManager` when `core.actor.manager.load.ScenarioPreflightValidator` finds a
  * scenario-wide mode-decision-engine configuration error (see
  * `model.hybrid.decision.ScenarioLoadValidator.validateModeDecisionEngines`) before any
  * Person/Node/Link actor has been created.
  *
  * `SimulationManager` aborts the whole simulation on receipt, the same way it aborts an
  * unparsable `simulation.json` (see `onSimulationConfigLoadFailed`) — a bad `strategyId` or a
  * missing dataset an engine needs is a fatal, whole-scenario problem, not something to discover
  * one `PendingDecision` at a time at runtime.
  */
case class ScenarioPreflightValidationFailedEvent(error: ScenarioLoadError)
    extends BaseEvent[DefaultBaseEventData]
