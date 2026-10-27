package org.interscity.htc
package core.actor.manager.loadbalance.allocation

import org.apache.pekko.actor.{ ActorRef, Address }
import org.apache.pekko.cluster.sharding.ShardRegion.ShardId
import org.apache.pekko.cluster.sharding.ShardCoordinator

import scala.collection.immutable
import scala.collection.mutable
import scala.concurrent.Future

/** Custom [[ShardCoordinator.ShardAllocationStrategy]] that delegates allocation and rebalance
  * decisions to the [[core.actor.manager.loadbalance.LoadBalanceManager]].
  *
  * How it works: – The LoadBalanceManager updates the `desiredAllocations` map when it decides a
  * shard should live on a specific cluster node. – [[allocateShard]] enforces that target; only
  * unplanned shards use fallback allocation. – [[rebalance]] returns the set of shards marked for migration
  * in `shardsToRebalance`. The LoadBalanceManager populates this set, then notifies the
  * ShardCoordinator to pick it up on its next callback.
  *
  * Each physical shard type uses its own child allocator. Callbacks and manager commands can run
  * on different actor threads; synchronized protects their shared state.
  *
  * @param fallbackStrategy
  *   Optional fallback for shards with no explicit allocation (default: least-shards heuristic)
  */
class LoadBalanceShardAllocator(
  private val fallbackStrategy: Option[ShardCoordinator.ShardAllocationStrategy] = None
) extends ShardCoordinator.ShardAllocationStrategy {

  /** Desired shard → region (ActorRef) mapping. Populated by [[LoadBalanceManager]] based on
    * strategy decisions.
    */
  private val desiredAllocations: mutable.Map[ShardId, ActorRef] = mutable.Map.empty
  private val typedAllocators = mutable.Map.empty[String, LoadBalanceShardAllocator]
  private val localRegionAddresses = mutable.Map.empty[ActorRef, Address]

  def forType(typeName: String): LoadBalanceShardAllocator = synchronized {
    typedAllocators.getOrElseUpdate(typeName, new LoadBalanceShardAllocator(fallbackStrategy))
  }

  def registerLocalRegion(region: ActorRef, address: Address): Unit = synchronized {
    localRegionAddresses(region) = address
  }

  private def regionAddress(region: ActorRef, localAddress: Address): Address =
    if (region.path.address.hasLocalScope) localRegionAddresses.getOrElse(region, localAddress)
    else region.path.address

  def resolveDestination(shardId: ShardId, source: Address, target: Address,
    localAddress: Address): Either[String, ActorRef] =
    resolvePlacement(shardId, source, target, localAddress).map(_._2)

  def resolvePlacement(shardId: ShardId, source: Address, target: Address,
    localAddress: Address): Either[String, (ActorRef, ActorRef)] = synchronized {
    val sources = regionIndex.iterator.collect { case (region, shards) if shards.contains(shardId) => region }.toSeq
    val targets = regionIndex.keysIterator.filter(regionAddress(_, localAddress) == target).toSeq
    if (sources.size != 1 || regionAddress(sources.head, localAddress) != source)
      Left(s"Shard '$shardId' has no unique observed owner at $source")
    else if (source == target) Left(s"Shard '$shardId' source and target are the same node")
    else if (targets.size != 1) Left(s"Shard '$shardId' has no unique registered target region at $target")
    else Right(sources.head -> targets.head)
  }

  /** Set of shards that should be moved on the next rebalance tick. Populated by
    * [[LoadBalanceManager]], consumed by [[rebalance]].
    */
  private val shardsToRebalance: mutable.Set[ShardId] = mutable.Set.empty

  /** Tracks which region ActorRef holds which shard IDs.
    *
    * Replaced atomically by each authoritative callback snapshot, including idle regions.
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
    updateRegionIndex(currentShardAllocations)

    val target = desiredAllocations.get(shardId) match {
      case Some(region) if currentShardAllocations.contains(region) =>
        region
      case Some(region) =>
        return Future.failed(new IllegalStateException(s"Explicit destination $region for '$shardId' is unavailable"))
      case _ =>
        fallbackStrategy match {
          case Some(fb) =>
            return fb.allocateShard(requester, shardId, currentShardAllocations)
          case None =>
            leastShardsRegion(currentShardAllocations, requester)
        }
    }

    Future.successful(target)
  }

  /** Called periodically by ShardCoordinator to check if any shards should be moved.
    *
    * We return the set of shards that the LoadBalanceManager has marked for migration. The
    * ShardCoordinator will then call [[allocateShard]] for each returned shard to find its new
    * home.
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
    updateRegionIndex(currentShardAllocations)

    val toRebalance = shardsToRebalance.diff(rebalanceInProgress).toSet
    shardsToRebalance --= toRebalance

    Future.successful(toRebalance)
  }

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
    shardsToRebalance.remove(shardId)
  }

  /** Clears all desired allocations, pending rebalances, and the region index. */
  def clearAll(): Unit = synchronized {
    desiredAllocations.clear()
    shardsToRebalance.clear()
    regionIndex.clear()
    // Coordinators retain these child instances; replacing them would split command ownership.
    typedAllocators.values.foreach(_.clearAll())
  }

  /** Returns the current desired allocations for monitoring. */
  def getDesiredAllocations: Map[ShardId, ActorRef] = synchronized {
    desiredAllocations.toMap
  }

  /** Returns the current set of pending rebalances. */
  def getPendingRebalances: Set[ShardId] = synchronized {
    shardsToRebalance.toSet ++ typedAllocators.values.flatMap(_.getPendingRebalances)
  }

  /** Gets the current region index (region → shards).
    *
    * Returns the last complete callback snapshot for this physical shard type.
    */
  def getRegionIndex: Map[ActorRef, Set[ShardId]] = synchronized {
    regionIndex.view.mapValues(_.toSet).toMap
  }

  /** Finds a region by checking which region hosts a given shard in the index. */
  def findRegionForShard(shardId: ShardId): Option[ActorRef] = synchronized {
    regionIndex.find {
      case (_, shards) => shards.contains(shardId)
    }.map(_._1)
  }

  private def updateRegionIndex(
    currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]]
  ): Unit = {
    regionIndex.clear()
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
  ): ActorRef =
    if (allocations.isEmpty) requester
    else {
      val (minRegion, _) = allocations.minBy {
        case (region, shards) =>
          val tieBreaker = if (region == requester) -0.5 else 0.0
          shards.size + tieBreaker
      }
      minRegion
    }
}

object LoadBalanceShardAllocator {

  /** Creates a registry root; ActorCreatorUtil installs its per-type children into sharding. */
  def apply(): LoadBalanceShardAllocator = new LoadBalanceShardAllocator()

  /** Creates with a fallback strategy. */
  def withFallback(
    fallback: ShardCoordinator.ShardAllocationStrategy
  ): LoadBalanceShardAllocator =
    new LoadBalanceShardAllocator(Some(fallback))
}
