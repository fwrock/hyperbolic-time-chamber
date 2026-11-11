package org.interscity.htc
package model.hybrid.entity.state.model

import com.fasterxml.jackson.databind.util.StdConverter

import scala.collection.mutable

/** Vehicles currently registered on a link, indexed by actor id.
  *
  * Replaces `mutable.Set[LinkRegister]`. Every question the link asks is about one actor id
  * ("is it here?", "remove it"), but the set was keyed by the whole `LinkRegister` record, so each
  * answer was a full scan (`exists(_.actorId == id)` x3, `filterInPlace(_.actorId != id)`) per car
  * passage: cost grew with cars on the link, which peaks exactly at rush hour.
  *
  * Still a `mutable.Set[LinkRegister]` (`+=`, `size`, `foreach`, `iterator` unchanged). At most one
  * entry per actor id; adding an existing id replaces it. The JSON form is still a plain array, via
  * [[LinkRegistry.ToJson]] / [[LinkRegistry.FromJson]], so checkpoints and migration snapshots keep
  * their format.
  */
final class LinkRegistry extends mutable.AbstractSet[LinkRegister] {
  private val byActor = mutable.LongMap.empty[LinkRegister]

  def containsActor(actorId: Long): Boolean = byActor.contains(actorId)

  /** @return true if the actor was registered (and is now removed). */
  def removeActor(actorId: Long): Boolean = byActor.remove(actorId).isDefined

  override def contains(elem: LinkRegister): Boolean = byActor.get(elem.actorId).contains(elem)
  override def addOne(elem: LinkRegister): this.type = { byActor.update(elem.actorId, elem); this }
  override def subtractOne(elem: LinkRegister): this.type = {
    if (contains(elem)) byActor.remove(elem.actorId)
    this
  }
  override def clear(): Unit = byActor.clear()
  override def iterator: Iterator[LinkRegister] = byActor.valuesIterator
  override def size: Int = byActor.size
  override def knownSize: Int = byActor.size
  override def isEmpty: Boolean = byActor.isEmpty
}

object LinkRegistry {
  def apply(elems: LinkRegister*): LinkRegistry = {
    val registry = new LinkRegistry
    elems.foreach(registry.addOne)
    registry
  }

  final class ToJson extends StdConverter[LinkRegistry, Seq[LinkRegister]] {
    override def convert(registry: LinkRegistry): Seq[LinkRegister] = registry.toSeq
  }

  final class FromJson extends StdConverter[Seq[LinkRegister], LinkRegistry] {
    override def convert(elems: Seq[LinkRegister]): LinkRegistry = LinkRegistry(elems*)
  }
}
