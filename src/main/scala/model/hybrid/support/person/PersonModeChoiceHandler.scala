package org.interscity.htc
package model.hybrid.support.person

import model.hybrid.entity.state.{ArrivalLogistics, PersonState}
import model.hybrid.util.strategy.{ModeChoiceResult, ModeChoiceStrategyRegistry}

/** Handles mode choice decisions for person trips.
  *
  * Responsibilities:
  *   - Execute static vs dynamic mode choice
  *   - Integrate with mode choice strategies
  *   - Manage multi-leg journey planning
  *   - Filter available vehicles by location
  *
  * @param personId Person actor ID
  * @param globalDynamicModeChoiceEnabled Global dynamic mode choice setting
  * @param globalModeChoiceIncludedModes Global override for included modes
  * @param metricsReporter Metrics reporter for logging decisions
  * @param logDebug Debug logging function
  * @param logWarn Warning logging function
  */
class PersonModeChoiceHandler(
  personId: String,
  globalDynamicModeChoiceEnabled: Boolean,
  globalModeChoiceIncludedModes: Option[Set[String]],
  metricsReporter: PersonMetricsReporter,
  logDebug: String => Unit,
  logWarn: String => Unit
) {

  /** Check if dynamic mode choice is enabled.
    *
    * @param state Current person state
    * @return true if dynamic mode choice should be used
    */
  def isDynamicModeChoiceEnabled(state: PersonState): Boolean =
    globalDynamicModeChoiceEnabled || state.enableDynamicModeChoice

  /** Execute mode choice logic.
    *
    * When dynamic mode choice is enabled, delegates to the configured
    * [[model.hybrid.util.strategy.ModeChoiceStrategy]] regardless of the requested schedule mode,
    * so all trips (`auto` and explicit modes like `car`/`bus`) follow the same decision pipeline.
    *
    * Fixed legs (`fixedMode = true`) always keep their original logistics.
    *
    * When RAPTOR returns a multi-leg transit route, the pending legs are stored and returned
    * as part of ModeChoiceExecutionResult.
    *
    * @param originNodeId Origin node ID
    * @param destinationNodeId Destination node ID
    * @param logistics Requested arrival logistics
    * @param state Current person state
    * @param modeChoiceLogEvery Sampling rate for mode choice logging
    * @return Mode choice execution result with resolved logistics and pending legs
    */
  def executeModeChoice(
    originNodeId: String,
    destinationNodeId: String,
    logistics: ArrivalLogistics,
    state: PersonState,
    modeChoiceLogEvery: Int
  ): ModeChoiceExecutionResult = {
    if (logistics.fixedMode)
      return ModeChoiceExecutionResult(logistics, Nil)

    if (!isDynamicModeChoiceEnabled(state)) {
      logDebug(s"$personId chose mode: ${logistics.mode} (static)")
      metricsReporter.maybeLogModeChoiceDecision(
        originNodeId = originNodeId,
        destinationNodeId = destinationNodeId,
        requestedMode = logistics.mode,
        resolvedMode = logistics.mode,
        source = "static",
        logEvery = modeChoiceLogEvery,
        globalEnabled = globalDynamicModeChoiceEnabled,
        stateEnabled = state.enableDynamicModeChoice
      )
      ModeChoiceExecutionResult(logistics, Nil)
    } else {
      val strategy = ModeChoiceStrategyRegistry.get(state.modeChoiceStrategyType)
      val effectiveWeights = globalModeChoiceIncludedModes
        .map(modes => state.modeChoiceWeights.copy(includedModes = modes))
        .getOrElse(state.modeChoiceWeights)

      // Only offer private vehicles that are currently parked at the origin.
      // If vehicleCurrentNode has no entry for a mode the vehicle hasn't moved yet
      // (start of day) — treat it as available at the current location.
      val availableVehicles = state.ownedVehicles.filter { case (mode, _) =>
        state.vehicleCurrentNode.get(mode).forall(_ == originNodeId)
      }

      val choiceResult = strategy.choose(
        originNodeId      = originNodeId,
        destinationNodeId = destinationNodeId,
        weights           = effectiveWeights,
        ownedVehicles     = availableVehicles
      )

      val resolved = choiceResult.logistics
      val pendingLegs = choiceResult.pendingLegs

      if (pendingLegs.nonEmpty) {
        logDebug(
          s"$personId RAPTOR multi-leg route: ${pendingLegs.size + 1} legs total"
        )
      }

      val effective =
        if (resolved.mode.equalsIgnoreCase("auto")) {
          logWarn(
            s"$personId strategy=${state.modeChoiceStrategyType} returned unresolved mode='auto'; " +
              s"keeping requested mode='${logistics.mode}'"
          )
          ModeChoiceExecutionResult(logistics, Nil) // Clear pending legs on fallback
        } else {
          ModeChoiceExecutionResult(resolved, pendingLegs)
        }

      metricsReporter.maybeLogModeChoiceDecision(
        originNodeId = originNodeId,
        destinationNodeId = destinationNodeId,
        requestedMode = logistics.mode,
        resolvedMode = effective.logistics.mode,
        source = "dynamic",
        logEvery = modeChoiceLogEvery,
        globalEnabled = globalDynamicModeChoiceEnabled,
        stateEnabled = state.enableDynamicModeChoice
      )
      effective
    }
  }

  /** Get current trip origin node ID (physical position during multi-leg journeys).
    *
    * During multi-leg journeys (between access walk, PT legs, transfer walks, and egress walk)
    * `currentPhysicalNodeId` tracks where the person really is. Outside journeys it falls back
    * to the current activity's node.
    *
    * @param state Current person state
    * @return Origin node ID for next trip leg
    */
  def currentTripOriginNodeId(state: PersonState): String =
    state.currentPhysicalNodeId
      .orElse(state.currentActivity.map(_.nodeId))
      .getOrElse("")
}

/** Result of mode choice execution.
  *
  * @param logistics Resolved arrival logistics
  * @param pendingLegs Pending transfer legs from multi-leg routing (RAPTOR)
  */
case class ModeChoiceExecutionResult(
  logistics: ArrivalLogistics,
  pendingLegs: List[ArrivalLogistics]
)
