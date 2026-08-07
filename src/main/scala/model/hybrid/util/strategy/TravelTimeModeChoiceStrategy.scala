package org.interscity.htc
package model.hybrid.util.strategy

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.model.hybrid.entity.state.{ArrivalLogistics, ModeChoiceWeights}
import org.interscity.htc.model.hybrid.util.{CityMapUtil, DynamicWeightCache, GPSUtil, ModeChoiceUtil, TransitMapUtil, TransitRouteUtil}

/** Strategy that estimates **travel time in seconds** for every candidate mode and picks the
  * highest-scoring option using the standard additive utility function.
  *
  * Unlike [[UtilityModeChoiceStrategy]] (which penalises haversine distance), this strategy
  * penalises time — and for private vehicles it calls the real A* router with live
  * [[DynamicWeightCache]] data, so **congestion is taken into account**.
  *
  * === Utility function ===
  *
  * All modes:
  * {{{U = betaMode × modePref(mode) − betaTravelTime × travelTimeSeconds(mode)}}}
  *
  * === How travel time is estimated per mode ===
  *
  * | Mode | Method |
  * |------|--------|
  * | `car` | `GPSUtil.calcRouteCompact` (A* + dynamic weights) → `dynamicCost / avgCarSpeedMs` |
  * | `bicycle` | same A*, divided by `avgBicycleSpeedMs` |
  * | `motorcycle` | same A*, divided by `avgMotorcycleSpeedMs` |
  * | `walk` | `GPSUtil.calcRouteCompactWalking` → `cost / walkingSpeedMs` |
  * | `bus` / `subway` | `(accessDistM / walkingSpeedMs) + (haversine(boarding,alighting) / avgPTSpeedMs) + (egressDistM / walkingSpeedMs)` |
  *
  * PT in-vehicle time uses haversine distance between boarding and alighting stops divided by an
  * average PT speed (`avgBusSpeedMs` / `avgSubwaySpeedMs`). A full PT network routing model is not
  * yet available, so this is an approximation — but access/egress times are always computed from
  * real stop positions.
  *
  * === Performance note ===
  *
  * Private-vehicle and walking modes invoke the A* router once per candidate. At decision time
  * (one call per person per trip departure) this is cheap compared to full simulation routing, but
  * it does add latency proportional to network size. Use [[UtilityModeChoiceStrategy]] for faster
  * (haversine-only) evaluation when congestion sensitivity is not required.
  *
  * Registered under the `"travel-time"` key in [[ModeChoiceStrategyRegistry]].
  */
class TravelTimeModeChoiceStrategy extends ModeChoiceStrategy {

  private val PrivateModes: Set[String] = Set("car", "bicycle", "motorcycle")

  override def choose(
    originNodeId: String,
    destinationNodeId: String,
    weights: ModeChoiceWeights,
    ownedVehicles: Map[String, Identify] = Map.empty
  ): ModeChoiceResult =
    ModeChoiceResult(
      scoredCandidates(originNodeId, destinationNodeId, weights, ownedVehicles)
        .maxByOption(_._2)
        .map(_._1)
        .getOrElse(ArrivalLogistics(mode = "walk", instant = true))
    )

