# Native MICRO traffic controls

Protocol and acceptance JSON files referenced below are retained local experiment records and are excluded from this repository. Their paths identify the evidence used for the documented checks.

Traffic control belongs in actor input JSON, independently of any converter. Existing files keep their legacy signal windows and disabled lane changing. The options below appear inside `data.content`; `TrafficSignal`, `Link` and `Node` schemas describe the fields.

## Lane changing on a Link

```json
{
  "simulationMode": "MICRO",
  "laneChanging": {
    "model": "Mobil",
    "politeness": 0.5,
    "safeDeceleration": 4.0,
    "accelerationThreshold": 0.1,
    "biasToRight": 0.2,
    "minGapMeters": 2.0,
    "cooldownSeconds": 2.0,
    "strategicLookaheadMeters": 150.0,
    "strategicCooldownSeconds": 0.2,
    "cooperativeStrategicChanges": true
  },
  "laneConnections": [
    {"outgoingLinkId": 30, "fromLane": 1, "toLane": 0}
  ]
}
```

Use `Disabled` to keep lanes fixed. Lane 0 is the rightmost lane. Distances are metres, times seconds, acceleration m/s². Existing Link speed limits retain their established km/h input convention. Changes occur between adjacent lanes before each physics step. Stable vehicle-ID arbitration, front/rear gap checks, bounded follower braking and cooldown prevent competing changes into the same space. MOBIL provides discretionary changes; strategic changes approach the lanes required by the next road. Strategic and discretionary cooldowns are separate so a short road can require several adjacent changes. With `cooperativeStrategicChanges`, a blocked strategic mover follows a deterministic vehicle-ID priority and brakes within its physical limit to create longitudinal clearance; it never swaps overlapping vehicles or resets speed/position. Restricted bus, bike and emergency lanes are unavailable to this passenger-car strategy.

`laneConnections` restricts the permitted exit lanes for each next road. A vehicle in the wrong lane waits rather than crossing an illegal turn. Supply corresponding Node `junctionMovements` with `incomingLane` and `outgoingLane` when lane-specific physical connectors exist; the granted destination lane survives the connector traversal. Omitted lane fields retain the legacy road-pair connector mapping.

## Ordered stages on a TrafficSignal

```json
{
  "startTick": 0,
  "program": {
    "control": "GapActuated",
    "maxGapSeconds": 3.0,
    "extensionSeconds": 2.0,
    "checkIntervalSeconds": 1.0,
    "stages": [
      {"durationSeconds": 20, "minDurationSeconds": 5,
       "maxDurationSeconds": 30, "green": [101], "detectors": [501]},
      {"durationSeconds": 3, "yellow": [101]},
      {"durationSeconds": 10}
    ],
    "movements": [
      {"origin": 101, "nodeId": 20, "incomingLinkId": 10,
       "outgoingLinkId": 30, "laneIds": [1]}
    ]
  }
}
```

Stages run in array order and wrap. Unlisted movements are red. Use `Fixed` for nominal stage durations, including multiple green windows and yellow stages. For `GapActuated`, each stage runs at least its minimum; recent eligible detector occupancy extends a green stage up to its maximum. A stage without demand ends at its minimum. Optional `initialStage` and `initialElapsedSeconds` define initialization explicitly. All evolving stage and detector state is checkpointed. Controller updates are batched by node and approach road at one effective tick; legacy single updates remain supported. Stage transitions are reported by default; optional `reportMovementChanges: true` also emits individual movement-aspect reports for detailed debugging.

For signal actor ID 40, the approach Link includes:

```json
{
  "detectors": [
    {"id": 501, "controllerId": 40, "laneId": 1, "positionMeters": 80}
  ],
  "signalControls": [{"targetLinkId": 30, "laneIds": [1]}]
}
```

Each logical detector has one simulation-wide ID. A source internal lane shared by multiple native connector copies may use the same ID on those copies; their occupancy observations are merged by the controller. The controlled Node includes:

