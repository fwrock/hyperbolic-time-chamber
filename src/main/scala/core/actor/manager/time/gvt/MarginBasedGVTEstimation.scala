package org.interscity.htc
package core.actor.manager.time.gvt

import core.types.Tick

/** v1 GVT estimation strategy (`docs/TIME_WARP_DESIGN.md` §3): `GVT = min(reported LVTs) - margin`.
  * Safe because underestimating GVT is always correct — it only delays fossil collection, never
  * causes it to discard something still needed — and a large-enough `margin` covers plausible
  * in-transit message lag without any per-channel send/receive counting. Cost: retains more
  * history than strictly necessary, by an amount proportional to `margin`. No default value is
  * chosen — needs measurement against a real scenario, per the design doc's own "Open questions."
  *
  * Deferred alternative: exact (Mattern-style channel counting) GVT — correct with no wasted
  * retention, but real per-LTM-pair send/receive-counter coordination-protocol complexity. Revisit
  * once this margin-based approach's actual memory cost is measured.
  */
final class MarginBasedGVTEstimation(margin: Tick) extends GVTEstimationStrategy {
  require(margin >= 0, s"margin must be >= 0, got $margin")

  /** No reports yet (e.g. before any `OptimisticLocalTimeManager` has reported once) means nothing
    * is known to be safe — returns `Long.MinValue` rather than guessing, so callers never treat an
    * empty report set as "everything is safe to prune."
    */
  override def estimate(localReports: Seq[LocalVirtualTimeReport]): Tick =
    if (localReports.isEmpty) {
      Long.MinValue
    } else {
      localReports.map(_.lvt).min - margin
    }
}
