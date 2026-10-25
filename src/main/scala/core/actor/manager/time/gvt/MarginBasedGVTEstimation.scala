package org.interscity.htc
package core.actor.manager.time.gvt

import core.types.Tick

/** Legacy heuristic: `min(reported LVTs) - margin`, with saturating subtraction.
  * A finite margin cannot bound arbitrarily delayed messages. This is not a proven commit or
  * fossil-collection watermark; see docs/TIME_WARP_EXTENSIBLE_RUNTIME.md for the replacement.
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
      val minimum = localReports.map(_.lvt).min
      if (minimum < Long.MinValue + margin) Long.MinValue else minimum - margin
    }
}