```json
{
  "movementSignals": [
    {"incomingLinkId": 10, "outgoingLinkId": 30, "origin": 101, "laneIds": [1]}
  ]
}
```

Include the controlled Nodes in the signal actor’s dependencies and the normal incoming Links in the Node’s dependencies, as in other actor inputs. Empty `laneIds` controls all approach lanes. Controls must not overlap with conflicting aspects for the same road-turn/lane. Link signal controls initialize red. A change effective at runtime tick t governs the following physics interval; the batch ending at t uses the preceding aspect. Yellow stops vehicles that can brake before the stop line and allows vehicles already too close to clear. A signal for one turn does not stop a different green turn.

Detector positions describe front-bumper crossing and body occupancy; optional `lengthMeters` defines an area instead of a point. Links publish occupancy times after integrating a tick without awaiting a response. Controllers consume strictly earlier-tick observations, retaining two timestamp cohorts so same-tick mailbox order cannot affect a decision. Choose `Simulation.tickDuration` and each Link's physics settings consistently. The converter option `--tick-duration 0.1 --micro-step 0.1 --sumo-step 0.1` selects a 0.1 s native runtime clock and physics interval. Native departures and horizon use scaled integer ticks; stage durations remain physical seconds. A 0.1 s physics step with a 1 s runtime tick does not provide 0.1 s signal reaction.

## Entry admission on a Link

```json
{"microEntry": {"enabled": true, "avoidWeavingConflicts": true}}
```

Queued entry preserves the requested position. If its lane lacks physical room, the Car waits
outside the Link rather than being inserted at an earlier coordinate. On short roads, conflicting
required lane movements also wait when vehicle length, braking clearance and adjacent-change
cooldown leave insufficient maneuvering distance. Legal independent lanes remain usable. Entries
resume as the Link's ordinary physics ticks release room; no timeout, overlapping lane swap,
rerouting or position reset clears the queue.

The pending sender, request sequence, request tick and typed payload belong to `LinkState`, so
checkpoint restoration retains the delayed reply. After a real admission wait, carried speed and
physics remainder are cleared. Origin delay contributes to `departDelay`; continuation delay
contributes to trip waiting time. A Car still queued at the horizon is reported as not departed.

Existing native inputs default to disabled admission. The converter enables it for new MICRO
imports with `--micro-entry queued`; use `--micro-entry legacy` to reproduce earlier clamped
admission. The current queue supports Car and cannot be combined with retained upstream spillback
footprints. It does not establish generalized multi-lane spillback or SUMO insertion equivalence.

## Runtime clock in native inputs

For a 600-second MICRO scenario with a 0.1-second runtime tick, set these fields in
`simulation.json`:

```json
{"startTick": 0, "endTick": 6000, "duration": 6000, "timeStep": 1, "tickDuration": 0.1}
```

Each MICRO Link uses `microTimeStep: 0.1` and `microTicksPerGlobalTick: 1`. A Car
departing at 12.3 seconds uses `startTick: 123`. Signal-program durations,
`initialElapsedSeconds`, detector positions and lane-change cooldowns retain their physical
units; avoid multiplying those durations by ten. The fractional-clock runtime currently
supports MICRO Car/Link/Node/TrafficSignal with static routes and no plugins or model births.

## SUMO import and comparison limits

`tools/sumo-conversor/convert_sumo.py --lane-changing mobil --signals preserve` writes these same native fields. New MICRO imports select `mobil` by default; MESO selects `disabled`. Explicit `--lane-changing disabled` is a fixed-lane experiment and can block routes requiring a lane change. Existing native input files that omit `laneChanging` remain disabled. The converter imports sequential static and basic gap-actuated programs, lane-specific movement aspects, minimum/maximum durations and eligible approach detectors. Custom controller conditions, unsupported parameters, branching transitions and nonzero actuated offsets fail with a diagnostic rather than silently substituting a program. Static offsets become explicit initial stage/elapsed time. Signals disabled by `--signals off` are disabled in both inputs.

