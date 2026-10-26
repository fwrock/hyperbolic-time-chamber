package org.interscity.htc
package core.runtime

import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import scala.collection.mutable

/** One owner drains this queue synchronously; callbacks never re-enter model dispatch. */
final class OrderedInputQueue {
  private case class Entry(event: AnyRef, tick: Long, order: Option[EventOrder], arrival: Long,
    var discarded: Boolean = false)
  private given Ordering[Entry] = Ordering.by[Entry, (Long, Long, String, Long)] { e =>
    (e.tick, e.order.fold(0L)(_.microstep), e.order.fold("")(_.key), e.arrival)
  }.reverse
  private val pending = mutable.PriorityQueue.empty[Entry]
  private val dispatches = mutable.LongMap.empty[Entry]
  private var arrival = 0L
  private var discardedEntries = 0

  def enqueue(event: AnyRef): Unit = {
    val (tick, order) = event match {
      case e: ActorInteractionEvent => (e.tick, e.order)
      case e: SpontaneousEvent      => (e.tick, e.order)
      case other => throw new IllegalArgumentException(s"unsupported LP input: ${other.getClass.getName}")
    }
    require(order.forall(_.tick == tick), "event/order tick mismatch")
    arrival = Math.addExact(arrival, 1L)
    val entry = Entry(event, tick, order, arrival)
    pending.enqueue(entry)
    if (event.isInstanceOf[SpontaneousEvent]) dispatches(arrival) = entry
  }

  def discardDispatchesWhere(predicate: SpontaneousEvent => Boolean): Vector[SpontaneousEvent] = {
    val obsolete = dispatches.valuesIterator.filter(e => predicate(e.event.asInstanceOf[SpontaneousEvent])).toVector
    obsolete.map { entry =>
      entry.discarded = true
      discardedEntries += 1
      dispatches.remove(entry.arrival)
      entry.event.asInstanceOf[SpontaneousEvent]
    }
  }

  private def trimHead(): Unit =
    while (pending.headOption.exists(_.discarded)) {
      pending.dequeue()
      discardedEntries -= 1
    }

  def headOption: Option[AnyRef] = { trimHead(); pending.headOption.map(_.event) }
  def dequeue(): AnyRef = {
    trimHead()
    val entry = pending.dequeue()
    dispatches.remove(entry.arrival)
    entry.event
  }
  def nonEmpty: Boolean = { trimHead(); pending.nonEmpty }
  def minimumTick: Option[Long] = { trimHead(); pending.headOption.map(_.tick) }
  def queuedDispatchCount: Int = dispatches.size
  def snapshot: Vector[AnyRef] = pending.iterator.filterNot(_.discarded).toVector.sorted.reverse.map(_.event)
  def restore(events: Vector[AnyRef]): Unit = {
    pending.clear()
    dispatches.clear()
    arrival = 0L
    discardedEntries = 0
    events.foreach(enqueue)
  }
  private[runtime] def retainedEntryCount: Int = pending.size
  def compactDiscarded(): Unit = if (discardedEntries > 0) {
    val live = pending.iterator.filterNot(_.discarded).toVector
    pending.clear()
    pending.enqueue(live*)
    discardedEntries = 0
  }
}
