# Node-local scheduler: execution time and cross-JVM traffic

Date: 2026-11-17. Instrumented single-host pilot comparing the existing
`cluster-pool` strategy with `node-local-tick`. The new option reduced traffic and
simulation-loop time in this workload; the end-to-end improvement was much smaller.
This is not a multi-host or city-scale performance result.

## Measured results

Medians over three measured runs per strategy, after one excluded warm-up run per
strategy. Positive reductions mean a lower value for the new option.

| Measurement | cluster-pool | node-local-tick | Reduction |
|---|---:|---:|---:|
| Simulation loop | 5.728 s | 4.878 s | 14.8% |
| Launch to engine stop | 30.423 s | 29.824 s | 2.0% |
| Cross-JVM envelopes, all categories | 14,785 | 7,623 | 48.4% |
| Cross-JVM scheduling envelopes | 5,951 | 1,438 | 75.8% |
| Serialized remote bytes, all categories | 14.164 MB | 6.831 MB | 51.8% |
| Serialized remote scheduling bytes | 7.689 MB | 1.259 MB | 83.6% |
| GTM synchronization stopwatch | 0.420 s | 0.178 s | 57.7% |
| Aggregate process CPU time | 104.88 s | 102.13 s | 2.6% |
| Peak simultaneous aggregate RSS | 711.1 MB | 742.6 MB | -4.4% |

Bytes use decimal MB. RSS increased by approximately 31.5 MB in this pilot.
Startup/non-loop overhead dominates total duration, so the smaller simulation-loop
time does not translate to a comparable end-to-end reduction.

Observed ranges:

| Measurement | cluster-pool range | node-local-tick range |
|---|---:|---:|
| Simulation loop | 5.113–5.773 s | 4.319–4.919 s |
| Launch to engine stop | 30.322–32.641 s | 29.411–30.219 s |
| Cross-JVM envelopes | 14,068–15,163 | 7,613–7,760 |
| Scheduling envelopes | 5,423–6,144 | 1,438–1,451 |

Raw measured pairs, in execution order:

| Pair | Strategy | Simulation seconds | Launch-to-stop seconds | Remote envelopes | Serialized bytes | Scheduling envelopes |
|---|---|---:|---:|---:|---:|---:|
| 1 | node-local-tick | 4.918679477 | 29.824292386 | 7,760 | 6,993,245 | 1,438 |
| 1 | cluster-pool | 5.112806304 | 32.640700791 | 14,785 | 14,164,188 | 5,951 |
| 2 | cluster-pool | 5.772577590 | 30.322483985 | 14,068 | 13,302,622 | 5,423 |
| 2 | node-local-tick | 4.877904494 | 30.218931500 | 7,623 | 6,830,882 | 1,438 |
| 3 | node-local-tick | 4.319454102 | 29.410623592 | 7,613 | 6,816,042 | 1,451 |
| 3 | cluster-pool | 5.728184035 | 30.423328936 | 15,163 | 14,651,408 | 6,144 |

## Workload and resources

- Generated MESO-only 4x4 grid: 16 Nodes, 48 road Links, 120 Persons owning 120 Cars,
  three daily activities, no transit, generator/runtime seed 71, one-second ticks.
- Every warm-up and measured run passed the mobility validator: 240 trips, 120 daily
  summaries and 610 matching road entries/exits. All three measured pairs also
  matched every Person's trip distances and completion ticks exactly.
- Two separate JVMs on the same PC, using real Artery TCP on loopback.
- Each JVM: `-Xmx2g`, `-XX:ActiveProcessorCount=2`, CPU affinity restricted to two
  logical CPUs. Four logical CPUs total: 0,1 for JVM 0 and 2,3 for JVM 1.
- This machine reports 0/1 as SMT siblings on one physical core and 2/3 as siblings
  on another. Thus the comparison uses two physical cores/four logical CPUs in total.
  It does not represent two physical cores per JVM.
- Both strategies configure three scheduling workers per JVM. The reference has
  six LTMs; the new option has six partition workers plus two dispatchers. Additional
  dispatcher overhead is included within the same CPU and heap budgets.