The native controller is a gap-actuated model, not a complete implementation of SUMO's controller. Detector placement follows unique upstream lanes, including internal lanes, and stops at controlled predecessors. It preserves SUMO's legitimate entry clamps at merges or signal boundaries. Source `passing-time` (default 1.9 s), `detector-gap` and `max-gap` are supported. Sensors on internal lanes outside the selected native connectors are explicitly omitted and counted. `detector_map.json` records source paths and native projections; `audit_detector_geometry.py` compares the instantiated positions against live SUMO loops. Permissive green conflict priority and unsignalized priority are not implemented. Multiple destination lanes for one incoming-lane turn use the first legal destination and its signal aspect; differing alternatives are reported. Approach turn-speed limits remain shared per next road and use the minimum across its lane variants. HTC's MOBIL is not SUMO's LC2013, so equal lane-change trajectories are not asserted. Keep these model differences visible in current comparisons and use fresh validation data for these traffic revisions; the frozen v1/v2 scientific protocols remain unchanged.

## Engineering verification (2026-11-14)

The focused Scala suite passes 112 tests, including old signal windows, per-turn signals, movement-specific interval timing, lane-change gap/braking checks, deterministic arbitration, checkpoint restore, delayed capacity grants and independent-peer Kryo decoding. Converter, comparator and audit tests pass 36 tests with one optional dependency skip.

The separate native-control acceptance protocol (`scripts/scenario/native_traffic_controls_protocol_v2.json`, local record) was fixed before generating reserved seeds 20639 and 20663. Both use the same frozen executable. Each passes all acceptance checks: 60 completed vehicles in each simulator, zero SUMO collisions or teleports, exact HTC route/distance accounting, valid stage intervals, 40 strategic lane changes; the two cases record 15 and 11 demand-driven extensions. The retained results are in `tools/sumo-conversor/output/native-traffic-controls-acceptance-v2-20261114.json` at the platform root; per-case inputs, source execution records and audits are retained beside it.

These are bounded operational checks. They do not establish equivalence to LC2013, empirical validity, dense origin-insertion behavior or a universal travel-time error bound. The Berlin current-run report presents its own measured outcomes and remaining differences separately.

The latest completed Berlin pair uses a 0.1 s runtime and physics clock over a five-hour horizon. Both simulators complete all 5,000 selected passenger vehicles. Source following parameters and all 5,000 recorded driver speed factors are imported. Route, native-control and live detector audits pass, with zero departure-position mismatches and maximum internal accounting error 4.37e-11 m. Travel-time MAE is 24.492 s, distance MAE is 0.626 m, and HTC durations average 7.330 s shorter. The [current-only report](../../tools/sumo-conversor/output/berlin-5000-driver-v7-20261115/report/REPORT.html) records this completed v7 executable/input pair. It predates optional sigma-enabled driver randomness and does not measure that revision.

## Finer-clock and detector acceptance

Protocol v3 (`scripts/scenario/native_traffic_controls_protocol_v3.json`, local record) was frozen before reserved seeds 20807 and 20831. Both short-approach scenarios run a 0.1 s runtime and physics clock. Each completes all 60 vehicles in both simulators with zero SUMO collisions/teleports, exact imported route accounting, valid native stage intervals, 40 strategic changes and three source-matching detector positions including one upstream internal lane. The cases record 11 and 13 actuated extensions. The acceptance record (`scripts/scenario/native_traffic_controls_acceptance_v3_20261114.json`, local record) binds criteria, executable and case paths. These are operational checks with no travel-time acceptance threshold.

The converter/comparator suite now passes 49 tests with one optional dependency skip; the focused fractional-clock, signal and lane-physics Scala suites pass 40 tests. Production engine code is unchanged by this import revision. The frozen scientific studies above retain their original scope and results.


## Queued-entry acceptance

