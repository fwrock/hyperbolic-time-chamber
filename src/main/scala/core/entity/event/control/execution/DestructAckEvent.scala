package org.interscity.htc
package core.entity.event.control.execution

/** Sent by an actor back to the time manager that force-destructed it (`DestructEvent.actorRef`),
  * once `BaseActor.destruct` has finished running `onDestruct` — which, under Time Warp, is what
  * flushes that actor's buffered `report()` calls (§4 of `docs/TIME_WARP_DESIGN.md`). Closes the
  * loop `LocalTimeManagerBase.forceDestructActiveActors` opens: without an ack, the LTM (and in
  * turn `OptimisticGlobalTimeManager`) has no way to know the destruct+flush cascade it triggered
  * has actually been *sent*, only that it was requested — which is exactly what let `ReportManager`
  * close its parquet writers before those flushes ever arrived, silently dropping report rows in a
  * real conservative-vs-optimistic comparison run.
  *
  * @param actorId
  *   the destructed actor's entity id, matched back against `LocalTimeManagerBase`'s pending set
  */
case class DestructAckEvent(actorId: String)
