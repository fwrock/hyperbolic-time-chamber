# ALT landmark index

The ALT index (A* with landmarks and the triangle inequality) supplies the A* heuristic of
`GPSUtil.calcRouteCompact` and `CompactGraph.aStarALT`. It exists for the vehicle graph
(`routing.alt.vehicle`) and the pedestrian graph (`routing.alt.pedestrian`). For k landmarks it
stores, per node, the distance from and to each landmark; the heuristic for a query `u -> t` is
the largest triangle-inequality lower bound over those 2k values.

This is bounded engineering evidence on one real graph (the Berlin 5,000-trip city map:
52,256 nodes, 88,439 edges). It does not establish behavior on graphs with millions of nodes.

## Builders

`htc.mobility.landmark-builder` selects how the index is computed:

| Value | Builder | Notes |
|---|---|---|
| `compact` (default) | `CompactAltBuilder` on the CSR `CompactGraph` | primitive indexed heap over `Int` node indices, CSR transpose for the backward graph, distances written straight into the index array, trees computed in parallel |
| `map` | `LandmarkIndex.build` on the Map-based `Graph` | legacy: boxed maps, a rebuilt reversed `Graph`, a translation pass, sequential |

`htc.mobility.landmark-selection` chooses the landmarks (`compact` builder only):

| Value | Selection | Parallelism |
|---|---|---|
| `weighted` (default) | farthest-first by travel cost, seed at the middle node; picks exactly the landmarks of the legacy builder | forward trees run on the calling thread because each selection needs the previous forward tree; the backward trees overlap with it |
| `hops` | farthest node by edge count from the already selected set (multi-source BFS, last node dequeued) | depends on topology only, so all 2k trees run in parallel from the start |

`htc.mobility.landmark-threads` is the pool size for the trees (`0` = `min(4, cores)`).
`htc.mobility.landmark-count` (default 16) is unchanged. Each key has an environment override
(`HTC_MOBILITY_LANDMARK_BUILDER`, `HTC_MOBILITY_LANDMARK_SELECTION`,
`HTC_MOBILITY_LANDMARK_THREADS`, `HTC_MOBILITY_LANDMARK_COUNT`).

The result does not depend on the thread count: selection is deterministic and each tree is
computed independently. Negative edge weights are rejected with a clear error, since the trees
assume non-negative weights. The index must fit one array (`2 * k * n <= Int.MaxValue`, about
67 million nodes at k = 16).

## Layout

The index is one flat array, node-major: `data(node * 2k + 2i)` is the distance from landmark i
to the node and `+ 1` the distance from the node to landmark i. A heuristic call reads the rows
of two nodes (a few cache lines) instead of up to 4k positions in 4k separate arrays.

The on-disk cache layout is unchanged (all forward rows, then all backward rows), so entries
written by earlier builds stay readable.

## Disk cache

`calculationCache.entries` in `simulation.json` may list `routing.alt.vehicle` and
`routing.alt.pedestrian`. The fingerprint is the SHA-256 of the CSR graph plus the landmark
count, extended with `selection=hops` when that selection is used (its landmarks differ).
The pedestrian entry also carries the builder, because the legacy pedestrian graph orders its
nodes differently and may pick other landmarks. Vehicle entries are interchangeable between
builders: `weighted` reproduces the legacy landmarks exactly.

## Measurements

Same machine, Berlin 5,000-trip city map, 16 landmarks.

**Build time, cold JVM** (the situation of the warmup phase, from the run logs):

| | ALT build | Notes |
|---|---|---|
| legacy `map` | 4.6 s + 0.35 s translation | selection about 1.8 s, 32 trees about 2.8 s |
| `compact`, `weighted`, 4 threads | 0.42 s | |

Warm and best of three, the compact build takes 0.07 to 0.18 s depending on threads and
selection.

**Equivalence.** `weighted` matches the legacy index on 2,000,000 sampled node pairs (no
mismatch). The unit tests compare every tree with the Map-based Dijkstra, forward and backward,
and the whole index with the legacy one on random graphs (exact, using integer weights), also
for a disconnected graph.

**A\* query time** (3,000 random origin-destination pairs, 2,620 reachable, static weights,
single thread, median of five repetitions after warm-up, two independent rounds; the old layout
was measured at the previous commit with the same builder):

| Index | Round 1 | Round 2 |
|---|---|---|
| landmark-major layout, `weighted` | 24.7 s | 24.0 s |
| node-major layout, `weighted` | 14.6 s | 12.7 s |
| node-major layout, `hops` | 9.4 s | 8.6 s |

The total route cost is identical in every run (148,797,608.050), so all indexes yield the same
optimal costs. The layout alone accounts for about 44% less time. `hops` is a further 34%
faster than `weighted`; as a tightness proxy, the mean of heuristic over true distance across
about 1.85 million reachable pairs is 0.90 for `hops` and 0.80 for `weighted`.

**Why `weighted` stays the default.** It reproduces the landmarks, and so the behavior, of the
previous builder. Both selections keep A* optimal in cost, but among equal-cost routes the
returned route can differ, so switching the default needs a parity check on the scenarios that
matter. `hops` is the candidate to evaluate.

## Map warmup

`CityMapUtil.warmUp` builds the CSR graph, the ALT index and the SCC index before the simulation
starts, so no actor pays for them on the first route request. The map can serve any model, so the
warmup is not skipped by default and the code does not guess that a scenario never routes. The
indexes are lazy, so a scenario author can omit the warmup target; the first route request then
builds them inside the simulation.

Measured on the Berlin 5,000-trip scenario, whose routes are all precomputed (no route request
reaches the router), alternating runs in single-node mode:

| | Wall time | Warmup phase | CSR and ALT built |
|---|---|---|---|
| with the warmup target | 129.6 s, 122.8 s | 5.3 s, 5.6 s | yes |
| without the warmup target | 131.8 s, 143.2 s | skipped | no |

Skipping it showed no measurable gain: the warmup is now about 5.5 s and the variation between
runs is larger than that.

## Not checked

- The landmark trees use static weights. With `useDynamicWeights` the heuristic stays
  admissible only if no dynamic weight is lower than its static weight; this was not verified.
- A\* expansions were not counted, only wall time.
- Graphs with millions of nodes were not measured; the build time scales with
  `k * (V + E) * log V`.
- MATSim's dead-end pre-processing (nodes with a single non-dead-end neighbor) is not
  implemented.

## Reference

MATSim's `SpeedyALTData` was read as an algorithm reference (4-thread pool, unit-weight
selection, node-major array). It is GPL-licensed and HTC is Apache-2.0, so the implementation
here was written independently from the algorithm and shares no code with it.
