# Dynamic Routing with Real-Time Traffic Conditions

## Overview

The Hyperbolic Time Chamber now supports dynamic routing that adapts to real-time traffic conditions. Instead of using only static edge weights (link length), the system incorporates live traffic data including congestion, current speeds, and incidents.

## Architecture

### Components

1. **DynamicLinkCost** - Data model representing real-time link conditions
2. **DynamicWeightCache** - Redis-based distributed cache for link costs
3. **Link Actor** - Calculates and publishes dynamic costs periodically
4. **GPSUtil** - Route calculation with dynamic weight support

### Data Flow

```
┌─────────────┐
│ Link Actors │ ──┐
│  (Multiple) │   │
└─────────────┘   │
                  │ Publish Cost
┌─────────────┐   │ Every N ticks
│ Link Actors │ ──┤
│  (Multiple) │   │
└─────────────┘   │
                  ↓
             ┌─────────┐
             │  Redis  │ ← Cluster-wide cache
             │  Cache  │   (One per cluster node)
             └─────────┘
                  ↑
                  │ Query Cost
                  │ During routing
             ┌────────┐
             │ GPSUtil│
             │Routing │
             └────────┘
```

## Dynamic Cost Calculation

The total cost for a link combines multiple factors:

```
totalCost = baseCost × congestionFactor × speedPenalty + incidentPenalty

where:
  baseCost = link length (meters)
  congestionFactor = 1.0 to 3.0+ (based on vehicle density)
  speedPenalty = freeFlowSpeed / currentSpeed (≥ 1.0)
  incidentPenalty = incidentFactor × baseCost × incidentMultiplier
```

### Cost Components

| Component | Description | Impact |
|-----------|-------------|--------|
| **Base Cost** | Physical link length | Static baseline |
| **Congestion Factor** | Vehicle density (vehicles/capacity) | 1.0 = free-flow, 2.0+ = heavy congestion |
| **Speed Penalty** | Current vs free-flow speed ratio | Higher when traffic is slow |
| **Incident Factor** | Accidents, road work, etc. | Doubles cost when present (configurable) |

## Configuration

### application.conf

```hocon
htc.routing {
  # Enable/disable dynamic weights
  use-dynamic-weights = true
  
  link-cost {
    # How often links publish their cost (ticks)
    publish-interval = 10
    
    # How long cached costs are valid (seconds)
    cache-ttl = 60
    
    # Incident impact multiplier
    incident-multiplier = 2.0
  }
}
```

### Environment Variables

Override configuration via environment:

```bash
export HTC_USE_DYNAMIC_WEIGHTS=true
export HTC_LINK_COST_PUBLISH_INTERVAL=10
export HTC_LINK_COST_CACHE_TTL=60
export HTC_INCIDENT_COST_MULTIPLIER=2.0
```

## Usage

### In Actor Code

```scala
// Calculate route with dynamic weights (default)
val routeOpt = GPSUtil.calcRoute(originId, destinationId)

// Force static weights (ignore traffic)
val staticRouteOpt = GPSUtil.calcRoute(originId, destinationId, useDynamicWeights = false)
```

### Monitoring Cache Statistics

```scala
import org.interscity.htc.model.hybrid.util.DynamicWeightCache

// Get cache statistics
val (totalLinks, avgCongestion, congestedLinks) = DynamicWeightCache.getStatistics()
println(s"Cached links: $totalLinks")
println(s"Average congestion: $avgCongestion")
println(s"Congested links: $congestedLinks")
```

### Manual Cost Query

```scala
// Query cost for specific link
val costOpt = DynamicWeightCache.getCost(linkId)
costOpt.foreach { cost =>
  println(s"Total cost: ${cost.totalCost}")
  println(s"Congestion: ${cost.congestionFactor}")
  println(s"Utilization: ${cost.utilization}")
  println(s"Is congested: ${cost.isCongested}")
}
```

## Distributed Cluster Behavior

### Single Node
- Link actors publish to local Redis
- GPSUtil queries local Redis
- Works seamlessly

### Multi-Node Cluster
- Each node runs link actors for its shard
- All nodes connect to same Redis instance
- Cost data is cluster-wide consistent
- Automatic failover with Redis cluster/sentinel

### Redis Architecture

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│  Node 1      │     │  Node 2      │     │  Node 3      │
│              │     │              │     │              │
│ ┌──────────┐ │     │ ┌──────────┐ │     │ ┌──────────┐ │
│ │Link      │ │     │ │Link      │ │     │ │Link      │ │
│ │Actors    │─┼──┐  │ │Actors    │─┼──┐  │ │Actors    │─┼──┐
│ └──────────┘ │  │  │ └──────────┘ │  │  │ └──────────┘ │  │
└──────────────┘  │  └──────────────┘  │  └──────────────┘  │
                  │                     │                     │
                  └─────────────────────┴─────────────────────┘
                                        │
                                        ↓
                               ┌─────────────────┐
                               │  Redis Cluster  │
                               │  (Shared State) │
                               └─────────────────┘
