package org.interscity.htc
package core.actor.manager.loadbalance.allocation

import java.util.concurrent.atomic.AtomicReference

/** Global registry for the [[LoadBalanceShardAllocator]] instance.
  *
  * Since [[org.apache.pekko.cluster.sharding.ClusterSharding.start]] requires the allocation
  * strategy at shard region creation time, we need a way for [[ActorCreatorUtil.createShardRegion]]
  * to access the allocator without passing it through every call.
  *
  * The allocator is set once by [[LoadBalanceManager.onStart]] and cleared on shutdown. When no
  * allocator is registered, shard creation falls back to Pekko's default strategy.
  *
  * Thread-safe via [[AtomicReference]].
  */
object ShardAllocatorRegistry {

  private val allocator: AtomicReference[LoadBalanceShardAllocator] =
    new AtomicReference[LoadBalanceShardAllocator](null)

  /** Registers the allocator to be used by all future shard region creations. */
  def register(alloc: LoadBalanceShardAllocator): Unit =
    allocator.set(alloc)

  /** Gets the registered allocator, if any. */
  def get: Option[LoadBalanceShardAllocator] =
    Option(allocator.get())

  /** Clears the registered allocator (reverts to Pekko default). */
  def clear(): Unit =
    allocator.set(null)

  /** Returns true if an allocator is registered. */
  def isRegistered: Boolean =
    allocator.get() != null
}