The v5 protocol (`scripts/scenario/native_traffic_controls_protocol_v5.json`, local record) was frozen before fresh reserved crowded-origin cases 21201 and 21221. Both complete 60 vehicles in both simulators, with zero SUMO collisions/teleports, exact native departure positions, distance accounting below 1e-6 m, valid native stages, queued entries and measured origin delays. The acceptance record (`scripts/scenario/native_traffic_controls_acceptance_v5_20261114.json`, local record) pins their executable and evidence. The inspected Berlin cohort and v4 cases are development/regression data.

Focused checks pass 66 Scala and 57 Python tests. A broader run had 19 reporting-assertion failures in five unrelated suites; all 19 reproduce against the original frozen engine. One additional new stale-ack test expectation was corrected, and its focused rerun passes. See the retained executable [verification record](../../tools/sumo-conversor/output/native-entry-v5-execution-20261114/verification.json). These limitations are separate from the passing traffic checks and do not reclassify the frozen scientific v1/v2 campaigns.

## Per-Car MICRO following parameters

Car inputs accept optional `microMinGap` (nonnegative metres), `microReactionTime` (positive
seconds) and `microMaxSpeed` (positive m/s). Omitted/null values retain native defaults:
2 m, 1 s and each road's speed limit. With the default speed factor of one, the maximum speed is capped by the road limit; the driver-factor option below adjusts that desired road limit.
These values are sent on every MICRO handoff and stored in the lane vehicle and pending
entry payload; ordinary checkpoint/rollback restoration retains them. No actor-local
random state or reply obligations are introduced.

Following, insertion spacing, retained-exit spacing and lane-change safety use these driver
parameters. Lane-change safety also retains the Link's configured gap as an additional floor;
target rear-gap checks use the target follower's parameters. The virtual signal obstacle is
shifted by the follower's gap, keeping its front-bumper stop line unchanged. Car MICRO state
and ideal free-flow travel-time reporting use the same vehicle speed cap. `microReactionTime`
is the following model's time parameter; it does not emulate SUMO action-step timing.

The SUMO converter defaults to `--vehicle-parameters source`, importing `minGap`, `tau` and
`maxSpeed` including their source defaults. `--vehicle-parameters legacy` preserves the earlier
mapping. Driver stochasticity, speed distributions and the native-vs-LC2013/controller/priority
limitations remain. The published Berlin v5 comparison predates this change and stays unchanged.
Fresh mixed-profile operational acceptance is defined by
v6 (`scripts/scenario/native_traffic_controls_protocol_v6.json`, local record); it does not assert scientific
or SUMO algorithm equivalence.


## Mixed-driver acceptance (2026-11-15)

The v6 acceptance record (`scripts/scenario/native_traffic_controls_acceptance_v6_20261115.json`, local record)
passes both fresh reserved seeds 21301 and 21323. Each has 60 vehicles in three explicitly
assigned source types, with gaps 2/3/4 m, reaction times 1/1.2/1.8 s and speed caps
55.55/7/9 m/s on 10 m/s roads. Both simulators complete every vehicle. The native entry audit
checks 260 entries per case against the imported source settings; all 94,323 recorded physics
updates and entry/exit speeds respect the vehicle/road caps. Route, native-control and live
SUMO detector audits pass; native distance-accounting error is at most 1.14e-13 m. SUMO reports
zero collisions and teleports. These observations do not establish absence of every possible
native conflict or SUMO trajectory equivalence.

Validation passes 76 focused Scala tests in ten suites, 60 converter/comparator Python tests
and assembly. The frozen executable, build/test logs and acceptance driver are retained in
`tools/sumo-conversor/output/native-vehicle-v6-execution-20261115/`. The existing Berlin
5,000-vehicle comparison has not been rerun with this executable. Its results therefore remain
evidence for v5 only. The earlier unrelated full-suite reporting failures are recorded in v5;
a full-suite rerun is not claimed here.

## Driver speed factors and desired maximum speed

