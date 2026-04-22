package org.interscity.htc
package core.actor.manager.loadbalance.allocation

import org.apache.pekko.actor.{ ActorRef, ActorSystem }
import org.apache.pekko.cluster.sharding.ShardRegion.ShardId
import org.apache.pekko.cluster.sharding.{ ClusterSharding, ShardCoordinator }

import scala.collection.immutable
import scala.collection.mutable
import scala.concurrent.{ Future, Promise }

/** Custom [[ShardCoordinator.ShardAllocationStrategy]] that delegates allocation and rebalance
  * decisions to the [[core.actor.manager.loadbalance.LoadBalanceManager]].
  *
  * How it works:
  *   – The LoadBalanceManager updates the `desiredAllocations` map when it decides
  *     a shard should live on a specific cluster node.
  *   – [[allocateShard]] consults that map first; falls back to least-shards allocation.
  *   – [[rebalance]] returns the set of shards marked for migration in `shardsToRebalance`.
  *     The LoadBalanceManager populates this set, then notifies the ShardCoordinator via
  *     [[triggerRebalance]] to pick it up.
  *
  * Thread safety: all mutable maps are updated from the LoadBalanceManager actor and read from the
  * ShardCoordinator. In practice both run on the same dispatcher, but we use synchronized for the
  * shared mutable state to be safe.
  *
  * @param fallbackStrategy
  *   Optional fallback for shards with no explicit allocation (default: least-shards heuristic)
  */
