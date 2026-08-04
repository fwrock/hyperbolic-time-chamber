package org.interscity.htc
package model.hybrid.util

import org.interscity.htc.model.hybrid.entity.state.{ ArrivalLogistics, ModeChoiceWeights }
import org.interscity.htc.model.hybrid.entity.state.model.TransitStop

/** Utility for runtime mode choice based on utility scores and geographic distance.
  *
  * Given an origin and destination (road-network node IDs), [[chooseBestLogistics]] evaluates all
  * reachable transit options and walking, then returns the `ArrivalLogistics` that maximises the
  * following additive utility function:
  *
  * {{{
  * U(mode, boarding, alighting) =
  *     betaMode  × modePref(mode)
  *   − betaAccess × haversine(origin,  boarding)   [metres]
  *   − betaEgress × haversine(alighting, destination) [metres]
  * }}}
  *
  * For walking, `boarding` and `alighting` collapse to origin/destination themselves:
  * {{{
  * U(walk) = betaMode × modePref(walk) − betaAccess × haversine(origin, destination)
  * }}}
  *
  * === Guaranteed backward compatibility ===
  *
  * The method always returns the original `currentLogistics` unchanged when:
  *   - `logistics.fixedMode` is `true` (designer-forced leg)
  *   - The [[TransitMapUtil]] is unavailable (no transit map configured)
  *   - Neither origin nor destination is found in the road-network map
  */
object ModeChoiceUtil {

  /** Evaluates all mode options and returns the best-scoring [[ArrivalLogistics]].
    *
    * @param originNodeId
    *   Road-network node ID of the trip origin (current activity location).
    * @param destinationNodeId
    *   Road-network node ID of the trip destination (next activity location).
    * @param currentLogistics
    *   The static logistics defined in the activity schedule.
    * @param weights
    *   Utility-function weights and configuration thresholds.
    * @return
    *   Updated logistics for the best mode, or `currentLogistics` if no better option is found or
    *   re-evaluation is skipped (see class-level docs).
    */
  def chooseBestLogistics(
    originNodeId: String,
    destinationNodeId: String,
    currentLogistics: ArrivalLogistics,
    weights: ModeChoiceWeights
  ): ArrivalLogistics = {
    if (shouldSkipModeChoice(originNodeId, destinationNodeId, currentLogistics)) {
      currentLogistics
    } else {
      evaluateBestCandidate(originNodeId, destinationNodeId, currentLogistics, weights)
        .getOrElse(currentLogistics)
    }
  }

  private def shouldSkipModeChoice(
    originNodeId: String,
    destinationNodeId: String,
    currentLogistics: ArrivalLogistics
  ): Boolean =
    if (currentLogistics.fixedMode) true
    else if (!TransitMapUtil.isAvailable) true
    else if (originNodeId.isEmpty || destinationNodeId.isEmpty) true
    else false

  private def evaluateBestCandidate(
    originNodeId: String,
    destinationNodeId: String,
    currentLogistics: ArrivalLogistics,
    weights: ModeChoiceWeights
  ): Option[ArrivalLogistics] =
    for {
      originNode <- CityMapUtil.nodesById.get(originNodeId)
      destinationNode <- CityMapUtil.nodesById.get(destinationNodeId)
      bestCandidate <- buildCandidates(
        originNode.latitude,
        originNode.longitude,
        destinationNode.latitude,
        destinationNode.longitude,
        currentLogistics,
        weights
      ).maxByOption(_._2)
    } yield bestCandidate._1

  private def buildCandidates(
    originLat: Double,
    originLon: Double,
    destinationLat: Double,
    destinationLon: Double,
    currentLogistics: ArrivalLogistics,
    weights: ModeChoiceWeights
  ): List[(ArrivalLogistics, Double)] = {
    val walkCandidate =
      buildWalkCandidate(
        originLat,
        originLon,
        destinationLat,
        destinationLon,
        currentLogistics,
        weights
      ).toList

    val transitCandidates =
      buildTransitCandidates(
        originLat,
        originLon,
        destinationLat,
        destinationLon,
        currentLogistics,
        weights
      )

    transitCandidates ++ walkCandidate
  }

