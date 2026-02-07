# Cache Strategy Comparison: Redis vs In-Memory

## Your Questions Answered

### 1. How does Redis sync data?

**Single Centralized Redis Instance:**
```
┌─────────────┐       ┌─────────────┐       ┌─────────────┐
│  Node 1     │       │  Node 2     │       │  Node 3     │
│             │       │             │       │             │
│ Link Actors │       │ Link Actors │       │ Link Actors │
└──────┬──────┘       └──────┬──────┘       └──────┬──────┘
       │                     │                     │
       │ TCP connection      │                     │
       │ (publish)           │ (publish)           │ (publish)
       └─────────────────────┴─────────────────────┘
                             ↓
                    ┌──────────────────┐
                    │  REDIS           │
                    │  (Single)        │
                    │  In-Memory DB    │
                    └──────────────────┘
                             ↑
       ┌─────────────────────┴─────────────────────┐
       │ (query)             │ (query)             │ (query)
       │                     │                     │
┌──────┴──────┐       ┌──────┴──────┐       ┌──────┴──────┐
│  Routing    │       │  Routing    │       │  Routing    │
│  GPSUtil    │       │  GPSUtil    │       │  GPSUtil    │
└─────────────┘       └─────────────┘       └─────────────┘
```

**Not multiple Redis instances** - all nodes connect to ONE shared Redis.

### 2. Isn't in-memory faster than Redis?

**YES! Absolutely!** Here's the performance comparison:

| Operation | Redis (Network) | In-Memory (Local) | Speedup |
|-----------|----------------|-------------------|---------|
| **Write** | ~1 millisecond | ~10 nanoseconds | **100,000x faster** |
| **Read** | ~1 millisecond | ~10 nanoseconds | **100,000x faster** |
| **Batch Read (100 links)** | ~5-10ms | ~1 microsecond | **10,000x faster** |

**Why Redis is slower:**
- Network latency (~0.5-1ms)
- Serialization (JSON encoding/decoding)
- TCP round-trip
- Redis processing time

**Why In-Memory is faster:**
- Direct HashMap lookup: O(1)
- No serialization
- No network
- CPU cache locality

### 3. In-Memory needs synchronization?

**YES!** That's why I implemented **Pekko Distributed Data**:

#### In-Memory Strategy Architecture

```scala
// Each node has:
┌─────────────────────────────────────────┐
│  Node 1                                 │
│                                         │
│  ┌────────────────────────────────┐    │
│  │ ConcurrentHashMap (local)      │◄───┼── ULTRA FAST READ (~10ns)
│  │ linkId -> DynamicLinkCost      │    │
│  └────────────────────────────────┘    │
│           ↕                             │
│  ┌────────────────────────────────┐    │
│  │ Pekko Distributed Data         │    │
│  │ (ORMap - CRDT)                 │    │
│  └────────────────────────────────┘    │
│           ↕ gossip protocol            │
└───────────┼────────────────────────────┘
            │
            │ Cluster sync (~10-50ms)
            │
┌───────────┼────────────────────────────┐
│  Node 2   ↓                            │
│  ┌────────────────────────────────┐    │
│  │ ConcurrentHashMap (local)      │    │
│  │ + Pekko Distributed Data       │    │
│  └────────────────────────────────┘    │
└─────────────────────────────────────────┘
```

#### Synchronization Mechanism

1. **Write Operation:**
   ```scala
   // Link actor publishes cost
   publishCost(cost) {
     localCache.put(linkId, cost)           // Immediate local update
     replicator ! Update(key, cost)          // Async cluster propagation
   }
   ```

2. **Gossip Protocol:**
   - Pekko uses **gossip** to sync between nodes
   - Changes propagate in ~10-50ms
   - **Eventually consistent** (not immediate)
   - Uses **CRDTs** (Conflict-free Replicated Data Types)

3. **Read Operation:**
   ```scala
   getCost(linkId) {
     localCache.get(linkId)  // Always local, always fast
   }
   ```

#### CRDT (Conflict-free Replicated Data Type)

**ORMap = Observed-Remove Map:**
- Each node can update independently
- Conflicts resolved automatically
- No locking, no coordination
- Merges happen via gossip

Example conflict resolution:
```
Node 1: link123 -> cost=100 (timestamp: 1000)
Node 2: link123 -> cost=120 (timestamp: 1001)

After gossip merge:
Both nodes: link123 -> cost=120 (latest wins)
```

## Complete Comparison

### Redis Strategy

**Architecture:**
- External Redis server
- TCP connections from all nodes
- Centralized data store

**Performance:**
- Read: ~1ms (network + serialization)
- Write: ~1ms (network + serialization)
- Consistent: Immediate (single source of truth)

**Pros:**
- ✅ Immediate consistency
- ✅ Simple architecture (no sync logic)
- ✅ Less memory (data in Redis only)
- ✅ Can persist to disk
- ✅ Can scale Redis independently

