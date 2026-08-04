package org.interscity.htc
package model.hybrid.decision

import org.htc.protobuf.core.entity.actor.Identify

/** Filters a person's owned private vehicles down to those actually usable as a mode candidate
  * from a given trip origin.
  *
  * Audited invariant: a private vehicle is only a viable candidate mode if it is currently parked
  * at the trip's origin node — a car left at home is not usable from work. A vehicle with no entry
  * in `vehicleCurrentNode` has never been moved from its initial position and, absent evidence
  * otherwise, is treated as available at the origin; this mirrors the assumption already baked into
  * [[model.hybrid.entity.state.PersonState.vehicleCurrentNode]]'s own doc ("When absent for a given
  * mode the vehicle is assumed to be at the person's current activity location").
  *
  * Used by [[TravelTimeEngine]], the only one of the three Phase 2 engines whose wrapped
  * implementation ([[model.hybrid.util.strategy.TravelTimeModeChoiceStrategy]]) evaluates private
  * vehicle modes at all — [[RaptorMultiModalEngine]] ([[model.hybrid.util.RaptorRouter]]) and
  * [[NearestStopUtilityEngine]] ([[model.hybrid.util.ModeChoiceUtil]]) are transit/walk-only by
  * construction and never consider a private vehicle candidate, so this filter would be a no-op
  * dead parameter there — see each engine's own doc for the audit trail of that claim.
  */
object PrivateVehicleCandidates {

  def available(
    originNodeId: String,
    ownedVehicles: Map[String, Identify],
    vehicleCurrentNode: Map[String, String]
  ): Map[String, Identify] =
    ownedVehicles.filter {
      case (mode, _) => vehicleCurrentNode.get(mode).forall(_ == originNodeId)
    }
}
