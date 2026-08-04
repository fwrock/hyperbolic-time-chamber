package org.interscity.htc
package model.hybrid.decision

import model.hybrid.entity.state.{ ArrivalLogistics, ModeChoiceWeights }
import model.hybrid.entity.state.plan.{ AtomicLeg, ConcreteMode, ModeDecisionRequest }
import model.hybrid.util.strategy.TravelTimeModeChoiceStrategy
import model.hybrid.util.{ CityMapUtil, RaptorRouter, TransitMapUtil, TransitRouteUtil }

/** Shared resolution flow for every `TravelTimeModeChoiceStrategy`-backed engine — currently
  * [[TravelTimeEngine]] (deterministic argmax) and [[TravelTimeLogitEngine]] (probabilistic
  * sampling). Both only differ in *how* a winner is picked from
  * `TravelTimeModeChoiceStrategy.scoredCandidates`'s list — the "lazy RAPTOR validation" flow
  * around that pick (see [[TravelTimeEngine]]'s class doc for the full rationale) is identical for
  * both, so it lives here once rather than being copy-pasted per engine.
  */
private[decision] object TravelTimeChoiceResolution {

  /** @param pick
    *   Chooses one candidate from a non-empty-or-empty scored list — `None` for an empty list (no
    *   candidate scored above `Double.MinValue`), otherwise the selected `ArrivalLogistics`. Called
    *   twice on the RAPTOR-rejection path: once against the full candidate set, once against a
    *   second scoring pass with bus/subway masked out — a probabilistic `pick` draws fresh both
    *   times, which is the correct semantics ("reconsider now that PT is known infeasible"), not a
    *   bug to guard against.
    */
  def resolve(
    strategy: TravelTimeModeChoiceStrategy,
    originNodeId: String,
    destinationNodeId: String,
    request: ModeDecisionRequest,
    ctx: DecisionContext,
    engineId: String,
    pick: List[(ArrivalLogistics, Double)] => Option[ArrivalLogistics]
  ): Either[NoViableJourney, List[AtomicLeg]] = {
    val weights = request.weightsOverride.getOrElse(ctx.weights)
    val availableVehicles = PrivateVehicleCandidates.available(originNodeId, ctx.ownedVehicles, ctx.vehicleCurrentNode)

    val candidates = strategy.scoredCandidates(originNodeId, destinationNodeId, weights, availableVehicles)
    val picked = pick(candidates).getOrElse(ArrivalLogistics(mode = "walk", instant = true))

    ArrivalLogisticsTranslation.modeOf(picked.mode) match {
      case Some(mode @ (ConcreteMode.Bus | ConcreteMode.Subway)) if TransitRouteUtil.isAvailable =>
        val stopTypes = raptorIncludedStopTypes(weights, request.allowedModes)
        if (stopTypes.isEmpty)
          // The pick landed on a PT mode the request doesn't actually allow — same outcome as the
          // non-RAPTOR path's post-hoc allowedModes rejection, just discovered earlier (no point
          // routing for a mode we'd reject anyway).
          Left(NoViableJourney(
            s"$engineId: resolved mode $mode not in allowedModes for $originNodeId -> $destinationNodeId"
          ))
        else
          raptorItinerary(originNodeId, destinationNodeId, weights, stopTypes) match {
            case Some(legs) => Right(legs)
            case None =>
              val withoutTransit = weights.copy(modePrefBus = Double.MinValue, modePrefSubway = Double.MinValue)
              val fallbackCandidates = strategy.scoredCandidates(originNodeId, destinationNodeId, withoutTransit, availableVehicles)
              val fallback = pick(fallbackCandidates).getOrElse(ArrivalLogistics(mode = "walk", instant = true))
              translateSingleLeg(originNodeId, destinationNodeId, fallback, request, engineId)
          }
      case _ =>
        translateSingleLeg(originNodeId, destinationNodeId, picked, request, engineId)
    }
  }

  /** Which RAPTOR stop types are actually worth searching for — the intersection of what the
    * person's `weights.includedModes` names and what this specific decision's `allowedModes`
    * permits. Pure — no singleton/file reads — so this narrowing logic is unit-testable on its
    * own, independent of `RaptorRouter`/`CityMapUtil`'s eager-singleton testing constraints (see
    * `RaptorMultiModalEngineSpec`'s doc).
    */
  def raptorIncludedStopTypes(weights: ModeChoiceWeights, allowedModes: Set[ConcreteMode]): Set[String] = {
    val included = weights.includedModes.map(_.toLowerCase).toSet
    Set(
      "bus" -> ConcreteMode.Bus,
      "subway" -> ConcreteMode.Subway
    ).collect { case (label, mode) if included.contains(label) && allowedModes.contains(mode) => label }
  }

  private def raptorItinerary(
    originNodeId: String,
    destinationNodeId: String,
    weights: ModeChoiceWeights,
    includedStopTypes: Set[String]
  ): Option[List[AtomicLeg]] =
    for {
      originNode <- CityMapUtil.nodesById.get(originNodeId)
      destNode   <- CityMapUtil.nodesById.get(destinationNodeId)
      if TransitMapUtil.isAvailable
      result <- RaptorRouter.route(
        originLat         = originNode.latitude,
        originLon         = originNode.longitude,
        destLat           = destNode.latitude,
        destLon           = destNode.longitude,
        includedStopTypes = includedStopTypes,
        maxAccessDistM    = weights.maxAccessDistanceM,
        maxTransferWalkM  = weights.maxAccessDistanceM
      )
    } yield RaptorMultiModalEngine.translateResult(originNodeId, destinationNodeId, result)

  private def translateSingleLeg(
    originNodeId: String,
    destinationNodeId: String,
    logistics: ArrivalLogistics,
    request: ModeDecisionRequest,
    engineId: String
  ): Either[NoViableJourney, List[AtomicLeg]] =
    ArrivalLogisticsTranslation.translate(originNodeId, destinationNodeId, logistics) match {
      case Some(leg) if request.allowedModes.contains(leg.mode) =>
        Right(List(leg))
      case Some(leg) =>
        Left(NoViableJourney(
          s"$engineId: resolved mode ${leg.mode} not in allowedModes for $originNodeId -> $destinationNodeId"
        ))
      case None =>
        Left(NoViableJourney(
          s"$engineId: could not translate mode-choice result for $originNodeId -> $destinationNodeId"
        ))
    }
}
