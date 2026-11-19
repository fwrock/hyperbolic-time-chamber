# Performance Optimization Candidates

Status: **analysis only, nothing implemented.** Produced by static reading of the code on
2026-11-19; no profiler or benchmark was run, so all cost figures are reasoned from the code, not
measured. Validate with JFR / async-profiler on a MICRO-heavy scenario before and after each change.

Notation: V = vehicles on a link, C = lane connections, P = pending entries, Q = queue length,
L = Time Warp log length, U = undone events, D = pending dispatches.

## Already good (no action)

- `CompactGraph` A*: CSR arrays, binary heap, dirty-tracked thread-local scratch buffers.
- `LinkRegistry`: `LongMap` keyed by actor id (O(1) contains/remove).
- `LocalTimeManagerBase.nextTick`: O(log n) over ordered structures.

## Tier 1 — Link MICRO path (every sub-tick, per vehicle)

| # | Where | Problem | Fix | Result |
|---|---|---|---|---|
| 1 | `DefaultMicroSimulationStrategy.processMicroLane` (`legalLanes`), `MobilLaneChangeStrategy` (`allowed`), `MicroEntryAdmission.lane/goal` | `laneConnections.filter(_.outgoingLinkId == …)` per vehicle per sub-tick | Precompute `Map[nextLinkId, Set[Int]]` once per `executeSubTick` (or cache on `LinkState`; connections are immutable after load) | O(V·C) → O(V + C) |
| 2 | `MobilLaneChangeStrategy.neighbours/safe/withDownstream` | Each call copies the lane into a `Vector` and filters twice; called several times per vehicle → O(V²) per sub-tick. `initial` also sorted twice per sub-tick | Lanes are already front-to-back sorted: find leader/follower by index (i±1) or binary search; sort `initial` once | O(V²) → O(V) / O(V log V) |
| 3 | `VehicleInLane.sortFrontToBack` | Full `sortWith` + clear + re-enqueue, twice per lane per sub-tick, on near-sorted data | Insertion pass (O(V) when nearly sorted); skip the trailing sort if order did not change. `insertFrontToBack` already exists | O(V log V) → ~O(V) |
| 4 | `findVehicleLane`, `LinkVehicleFlowHandler.sendLeaveLinkMicro` (3 scans), `sendMicroLeaveLinkToVehicle`, `dequeueAll(_.actorId == …)` | Every vehicle-id lookup scans all lanes: O(V) per event, O(V²) per tick when a platoon exits | `LongMap[Int]` actorId → lane in link state, maintained on admit / lane change / removal | O(V) → O(1) lookup |
| 5 | `LinkVehicleFlowHandler` (`pendingMicroEntries`), `drainMicroEntries` | `filterNot` + `:+` + `sortBy` per arrival (O(P log P)); drain re-runs `MicroEntryAdmission.reason` (lane scan, plus all-vehicle scan with weaving checks) for every pending entry → O(P·V) per tick | Sorted map keyed by `(requestedTick, actorId, requestSeq)` + `LongMap` actorId → key | insert/remove O(log P) |
| 6 | `LinkMicroSimulationHandler.handleGlobalTick`, `LinkMesoCapacityHandler.updateDensity`, `LinkState.totalVehiclesInMicro` | `LinkState.copy` re-runs ~15 `require`s over lanes/detectors/signals/exit limits each tick (and each meso admit/release); `toVector`+`map`+`sum` for a mean; `values.map(_.size).sum` allocates | Make `currentSpeed`/`congestionFactor` `var`s in state; single-pass fold for mean; running vehicle counter | removes per-tick allocation and O(lanes+detectors+signals) |

## Tier 2 — Meso capacity handler (`LinkMesoCapacityHandler`)

`entries`, `exits`, `originVehicles`, `destinationVehicles` are `List`s keyed by actor id:

- `enter`/`leave`: `indexWhere`, `:+`, `updated`, `filterNot` → O(Q) each.
- `drain`: re-`find`s after every `admit`, and `admit`/`release` each `filterNot` → draining k vehicles is O(k·Q).
- `drain` line `exits.filter(origin.contains || destination.contains)` → O(E·(O+D)).
- `enter`/`nextWake`: `entries.exists(...)` per message.

