package org.interscity.htc
package model.hybrid.entity.state.model

/** Immutable lane-owned boundary occupancy, in metres from the publishing Link's origin.
  *
  * Rearmost supports downstream blocking; frontmost supports approach observation. Neither
  * observation is a permission to enter a junction or a substitute for physical admission.
  */
final case class MicroLaneBoundary(
  laneId: Int,
  rearmost: Option[MicroBoundaryVehicle],
  frontmost: Option[MicroBoundaryVehicle]
) {
  require(laneId >= 0, "negative MICRO boundary lane")
  require(rearmost.isDefined == frontmost.isDefined, "incomplete MICRO lane occupancy")
}
