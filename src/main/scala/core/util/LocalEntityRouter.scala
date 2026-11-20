package org.interscity.htc
package core.util

import io.prometheus.client.Counter
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
  import LocalEntityRouter.{ Direct, RegionNew, RegionPinned }

  private val enabled = LocalRegions.enabled(system)
  private val counters = new java.util.HashMap[String, Array[Counter.Child]]()
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
    val byPath = counters.computeIfAbsent(regionName, LocalEntityRouter.childrenFor)
    if (target != null && !pinned) {
      byPath(Direct).inc()
      target.tell(payload, sender)
      true
    } else {
      if (!pinned) regionRouted.computeIfAbsent(regionName, _ => new java.util.HashSet[String]()).add(entityId)
      byPath(if (pinned) RegionPinned else RegionNew).inc()
      false
    }
  }
}

object LocalEntityRouter {
  private val Direct = 0
  private val RegionNew = 1
  private val RegionPinned = 2

  // Region is the model type, so cardinality is bounded; path is a closed set.
  private val deliveries = Counter
    .build()
    .name("htc_single_node_deliveries_total")
    .help("Single-node entity deliveries by path: direct, region_new (first contact), region_pinned")
    .labelNames("region", "path")
    .register()

  private def childrenFor(region: String): Array[Counter.Child] =
    Array("direct", "region_new", "region_pinned").map(deliveries.labels(region, _))
}