Fix: two insertion-ordered keyed structures (physical entries / origin-at-end entries, so
`find(!mesoOriginAtEnd)` becomes `head`) — `LinkedHashMap[Long, Pending]` or `LongMap` + `ArrayDeque`;
`LongSet` for origin/destination vehicles. All ops O(1) amortized; `drain` O(k). Keep the JSON form
a plain array via a converter, as `LinkRegistry.ToJson/FromJson` does, so snapshots stay compatible.

## Tier 3 — Node junction logic (`NodeEventHandler`)

Per request, linear scans over `movementSignals`, `junctionMovements`, `conflicts`
(`signals(...)`, `priorityMovement`, `conflicts.filter(_.involves(c))`, `refreshPermissions`
`.distinct.sorted`); `drainConflictGroup` multiplies this by group size. Fine for a 4-way node,
quadratic for large junctions.

Fix: build immutable indexes at node init — `Map[(in, out), Movement]`, `Map[connector, Movement]`,
`Map[connector, Vector[ConflictRule]]`, `Map[origin, Vector[MovementSignal]]`. Use `Set` for
`observationsRequested` and `MicroFollowingState.requested/subscribers` (currently `List.contains`).

## Tier 4 — Time Warp rollback (relevant in optimistic mode with long logs)

| Where | Problem | Fix |
|---|---|---|
| `RollbackHistoryHandler.rollbackFrom` | `log.sortBy(_.seq)` each rollback (the log is already seq-monotonic); `log --= undone` is O(L·U); checkpoint selection via `filter`+`sortBy` | Binary search first index with `seq >= firstUndoneSeq`, `trimEnd`; binary search checkpoints → O(log L + U) |
| `lastScheduleOutcome` | Sorts whole log per call | Maintain running value on append, restore on rollback |
| `findEvent` (anti-message lookup) | O(L) scan, rebuilds `messageId` per entry | `HashMap[MessageId, LoggedEvent]` beside the log |
| `markCommitted`, `pruneBelow`, `rollbackTo/After` | `log.count` / `iterator.filter(...).minOption` are O(L) | Binary search on monotone `tick`/`seq` |
| `OrderedInputQueue.discardDispatchesWhere` (called in the drain loop) | Scans all D pending dispatches per dequeued event → O(D·N) per turn | Run once per turn, or only when `scheduleRevision`/`currentGeneration` changed |
| `SimulationBaseActor.reportBuffer.partition` (rollback + GVT flush) | Full partition each time | Buffer is ordered by `eventSeq`: binary search + `trimEnd` on rollback; prefix split on GVT flush |
| `processedMessages/cancelledMessages.filterInPlace(_.tick >= gvt)` | O(S) per GVT round | Tick-bucketed `TreeMap[Tick, Set[MessageId]]`, prune only expired buckets |

## Tier 5 — Minor

- `CompactGraph.nodeIndex` is `java.util.HashMap[Long, Int]` (boxing) → open-addressing `LongIntMap`.
- `DoubleIntHeap.pop()` returns a tuple (allocation per expansion) → return node, read key from a field.
- `LoadBalanceManager` per-node `metrics.filter(locations(shardId) == node)` is O(N·S) → group once (low priority; rebalancing is disabled).
- `LinkMetricsReporter`: `valuesIterator.flatMap(...).toVector` per link per tick → lazy fold.

## Suggested order

1. Tier 1 items 1, 4 and the lane-lookup part of 2: small, local, take MICRO from O(V²) toward O(V).
2. Tier 1 items 3, 5, 6: cheap and mechanical.
3. Tier 2: self-contained behind `LinkMesoCapacityHandler` / `MesoCapacityState`.
4. Tier 4: only matters once optimistic mode runs at scale.
5. Tiers 3 and 5 as profiling justifies.

## Constraints when implementing

- State must stay Jackson-serializable and snapshot-compatible (see `LinkRegistry` converters).
- Any new actor-local `var` holding something needed after restart must live in `state` (CLAUDE.md).
- Keep `PlanCursorSpec`, `SpeedUtilSpec` and the meso/micro specs green; add pure-logic specs for
  each new index (equivalence against the old list-based behaviour on randomized input).
- Measure with micro-benchmarks of `processMicroLane` and `MobilLaneChangeStrategy.execute`
  (a few hundred vehicles) plus a JFR run on a full scenario.
