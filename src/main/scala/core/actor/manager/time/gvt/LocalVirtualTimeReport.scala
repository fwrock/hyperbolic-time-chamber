package org.interscity.htc
package core.actor.manager.time.gvt

import core.types.Tick

import org.apache.pekko.actor.ActorRef

/** One `OptimisticLocalTimeManager`'s self-reported local virtual time — the tick below which that
  * LTM guarantees nothing it still holds can ever need to roll back further, per
  * `docs/TIME_WARP_DESIGN.md` §3. `isIdle` feeds §11's termination detection alongside the GVT
  * estimate itself.
  */
final case class LocalVirtualTimeReport(source: ActorRef, lvt: Tick, isIdle: Boolean)