  /** Every viable `(ArrivalLogistics, score)` candidate this strategy can build — the same
    * evaluation `choose` runs, minus the final argmax. Exposed so a probabilistic strategy
    * ([[org.interscity.htc.model.hybrid.decision.TravelTimeLogitEngine]]) can sample from the full
    * set instead of deterministically taking the highest score every time — real riders don't all
    * make the identical choice when two options score close to each other (random utility theory,
    * McFadden 1974); `choose`'s `maxByOption` is the simplification, not the ground truth.
    */
  def scoredCandidates(
    originNodeId: String,
    destinationNodeId: String,
    weights: ModeChoiceWeights,
    ownedVehicles: Map[String, Identify] = Map.empty
  ): List[(ArrivalLogistics, Double)] = {
    val included = weights.includedModes.map(_.toLowerCase)

    // --- Walk ---
    val walkCandidate: Option[(ArrivalLogistics, Double)] =
      if (!included.contains("walk")) None
      else {
        GPSUtil.calcRouteCompactWalking(originNodeId, destinationNodeId) match {
          case Some((cost, routeQueue)) =>
            val timeS = cost / weights.walkingSpeedMs
            val score = weights.betaMode * weights.modePrefWalk - weights.betaTravelTime * timeS
            Some((ArrivalLogistics(mode = "walk", precomputedRoute = Some(routeQueue.toList)), score))
          case None => None
        }
      }

    // --- Transit (bus / subway) ---
    val transitCandidates: List[(ArrivalLogistics, Double)] =
      if (!TransitMapUtil.isAvailable) Nil
      else {
        val originNodeOpt      = CityMapUtil.nodesById.get(originNodeId)
        val destinationNodeOpt = CityMapUtil.nodesById.get(destinationNodeId)
        (originNodeOpt, destinationNodeOpt) match {
          case (Some(originNode), Some(destNode)) =>
            List("bus", "subway")
              .filter(included.contains)
              .flatMap { mode =>
                val avgPTSpeed = if (mode == "subway") weights.avgSubwaySpeedMs else weights.avgBusSpeedMs
                val modePref   = if (mode == "subway") weights.modePrefSubway    else weights.modePrefBus

                TransitMapUtil
                  .nearestStops(originNode.latitude, originNode.longitude, mode, weights.maxAccessDistanceM)
                  .flatMap { case (boardingStop, accessDistM) =>
                    boardingStop.lines.flatMap { line =>
                      bestAlightingStop(boardingStop, line, destNode.latitude, destNode.longitude).map {
                        case (alightingStop, egressDistM) =>
                          val inVehicleDistM = TransitMapUtil.haversineM(
                            boardingStop.latitude, boardingStop.longitude,
                            alightingStop.latitude, alightingStop.longitude
                          )
                          val accessTimeS    = accessDistM / weights.walkingSpeedMs
                          val inVehicleTimeS = inVehicleDistM / avgPTSpeed
                          val egressTimeS    = egressDistM / weights.walkingSpeedMs
                          val totalTimeS     = accessTimeS + inVehicleTimeS + egressTimeS

                          val score = weights.betaMode * modePref - weights.betaTravelTime * totalTimeS

                          val logistics = ArrivalLogistics(
                            mode                  = mode,
                            line                  = Some(line),
                            boardingStopId        = Some(boardingStop.actorId),
                            boardingStopClassType = Some(boardingStop.actorClassType),
                            alightingNodeId       = Some(alightingStop.nodeId)
                          )
                          (logistics, score)
                      }
                    }
                  }
              }
          case _ => Nil
        }
      }

    // --- Private vehicles (A* with dynamic weights) ---
    val privateVehicleCandidates: List[(ArrivalLogistics, Double)] =
      PrivateModes
        .filter(included.contains)
        .flatMap { mode =>
          ownedVehicles.get(mode).flatMap { vehicleRef =>
            val typicalSpeedMs = mode match {
              case "car"        => weights.avgCarSpeedMs
              case "bicycle"    => weights.avgBicycleSpeedMs
              case "motorcycle" => weights.avgMotorcycleSpeedMs
              case _            => weights.avgCarSpeedMs
            }
            val modePref = mode match {
              case "car"        => weights.modePrefCar
              case "bicycle"    => weights.modePrefBicycle
              case "motorcycle" => weights.modePrefMotorcycle
              case _            => 0.0
            }
            
            GPSUtil.calcRouteCompact(originNodeId, destinationNodeId) map { case (dynamicCost, routeQueue) =>
              val travelTimeS = dynamicCost / typicalSpeedMs
              val score = weights.betaMode * modePref - weights.betaTravelTime * travelTimeS
              (ArrivalLogistics(mode = mode, vehicle = Some(vehicleRef), precomputedRoute = Some(routeQueue.toList)), score)
            }
          }
        }
        .toList

    // --- Every viable candidate, unranked — caller (choose, or a probabilistic strategy) picks ---
    val allCandidates = transitCandidates ++ privateVehicleCandidates ++ walkCandidate.toList
    allCandidates.filter(_._2 > Double.MinValue)
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  /** Finds the stop on `line` (other than `boardingStop`) that minimises haversine distance to
    * `(destLat, destLon)`, filtered to stops the line's real scheduled service actually reaches
    * from `boardingStop` — see `TransitRouteUtil.reachableStopIdsAfter`'s doc for why this filter
    * exists and what it fixes. Falls back to the old undirected candidate set (every other stop on
    * the line, regardless of direction) when `transit_routes.json` has no ordered entry for `line`
    * — the same graceful-degradation behavior as every other `TransitRouteUtil.isAvailable` check
    * in this codebase, not a new failure mode.
    */
  private def bestAlightingStop(
    boardingStop: org.interscity.htc.model.hybrid.entity.state.model.TransitStop,
    line: String,
    destLat: Double,
    destLon: Double
  ): Option[(org.interscity.htc.model.hybrid.entity.state.model.TransitStop, Double)] = {
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
