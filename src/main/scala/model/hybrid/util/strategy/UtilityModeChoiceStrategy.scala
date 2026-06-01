package org.interscity.htc
package model.hybrid.util.strategy

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.model.hybrid.entity.state.{ ArrivalLogistics, ModeChoiceWeights }
import org.interscity.htc.model.hybrid.util.{
  CityMapUtil,
  ModeChoiceUtil,
  RaptorRouter,
  TransitMapUtil,
  TransitRouteUtil
}

/** Strategy that applies the additive utility function over all modes listed in
  * `weights.includedModes`.
  *
  * === Supported modes ===
  *
  *   - `"bus"` / `"subway"` — evaluated via [[ModeChoiceUtil]] (single-leg, no transfer) when
  *     [[TransitRouteUtil]] is unavailable, or via the frequency-based RAPTOR algorithm (multi-leg,
  *     with transfers) when [[TransitRouteUtil]] is available.
  *   - `"walk"` — evaluated when O→D distance ≤ `weights.maxWalkDistanceM`
  *   - `"car"` / `"bicycle"` / `"motorcycle"` — evaluated only when the person owns that vehicle
  *     (`ownedVehicles` map must contain the mode key)
  *
  * === Utility function ===
  *
  * Transit / walking:
  * {{{U = betaMode × modePref − betaAccess × accessDistM − betaEgress × egressDistM}}}
  *
  * Private vehicles (door-to-door, no access/egress legs):
  * {{{U = betaMode × modePref − betaPrivateVehicle × haversine(origin, destination)}}}
  *
  * RAPTOR PT (scored by total estimated travel time in seconds):
  * {{{U = betaMode × modePref(mode) − betaTravelTime × totalTravelTimeSecs}}}
  *
  * Falls back to `mode = "walk"` when no candidate is available.
  *
  * This is the default strategy registered under the `"utility"` key in
  * [[ModeChoiceStrategyRegistry]].
  */
class UtilityModeChoiceStrategy extends ModeChoiceStrategy {

  private val PrivateModes: Set[String] = Set("car", "bicycle", "motorcycle")

