# Spatial directory validation — 2026-10-25

The accepted implementation passes **34 focused tests in four suites** and the
full Scala suite: **745 tests in 147 suites**. Two independent-JVM launches pass:
one uses a GeoAffinity assignment from the production LoadBalanceManager, the
other preserves explicit hash-ID placement with the new publication barrier.
Both pre-teardown execution-log pairs contain **zero engine errors**. Python
launcher compilation and `git diff --check` pass.

The [machine-readable record](validation/spatial-directory-2026-10-25.json)
retains addresses, PIDs, authority identity, per-run classpath and launcher hashes,
and result fields. The base commit is `cb1c221` with the Step 8/9 worktree changes;
the executable identity is the content hash, rather than a claim that HEAD alone
contains the tested code.

## Independent process evidence

| Case | Controller | Worker / callback owner / restored owner | Shard ID | Directory revision | Restored value |
|---|---|---|---|---|---|
| GeoAffinity spatial | `127.0.0.1:36775`, PID 1453603 | `127.0.0.1:44407`, PID 1453123 | `dynamic-qt-shard-0-0` | 1 | 23 |
| Explicit published hash | `127.0.0.1:40519`, PID 1455065 | `127.0.0.1:36585`, PID 1454806 | `353` | 1 | 23 |

Both use actor-system name `AllocationAuthorityJvmProbe`. The worker is the
oldest member and owns the active native shard coordinator. The controller actor
lives in the other process, so neither the placement authority nor directory can
depend on shared controller/worker objects. Both local allocator registries are
absent. Before entity creation the worker reports one installed directory row at
revision 1. The controller resolves the same typed published row. In spatial mode
the assignment comes from a real batch registration with batch/chunk correlation;
neither JVM manually fills its spatial registry.

After publication, the source entity receives value 23. A migration passes source
snapshot freezing/final save, observed placement, target restoration, accepted
store confirmation, intent retirement and both window-close ACKs. Actual region
queries show the shard absent at source and present at the requested worker.
Reads routed from each JVM return the same restored actor and value. Native
target startup handles the idle entity without using a new model message to
force recreation.

Artifacts:

- `/tmp/htc-spatial-directory-20261025-1/`
- `/tmp/htc-published-hash-directory-20261025-1/`

Each directory holds the result, manifest and live process logs. The launcher
copies `worker-execution.log` and `controller-execution.log` before it tears down
its child processes. Engine-error acceptance checks those captured logs; connection
warnings during deliberate teardown do not replace execution evidence. The
classpath content digest is identical before and after each launch, and matches
between cases:

```text
c35aec5708d4179e933383d0560a76d8a09b1960db7ebac904d84af8e23243c6
```

## Regression coverage

The focused run covers existing migration wave/restore gating and independent
cold codecs, plus directory-specific cases: all-node ACK readiness; an Up member
without a registered replica; foreign/wrong-epoch/wrong-revision ACKs; serialized
publication; queued duplicate receipts; conflicting rows without partial install;
replica loss; replacement identities; epoch/revision gaps and type-aware routing;
replica hydration and authority replacement; and disabled startup. Creator tests
ensure inactive/stale chunk responses cannot mutate registries and an incomplete
matching response aborts before local assignment writes.

## Reproduction

```bash
sbt 'testOnly *SpatialDirectorySpec *IndependentRuntimeCodecSpec *CreatorLoadDataCorrelationSpec *LoadBalanceManagerMigrationQueueSpec'
sbt test 'export Test / fullClasspath'
python3 scripts/scenario/run_allocation_authority_validation.py --spatial --output /tmp/htc-spatial-directory-recheck
python3 scripts/scenario/run_allocation_authority_validation.py --output /tmp/htc-published-hash-directory-recheck
python3 -m py_compile scripts/scenario/run_allocation_authority_validation.py
git diff --check
```

Output directories must not already exist. Keep the compiled classpath unchanged
during each independent-process launch; the launcher checks its contents, not
only its path list.

## Acceptance limits

This fixture pins the production controller actor and supplies the conservative
safe-boundary response through a TestProbe. It calls the real registration and
directory path, but does not run CreatorLoadData's complete actor construction
pipeline or SimulationManager's singleton election. The entity is a controlled
counter, not a traffic model. This establishes publication, routing and relocation
for a fixed creation cohort; it does not establish full traffic migration.

The directory records creation assignments, not accepted initialization, live
birth/retirement or progressive membership. General migration must close those
lifecycle obligations before treating directory rows as snapshot obligations.
Adaptive partition publication, multi-host failure/recovery, new member enrollment,
repeated runs, accounted conservative/optimistic migration and city-scale costs
remain open. Custom balancing remains disabled by default.
