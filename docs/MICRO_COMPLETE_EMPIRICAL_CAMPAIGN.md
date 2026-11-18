# Complete microscopic empirical campaign

Status, 2026-11-18: **in progress; complete empirical validity has not been established**.
The requested deliverable covers autonomous microscopic traffic, not only conditional
following. The earlier prospective Peachtree component study was inconclusive and
does not complete this campaign. Its protocol and results remain frozen.

The [implementation gaps and evaluation requirements (Portuguese)](MICRO_EMPIRICAL_GAPS_AND_ACCEPTANCE.pt.md)
define the E01–E06 evaluation stages and G01–G07 implementation backlog, including
code ownership boundaries, dependencies, architectural contracts and acceptance checks.
They distinguish missing mechanisms from existing capabilities that still need field
evaluation; the proposed mechanisms remain unimplemented.

## Coverage and outstanding work

| Required behavior/evidence | Native capability audited | Empirical acceptance status / next work |
| --- | --- | --- |
| Following and free flow | Krauss, optional heterogeneous parameters and seeded dawdling | Conditional Peachtree study has 9 following episodes against a fixed target of 10; insufficient evidence. Autonomous following remains required. |
| Correct measured initial state | Position existed; optional first-admission speed and lane added in this change | Four actor subjects preserve position, speed, lane and departure tick exactly; independent validation remains required. |
| Signal response, queues, discharge, lost time | Native staged signals, lane/movement binding and stop-line physics exist | Original Peachtree signal files recovered. First autonomous approach diagnostics expose early departure; surveyed geometry and complete approach traffic must be resolved before calibration. |
| Lane changes and lane-specific discharge | MOBIL and a documented LC2013 subset exist | Observed maneuvers, preparation distance, completed/failed changes, lane counts and gap acceptance must be measured. Implementation presence is not empirical validation. |
| Finite intersection traversal | Fixed connector Links and Node movement/lane bindings exist | Recover lengths and lane connectivity from source CAD; avoid double-counting connector distance. Independent turning trajectory/time evidence is outstanding. |
| Unsignalized and permissive crossing conflicts | **Missing general movement priority and gap acceptance** | Design and implement checkpointed Node conflict control and causal approaching-vehicle observations, then runtime/physics checks and independent empirical evaluation. Separate protected from permissive movements. |
| Downstream blocking and spillback | Bounded single-lane cross-link following and retained-footprint spillback exist | **General multilane spillback and its combination with queued origin admission are missing.** Implement lane-specific boundaries/handoffs before using crowded corridor cases. |
| Autonomous corridor/network behavior | Car/Link/Node/signal runtime exists | Entire observed traffic and boundary demand must be represented. Flow, queue, travel-time and unfinished-vehicle coverage remain open. A short approach with an empty sink cannot establish network validity. |
| Runtime reproducibility | Conservative and Time Warp supported in the documented fractional-clock scope | First observed-signal actor integration agrees across policies, including rollback. Full scenario and distributed-policy checks remain necessary for each added mechanism. |
| Independent statistical conclusion | Exposure registry and frozen protocols exist | Previously inspected NGSIM records are development data. Reserve a genuinely uninspected recording/cohort before further final evaluation. No lowered cohort targets or retrospective acceptance gates. |

Here “complete MICRO” means the documented Car/Link/Node/TrafficSignal scope. It does
not silently claim empirical validity for pedestrians, buses or other vehicle classes.
If an evaluated scene has material pedestrian/cycle interference, either model that
interaction with an explicit supported implementation or report the scene as unsupported;
removing those participants cannot support a full-scene validity claim.

## Work completed after the scope correction

### Native initial conditions

`CarState.microDepartSpeed` (m/s) and `microDepartLane` (zero-based native lane) are optional,
nonnegative fields. Speed must be finite. They enter the existing Movable admission
protocol only before the first accepted MICRO departure. Later road admissions preserve
physical exit velocity and Node-selected lanes; a connector does not inherit an origin
lane. Real admission queues retain their existing stopping semantics and ACK the actual
admitted state. No actor-local behavioral state, new message round-trip or shared RNG
was introduced. Old JSON inputs retain their defaults.

Validation: 31 tests across Car departure, Car MICRO handler/reply, entry admission and
signal program suites pass; assembly succeeds. New departure tests include first versus
subsequent admissions, connector handling, legacy defaults, JSON numeric inputs,
invalid values and independent cold Kryo codecs.

### Actual Peachtree signal and geometry sources