  override def choose(
    originNodeId: String,
    destinationNodeId: String,
    weights: ModeChoiceWeights,
    ownedVehicles: Map[String, Identify] = Map.empty
  ): ModeChoiceResult = {
    val included = weights.includedModes.map(_.toLowerCase)

    // --- Transit + walking candidates ---
    // When TransitRouteUtil is available, use RAPTOR for multi-leg routing.
    // Otherwise fall back to the single-leg ModeChoiceUtil.
    val transitIncluded = included.intersect(Set("bus", "subway", "walk"))

    val (transitAndWalk: List[(ModeChoiceResult, Double)]) =
      if (transitIncluded.isEmpty) Nil
      else if (TransitRouteUtil.isAvailable && transitIncluded.intersect(Set("bus", "subway")).nonEmpty) {
        // ── RAPTOR path ──────────────────────────────────────────────────
        val raptorResult = for {
          originNode <- CityMapUtil.nodesById.get(originNodeId)
          destNode   <- CityMapUtil.nodesById.get(destinationNodeId)
          result <- RaptorRouter.route(
            originLat        = originNode.latitude,
            originLon        = originNode.longitude,
            destLat          = destNode.latitude,
            destLon          = destNode.longitude,
            includedStopTypes = transitIncluded.intersect(Set("bus", "subway")),
            maxAccessDistM   = weights.maxAccessDistanceM,
            maxTransferWalkM = weights.maxAccessDistanceM,
            maxRounds        = 3
          )
        } yield result

        val raptorCandidates: List[(ModeChoiceResult, Double)] = raptorResult match {
          case Some(r) if r.legs.nonEmpty =>
            val firstMode = r.legs.head.mode  // "bus" or "subway"
            val modePref = if (firstMode == "subway") weights.modePrefSubway else weights.modePrefBus
            val score = weights.betaMode * modePref - weights.betaTravelTime * r.totalTimeSecs

            // Build full leg chain: access walk + PT legs + transfer walks + egress walk.
            val allLegs         = buildFullLegChain(originNodeId, destinationNodeId, r.legs)
            val firstLogistics  = allLegs.head
            val pendingLogistics = allLegs.tail

            List((ModeChoiceResult(firstLogistics, pendingLogistics), score))

          case _ => Nil
        }

        // Also add walk candidate if included.
        val walkCandidates: List[(ModeChoiceResult, Double)] =
          if (included.contains("walk")) {
            val score = scoreWalk(originNodeId, destinationNodeId, weights)
            if (score > Double.MinValue)
              List((ModeChoiceResult(ArrivalLogistics(mode = "walk")), score))
            else Nil
          } else Nil

        raptorCandidates ++ walkCandidates

      } else {
        // ── Single-leg fallback (no routes file) ─────────────────────────
        val maskedWeights = weights.copy(
          modePrefBus    = if (included.contains("bus"))    weights.modePrefBus    else Double.MinValue,
          modePrefSubway = if (included.contains("subway")) weights.modePrefSubway else Double.MinValue,
          modePrefWalk   = if (included.contains("walk"))   weights.modePrefWalk   else Double.MinValue
        )
        val best = ModeChoiceUtil.chooseBestLogistics(
          originNodeId      = originNodeId,
          destinationNodeId = destinationNodeId,
          currentLogistics  = ArrivalLogistics(mode = "walk"),
          weights           = maskedWeights
        )
        val score = best.mode.toLowerCase match {
          case "walk" => scoreWalk(originNodeId, destinationNodeId, weights)
          case m      => scorePT(best, originNodeId, destinationNodeId, m, weights)
        }
        List((ModeChoiceResult(best), score))
      }

    // --- Private vehicle candidates ---
    val privateVehicles: List[(ModeChoiceResult, Double)] =
      PrivateModes
        .filter(included.contains)
        .flatMap { mode =>
          ownedVehicles.get(mode).map { vehicleRef =>
            val dist  = directDistanceM(originNodeId, destinationNodeId)
            val pref  = mode match {
              case "car"        => weights.modePrefCar
              case "bicycle"    => weights.modePrefBicycle
              case "motorcycle" => weights.modePrefMotorcycle
              case _            => 0.0
            }
            val score    = weights.betaMode * pref - weights.betaPrivateVehicle * dist
            val logistics = ArrivalLogistics(mode = mode, vehicle = Some(vehicleRef))
            (ModeChoiceResult(logistics), score)
          }
        }
        .toList

    // --- Pick best overall ---
    val viable = (transitAndWalk ++ privateVehicles).filter(_._2 > Double.MinValue)
    viable.maxByOption(_._2).map(_._1).getOrElse(
      ModeChoiceResult(ArrivalLogistics(mode = "walk", instant = true))
    )
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  /** Builds the complete leg chain for a RAPTOR result, inserting walking legs before the first
    * boarding stop (access walk), between consecutive PT legs at different nodes (transfer walk),
    * and after the last alighting stop (egress walk).
    *
    * Walk legs use `alightingNodeId` to carry the walk destination so that [[model.hybrid.actor.Person]]
    * can route to the correct node without an activity-index advance.
    */
  private def buildFullLegChain(
    originNodeId: String,
    destinationNodeId: String,
    legs: List[RaptorRouter.RaptorLeg]
  ): List[ArrivalLogistics] = {
    var result = List.empty[ArrivalLogistics]

    // Access walk: origin → first boarding stop (only when they are different nodes)
    val firstBoardingNodeId = legs.head.boardingStop.nodeId
    if (firstBoardingNodeId != originNodeId)
      result = result :+ ArrivalLogistics(mode = "walk", alightingNodeId = Some(firstBoardingNodeId))

    // PT legs with optional transfer walks between consecutive legs
    legs.zipWithIndex.foreach { case (leg, idx) =>
      result = result :+ ArrivalLogistics(
        mode                  = leg.mode,
        line                  = Some(leg.line),
        boardingStopId        = Some(leg.boardingStop.actorId),   // actor ID, not nodeId
        boardingStopClassType = Some(leg.boardingStop.actorClassType),
        alightingNodeId       = Some(leg.alightingStop.nodeId)
      )
      // Transfer walk to the next boarding stop (only when nodes differ)
      if (idx < legs.size - 1) {
        val nextBoardingNodeId = legs(idx + 1).boardingStop.nodeId
        if (leg.alightingStop.nodeId != nextBoardingNodeId)
          result = result :+ ArrivalLogistics(mode = "walk", alightingNodeId = Some(nextBoardingNodeId))
      }
    }

    // Egress walk: last alighting stop → destination (only when nodes differ)
    val lastAlightingNodeId = legs.last.alightingStop.nodeId
    if (lastAlightingNodeId != destinationNodeId)
      result = result :+ ArrivalLogistics(mode = "walk", alightingNodeId = Some(destinationNodeId))

    result
  }

  private def directDistanceM(originNodeId: String, destinationNodeId: String): Double =
    (for {
      o <- CityMapUtil.nodesById.get(originNodeId)
      d <- CityMapUtil.nodesById.get(destinationNodeId)
    } yield TransitMapUtil.haversineM(o.latitude, o.longitude, d.latitude, d.longitude))
      .getOrElse(Double.MaxValue)

  private def scoreWalk(
    originNodeId: String,
    destinationNodeId: String,
    weights: ModeChoiceWeights
  ): Double = {
    val dist = directDistanceM(originNodeId, destinationNodeId)
    if (dist > weights.maxWalkDistanceM) Double.MinValue
    else weights.betaMode * weights.modePrefWalk - weights.betaAccess * dist
  }

  private def scorePT(
    logistics: ArrivalLogistics,
    originNodeId: String,
    destinationNodeId: String,
    mode: String,
    weights: ModeChoiceWeights
  ): Double = {
    val modePref = if (mode == "subway") weights.modePrefSubway else weights.modePrefBus
    val accessDist = logistics.boardingStopId
      .flatMap(CityMapUtil.nodesById.get)
      .flatMap(boarding =>
        CityMapUtil.nodesById.get(originNodeId).map { origin =>
          TransitMapUtil.haversineM(origin.latitude, origin.longitude, boarding.latitude, boarding.longitude)
        }
      )
      .getOrElse(0.0)
    val egressDist = logistics.alightingNodeId
      .flatMap(CityMapUtil.nodesById.get)
      .flatMap(alighting =>
        CityMapUtil.nodesById.get(destinationNodeId).map { dest =>
          TransitMapUtil.haversineM(alighting.latitude, alighting.longitude, dest.latitude, dest.longitude)
        }
      )
      .getOrElse(0.0)
    weights.betaMode * modePref - weights.betaAccess * accessDist - weights.betaEgress * egressDist
  }
}

