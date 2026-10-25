package org.interscity.htc
package core.entity.event.control.rollback

import scala.collection.mutable

/** Values must be immutable: the journal retains them until their event is fossil-collected. */
final case class RecordedRead(key: String, value: Any)

/** Event-scoped observations of inputs outside a model's checkpointed state.
  * Scopes are synchronous; they must never cross a Future or actor-message boundary.
  */
object EventReadJournal {
  private sealed trait Scope
  private case class Recording(sink: mutable.Buffer[RecordedRead]) extends Scope
  private case class Replaying(reads: Iterator[RecordedRead]) extends Scope
  private val active = new ThreadLocal[Scope]()

  def isActive: Boolean = active.get() != null

  def read[A](key: String)(liveRead: => A): A = active.get() match {
    case Recording(sink) =>
      // A higher-level observation owns its inputs; cache hits may skip its nested reads.
      val value = within(null)(liveRead)
      sink += RecordedRead(key, value)
      value
    case Replaying(reads) =>
      require(reads.hasNext, s"Replay requested an unrecorded read: $key")
      val recorded = reads.next()
      require(recorded.key == key, s"Replay read order changed: expected ${recorded.key}, got $key")
      recorded.value.asInstanceOf[A]
    case null => liveRead
  }

  def withRecording[A](sink: mutable.Buffer[RecordedRead])(body: => A): A =
    within(Recording(sink))(body)

  def withReplay[A](reads: Seq[RecordedRead])(body: => A): A = {
    val replay = Replaying(reads.iterator)
    within(replay) {
      val result = body
      require(!replay.reads.hasNext, "Replay did not consume all recorded reads")
      result
    }
  }

  private def within[A](scope: Scope)(body: => A): A = {
    val previous = active.get()
    active.set(scope)
    try body
    finally {
      if (previous == null) active.remove()
      else active.set(previous)
    }
  }
}
