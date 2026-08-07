package org.interscity.htc
package core.entity.event.control.execution

import core.types.Tick

/** Broadcast by an `OptimisticGlobalTimeManager` to every registered `OptimisticLocalTimeManager`
  * (`docs/TIME_WARP_DESIGN.md` §3/§4) whenever its GVT estimate advances. Fire-and-forget, same as
  * `LvtReportEvent` going the other direction — the GTM never waits for an ack, and an LTM that
  * misses one round simply picks up the next broadcast; nothing depends on every round being seen.
  *
  * Each `OptimisticLocalTimeManager` caches the latest value and piggybacks it onto its next
  * `SpontaneousEvent` dispatches (see `LocalTimeManagerBase.currentGvt`), which is how it actually
  * reaches actors — there's no separate LTM-to-actor broadcast channel, since actors already receive
  * `SpontaneousEvent` regularly and idle actors have nothing new to flush regardless.
  *
  * @param gvt
  *   the new GVT estimate — a watermark below which no rollback can ever reach again, safe to use
  *   for both fossil collection and (§4) flushing buffered `report()` output
  */
case class GvtUpdateEvent(gvt: Tick)
