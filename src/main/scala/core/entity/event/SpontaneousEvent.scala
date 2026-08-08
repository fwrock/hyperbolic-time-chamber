package org.interscity.htc
package core.entity.event

import core.types.Tick

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

/** Spontaneous event that triggers actor execution
  *
  * @param tick
  *   current simulation tick
  * @param actorRef
  *   reference to the time manager
  * @param safeHorizon
  *   maximum tick the actor can safely advance to without external synchronization (default: same
  *   as tick for conservative execution)
  * @param generation
  *   monotonically increasing per-actor dispatch counter, assigned by the TimeManager each time it
  *   sends this actor a `SpontaneousEvent` (default 0 for TM implementations/paths that don't
  *   dispatch actors directly, e.g. `GlobalTimeManager` sending itself control events). The actor
  *   echoes it back on every `FinishEvent` so `LocalTimeManagerBase.finishEvent` can recognize and
  *   drop a `FinishEvent` that belongs to an already-superseded dispatch cycle instead of
  *   "rescuing" it into a phantom extra schedule — see docs/KNOWN_GAPS.md's non-reproducibility
  *   investigation.
  * @param gvt
  *   this dispatch's `OptimisticLocalTimeManager`'s last-known GVT estimate (`docs/
  *   TIME_WARP_DESIGN.md` §3/§4), piggybacked on the one message channel every LTM already sends
  *   an actor regularly. `None` for every conservative-mode path (`LocalTimeManagerBase`'s default
  *   `currentGvt` hook) and before an `OptimisticLocalTimeManager` has received its first
  *   `GvtUpdateEvent`; ignored entirely by conservative-mode actors. `Option`, not a sentinel `Tick`
  *   value, because the margin-based GVT estimate (§3: `min(LVTs) - margin`) is routinely negative
  *   early in a run — no in-range `Long` could double as "not known yet" safely. Under Time Warp, an
  *   actor uses a `Some` value to flush `report()` calls buffered for ticks `<= gvt` (§4's "safe,
  *   can never be rolled back" watermark). Not itself the actor's own tick-monotonic checkpoint
  *   clock — a separate, coarser signal from the GVT coordinator.
  */
case class SpontaneousEvent(
  tick: Tick,
  actorRef: ActorRef,
  safeHorizon: Tick = -1,
  generation: Long = 0L,
  gvt: Option[Tick] = None
) extends BaseEvent[DefaultBaseEventData](tick = tick, actorRef = actorRef) {

  /** Returns the effective safe horizon (tick if not set) */
  def effectiveSafeHorizon: Tick = if (safeHorizon == -1) tick else safeHorizon

  /** Returns true if lookahead is enabled (horizon > tick) */
  def hasLookahead: Boolean = safeHorizon > tick
}
