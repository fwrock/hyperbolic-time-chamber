package org.interscity.htc
package core.actor.manager.time

import core.types.Tick

import scala.collection.mutable

/** Per-actor high-water ticks of a local time manager, with an O(log n) global minimum.
  *
  * Replaces `mutable.Map[Long, Tick].withDefaultValue(-1L)`, whose minimum was `values.min`: a scan
  * over every actor the LTM has ever dispatched, taken on every LVT report under Time Warp. The
  * index keeps a multiset of the current values (tick -> how many actors sit at it), so `min` is the
  * first key. Same map semantics otherwise: unknown actors read as `-1L` (and are not inserted),
  * `update` may lower a value (rollback resync), `remove` forgets an actor.
  */
final class WatermarkIndex {
  private val byActor = mutable.LongMap.empty[Tick]
  private val counts = mutable.TreeMap.empty[Tick, Int]

  def apply(actorId: Long): Tick = byActor.getOrElse(actorId, -1L)

  def update(actorId: Long, tick: Tick): Unit = {
    byActor.put(actorId, tick).foreach(decrement)
    counts.update(tick, counts.getOrElse(tick, 0) + 1)
  }

  def remove(actorId: Long): Unit = byActor.remove(actorId).foreach(decrement)

  def isEmpty: Boolean = byActor.isEmpty

  /** Smallest watermark over all tracked actors; `None` when none are tracked. */
  def min: Option[Tick] = counts.headOption.map(_._1)

  private def decrement(tick: Tick): Unit = counts.get(tick) match {
    case Some(1) => counts.remove(tick)
    case Some(n) => counts.update(tick, n - 1)
    case None    => ()
  }
}