Recovered the official support members from the
[USDOT aggregate Peachtree archive](https://data.transportation.gov/api/views/8ect-6jqj/files/7244a8b5-077f-4851-bc06-3cfc7a75f0b0?download=true&filename=Peachtree-Street-Atlanta-GA.zip).
The former invalid support attachment is not evidence that no measured signals exist.
The archive contains valid signal timing, CAD, GIS, analysis, tube-count and aerial
support packages. Selective HTTP ranges retrieved signal/CAD/GIS/analysis members without
opening the aggregate trajectory CSV. Range receipts, source SHA-256 and archive CRCs
are retained locally.

All 32 original tab-separated signal files were checked against their 32 converted CSV
counterparts: exact agreement. `ngsim_observed_signals.py` handles missing initial/final
cells and zero-duration greens without inventing a phase interval. Unknown initial
aspects are rejected. It compiles observed frame changes into the existing Fixed
`SignalProgram`; a final extra frame prevents cycling within the inclusive horizon.
Six parser/compiler regression tests pass. Frame numbers use the documented 0.1 s
trajectory clock. Through and arrow aspects must not be confused with complete legal
permission for a permissive turn.

The reusable `fetch_ngsim_support.py` reproduced all four support package hashes and
also retrieved tube data: five packages, 2,149,361 HTTP response bytes, no aggregate
trajectory payload. It validates source identity, exact Content-Range, outer CRC,
nested CRC and extraction paths. The tube package has 30 original files with 8,640
rows covering November 7–9, including the trajectory day. Preserve the published
values: headers/metadata describe vehicles/hour while rows are 15 minutes apart.
Aggregation semantics and station alignment need checking before treating these
numbers as counts or rates for a flow score.

The DWG was converted with GNU LibreDWG commit
`3d439037f2d31edca68267dd22e8428637e12220`. The DXF contains 659 polylines, 15 text entities
and four inserts. `$INSUNITS=0`; units and surveyed stop-line interpretation must be
verified, despite the close visual overlay with source global coordinates. The GIS
member contains camera-coverage polygons, not a lane graph. Do not advertise it as
a ready-to-run road network.

### Native observed-signal integration experiment

`ngsim_actor_approach.py` uses the canonical scenario output writer and actual
Car/Link/Node/TrafficSignal actors, static routes and a 0.1 s physical clock. Vehicles
receive measured initial states once; no observed leader forcing is used afterward.
`analyze_ngsim_actor_approach.py` retains every subject and approach observation,
including samples after an early simulated exit through absorbing boundary progress.
Three coverage/comparison tests verify that unknown entry lanes cannot shift
initialization forward, early exits retain later source errors, missing predictions
are counted and physical-event differences survive policy comparison.

The original frame 600–1600 development request is refused because source lane 11
is a left-turn bay that the two-through-lane scenario cannot represent. This is a
visible coverage rejection, not a discarded model failure. A smaller 600–1299
**engineering development window** was then run to exercise initialization and
recorded signals. It does not replace the complete approach/corridor experiment.

The excluded turn movements are an actual modeling requirement: vehicles 234, 236
and 1681 first have intersection left-turn labels at frames 1313, 1339 and 1369.
At all three frames the recorded left arrow is Red and through aspect is Green.
An arrow-only prohibition would prevent observed movements, while an unconditional
green would omit yielding. Verify legal permissive operation and conflicting demand;
the observations alone do not establish that each maneuver is lawful. Preserve raw
aspects separately from operational movement permission. See the
[left-turn audit](validation/micro-complete-20261118-left-turn-audit.json).

| Diagnostic | Result |
| --- | --- |
| Native subjects / source approach samples | 4 / 1,293 |
| Exact initial position, velocity, lane and tick | 4/4 |
| Missing approach predictions | 0 |
| Observed signal trace | Red at tick 0, Green at tick 543; native trace matches exactly |
| Conservative / Time Warp execution | Both complete; zero engine errors |
| Committed physical event comparison | 1,586/1,586 events exactly equal |
| Time Warp rollbacks | 31 |
| Absorbing boundary progress position RMSE | 5.08156 m, diagnostic only |
| Boundary timing error, source vehicles 75 / 95 | −6.9 / −6.2 s |
| Boundary timing error, source vehicles 222 / 224 | −0.9 / −1.4 s |

Executable SHA-256: `f53ac8e0fa4d4f4155aa0ea96dfb4c2448c1921ce222cec367642d484ea2835b`.
Native phase onset is correct, but the initial vehicles cross the adopted boundary
too early. This result does not establish whether the discrepancy comes from geometry,
missing initial context, stop behavior or departure response. No parameter was fitted
to eliminate it. The 43.75 m approach boundary and 56.32704 km/h speed cap are explicit
development assumptions, not verified surveyed/posting values. Downstream is an empty
300 m sink; lane changing is disabled; measured truck length uses Car dynamics.
There are no empirical pass/fail gates for this exposed-data engineering run.

Evidence directory: `../experiments/micro-complete-20261118/` in the platform workspace.
`actor-diagnostics.json` retains the per-subject evidence; each `actor-run-*` retains
runtime logs, JSON event output, effective runner config and executable hash.

## Implementation sequence and architectural decisions

1. Finish the measured lane graph and approach inventory, including left bays, turn
   permissions, physical stop lines and all initial/boundary traffic. Keep source
   coordinate transformation separate from behavioral calibration. Audit downstream
   obstruction and recording coverage before assuming a free sink.
2. Extend causal boundary observations to explicit lane paths. Preserve the existing
   single-lane defaults; require unambiguous incoming-lane → connector-lane →
   outgoing-lane mapping. Publish immutable per-lane occupancy and use snapshots
   strictly before integration tick t, preserving both tick cohorts. Lane-changing
   candidate safety needs the candidate lane's downstream leader, not only the current
   lane's leader. Removing the current single-lane guard alone is incorrect.
3. Add a stateless junction-control handler owned by Node. Its typed configuration
   describes conflict areas, movement precedence and separately calibrated critical
   gaps. Its checkpointed state must retain pending original request identities,
   occupancy/reservations and decision scheduling. A connector reservation lasts until
   actual clearance; a nominal free-flow timer cannot prove it has cleared. Gap
   acceptance requires causal approaching-priority observations. Mutual exclusion
   alone must not be described as gap acceptance. Keep protected signal clearance,
   capacity buffering and runtime request/reply accounting intact.
   A permissive approach must retain its stop-line footprint while Node decides
   whether a gap is safe; it must not first cross the stop line and only then ask
   for permission. Thus this integration depends on the lane-boundary/handoff work,
   rather than a conversion of a raw red arrow into an unconditional green.
4. Combine admission queues and retained footprints through an explicit two-stage
   handoff. Preserve the upstream physical footprint until actual downstream
   acceptance, release exactly once, and keep cancellation/replay identities in
   checkpointed state. Never remove the unsupported-combination guard until this
   contract, horizon behavior and multilane safety are tested.
5. Verify each mechanism with focused handler/physics tests, JSON and independent
   cold codecs, rollback/replay, admission denial and horizon censoring. Run actual
   actor scenarios under both supported policies, plus competing simultaneous
   movements and saturated downstream links. A watchdog or model timeout is not
   a repair for a missing reply obligation.
6. On development data, separate measurement errors, representational gaps and
   behavioral discrepancies. Then freeze model/configuration, observation operators,
   calibration budget, baselines, seed pairing, adequacy targets and acceptance
   criteria before opening reserved final data. Evaluate every required behavior;
   retain failed runs, unfinished vehicles and unsupported cases in the report.
7. Produce the final empirical report with uncertainty intervals clustered at the
   independent recording/episode level, sensitivity and parameter-identifiability
   checks, all-subject coverage, per-regime results and a bounded validity claim.
   Runtime agreement and SUMO agreement remain separate from agreement with reality.

## Independent data access

The official [SinD repository](https://github.com/SOTIF-AVLab/SinD) offers legacy
sample records and describes trajectories, maps and traffic-light information.
Its full dataset requires an access application. The official
[CitySim repository](https://github.com/UCF-SST-Lab/UCF-SST-CitySim1-Dataset)
lists signalized, unsignalized and permissive-left sites and signal/map assets.
These are candidate sources, not an acquired or adequate final cohort. Do not
inspect candidate trajectory outcomes before recording the final reservation and
protocol, and do not assume mixed-traffic interference is supported by Car-only
simulation. No access-request email has been sent.

## Reproduce the completed development stage

Run from the platform workspace. Keep the source directories immutable.

```bash
python3 hyperbolic-time-chamber/scripts/scenario/fetch_ngsim_support.py \
  --output experiments/micro-complete-20261118/fresh-support

python3 hyperbolic-time-chamber/scripts/scenario/ngsim_actor_approach.py \
  --raw experiments/micro-empirical-v3-20261118/heldout \
  --signal 'experiments/micro-complete-20261118/support/peachtree-street-signal-timing/signal-timing/signal-change/Original Text files/Peachtree_10th_NB_0400-0415.txt' \
  --output experiments/micro-complete-20261118/reproduce-input \
  --end-frame 1299 --boundary-m 43.75 --speed-limit-kmh 56.32704

python3 hyperbolic-time-chamber/scripts/scenario/run_cluster_validation.py \
  --jar hyperbolic-time-chamber/target/scala-3.3.5/hyperbolic-time-chamber-3.10.0.jar \
  --simulation experiments/micro-complete-20261118/reproduce-input/simulation.json \
  --output experiments/micro-complete-20261118/reproduce-run \
  --base-port 26600 --nodes 1 --timeout 180 --measure \
  --extra-config experiments/micro-complete-20261118/actor-reporting.conf
```

The extra config sets `htc.report-manager.kafka.micro-update-report-every=1`, which
controls emission of the common MICRO observation reports even for the JSON sink.
Use `--policy time-warp` with a separate input/output directory for the second run.
The executable is a development workspace build containing pre-existing local
settings changes as well as the departure fields. It is identified by its hash;
do not claim that this JAR is reconstructible from only the campaign commit.
