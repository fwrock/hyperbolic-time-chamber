package org.interscity.htc
package model.hybrid.entity.state.plan

/** A mode that has already been resolved to a concrete, executable form.
  *
  * Distinct from [[model.hybrid.entity.state.enumeration.TravelMode]] (used by the legacy
  * `ArrivalLogistics`/`PersonState` trip-tracking model): `ConcreteMode` is scoped to the new
  * plan-element hierarchy and only ever labels an [[AtomicLeg]] that is ready to execute — never
  * a pending, unresolved choice.
  */
enum ConcreteMode:
  case Walk, Car, Bicycle, Motorcycle, Bus, Subway
