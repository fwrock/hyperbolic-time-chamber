package org.interscity.htc
package core.util

import org.apache.pekko.actor.{ ActorRef, ActorSystem }

import java.util.concurrent.ConcurrentHashMap

/** Per-sender decision to tell a single-node entity directly instead of through its region.
  *
  * A sender may go direct to a target only if it never routed a message to that target through the
  * region: otherwise an earlier message of its own may still be queued in the region's mailbox and a
  * direct one would overtake it. Such a (region, entity) pair stays on the region for good, which
  * only happens for entities not yet published at first contact (dynamic births).
  *
  * Owned by one actor and used only from its thread.
  */
final class LocalEntityRouter(system: ActorSystem) {
  private val enabled = LocalRegions.enabled(system)
  private val indexes = new java.util.HashMap[String, ConcurrentHashMap[String, ActorRef]]()
  private val regionRouted = new java.util.HashMap[String, java.util.HashSet[String]]()

  /** True when the payload was delivered directly; false means the caller must send it through the
    * region, and this pair is now pinned to the region.
    */
  def tryDirect(regionName: String, entityId: String, payload: AnyRef, sender: ActorRef): Boolean = {
    if (!enabled) return false
    val index = indexes.computeIfAbsent(regionName, name => LocalRegions.entityIndex(system, name))
    val target = if (index == null) null else index.get(entityId)
    val pinned = !regionRouted.isEmpty && {
      val ids = regionRouted.get(regionName)
      ids != null && ids.contains(entityId)
    }
    if (target != null && !pinned) {
      target.tell(payload, sender)
      true
    } else {
      if (!pinned) regionRouted.computeIfAbsent(regionName, _ => new java.util.HashSet[String]()).add(entityId)
      false
    }
  }
}
