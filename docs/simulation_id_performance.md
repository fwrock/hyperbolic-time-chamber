# Simulation ID Performance Optimization

## Problem
Initially, the simulation ID was being read from `simulation.json` on every access, which is inefficient for a value that doesn't change during execution.

## Solution: Lazy Evaluation

### What is `lazy val`?
- `lazy val` is evaluated only once, on first access
- The result is cached for subsequent accesses
- Thread-safe by default in Scala

### Performance Benefits

#### Before (inefficient):
```scala
private def getSimulationId: String = {
  // This would read simulation.json EVERY time
  val simulationConfig = SimulationUtil.loadSimulation()
  // ... rest of logic
}
```

#### After (optimized):
```scala
private lazy val simulationId: String = {
  // This reads simulation.json ONLY ONCE
  val simulationConfig = core.util.SimulationUtil.loadSimulationConfig()
  // ... rest of logic
}
```

### Impact Analysis

For a typical simulation that generates thousands of report events:

| Approach | File Reads | Performance Impact |
|----------|------------|-------------------|
| Method call | 1 read per report event | ~1000+ file reads |
| `lazy val` | 1 read per execution | 1 file read total |

### Memory Usage
- Minimal: Only stores the final string result
- No repeated JSON parsing overhead
- Single computation per JVM instance

### Thread Safety
- `lazy val` is thread-safe by default
- No need for additional synchronization
- Safe for concurrent report generation

## Implementation Details

The `lazy val simulationId` follows the priority hierarchy:
1. **simulation.json** `id` field (highest priority)
2. **Environment variable** `HTC_SIMULATION_ID`
3. **application.conf** `htc.simulation.id`
4. **Auto-generation** with semantic naming

## Scientific Benefits

For reproducibility analysis:
- Consistent ID throughout simulation execution
- No performance degradation during data collection
- Efficient for long-running simulations with high data output rates

## Best Practices Applied

✅ **Lazy initialization**: Compute only when needed  
✅ **Caching**: Store result for reuse  
✅ **Immutability**: `val` ensures ID doesn't change  
✅ **Fallback chain**: Robust configuration priority  
✅ **Semantic naming**: Clear intent in auto-generated IDs  

This optimization ensures that simulation ID determination has virtually zero performance impact on the simulation execution while maintaining full configurability and scientific reproducibility.