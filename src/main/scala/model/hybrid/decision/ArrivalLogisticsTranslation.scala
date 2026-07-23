package org.interscity.htc
package model.hybrid.decision

import model.hybrid.entity.state.ArrivalLogistics
import model.hybrid.entity.state.plan.{ AtomicLeg, ConcreteMode, PrivateVehicleLeg, StopRef, TransitLeg, WalkLeg }
import model.hybrid.util.TransitMapUtil

/** Adapts the legacy single-leg [[ArrivalLogistics]] shape — returned by
  * [[model.hybrid.util.ModeChoiceUtil.chooseBestLogistics]] and
  * [[model.hybrid.util.strategy.TravelTimeModeChoiceStrategy.choose]] — into a single [[AtomicLeg]].
  *
  * === Why this isn't a 1:1 field copy ===
  *
  * `ArrivalLogistics` carries the boarding stop's `actorId`/`actorClassType` but only the alighting
  * ''node'' (`alightingNodeId`) — it never stores the alighting stop's own `actorId`/`actorClassType`,
  * because the legacy `Person` actor only ever needs to know the node at which to get off, never to
  * message the alighting stop directly. [[TransitLeg.alightingStop]] is a full [[StopRef]] though,
  * so building one losslessly requires reconstructing the missing `actorId`/`actorClassType` by
  * searching [[TransitMapUtil]] for a stop on the same line whose `nodeId` matches. When no such stop
  * is found, translation reports failure (`None`) rather than fabricating a [[StopRef]] with blank
  * fields — see [[resolveTransitLeg]].
  *
  * Contrast with [[RaptorMultiModalEngine.translateResult]], which starts from
  * [[model.hybrid.util.RaptorRouter.RaptorResult]] — a shape that already carries full
  * [[model.hybrid.entity.state.model.TransitStop]]s at both ends of every leg — and is therefore a
  * lossless, lookup-free translation.
  */
object ArrivalLogisticsTranslation {

  def modeOf(rawMode: String): Option[ConcreteMode] = rawMode.toLowerCase match {
    case "walk"       => Some(ConcreteMode.Walk)
    case "car"        => Some(ConcreteMode.Car)
    case "bicycle"    => Some(ConcreteMode.Bicycle)
    case "motorcycle" => Some(ConcreteMode.Motorcycle)
    case "bus"        => Some(ConcreteMode.Bus)
    case "subway"     => Some(ConcreteMode.Subway)
    case _            => None // "auto", "transit" — never a resolved, executable mode
  }

  /** Pure — no lookups. */
  def buildWalkLeg(originNodeId: String, destinationNodeId: String, logistics: ArrivalLogistics): WalkLeg =
    WalkLeg(originNodeId, destinationNodeId, logistics.precomputedRoute)

  /** Pure — no lookups. `None` only when `logistics.vehicle` is empty, which should not happen for
    * a private-vehicle mode chosen by a strategy given a non-empty `ownedVehicles` map.
    */
  def buildPrivateVehicleLeg(mode: ConcreteMode, logistics: ArrivalLogistics): Option[PrivateVehicleLeg] =
    logistics.vehicle.map(vehicle => PrivateVehicleLeg(mode, vehicle, logistics.driverAttributes))

  /** Pure — no lookups. Builds a [[TransitLeg]] once both [[StopRef]]s are already known. Kept
    * separate from [[resolveTransitLeg]] specifically so this half of the translation is directly
    * unit-testable with synthetic data, without touching the [[TransitMapUtil]] singleton.
    */
  def buildTransitLeg(mode: ConcreteMode, line: String, boardingStop: StopRef, alightingStop: StopRef): TransitLeg =
    TransitLeg(mode, line, boardingStop, alightingStop)

  /** Impure glue: reconstructs the [[StopRef]]s missing from `logistics` (see class doc) by
    * searching [[TransitMapUtil]], then delegates to [[buildTransitLeg]].
    *
    * Not covered by a dedicated success-path unit test: exercising the "stop found" branch requires
    * [[TransitMapUtil]] to have real stops loaded, which in turn requires `chooseBestLogistics`/
    * `choose` to have picked a transit candidate in the first place — and both of the wrapped
    * implementations only evaluate transit candidates once `CityMapUtil.nodesById` resolves the
    * origin/destination node's lat/lon, which hits the same un-mockable, eagerly-initialised,
    * file-backed singleton documented on `RaptorMultiModalEngineSpec`. The "not found" branch (and
    * the overall glue logic) is still exercised indirectly: see `NearestStopUtilityEngineSpec`'s and
    * `TravelTimeEngineSpec`'s "no viable journey" cases, which fall through the walk-only path
    * precisely because transit data is unavailable in the test environment.
    */
  def resolveTransitLeg(mode: ConcreteMode, logistics: ArrivalLogistics): Option[TransitLeg] =
    for {
      line            <- logistics.line
      boardingActorId <- logistics.boardingStopId
      alightingNodeId <- logistics.alightingNodeId
      boardingStop    <- TransitMapUtil.stopsById.values.find(_.actorId == boardingActorId)
      alightingStop   <- TransitMapUtil.stopsById.values.find(s => s.nodeId == alightingNodeId && s.lines.contains(line))
    } yield buildTransitLeg(
      mode,
      line,
      StopRef(boardingStop.actorId, boardingStop.actorClassType, boardingStop.nodeId),
      StopRef(alightingStop.actorId, alightingStop.actorClassType, alightingStop.nodeId)
    )

  /** Full single-leg translation entry point used by [[NearestStopUtilityEngine]] and
    * [[TravelTimeEngine]]. `None` when the mode string is unresolvable (`"auto"`), or a
    * private-vehicle/transit leg is missing the data it needs to build a complete [[AtomicLeg]].
    */
  def translate(originNodeId: String, destinationNodeId: String, logistics: ArrivalLogistics): Option[AtomicLeg] =
    modeOf(logistics.mode).flatMap {
      case ConcreteMode.Walk => Some(buildWalkLeg(originNodeId, destinationNodeId, logistics))
      case mode @ (ConcreteMode.Car | ConcreteMode.Bicycle | ConcreteMode.Motorcycle) =>
        buildPrivateVehicleLeg(mode, logistics)
      case mode @ (ConcreteMode.Bus | ConcreteMode.Subway) =>
        resolveTransitLeg(mode, logistics)
    }
}