Car inputs additionally accept `microSpeedFactor` (positive, default 1) and
`microDesiredMaxSpeed` (optional positive m/s). Each road uses
`min(microMaxSpeed, microSpeedFactor * roadSpeed, microSpeedFactor * microDesiredMaxSpeed)`,
ignoring an omitted cap. The road limit is in m/s after conversion from Link km/h.
A factor above one permits a desired speed above the posted limit; the technical vehicle
maximum is never multiplied. This follows [SUMO's speed-limit semantics](https://sumo.dlr.de/docs/Simulation/VehicleSpeed.html).
Admission, lane following, lane-change safety, approach turn braking, Car state and ideal
travel-time reporting use the same calculation. Turn limits imported from internal-lane
speeds are also adjusted by the driver factor. Pending requests, lane snapshots and Car
checkpoints retain both fields. Remainder transfer rejects roads shorter than the resulting
driver-adjusted speed step, preserving the existing bounded boundary contract.

The converter's `--speed-factors fixed` default imports explicit per-vehicle factors or
scalar type factors with zero deviation. Other source distributions remain flagged.
`--speed-factors legacy` retains the previous factor of one. Existing JSON experiments
without `conversion.speed_factors` explicitly select legacy behavior.

For paired comparisons, use `--vehicle-parameters source --speed-factors reference`.
This raises SUMO output precision to 12 decimal places and marks the native package as
awaiting factor resolution. After a successful reference execution, run
`tools/sumo-conversor/resolve_speed_factors.py --scenario <package>` before starting HTC;
`run_experiment.py` inserts that stage automatically, and `convert_sumo.py --run-sumo`
resolves it after its reference run. Resolution requires verified source inputs/output,
at least ten decimal places, and one finite positive recorded factor for every selected
vehicle. It validates all native shards before changing them and retains hashes and the
factor map in `speed-factor-resolution.json`. Unfinished/uninserted source vehicles without
a recorded factor fail explicitly. Repeated resolution verifies its existing receipt.

Only the source driver's preassigned descriptor is transferred. Travel times, trajectories,
arrival outcomes and lane choices are not fitted, and the source SUMO demand is unchanged.
The shared factors match at recorded output precision; driving randomness (`sigma`), lane
and controller algorithms, priority and unsupported source effects remain separate gaps.
Operational checks are frozen separately in
v7 (`scripts/scenario/native_traffic_controls_protocol_v7.json`, local record). Earlier Berlin and
scientific/operational records retain their original executable and scope.


## Paired-speed acceptance (2026-11-15)

The v7 acceptance record (`scripts/scenario/native_traffic_controls_acceptance_v7_20261115.json`, local record)
passes fresh reserved seeds 21401 and 21419. Each completes all 60 vehicles in both simulators,
with zero source collisions or teleports. Native audits check 520 entries and 97,177 physics
updates against the recorded driver-adjusted limits. Source factor descriptors and native
factors match; the source demand hashes are unchanged. Route, native-control and live detector
audits pass, with maximum native accounting error 1.14e-13 m. Reported route-distance MAE is
zero in both cases. Current per-case travel-time MAE is 9.278 s and 15.022 s; neither was used
as a tuning target or acceptance threshold. These small fixtures do not measure Berlin-wide
agreement or establish SUMO algorithm/empirical equivalence.

Validation passes 78 focused Scala tests across ten suites, 68 Python tests and assembly.
The pinned executable, test/build logs, acceptance driver and verification receipt are retained
in `tools/sumo-conversor/output/native-speed-v7-execution-20261115/`. The earlier full-suite
reporting limitations remain documented with v5; a full-suite rerun is not claimed here.
A fresh same-cohort Berlin run is configured in
`tools/sumo-conversor/experiments/berlin-5000-driver-v7.json`, with source factor resolution
before HTC and final native input hashing. This v7 run completed all 5,000 vehicles in both
simulators; the current report is linked above. It does not include the later v8 driver randomness.


## Seeded driver imperfection

Native Car inputs accept `microSigma` in [0,1] (default zero) and a signed 64-bit
`microRandomSeed` (default zero). Sigma zero follows the exact previous deterministic path.
For positive sigma, every MICRO physics step applies bounded Krauss-style dawdling to the
already constrained deterministic velocity. The reduction is
`sigma * min(candidateVelocity, maxAcceleration) * deltaSeconds * uniformDraw`.
It is clamped so it cannot increase the deterministic candidate or add braking beyond the
normal deceleration bound when that bound is compatible with the deterministic cap. The
low-speed form preserves starts from rest; the physical stop line and exit velocity contracts
remain unchanged. Acceleration reports use the actual resulting velocity change.

The draw is a stateless 53-bit uniform value keyed by the configured seed, logical vehicle ID
and absolute physics-step index. It uses a SplitMix-style integer mixing function, with no
shared mutable RNG or actor-local sample counter. Lane/Link assignment, shard placement,
message ordering and checkpoint/replay do not consume a different stream. Both seed and
sigma travel in each entry request, pending-entry checkpoint and lane/Car snapshot. The
physics-step index accounts for all substeps within a runtime tick. Equal physics intervals
grouped into different runtime ticks therefore use the same draws. Native files explicitly
set `microRandomSeed` to choose another stream; the SUMO importer sets it from `--seed`.

SUMO import is opt-in with `--driver-randomness source`, or JSON experiment
`conversion.driver_randomness: "source"`. The converter imports source `sigma` (passenger
default 0.5); `disabled` and omitted experiment options preserve earlier inputs. Positive
sigma currently requires source `sigmaStep` to equal the HTC physics interval. A longer
sample-hold cadence fails explicitly. Action-step scheduling and junction-specific sigma
variants are not reproduced by this option. The report replaces the unmapped-sigma gap
with an explicit native-stream difference.

The dawdling rule follows the per-step form in
[SUMO 1.27.1's Krauss implementation](https://github.com/eclipse-sumo/sumo/blob/v1_27_1/src/microsim/cfmodels/MSCFModel_Krauss.cpp).
HTC's counter stream differs from SUMO's vehicle RNGs; setting the same sigma and seed does
not reproduce SUMO samples or trajectories. The underlying following/safety, lane-change,
priority and controller models retain their documented differences. The separate
v8 protocol (`scripts/scenario/native_traffic_controls_protocol_v8.json`, local record) fixes fresh
operational checks and an exact repeated native trajectory before inspecting their results.

LC2013 is the direct compatibility target for SUMO lane choices, while MOBIL is a general
acceleration-based incentive/safety model. Neither is established as universally more
accurate by this comparison. Selecting between them requires calibration and validation
for the intended traffic conditions. See the original
[LC2013 description](https://elib.dlr.de/102254/) and
[MOBIL paper](https://akesting.de/download/MOBIL_TRR_2007.pdf).


## Driver-randomness acceptance (2026-11-15)

The v8 acceptance record (`scripts/scenario/native_traffic_controls_acceptance_v8_20261115.json`, local record)
passes both fresh reserved seeds 21501 and 21523. Each completes all 60 vehicles in both
simulators, with source sigma profiles 0, 0.5 and 1. Source sigma and native seed values
match every observed native admission. The audits check 520 entries and 97,882 physics
updates against each driver's configured limits; route, signal and live detector checks
pass. Source collisions and teleports are zero; native path-accounting error is at most
1.14e-13 m. Distance MAE is zero in both small cases. Current travel-time MAE is 19.517 s
and 7.277 s respectively, with no travel-time tuning or acceptance threshold.

The first case is also rerun with identical native inputs and executable. All 49,294
normalized micro-update observations match exactly, including position, velocity,
acceleration, lane and tick. Source/native RNG samples are not expected to match. This is
bounded operational and reproducibility evidence, not empirical traffic validation.
Validation passes 86 focused Scala tests in eleven suites, 71 Python tests and assembly.
The executable, logs, acceptance driver and verification receipt are retained in
`tools/sumo-conversor/output/native-random-v8-execution-20261115/`.

The same-cohort Berlin configuration `tools/sumo-conversor/experiments/berlin-5000-random-v8.json`
is prepared with source sigma enabled and a verified reuse of the identical completed v7
SUMO reference. It has not been started. The linked completed 5,000-vehicle v7 report remains
the latest full-network measurement; its metrics do not measure stochastic HTC driving.


## Selectable native LC2013-style strategy

Select `laneChanging.model: "Lc2013"` on native MICRO Links, or
`convert_sumo.py --lane-changing lc2013`. Existing native inputs remain disabled and the
converter's existing default remains MOBIL. `Mobil` and `Lc2013` are separate strategies.
This is a bounded native implementation of LC2013's motivation hierarchy, not a full port
of SUMO's model. Reports retain `native_lc2013_is_supported_subset` as an explicit difference.

The policy gives priority to next-road strategic preparation, then local cooperative clearing,
accumulated relative speed advantage, and sustained free-flow keep right. It uses native
Krauss gap/braking checks for both the changing vehicle and target follower. Changes are
adjacent and atomically arbitrated by stable vehicle ID; restricted lanes and overlapping
swaps remain forbidden. Opposing strategic moves can request bounded local yielding;
queued short-road admission prevents incompatible weaving from entering without clearance.
It adds no actor round trip or timeout.

Optional per-Car `microLc2013` parameters are `strategic`, `cooperative`, `speedGain`,
`keepRight`, `lookaheadLeft`, `speedGainRight`, `speedGainRemainTimeSeconds` and
`keepRightTimeSeconds`. Defaults are 1, 1, 1, 1, 2, 0.1, 20 s and 5 s. Strategic weight
scales lookahead; speed-gain weights scale accumulation thresholds; keep-right weight scales
its time condition. Positive cooperative weight enables native clearing/yielding, rather than
reproducing SUMO's full continuous cooperation response. Zero disables the corresponding
motivation. Parameters travel in every entry and pending-entry checkpoint. Gain, keep-right
and lookahead-speed memory lives in `VehicleInLane.lc2013Memory`, survives Link checkpoints,
and resets on a completed lane change or road handoff.

The converter imports matching `lcStrategic`, `lcCooperative`, `lcSpeedGain`, `lcKeepRight`,
`lcLookaheadLeft`, `lcSpeedGainRight` and `lcSpeedGainRemainTime`. Unsupported source `lc*`
parameters and source lane models other than LC2013 are counted separately. It retains the
source SUMO settings and routes. Shared native cooldown/gap settings still apply.

Limits include next-road-only lookahead, road-local memory, Link-wide speed limits,
approximated cooperative advice, and native safety/arbitration. SUMO's multi-road best-lane
search, blocker messaging, lateral motion, roundabout/opposite-direction rules and full
source parameter semantics are not implemented. Matching the model name therefore does
not establish trajectory equivalence. See the [LC2013 paper](https://elib.dlr.de/102254/)
and [SUMO 1.27.1 implementation](https://github.com/eclipse-sumo/sumo/blob/v1_27_1/src/microsim/lcmodels/MSLCM_LC2013.cpp).
The separate v9 protocol (`scripts/scenario/native_traffic_controls_protocol_v9.json`, local record)
fixes operational checks before inspecting fresh reserved cases.


## Native LC2013-style acceptance and Berlin run (2026-11-15)

The v9 acceptance record (`scripts/scenario/native_traffic_controls_acceptance_v9_20261115.json`, local record)
passes fresh reserved seeds 21601 and 21623. Each simulator completes all 60 vehicles in each
case. Every road selects the new native strategy, source weights/sigma/seeds match admissions,
and route, control and live detector audits pass. Audits cover 520 native entries and 98,879
physics observations. Source collisions/teleports are zero; native accounting error is at most
1.14e-13 m. The first native case is repeated with identical inputs: all 49,446 normalized
trajectory observations match exactly. These fixtures exercise runtime integration and required
lane preparation; independent focused tests exercise all four motivation branches. They do
not establish full SUMO LC2013 equivalence or empirical lane-changing validity.

Validation passes 95 focused Scala tests in twelve suites, 72 Python tests and assembly.
The frozen executable and evidence are retained under
`tools/sumo-conversor/output/native-lc2013-v9-execution-20261115/`.
The same-cohort 5,000-vehicle Berlin experiment has been started using
`tools/sumo-conversor/experiments/berlin-5000-lc2013-v9.json`. It enables source driver sigma,
paired source speed factors and the native LC2013-style subset, with the unchanged 0.1 s
clock and five-hour horizon. The completed SUMO reference is reused only after all original
input, demand, effective-configuration, version and output-hash checks. Progress and eventual
current-only report are in `tools/sumo-conversor/output/berlin-5000-lc2013-v9-20261115/`.
The earlier full v7 metrics remain the latest completed result until this run finishes.

### Short-road exit-compatible blocker release (2026-11-16)

The fresh 5,000-car Berlin development run reached its horizon with 139 unfinished
cars on 14 short multi-lane roads. An isolated production-physics reproduction
identified a cooperation bug: a wrong-lane requester could hold an adjacent
exit-compatible car stationary. On a short road that car could no longer drive
forward to create the merge gap. Native LC2013 cooperation now gives the compatible
car clearance priority; the requester yields instead. Signal checks, safe gaps,
adjacent changes and permitted exit lanes still apply. Other competing strategic
requests keep deterministic ID arbitration. MOBIL and disabled behavior are unchanged.

Regressions cover the 14 imported road geometries, both ID orders, zero/nonzero
sigma, red-to-green transitions and legal safe exits. Their initial stopped
positions are synthetic reproductions, not final-state snapshots. The separate
v10 operational protocol (`scripts/scenario/native_traffic_controls_protocol_v10.json`, local record)
freezes two fresh 60-car seeds and an exact native repeat before the new full
Berlin rerun. These are engineering checks; full-network completion and measured
travel-time differences must come from the rerun, and empirical validity remains
outside their scope.

The external process monitor also writes Prometheus samples incrementally in its
existing JSON format, avoiding retention and final serialization of all raw
responses in memory. Sampling failures remain explicit, and JVM teardown runs
even if monitor finalization fails. This fixes a concrete resource risk; it does
not establish the cause of the previous monitoring process's SIGKILL. Native
traffic reporting cadence is unchanged in the lane-release experiment.


### Route-aware junction destination lanes

Node inputs may set `routeAwareJunctionLanes: true` (default false). Multiple `junctionMovements`
may share an incoming road, outgoing road and incoming lane when their outgoing lanes differ.
Each movement retains its own connector and optional `continuationLanes` list of
`{outgoingLinkId, fromLane, toLane}` connections on the outgoing road.

Car requests include `followingLinkId` from their remaining route. Selection minimizes changes
needed for that following turn, then prefers available connector capacity and lane alignment,
with stable lane/connector IDs resolving ties. Only the actual approach lane's legal movements
are eligible. Without following-turn data, capacity and alignment still apply. Legacy Nodes
preserve first-match selection. Destination-lane and connector choices are stored in the existing
pending capacity request, so restore/replay does not reselect them.

This is a native bounded policy. It does not observe downstream lane occupancy, implement full
SUMO best-lane planning or change signal conflict rules. The SUMO importer keeps alternatives
with different signal patterns restricted until destination-specific signal control is supported.

Operational acceptance: destination-lane protocol (`scripts/scenario/native_destination_lanes_protocol_v1.json`, local record)
and results (`scripts/scenario/native_destination_lanes_acceptance_v1_20261116.json`, local record).
The two reserved seeds each complete 30 trips in SUMO and HTC, with every HTC outgoing-road
entry lane matching its following turn. Route/control/detector audits pass; 77 focused Scala tests
and 75 Python tests (three skipped) pass. These are bounded engineering checks. The existing
5,000-car Berlin v10 report predates this option and remains unchanged.
