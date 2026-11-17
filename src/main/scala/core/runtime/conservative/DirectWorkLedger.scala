package org.interscity.htc
package core.runtime.conservative

import scala.collection.mutable

/** Single-owner tick ledger. Early child completions cannot release an undeclared ancestor. No
  * actor references or model state are retained here. A new tick requires a closed ledger.
  */
final class DirectWorkLedger {
  private var activeTick: Option[Long] = None
  private val pending = mutable.Map.empty[DirectWorkId, DirectWork]
  private val early = mutable.Map.empty[DirectWorkId, DirectWorkCompletion]
  private val completed = mutable.Map.empty[DirectWorkId, DirectWorkCompletion]

  /** Reclaim identities only after global closure authorizes a strictly later tick. */
  def begin(tick: Long): Unit = {
    require(isClosed, "cannot advance a direct ledger with unfinished work")
    require(activeTick.forall(_ < tick), "direct ledger tick did not advance")
    activeTick = Some(tick)
    completed.clear()
  }

  /** Register a dispatch before it can reach its LP. */
  def enroll(work: DirectWork): Unit = {
    require(work.parent.isEmpty, "only an owner may enroll a root")
    declare(work)
  }

  /** Atomically replace a completed callback by all its children, in either delivery order. */
  def complete(receipt: DirectWorkCompletion): Unit = {
    validate(receipt.work)
    val ready = mutable.Queue(receipt)
    while (ready.nonEmpty) {
      val current = ready.dequeue()
      val work = current.work
      completed.get(work.id) match {
        case Some(previous) => require(previous == current, "conflicting direct completion")
        case None =>
          pending.get(work.id) match {
            case None =>
              require(work.parent.nonEmpty, "completion for an unenrolled root")
              early
                .get(work.id)
                .foreach(
                  previous => require(previous == current, "conflicting early completion")
                )
              early(work.id) = current
            case Some(expected) =>
              require(expected == work, "completion changed its declared obligation")
              require(
                current.children.map(_.id).distinct.size == current.children.size,
                "duplicate child identity"
              )
              current.children.foreach {
                child =>
                  require(
                    child.parent.contains(work.id) && child.id.source == work.receiver,
                    "child does not belong to its completing parent"
                  )
                  declare(child)
              }
              pending.remove(work.id)
              completed(work.id) = current
              current.children.foreach(
                child => early.remove(child.id).foreach(ready.enqueue(_))
              )
          }
      }
    }
  }

  /** Pending roots fence early receipts until their declarations have actually arrived. */
  def isClosed: Boolean = pending.isEmpty && early.isEmpty

  /** Count executable/in-transit obligations; early receipts are separately fenced. */
  def pendingCount: Int = pending.size

  private def validate(work: DirectWork): Unit = {
    require(activeTick.contains(work.tick), "direct work outside its authorized tick")
    require(
      work.id.incarnation.nonEmpty && work.id.sequence != 0L && work.receiver.model.nonEmpty,
      "invalid direct work identity"
    )
  }

  private def declare(work: DirectWork): Unit = {
    validate(work)
    require(
      !pending.contains(work.id) && !completed.contains(work.id),
      "direct identity declared twice"
    )
    early
      .get(work.id)
      .foreach(
        receipt => require(receipt.work == work, "early receipt changed its declaration")
      )
    pending(work.id) = work
  }
}
