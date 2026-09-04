package org.interscity.htc
package core.actor.manager.time.gvt

import core.types.Tick

/** Optimistic-mode termination detector (`docs/TIME_WARP_DESIGN.md` §11). Conservative mode's
  * termination check ("is everyone idle") doesn't transfer to Time Warp: an idle optimistic actor
  * can still be reactivated by a straggler or anti-message that hasn't arrived yet. Combines two
  * signals instead:
  *
  *   1. Every `OptimisticLocalTimeManager` reports locally idle (nothing scheduled, no rollback in
  *      flight, nothing mid-processing).
  *   2. The GVT *plateaus* — stops advancing — across several consecutive rounds while (1) holds.
  *
  * (2) is necessary, not just a nicety, because of the margin-based GVT strategy (§3):
  * `GVT = min(LVTs) - margin` may never numerically reach "the last real tick processed" even with
  * zero remaining activity, since the margin is a constant subtraction — so "GVT stopped moving"
  * has to substitute for "GVT caught up exactly." This reuses the same reconfirm-before-declaring-
  * done posture the existing `QueryNextTickEvent` grace-period probe already uses in conservative
  * mode, rather than inventing a separate protocol.
  *
  * Pure and stateful, no Pekko — one instance per `OptimisticGlobalTimeManager`, fed one
  * `observe` call per aggregation round.
  *
  * @param plateauRoundsRequired
  *   how many consecutive idle rounds with an unchanged GVT before declaring termination; must be
  *   >= 1. No default tuned yet, same "measure before guessing" posture as `checkpointInterval`/
  *   the GVT margin.
  */
final class TerminationPlateauDetector(plateauRoundsRequired: Int) {
  require(plateauRoundsRequired >= 1, s"plateauRoundsRequired must be >= 1, got $plateauRoundsRequired")

  private var lastObservedGvt: Option[Tick] = None
  private var consecutivePlateauRounds: Int = 0

  /** Feed one round's observation. Returns true once termination should be declared.
    *
    * @param allIdle
    *   whether every registered `OptimisticLocalTimeManager` reported idle this round
    * @param gvt
    *   this round's GVT estimate
    */
  def observe(allIdle: Boolean, gvt: Tick): Boolean =
    if (!allIdle) {
      consecutivePlateauRounds = 0
      lastObservedGvt = Some(gvt)
      false
    } else if (lastObservedGvt.contains(gvt)) {
      consecutivePlateauRounds += 1
      consecutivePlateauRounds >= plateauRoundsRequired
    } else {
      lastObservedGvt = Some(gvt)
      consecutivePlateauRounds = 1
      plateauRoundsRequired <= 1
    }

  /** Resets to the pre-any-observation state — e.g. after a pause/resume, where "idle" observed
    * before the pause shouldn't count toward a plateau that spans the pause.
    */
  def reset(): Unit = {
    lastObservedGvt = None
    consecutivePlateauRounds = 0
  }
}
