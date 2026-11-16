package org.interscity.htc
package core.util

import core.entity.actor.ActorSimulation

import java.sql.ResultSet

/** Iterates a caller-owned SQLite cursor without advancing twice on repeated availability checks.
  *
  * The cursor starts before its first row. The owning connection must stay open during iteration;
  * closing it releases the cursor and statement. Decoding failures propagate to the caller.
  */
private[util] final class SqliteActorSourceIterator(rows: ResultSet)
    extends Iterator[ActorSimulation] {
  private var advanced = false
  private var hasNextCached = false

  /** Advances the cursor at most once until next consumes the current row. */
  override def hasNext: Boolean = {
    if (!advanced) {
      hasNextCached = rows.next()
      advanced = true
    }
    hasNextCached
  }

  /** Decodes one row, or throws NoSuchElementException after exhaustion. */
  override def next(): ActorSimulation = {
    if (!hasNext) throw new NoSuchElementException("next() on exhausted iterator")
    advanced = false
    SqliteActorRowDecoder.decode(rows)
  }
}
