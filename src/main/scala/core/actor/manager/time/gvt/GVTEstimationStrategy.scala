package org.interscity.htc
package core.actor.manager.time.gvt

import core.types.Tick

/** Pluggable global-virtual-time estimator (`docs/TIME_WARP_DESIGN.md` §3). GVT is the lowest
  * timestamp of anything still unprocessed or in flight anywhere in the optimistic simulation —
  * the watermark below which fossil collection (pruning `RollbackHistoryHandler` checkpoint/log
  * history) and irreversible side-effect commits (buffered `report()` calls, §4, not yet wired)
  * are safe.
  *
  * Composition, not subclassing `OptimisticGlobalTimeManager`: the rollback/coordination logic is
  * identical regardless of how GVT is estimated, only the aggregation math differs.
  */
trait GVTEstimationStrategy {

  /** Estimate the current GVT from every `OptimisticLocalTimeManager`'s latest report. Must be
    * safe to call with a report set that's missing some LTMs (e.g. mid-startup, before every LTM
    * has reported at least once) — implementations should return a value that under-estimates
    * (never over-estimates) the true GVT in that case, since underestimating only delays fossil
    * collection while overestimating risks discarding history something still needs.
    */
  def estimate(localReports: Seq[LocalVirtualTimeReport]): Tick
}
