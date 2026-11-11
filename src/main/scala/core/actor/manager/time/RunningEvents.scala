package org.interscity.htc
package core.actor.manager.time

import org.htc.protobuf.core.entity.actor.Identify

import scala.collection.mutable

/** In-flight dispatches of a local time manager, indexed by actor id.
  *
  * This replaces a `mutable.Set[Identify]`. The set was keyed by the whole protobuf `Identify`
  * (hash/equals over every field), yet every lookup the LTM makes is "is actor `id` running?" or
  * "stop tracking actor `id`". Those were written as `exists(_.id == id)` and
  * `filterInPlace(_.id != id)`, i.e. two full scans of the running set for every `FinishEvent`
  * (profiled as the dominant cost of conservative mode). Keying by the primitive id makes
  * [[containsId]] and [[removeId]] O(1) and avoids hashing the full identity.
  *
  * It remains an `Iterable[Identify]`, so `foreach`, `map`, `take`, `iterator`, ... still work.
  * Semantics: at most one entry per actor id (re-adding replaces the identity); an actor id is
  * unique per simulation, so this matches what the old set effectively held.
  */
final class RunningEvents extends Iterable[Identify] {
  private val byId = mutable.LongMap.empty[Identify]

  /** @return true if the actor was not already running. */
  def add(identity: Identify): Boolean = byId.put(identity.id, identity).isEmpty

  def containsId(id: Long): Boolean = byId.contains(id)

  /** @return true if the actor was running (and is now removed). */
  def removeId(id: Long): Boolean = byId.remove(id).isDefined

  override def iterator: Iterator[Identify] = byId.valuesIterator
  override def size: Int = byId.size
  override def knownSize: Int = byId.size
  override def isEmpty: Boolean = byId.isEmpty
  override def nonEmpty: Boolean = byId.nonEmpty
}