  private def buildWalkCandidate(
    originLat: Double,
    originLon: Double,
    destinationLat: Double,
    destinationLon: Double,
    currentLogistics: ArrivalLogistics,
    weights: ModeChoiceWeights
  ): Option[(ArrivalLogistics, Double)] = {
    val straightLineM = TransitMapUtil.haversineM(originLat, originLon, destinationLat, destinationLon)

    if (straightLineM <= weights.maxWalkDistanceM) {
      val score = weights.betaMode * weights.modePrefWalk - weights.betaAccess * straightLineM
      Some((toWalkLogistics(currentLogistics), score))
    } else {
      None
    }
  }

  private def buildTransitCandidates(
    originLat: Double,
    originLon: Double,
    destinationLat: Double,
    destinationLon: Double,
    currentLogistics: ArrivalLogistics,
    weights: ModeChoiceWeights
  ): List[(ArrivalLogistics, Double)] =
    List("bus", "subway").flatMap { mode =>
      val modePref = modePreference(mode, weights)

      TransitMapUtil
        .nearestStops(originLat, originLon, mode, weights.maxAccessDistanceM)
        .flatMap { case (boardingStop, accessDistM) =>
          boardingStop.lines.flatMap { line =>
            bestAlightingStop(boardingStop, line, destinationLat, destinationLon).map {
              case (alightingStop, egressDistM) =>
                val score =
                  weights.betaMode * modePref -
                    weights.betaAccess * accessDistM -
                    weights.betaEgress * egressDistM

                val logistics =
                  toTransitLogistics(currentLogistics, mode, line, boardingStop, alightingStop)

                (logistics, score)
            }
          }
        }
    }

  private def modePreference(mode: String, weights: ModeChoiceWeights): Double =
    if (mode == "subway") weights.modePrefSubway else weights.modePrefBus

  private def toWalkLogistics(currentLogistics: ArrivalLogistics): ArrivalLogistics =
    currentLogistics.copy(
      mode                  = "walk",
      vehicle               = None,
      line                  = None,
      boardingStopId        = None,
      boardingStopClassType = None,
      alightingNodeId       = None,
      fixedMode             = false
    )

  private def toTransitLogistics(
    currentLogistics: ArrivalLogistics,
    mode: String,
    line: String,
    boardingStop: TransitStop,
    alightingStop: TransitStop
  ): ArrivalLogistics =
    currentLogistics.copy(
      mode                  = mode,
      vehicle               = None,
      line                  = Some(line),
      boardingStopId        = Some(boardingStop.actorId),
      boardingStopClassType = Some(boardingStop.actorClassType),
      alightingNodeId       = Some(alightingStop.nodeId),
      fixedMode             = false
    )

  /** Finds the stop on `line` (other than `boardingStop`) that minimises haversine distance to
    * `(destLat, destLon)`, filtered to stops the line's real scheduled service actually reaches
    * from `boardingStop` — see `TransitRouteUtil.reachableStopIdsAfter`'s doc for why this filter
    * exists (it prevents offering a PT trip no vehicle ever fulfills, the class of bug found via
    * `htc-scenario-qa`'s hybrid smoke-test scenario, 2026-07-23). Falls back to the old undirected
    * candidate set (every other stop on the line, regardless of direction) when
    * `transit_routes.json` has no ordered entry for `line` — the same graceful-degradation
    * behavior as every other `TransitRouteUtil.isAvailable`-gated check in this codebase, not a
    * new failure mode. Returns `None` when no candidate remains either way.
    */
  private def bestAlightingStop(
    boardingStop: TransitStop,
    line: String,
    destLat: Double,
    destLon: Double
  ): Option[(TransitStop, Double)] = {
    val candidates = TransitMapUtil.stopsByLine.getOrElse(line, Nil).filter(_.id != boardingStop.id)
    val reachable = TransitRouteUtil.routesByLine
      .get(line)
      .flatMap(route => TransitRouteUtil.reachableStopIdsAfter(route, boardingStop.id))
      .map(reachableIds => candidates.filter(s => reachableIds.contains(s.id)))
      .getOrElse(candidates)
    reachable
      .map(s => (s, TransitMapUtil.haversineM(s.latitude, s.longitude, destLat, destLon)))
      .minByOption(_._2)
  }
}