class LoadBalanceShardAllocator(
  private val fallbackStrategy: Option[ShardCoordinator.ShardAllocationStrategy] = None
) extends ShardCoordinator.ShardAllocationStrategy {

  /** Desired shard → region (ActorRef) mapping.
    * Populated by [[LoadBalanceManager]] based on strategy decisions.
    */
  private val desiredAllocations: mutable.Map[ShardId, ActorRef] = mutable.Map.empty

  /** Set of shards that should be moved on the next rebalance tick.
    * Populated by [[LoadBalanceManager]], consumed by [[rebalance]].
    */
  private val shardsToRebalance: mutable.Set[ShardId] = mutable.Set.empty

  /** Tracks which region ActorRef holds which shard IDs.
    *
    * Updated via a MERGE strategy from `currentShardAllocations` snapshots passed in by
    * `allocateShard` and `rebalance`. We intentionally do NOT clear this map between Pekko
    * callbacks: clearing would make `getRegionIndex` return an empty map whenever
    * `handleCollectAndFeedMetrics` runs outside of a Pekko callback window.
    *
    * The merge approach keeps the last-known allocation for every region. Regions that have
    * left the cluster are evicted only when `clearAll` is called (simulation shutdown).
    */
  private val regionIndex: mutable.Map[ActorRef, mutable.Set[ShardId]] = mutable.Map.empty

  // ── ShardAllocationStrategy API ──────────────────────────────────────────

  /** Called by the ShardCoordinator when a new shard needs to be allocated.
    *
    * @param requester
    *   The ShardRegion that requested the shard
    * @param shardId
    *   The shard to allocate
    * @param currentShardAllocations
    *   The current mapping of regions → shards
    * @return
    *   The ActorRef of the region where the shard should live
    */
  override def allocateShard(
    requester: ActorRef,
    shardId: ShardId,
    currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]]
  ): Future[ActorRef] = synchronized {
    mergeRegionIndex(currentShardAllocations)

    // If LoadBalanceManager has a desired allocation, use it
    val target = desiredAllocations.get(shardId) match {
      case Some(region) if currentShardAllocations.contains(region) =>
        region
      case _ =>
        // Fallback: allocate to the region with fewest shards (standard heuristic)
        fallbackStrategy match {
          case Some(fb) =>
            // Delegate to fallback (returns Future, but we unwrap for simplicity)
            return fb.allocateShard(requester, shardId, currentShardAllocations)
          case None =>
            leastShardsRegion(currentShardAllocations, requester)
        }
    }

    Future.successful(target)
  }

  /** Called periodically by ShardCoordinator to check if any shards should be moved.
    *
    * We return the set of shards that the LoadBalanceManager has marked for migration.
    * The ShardCoordinator will then call [[allocateShard]] for each returned shard to find
    * its new home.
    *
    * @param currentShardAllocations
    *   The current mapping of regions → shards
    * @param rebalanceInProgress
    *   Shards currently being rebalanced (we skip these)
    * @return
    *   Set of shard IDs that should be rebalanced
    */
  override def rebalance(
    currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]],
    rebalanceInProgress: Set[ShardId]
  ): Future[Set[ShardId]] = synchronized {
    mergeRegionIndex(currentShardAllocations)

    // Take all pending rebalance requests, excluding already-in-progress ones
    val toRebalance = shardsToRebalance.diff(rebalanceInProgress).toSet
    shardsToRebalance.clear()

    Future.successful(toRebalance)
  }

  // ── LoadBalanceManager API ─────────────────────────────────────────────────

  /** Sets the desired allocation for a shard. Called by LoadBalanceManager.
    *
    * @param shardId
    *   The shard to allocate
    * @param region
    *   The target region (ActorRef)
    */
  def setDesiredAllocation(shardId: ShardId, region: ActorRef): Unit = synchronized {
    desiredAllocations.put(shardId, region)
  }

  /** Marks a shard for migration on the next rebalance cycle.
    *
    * @param shardId
    *   The shard to migrate
    * @param targetRegion
    *   The desired target region
    */
  def requestRebalance(shardId: ShardId, targetRegion: ActorRef): Unit = synchronized {
    desiredAllocations.put(shardId, targetRegion)
    shardsToRebalance.add(shardId)
  }

  /** Marks multiple shards for migration on the next rebalance cycle.
    *
    * @param migrations
    *   Map of shard ID → target region
    */
  def requestRebalanceBatch(migrations: Map[ShardId, ActorRef]): Unit = synchronized {
    migrations.foreach {
      case (shardId, region) =>
        desiredAllocations.put(shardId, region)
        shardsToRebalance.add(shardId)
    }
  }

  /** Removes a desired allocation (reverts to fallback heuristic for this shard). */
  def clearDesiredAllocation(shardId: ShardId): Unit = synchronized {
    desiredAllocations.remove(shardId)
  }

  /** Clears all desired allocations, pending rebalances, and the region index. */
  def clearAll(): Unit = synchronized {
    desiredAllocations.clear()
    shardsToRebalance.clear()
    regionIndex.clear()
  }

  /** Returns the current desired allocations for monitoring. */
  def getDesiredAllocations: Map[ShardId, ActorRef] = synchronized {
    desiredAllocations.toMap
  }

  /** Returns the current set of pending rebalances. */
  def getPendingRebalances: Set[ShardId] = synchronized {
    shardsToRebalance.toSet
  }

  /** Gets the current region index (region → shards).
    *
    * Returns the last-known allocation for every region seen in at least one Pekko callback.
    * Because we use a merge strategy in [[mergeRegionIndex]], this map is kept alive between
    * Pekko callbacks so that [[LoadBalanceManager.handleCollectAndFeedMetrics]] always sees
    * real data rather than an empty snapshot.
    */
  def getRegionIndex: Map[ActorRef, Set[ShardId]] = synchronized {
    regionIndex.view.mapValues(_.toSet).toMap
  }

  /** Finds a region by checking which region hosts a given shard in the index. */
  def findRegionForShard(shardId: ShardId): Option[ActorRef] = synchronized {
    regionIndex.find { case (_, shards) => shards.contains(shardId) }.map(_._1)
  }

  // ── Internal ────────────────────────────────────────────────────────────────

  /** Merges the Pekko-provided allocation snapshot into the persistent region index.
    *
    * Unlike a destructive clear+repopulate, this approach:
    *   1. Adds any new regions seen in `currentShardAllocations`.
    *   2. Replaces the shard set for regions already tracked (snapshot is authoritative).
    *   3. Preserves entries for regions absent in this snapshot (they may have no shards
    *      right now rather than having left the cluster).
    *
    * This ensures `getRegionIndex` always reflects at least the most recent known state
    * even between Pekko ShardCoordinator callback windows.
    */
  private def mergeRegionIndex(
    currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]]
  ): Unit = {
    currentShardAllocations.foreach {
      case (region, shards) =>
        val entry = regionIndex.getOrElseUpdate(region, mutable.Set.empty)
        entry.clear()
        entry ++= shards
    }
  }

  /** Selects the region with fewest shards, breaking ties by preferring the requester. */
  private def leastShardsRegion(
    allocations: Map[ActorRef, immutable.IndexedSeq[ShardId]],
    requester: ActorRef
  ): ActorRef = {
    if (allocations.isEmpty) requester
    else {
      val (minRegion, _) = allocations.minBy {
        case (region, shards) =>
          // Prefer requester on ties (add a tiny offset so requester wins)
          val tieBreaker = if (region == requester) -0.5 else 0.0
          shards.size + tieBreaker
      }
      minRegion
    }
  }
}

object LoadBalanceShardAllocator {

  /** Creates a new allocator instance. Thread-safe singleton per ActorSystem.
    * Call this once at system startup and pass it to ClusterSharding.start().
    */
  def apply(): LoadBalanceShardAllocator = new LoadBalanceShardAllocator()

  /** Creates with a fallback strategy. */
  def withFallback(
    fallback: ShardCoordinator.ShardAllocationStrategy
  ): LoadBalanceShardAllocator =
    new LoadBalanceShardAllocator(Some(fallback))
}