- JSON reporting uses batch size 1000 for both; shared routing cache and live load
  balancing are disabled. Prometheus scraping is disabled during timing; external
  process sampling runs every 0.25 s.
- Both strategies have the same optional remote instrument enabled. Each run starts
  fresh JVMs. Excluded warm-up runs prime host/file caches; they do not constitute
  steady-state JIT warm-up within subsequent JVMs.

All runs use assembly SHA-256:
`0471563e92503d931a637c6fd58bc16ba10d7d4ae084c19278e178708053f46e`.

## Measurement boundaries and interpretation

Simulation time comes from the settled monotonic GTM `simulation_loop` stopwatch.
Launch-to-stop time comes from the external launcher before teardown. The two
measurements describe different boundaries; neither includes the harness's final
forced process cleanup.

Traffic is the sum of **outbound** serialized Artery envelopes from the two JVMs,
captured through each process's final timing snapshot. It includes startup and
cluster background traffic, excludes subsequent teardown, and is not double-counted
with inbound receipts. Serialized bytes include Pekko headers but exclude TCP/IP
overhead. These are not packet counts or NIC bytes. See the
[measurement guide](REMOTE_TRAFFIC_MEASUREMENT.md) for category definitions.

The scheduling category includes root dispatch and callback/accounting/control
envelopes. The new option makes actor-to-dispatcher effects local, although remote
cascade accounting still exists. Counts measure actual transport, rather than
assuming every logical message crosses a network. Routing, coalescing, inline
zero-delay continuations and node-level coordination change together; this pilot
does not isolate the contribution of each mechanism.

Placements can vary between fresh clustered runs; per-type/node counts are retained.
All final measured pairs nevertheless matched mobility outcomes and completion ticks.
Host one-minute load varied approximately 5.04–9.52 during measured runs. CPU affinity
does not provide exclusive CPU ownership. Three repetitions, instrumentation cost,
shared-host interference, startup variance and loopback networking limit inference.
No confidence interval or general performance guarantee is claimed. A larger,
longer-running and multi-host campaign is needed to establish broader performance.
The subsequent 3.10.0 default change is a product decision within the supported scope,
not evidence of a general speedup.

## Reproduce

```bash
python3 scripts/scenario/generate_hybrid_scenario.py \
  --output-dir /tmp/htc-node-local-benchmark-recheck/scenario \
  --rows 4 --cols 4 --num-persons 120 --num-cars 120 \
  --num-bus-lines 0 --num-subway-lines 0 --micro-pct 0 --seed 71
```

Set generated `simulation.json` fields `timeManagerType="discrete-event"` and
`randomSeed=71`, then:

```bash
python3 scripts/scenario/benchmark_node_local_scheduler.py \
  --jar target/scala-3.3.5/hyperbolic-time-chamber-3.9.0.jar \
  --simulation /tmp/htc-node-local-benchmark-recheck/scenario/simulation.json \
  --output /tmp/htc-node-local-benchmark-recheck/results \
  --workers-per-node 3 --repetitions 3 --warmup-pairs 1 \
  --base-port 28600 --timeout 180 --cpu-ids 0,1,2,3
```

Retained temporary artifacts: `/tmp/htc-node-local-benchmark-20261117/`. They include
the generated inputs, manifest, override configurations, every JVM command/log,
process samples, reports, per-run validated outcomes, `runs.json` and `summary.json`.
OS cleanup can remove temporary files; this record and runner remain in the repo.

Instrumentation tests: `RemoteTrafficMetricsSpec` and `CommittedReportDrainSpec`
passed seven tests; the comparison extractor passed three Python tests; the existing
benchmark-metrics tests passed nine tests. Compilation and assembly passed. The
instrument remains opt-in. The measured executable defaulted to `cluster-pool`; both strategies were selected
explicitly. Release 3.10.0 subsequently changes the default to `auto`; see the runtime
guide for policy selection and startup cohort discovery.
