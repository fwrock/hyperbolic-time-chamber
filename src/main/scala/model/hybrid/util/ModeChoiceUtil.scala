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
  *   - `logistics.vehicle` is defined (private vehicle trip — not subject to re-evaluation)
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
    // Private vehicle trips and explicitly fixed legs are never re-evaluated.
    if (currentLogistics.vehicle.isDefined || currentLogistics.fixedMode)
      return currentLogistics

    if (!TransitMapUtil.isAvailable) return currentLogistics

    if (originNodeId.isEmpty || destinationNodeId.isEmpty) return currentLogistics

    val originNodeOpt      = CityMapUtil.nodesById.get(originNodeId)
    val destinationNodeOpt = CityMapUtil.nodesById.get(destinationNodeId)

    (originNodeOpt, destinationNodeOpt) match {
      case (Some(originNode), Some(destinationNode)) =>
        val oLat = originNode.latitude
        val oLon = originNode.longitude
        val dLat = destinationNode.latitude
        val dLon = destinationNode.longitude

        val straightLineM = TransitMapUtil.haversineM(oLat, oLon, dLat, dLon)

        val walkCandidate: Option[(ArrivalLogistics, Double)] =
          if (straightLineM <= weights.maxWalkDistanceM) {
            val score =
              weights.betaMode * weights.modePrefWalk - weights.betaAccess * straightLineM
            val logistics = currentLogistics.copy(
              mode    = "walk",
              vehicle = None,
              line    = None,
              boardingStopId        = None,
              boardingStopClassType = None,
              alightingNodeId       = None,
              fixedMode             = false
            )
            Some((logistics, score))
          } else None

        val transitCandidates: List[(ArrivalLogistics, Double)] =
          List("bus", "subway").flatMap { mode =>
            val modePref = if (mode == "subway") weights.modePrefSubway else weights.modePrefBus

            TransitMapUtil
              .nearestStops(oLat, oLon, mode, weights.maxAccessDistanceM)
              .flatMap { case (boardingStop, accessDistM) =>
                boardingStop.lines.flatMap { line =>
                  bestAlightingStop(boardingStop, line, dLat, dLon).map {
                    case (alightingStop, egressDistM) =>
                      val score =
                        weights.betaMode * modePref -
                          weights.betaAccess * accessDistM -
                          weights.betaEgress * egressDistM

                      val logistics = currentLogistics.copy(
                        mode                  = mode,
                        vehicle               = None,
                        line                  = Some(line),
                        boardingStopId        = Some(boardingStop.actorId),
                        boardingStopClassType = Some(boardingStop.actorClassType),
                        alightingNodeId       = Some(alightingStop.nodeId),
                        fixedMode             = false
                      )
                      (logistics, score)
                  }
                }
              }
          }

        val allCandidates = transitCandidates ++ walkCandidate.toList

        allCandidates.maxByOption(_._2) match {
          case Some((bestLogistics, _)) => bestLogistics
          case None                     => currentLogistics
        }

      case _ =>
        currentLogistics
    }
  }

  /** Finds the stop on `line` (other than `boardingStop`) that minimises haversine distance to
    * `(destLat, destLon)`. Returns `None` when the line has no other stops.
    */
  private def bestAlightingStop(
    boardingStop: TransitStop,
    line: String,
    destLat: Double,
    destLon: Double
  ): Option[(TransitStop, Double)] =
    TransitMapUtil.stopsByLine
      .getOrElse(line, Nil)
      .filter(_.id != boardingStop.id)
      .map(s => (s, TransitMapUtil.haversineM(s.latitude, s.longitude, destLat, destLon)))
      .minByOption(_._2)
}
