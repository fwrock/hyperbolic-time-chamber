package org.interscity.htc
package core.entity.event.control.execution

/** Sent by a `LocalTimeManagerBase` up to its `parentManager` once every actor it force-destructed
  * (`forceDestructActiveActors`) has acked via [[DestructAckEvent]] — i.e. once this LTM's whole
  * destruct+flush cascade has genuinely finished being *sent*, not merely requested.
  *
  * `OptimisticGlobalTimeManager.terminateSimulation` waits for one of these from every registered
  * LTM before telling `simulationManager`/`ReportManager` to stop, closing the race that otherwise
  * let `ReportManager` shut its parquet writers before Time-Warp-buffered reports (§4) ever arrived
  * (see `docs/TIME_WARP_DESIGN.md`'s conservative-vs-optimistic comparison finding).
  * `ConservativeGlobalTimeManager` never needed this — `report()` isn't buffered there, so there's
  * nothing this handshake would protect.
  */
case class LocalDestructCompleteEvent()
