package org.interscity.htc
package core.runtime.accounting

import core.entity.event.control.report.ReportEvent
import org.apache.pekko.actor.ActorRef
import scala.collection.mutable

/** Owner-local bounded report envelopes. Allocation must register a receipt before buffering; flush
  * never waits for another modeled event or a future tick.
  */
final class CommittedReportBatcher(
  limit: Int,
  allocate: () => Long,
  deliver: (ActorRef, Long, Vector[ReportEvent]) => Unit
) {
  require(limit > 0, "report batch size must be positive")
  private case class Pending(token: Long, events: mutable.ArrayBuffer[ReportEvent])
  private val pending = mutable.LinkedHashMap.empty[ActorRef, Pending]

  /** Preserve per-target enqueue order and send full envelopes immediately. */
  def add(target: ActorRef, event: ReportEvent): Unit = {
    val batch = pending.getOrElseUpdate(target, Pending(allocate(), mutable.ArrayBuffer.empty))
    batch.events += event
    if (batch.events.size == limit) send(target)
  }

  /** Drain partial envelopes at the mailbox boundary, including single-event runs. */
  def flush(): Unit = pending.keys.toVector.foreach(send)

  /** Whether an owner must enqueue its local flush message. */
  def nonEmpty: Boolean = pending.nonEmpty

  private def send(target: ActorRef): Unit = {
    val batch = pending.remove(target).get
    deliver(target, batch.token, batch.events.toVector)
  }
}