**Cons:**
- ❌ Network latency (~1ms)
- ❌ External dependency
- ❌ Single point of failure (unless Redis cluster)
- ❌ 100,000x slower than local memory

**Best for:**
- Multi-node clusters
- When consistency is critical
- When Redis already in infrastructure

### In-Memory Strategy

**Architecture:**
- ConcurrentHashMap per node
- Pekko Distributed Data for sync
- Gossip protocol (automatic)

**Performance:**
- Read: ~10ns (local HashMap)
- Write: ~100ns local + 10-50ms gossip
- Consistent: Eventually (~10-50ms delay)

**Pros:**
- ✅ **100,000x faster reads**
- ✅ No external dependencies
- ✅ Automatic cluster sync (Pekko)
- ✅ No network for reads
- ✅ Survives Redis failures

**Cons:**
- ❌ Eventually consistent (not immediate)
- ❌ Higher memory (replicated on all nodes)
- ❌ Gossip overhead (~50ms sync)
- ❌ No persistence (in-memory only)

**Best for:**
- Single-node deployments (ultra-fast)
- When eventual consistency acceptable
- High-frequency reads (routing calculations)

## Memory Comparison

### Redis: 10,000 links

**On Redis server:**
- 10,000 × 300 bytes = ~3 MB

**On each simulation node:**
- Zero! (except during query)

**Total cluster (3 nodes):**
- **3 MB** (only in Redis)

### In-Memory: 10,000 links

**On each simulation node:**
- 10,000 × 300 bytes = ~3 MB (local cache)
- + Pekko DistributedData overhead: ~1 MB
- = **4 MB per node**

**Total cluster (3 nodes):**
- **12 MB** (replicated on all nodes)

**4x more memory, but 100,000x faster reads!**

## Hybrid Strategy (Future Enhancement)

Best of both worlds:

```scala
class HybridCacheStrategy extends WeightCacheStrategy {
  private val localCache = new ConcurrentHashMap[String, DynamicLinkCost]()
  private val redis = new RedisCacheStrategy()
  
  def getCost(linkId: String): Option[DynamicLinkCost] = {
    // Try local first (fast)
    Option(localCache.get(linkId)) match {
      case Some(cost) => Some(cost)
      case None =>
        // Miss: query Redis, populate local
        redis.getCost(linkId).map { cost =>
          localCache.put(linkId, cost)
          cost
        }
    }
  }
  
  def publishCost(cost: DynamicLinkCost, ttl: Int): Try[Unit] = {
    localCache.put(cost.linkId, cost)     // Local
    redis.publishCost(cost, ttl)          // + Redis backup
  }
}
```

**Characteristics:**
- Read: ~10ns (local cache hit)
- Write: ~1ms (Redis backup)
- Consistency: Immediate (Redis as source of truth)
- Memory: Full replication per node

## Recommendation

### Single-Node Deployment
**Use In-Memory:**
```hocon
htc.routing.cache-strategy = "inmemory"
```
- No cluster sync needed
- Ultra-fast (10ns reads)
- No external dependencies

### Multi-Node Cluster (Small: 2-5 nodes)
**Use In-Memory:**
```hocon
htc.routing.cache-strategy = "inmemory"
```
- Gossip overhead acceptable
- 100,000x faster reads worth it
- Eventual consistency fine for routing

### Multi-Node Cluster (Large: 10+ nodes)
**Use Redis:**
```hocon
htc.routing.cache-strategy = "redis"
```
- Gossip overhead grows with nodes
- Centralized more efficient
- Consistency guaranteed

### Critical Consistency Required
**Use Redis:**
```hocon
htc.routing.cache-strategy = "redis"
```
- Immediate consistency
- Single source of truth
- No eventual consistency issues

## Configuration

```hocon
htc.routing {
  use-dynamic-weights = true
  
  # Choose strategy
  cache-strategy = "inmemory"  # or "redis"
  
  link-cost {
    publish-interval = 10
    cache-ttl = 60
  }
}
```

## Performance Numbers (10,000 links)

### Scenario: Calculate 1000 routes (100 links per route)

**Redis:**
- Route calculation: 100 links × 1ms = **100ms per route**
- 1000 routes: **100 seconds**
- Network traffic: 100ms × 1000 = **100 seconds of network time**

**In-Memory:**
- Route calculation: 100 links × 10ns = **1 microsecond per route**
- 1000 routes: **1 millisecond**
- Network traffic: **0** (all local)

**Speedup: 100,000x faster!**

## Conclusion

**Your intuition was correct:**
- ✅ In-memory IS faster (100,000x!)
- ✅ In-memory DOES need synchronization (Pekko Distributed Data provides it)
- ✅ Redis IS slower but simpler and immediately consistent

**Best approach:**
- Start with **in-memory** for performance
- Switch to **Redis** only if:
  - Immediate consistency critical
  - Very large cluster (>10 nodes)
  - External monitoring/debugging needed
  - Persistence required

The implementation now supports **both strategies** - choose based on your needs!
