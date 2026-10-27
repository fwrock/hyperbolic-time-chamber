# MICRO implementation and validation status

Status updated on **2026-10-26**. The v2 scientific results below retain the
**2026-10-25** campaign following milestone `729a746` on `feat/time-warp`;
the new [bounded spillback acceptance](MICRO_SPILLBACK.md) is separate engineering
evidence. This page consolidates the recovered MICRO/SUMO work, its
scientific interpretation and the remaining work. Dated experiment records
remain the source of detailed measurements; older failures are preserved.

## Current outcome

Engineering verification and the predeclared v2 numerical comparisons pass.
**Independent empirical validation of following and queue discharge remains open.**

The latest [field input audit and prospective v2 study](MICRO_FIELD_INPUT_AUDIT.md)
use the documented 35 mph site cap, session-aware identity exclusions,
same-section following and observed head-release alignment. Eight fresh following
episodes and four fresh queues qualify. All comparative and physical checks pass
on that subset: mean following position RMSE is 1.980 m versus rigid spacing
9.228 m, and queue headway MAE is 0.463 s versus 0.999 s. The original ten/five
sample targets are not met, so the overall field gate remains inconclusive.
The current adapter build passes 780 Scala tests in 154 suites and 49 Python
tests, with exact replay from the retained v2 ZIP. Production physics is unchanged;
the retrieved signal-support attachment fails ZIP integrity and supplies no
accepted phase reconstruction.

The later [conditional urban field assessment](MICRO_FIELD_VALIDATION.md) now
tests actual Link following physics against Lankershim observations. Ten reserved
following episodes have mean position RMSE 2.223 m, improving on constant velocity
(12.384 m). The paired interval against rigid spacing includes zero, and only two
of five required queue episodes qualify. Both queues discharge, with mean headway
MAE 0.625 s, but the declared field gate is not established. All observations,
exclusions, frozen criteria and exact offline replay survive in the repository
snapshot. The field adapter build passes 779 Scala tests in 154 suites and 44
Python tests; production traffic physics is unchanged.

The later opt-in spillback build passes 775 Scala tests in 153 suites and 34
Python tests. Its reserved short-road, connector and blocked-horizon fixtures
preserve physical queues, spacing and journeys under both policies, including
delayed two-JVM execution with 4,468 rollbacks. All ten new report comparisons
pass. The table below records the earlier v2 campaign; it is not a claim that all
19 SUMO comparisons were rerun on the spillback build.

| Evidence | Current result | Meaning |
|---|---|---|
| Scala suite | 653 tests in 135 suites pass | Implementation and runtime regression coverage |
| Python harness suite | 18 tests pass | Measurement, geometry, clock and statistical checks |
| Predeclared physical scenarios | All 4 pass under both HTC policies | Controlled agreement with SUMO for the specified cases |
| Reserved demand realizations | All 5 pass; 18 cars per seed | Demand sensitivity with deterministic drivers |
| Exact report comparisons | All 17 match | Bounded policy, layout, rollback and compatibility checks |
| Accepted measurements | 19 SUMO comparisons, 23 HTC runs | Includes controls and repeated executions |
| Completed matched Car journeys | 260 across those comparisons | Repeated policies, layouts and ablations are included; not 260 independent samples |
| Field data | Three exploratory NGSIM isolated-cruising forecasts | Does not validate interacting traffic or networks |

The five fresh scenario means have HTC-minus-SUMO duration bias **+0.064 s**,
with exploratory 95% Student-t interval **[+0.046, +0.083] s**. The inference unit
is the demand seed, with sample size five. A small positive bias remains; the
interval does not quantify all model or field-data uncertainty.

Current evidence:
[v2 machine record](validation/micro-discharge-2026-10-25.json),
[methods and ablations](MICRO_CROSS_LINK_FOLLOWING.md),
[figure](validation/figures/micro-discharge-2026-10-25.svg).

## Implementation delivered

