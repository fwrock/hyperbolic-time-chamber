package org.interscity.htc
package core.entity.event.control.execution

import core.types.Tick

/** Sent by an `OptimisticLocalTimeManager` to its `OptimisticGlobalTimeManager` reporting its
  * current local virtual time (`docs/TIME_WARP_DESIGN.md` §3). Unlike the conservative barrier's
  * `LocalTimeReportEvent` handshake, this is fire-and-forget: the sender never waits for a reply
  * before continuing to dispatch — it's a background watermark input, not a permission gate.
  *
  * @param lvt
  *   this LTM's current local virtual time estimate (see `OptimisticLocalTimeManager.
  *   localVirtualTime`'s doc for how it's computed)
  * @param isIdle
  *   whether this LTM currently has nothing scheduled and nothing running — feeds §11's
  *   termination-plateau detection alongside the GVT estimate itself
  */
case class LvtReportEvent(lvt: Tick, isIdle: Boolean)