```

## Performance Considerations

### Bottleneck Prevention

1. **Non-Blocking Operations**
   - All Redis operations are async/fire-and-forget
   - Link actors don't wait for confirmation
   - Route calculation continues with stale data if Redis is slow

2. **Caching Strategy**
   - TTL prevents stale data accumulation
   - Fallback to static weights if cache miss
   - Batch operations for efficiency

3. **Update Frequency**
   - Configurable publish interval
   - Default: every 10 ticks (adjustable)
   - Higher interval = lower overhead, less accurate routing

### Tuning Guidelines

| Scenario | Publish Interval | Cache TTL | Notes |
|----------|------------------|-----------|-------|
| **Real-time routing** | 5-10 ticks | 30s | More updates, responsive |
| **Balanced** | 10-20 ticks | 60s | Default, good compromise |
| **Performance-focused** | 50+ ticks | 120s | Less overhead, less accurate |
| **Static routing** | disabled | - | Set use-dynamic-weights=false |

## Data Model

### DynamicLinkCost

```scala
case class DynamicLinkCost(
  linkId: String,
  baseCost: Double,              // Link length (m)
  congestionFactor: Double,      // 1.0 = free, 3.0+ = jammed
  currentSpeed: Double,          // m/s
  freeFlowSpeed: Double,         // m/s
  vehicleCount: Int,
  capacity: Double,
  incidentFactor: Double = 0.0,  // 0.0 = none, >0 = incident
  lastUpdateTick: Long,
  timestamp: Long
)
```

### Redis Storage

- **Key Pattern**: `dynamic:link:cost:{linkId}`
- **Value**: JSON-serialized DynamicLinkCost
- **TTL**: Configurable (default 60s)
- **Size**: ~200-300 bytes per link

### Memory Footprint

For 10,000 links:
- Storage: ~2-3 MB in Redis
- Network: ~20-30 KB/s per node (10 tick interval)
- Minimal impact on simulation performance

## Testing

### Unit Tests

```scala
// Test dynamic cost calculation
val cost = DynamicLinkCost.fromLinkState(
  linkId = "link1",
  length = 100.0,
  currentSpeed = 5.0,
  freeFlowSpeed = 10.0,
  vehicleCount = 80,
  capacity = 100.0,
  congestionFactor = 1.5,
  tick = 100
)

assert(cost.utilization == 0.8)
assert(cost.isCongested)
assert(cost.totalCost > cost.baseCost)
```

### Integration Tests

```scala
// Test cache operations
val cost = DynamicLinkCost(/* ... */)
DynamicWeightCache.publishCost(cost)

val retrieved = DynamicWeightCache.getCost("link1")
assert(retrieved.isDefined)
assert(retrieved.get.totalCost == cost.totalCost)
```

## Future Enhancements

### Planned Features

1. **Predictive Routing**
   - ML-based traffic prediction
   - Time-dependent routing
   - Historical pattern analysis

2. **Advanced Cost Factors**
   - Weather conditions
   - Special events
   - Road type preferences
   - Vehicle-specific costs (bus lanes, etc.)

3. **Route Re-calculation**
   - Automatic re-routing on condition change
   - Threshold-based triggers
   - Proactive congestion avoidance

4. **Distributed Graph Algorithm**
   - Custom A* with dynamic weight function
   - Distributed Dijkstra for scalability
   - Parallel route calculation

### Extensibility

To add custom cost factors:

```scala
// Extend DynamicLinkCost
case class ExtendedLinkCost(
  base: DynamicLinkCost,
  weatherFactor: Double,
  eventFactor: Double
) {
  def totalCost: Double = 
    base.totalCost * weatherFactor * eventFactor
}

// Update Link actor to calculate custom factors
private def publishDynamicCost(): Unit = {
  val baseCost = DynamicLinkCost.fromLinkState(/* ... */)
  val extendedCost = ExtendedLinkCost(
    base = baseCost,
    weatherFactor = calculateWeather(),
    eventFactor = checkEvents()
  )
  // Publish extended cost
}
```

## Troubleshooting

### Routes not adapting to congestion

**Check:**
1. `htc.routing.use-dynamic-weights = true`
2. Redis is running and accessible
3. Link actors are publishing costs (check logs)
4. Cache TTL is reasonable (not too short)

### High Redis load

**Solutions:**
1. Increase `publish-interval` (less frequent updates)
2. Increase `cache-ttl` (reduce expiration churn)
3. Use Redis cluster for horizontal scaling
4. Enable Redis persistence for recovery

### Stale routing data

**Causes:**
- `publish-interval` too high
- `cache-ttl` too long
- Redis connection issues

**Fix:**
- Lower publish interval
- Monitor Redis connectivity
- Check network latency

## References

- [CONFIGURATION.md](CONFIGURATION.md) - Full configuration reference
- [ARCHITECTURE.md](ARCHITECTURE.md) - System architecture
- [API_REFERENCE.md](API_REFERENCE.md) - Actor and event APIs