| Area | Result | Implementation or contract |
|---|---|---|
| Comparison harness | Real SUMO runs; shared numeric identities; actual trip completion; link-aware and continuous-route metrics; failures and missing coverage rejected | [Initial measurement audit](SUMO_MICRO_VALIDATION_2026_10_24.md), `a06c1b6` |
| Node crossing and signals | A confirmed MICRO crossing adds no artificial point-node tick; physical speed is carried; Node still checks capacity; interval-effective signal state is checkpointed | [Boundary contract](SUMO_MICRO_VALIDATION_2026_10_24.md), `a06c1b6` |
| Finite junction geometry | Fixed Link connectors with measured internal distance, metric shape and turn speed; approach braking; original road identity and Bus cursor retained | [Junction geometry](JUNCTION_GEOMETRY.md), `dd2fe1a` |
| Physical clock and boundaries | Integer event order with explicit seconds per tick; optional same-tick step-remainder transfer; actual downstream entry position and velocity acknowledged | [Clock contract](MICRO_SCIENTIFIC_VALIDATION.md), `9d71be9` |
| Reporting and replay | Physical time units, stable floating reductions, typed checkpoint restore, censored horizon reporting and retained field inputs | [v1 methods](MICRO_SCIENTIFIC_VALIDATION.md), `e76ae78`, `eacf12d` |
| Following across boundaries | A leader remains observable after entering a connector or the next road; explicit signal stop gap; stricter red constraint retained alongside a moving leader | [Following contract](MICRO_CROSS_LINK_FOLLOWING.md), `8ba005d` |
| Optimistic stale publications | Ignore a late positive observation after rollback undoes its subscription; preserve ordinary transport, anti-message and commit accounting | [Rollback repair](MICRO_CROSS_LINK_FOLLOWING.md#exchange-and-ordering), `222f2d0` |

`Link.actSpontaneous` delegates physics to `LinkMicroSimulationHandler` and
`DefaultMicroSimulationStrategy`. The active default is simplified Krauss
car-following with `NoLaneChangeStrategy`. The presence of other model classes
does not establish integrated MOBIL lane changing in this path.

Boundary observations use ordinary fire-and-forget `sendMessageTo` exchanges.
Snapshots are immutable; subscriptions, publication sequence and two tick cohorts
live in checkpointed `LinkState`. Physics at tick `t` uses a snapshot strictly
before `t`. Receiving an observation neither republishes it nor adds a wait or
spontaneous event. See the [source handler](../src/main/scala/model/hybrid/support/link/LinkFollowingHandler.scala).

## Status of the four scientific priorities

| Priority | Delivered | Remaining acceptance |
|---|---|---|
| Clock and numerical convergence | Explicit physical clocks; fixed-physics-step v1 convergence study; remainder transfer removes observed cumulative boundary loss on the 1.2 km development route | No general convergence proof for every traffic regime; the default one-second mode retains its coarser timing behavior |
| Traffic metrics and comparison correctness | Duration distributions, route/speed errors, completion coverage, halted queues, discharge headways and completed-cohort throughput; independent harness regressions | Thresholds are provisional for matched experiments; completed-cohort throughput does not establish saturated capacity |
| Replication and uncertainty | Frozen scenarios and demand seeds; paired per-seed differences and small-sample Student-t interval; strict policy/layout report comparisons | Drivers remain deterministic; demand-only uncertainty does not cover driver populations, geometry error or model uncertainty |
| Independent empirical evidence | Three isolated-cruising forecasts and conditional Lankershim v1/v2 studies with retained snapshots and paired baselines | V2 comparative checks pass on its qualified subset, but eight following/four queues miss fixed ten/five targets; autonomous network adequacy and real signal reconstruction remain open |

The NGSIM ten-second position RMSE values are **2.35–7.57 m**. There is no
field-derived acceptance threshold for that pilot. Raw input survives outside
temporary directories in the tracked
[replay snapshot](validation/ngsim-free-flow-pilot-snapshot-2026-10-25.zip).
See [the v1 field methods](MICRO_SCIENTIFIC_VALIDATION.md).

## Scope and configuration

The v2 correction is **opt-in, single-lane, fine-clock MICRO** with static routes
and the supported Car/Link/Node/TrafficSignal actor sources. The one-second mixed
model remains available. Fractional clocks currently reject Person, transit,
two-wheelers, MESO, model births, dynamic mode choice and plugins. Runtime budgets
remain expressed in integer ticks.

| Setting | Existing default | Accepted v2 fixture |
|---|---|---|
| Simulation `tickDuration` | 1.0 s | 0.1 s |
| Link `simulationMode` | `MESO` | `MICRO` |
| Link `lanes` | Required input | 1 |
| Link `microTimeStep` | 0.1 s | 0.1 s |
| Link `microTicksPerGlobalTick` | 10 | 1 |
| Link `carryMicroRemainder` | `false` | `true` |
| Link `downstreamLookahead` | `false` | `true` |
| Link `stopLineGapM` | 0.0 m | 1.0 m, explicitly matched in SUMO |
| Dynamic routing cache | Scenario-dependent | `htc.routing.cache-strategy=disabled`; routes supplied explicitly |

Input times such as departures, signal cycles and the horizon use runtime ticks:
at 0.1 s/tick, 7.3 s is tick 73, a 60 s cycle is 600 ticks and 180 s is 1,800 ticks.
Preserve raw tick fields for ordering; reported physical durations use seconds.
`LinkState.speedLimit` and `freeSpeed` use **km/h** in the active Link path;
microscopic velocities, geometry `speedLimitMps` and `exitSpeedLimitsMps` use **m/s**.
Lengths and positions use metres.

Lookahead requires one physics step per runtime tick, single-lane MICRO on the
observed downstream Links and equal physics steps. Remainder transfer also
requires each Link to be longer than one speed-limit physics step. A real stop
or delayed capacity grant invalidates same-tick remainder continuation.

For a road-to-connector-to-road movement, keep the Node's `junctionMovements`,
the approach's `downstreamConnectors` and `exitSpeedLimitsMps`, connector endpoints
and metric geometry consistent. Trim road lengths at junction boundaries before
adding internal connector distance. Follow the
[configuration examples](MICRO_CROSS_LINK_FOLLOWING.md#input-contract) and
[geometry schema](JUNCTION_GEOMETRY.md#scenario-contract).

The accepted scenarios protect one selected movement and hold competing
approaches red. They do not establish conflict-area arbitration, multi-lane
turning, saturated capacity, blocked handoff or city-network accuracy.

## Measured correction and validation history

The v1 16-car seed-101 diagnostic exposed a leader disappearing from the approach
queue at 60.1 s. The following car then took full acceleration while its leader
was still immediately ahead. Fixing that observation and matching the stop gap
reduces trip-time MAE from **1.325 to 0.081 s**, mean route-position RMSE from
**8.283 to 0.479 m**, and the last queued car's error from **−3.2 to +0.1 s**.
The [controlled ablation table](MICRO_CROSS_LINK_FOLLOWING.md#paired-development-diagnostic)
separates stop-gap and following effects using the same demand and SUMO reference.

V1's five demand seeds all failed the provisional criteria, with mean bias
−1.185 s. Those outputs became development evidence. V2 reserved fresh cases and
seeds in `4c9f105` before physics implementation `8ba005d`; its thresholds were
not widened. The v1 and v2 cohort means use different demand and cohort sizes and
must not be presented as a paired treatment-effect estimate.

The first v2 run exposed an engine failure on the segmented optimistic route:
an observation outlived a rolled-back subscription. `222f2d0` repairs this
stale-input case without changing traffic parameters or thresholds. All accepted
measurements were repeated on the repaired build. Four exact conservative
comparisons verify unchanged physical output across the repair. The failed run
and earlier diagnostic builds remain explicitly excluded from final acceptance.

| Accepted v2 physical case | Cars per run | Trip-time MAE | Mean route-position RMSE |
|---|---:|---:|---:|
| Left turn, 211/137 m centreline roads | 6 | <0.001 s | 0.059 m |
| Right turn, 229/149 m centreline roads | 6 | 0.100 s | 0.642 m |
| Signal-controlled right turn, 187/137 m roads | 18 | 0.061 s | 0.494 m |
| Eight unequal road segments, 1,080 m total | 4 | <0.001 s | <0.001 m |

All five new 18-car demand seeds pass, with trip-time MAE 0.050–0.083 s and
p95 absolute error 0.100 s. The one-/two-JVM signal case matches all **9,255**
report rows; its two-JVM optimistic run exercises **3,297 rollbacks**. The
reproduced stale-publication failure completes through 1,330 rollbacks and matches
5,824 conservative rows. The legacy one-second Car and cyclic Bus/Subway fixtures
retain all 213 and 2,183 previous report rows respectively, including 39 completed
Bus connector traversals per transit run.

## Acceptance criteria and claim boundaries

The frozen [v2 protocol](../scripts/scenario/sumo_scientific_protocol_v2.json)
requires all applicable criteria, separately from engine and regression checks:

| Numerical criterion | Limit |
|---|---:|
| Completion coverage | 100% |
| Mean absolute trip-time error | ≤0.5 s |
| p95 absolute trip-time error | ≤1.0 s |
| Mean per-vehicle route-distance RMSE | ≤2.0 m |
| Mean per-vehicle speed RMSE | ≤1.0 m/s |
| Halted approach-queue count RMSE | ≤1 vehicle |
| Discharge-headway MAE | ≤0.3 s |
| Completed-cohort throughput relative error | ≤5% |

The halted queue counts approach vehicles below 0.1 m/s, sampled at equal times.
Headways match consecutive vehicle pairs within the same green interval.
Trajectories are compared at equal physical times without fitted shifts; duration
comes from actual completed-trip records. Geometry distance, crossing speed,
signal intervals and node delay also have independent invariant checks.

These are provisional numerical-equivalence criteria for the specified SUMO
experiments. Exact policy parity establishes consistency between HTC executions;
SUMO agreement establishes controlled reference agreement. Neither establishes
empirical following, intersection or network validity. The current safe-speed
expression remains a continuous stopping-distance approximation rather than an
exact implementation of every SUMO discrete Krauss detail.

## Remaining work and proposed acceptance gates

The table separates the accepted bounded spillback gate from remaining work.
Before changing traffic physics again, freeze a new protocol and reserve
fresh validation cases; inspected v1/v2 results may be used as development data.

| Work item | Priority | Evidence required before closing it |
|---|---|---|
| Downstream blocking and spillback at Nodes | Bounded Car gate accepted on 2026-10-26 | Opt-in retained lane footprints pass short-road, fixed-connector, horizon and policy/layout checks; checkpoint/cancellation tests preserve grant identity. General network, multi-lane and origin-admission behavior remain open. See [acceptance](MICRO_SPILLBACK.md). |
| Independent field following and queue discharge | Next scientific priority; v2 data gate incomplete | The input audit and fresh v2 comparative checks are complete. Preregister additional independent, uninspected sampling opportunities to meet the fixed cohort counts; do not lower targets or retrospectively pool favorable cases. See [audit and v2](MICRO_FIELD_INPUT_AUDIT.md). |
| Competing junction movements | Next model extension | Conflict-area occupancy, priority/gap acceptance and cross-stream delays checked against controlled cases and independent observations |
| Multi-lane behavior and longer observation horizons | Subsequent model work | Exercise actual lane-change integration and cases needing a leader beyond one connector plus the next road; establish spacing and policy parity |
| Discrete safe-speed details | Further reference audit | Isolate stopping/following integration differences without fitting coefficients or time offsets to inspected holdouts |
| City geometry import and execution cost | Subsequent integration/performance work | Validate complete movement mappings and distance conservation on an imported network; measure message rates, checkpoint memory, rollback cost and throughput under stated conditions |

Boundary snapshots represent lane queues. `downstreamSpillback=true` now keeps
the waiting Car's footprint in that queue until cleanup; without the option,
Node-resident vehicles remain absent. The bounded gate does not establish
general network spillback, calibrated gate braking or capacity-dwell emissions.

## Reproduction and session recovery

The accepted executable uses implementation `222f2d0`, frozen v2 protocol
`4c9f105`, SUMO 1.27.1 and SHA-256:

```text
c13c6c51f73b4689bf151cbca5146bf41697f21de5c3d870fd3ec0453ac2696d
```

Run commands in the [following reproduction guide](MICRO_CROSS_LINK_FOLLOWING.md#reproduction).
The main v2 stages are `held-out` and `stochastic`. The coarse-global-clock
convergence experiment belongs to the v1 study; v2 lookahead defaults require one
physics step per runtime tick. Use fresh output directories and retain failures.
The [machine record](validation/micro-discharge-2026-10-25.json) contains exact
commands, protocol/input hashes, per-vehicle results, criteria and exclusions.

Accepted raw artifacts are in `/tmp/htc-discharge-accepted-20261025/`; intermediate
builds are in `/tmp/htc-discharge-20261025/` and
`/tmp/htc-discharge-final-20261025/`. Temporary directories may disappear on reboot.
Committed records, protocol files, source revisions, figures and the NGSIM replay
snapshot remain available. Rebuild from the recorded implementation to reproduce
main experiments; do not substitute an older JAR or reuse earlier output folders.

Start a recovered session with this page, [Known Gaps](KNOWN_GAPS.md) and the
[recovery ledger](WORK_RECOVERY_2026_10_24.md). Historical references are the
[point-node audit](SUMO_MICRO_VALIDATION_2026_10_24.md),
[geometry milestone](JUNCTION_GEOMETRY.md),
[v1 scientific workflow](MICRO_SCIENTIFIC_VALIDATION.md) and their linked records.
