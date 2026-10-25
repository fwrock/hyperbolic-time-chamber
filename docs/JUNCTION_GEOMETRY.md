# Junction geometry — 2026-10-24

This page retains the original geometry-stage results. Fractional clocks and
boundary remainder transfer were subsequently implemented, followed by cross-link
leader observations. See [current MICRO status](MICRO_VALIDATION_STATUS.md),
[clock methods](MICRO_SCIENTIFIC_VALIDATION.md) and
[v2 following results](MICRO_CROSS_LINK_FOLLOWING.md) for the later scope and evidence.

The first stage models finite movement paths and turn-speed constraints. Conflicting
movement priority and gap acceptance are separate from geometry. Fractional runtime
crossing times remain a later stage.

## Runtime design

Each movement is a fixed, ordinary MICRO Link actor at the junction node, with a
metric polyline, explicit length and speed limit. NodeState maps an incoming/outgoing
road pair to that connector. The route planner continues producing road links;
Node admission inserts the selected connector as checkpointed pending route state.
A cyclic Bus route keeps its original cursor. Connector traversal contributes to
physical journey distance, speed, waiting and link reports through the same paths
as a road link. No new actor type, clock policy, coordinator or runtime birth is
introduced.

A vehicle requests its next road with its actual approach link ID. Node checks the
existing signal clearance, then reserves connector capacity. An immediate or delayed
grant includes the connector route item while retaining the original request
identity. A full connector buffers the original target, clearance and connector
selection in NodeState. Cancellation removes that same pending obligation. After
traversing the connector, the vehicle requests downstream road capacity through
the existing exchange; it does not re-enter the connector or recheck a completed
signal crossing. This additional exchange is necessary to obtain the next link's
capacity and cannot depend on an unanswered request to the waiting vehicle.

Approach links carry immutable target-road-to-exit-speed limits, and each lane
vehicle carries its selected limit. Braking therefore begins before entering a
slower turn. Geometry and capacity state use numeric IDs and remain serializable.
All execution state is restored with model snapshots/typed state; transport and
anti-messages cross the same owner acceptance ledgers. Output commits strictly
below GVT and completion still waits for sink receipts.

The opt-in representation defaults to no junction movements for existing scenarios.
Connectors are explicit scenario resources and city-map edge labels, with positive
lengths and finite metric shapes. A self-loop connector cannot be chosen to shorten
a road shortest path. This stage does not add turn costs to route choice, finite
vehicle conflict areas, cross-traffic priority, lane selection inside a multi-lane
junction, or sub-second cross-link scheduling.

Each configured movement adds one fixed Link LP, its lane state, geometry and
rollback history. It also adds a physical link admission/traversal and downstream
capacity exchange per crossing. Shape interpolation and movement selection are
linear in the number of shape segments and configured movements respectively.
The bounded fixtures establish correctness, not city-scale memory or throughput.

## Scenario contract

Road lengths end at the stop lines. The connector supplies the missing internal
distance; adding it to unchanged center-to-center road lengths counts the junction
twice. `validate_sumo_micro.py --junction-geometry` imports external lane lengths
after netconvert, then follows every internal lane in the selected SUMO connection.
Left turns can include a chain of internal lanes. Their lengths and shapes are
combined, and SUMO samples receive cumulative internal-lane offsets. This importer
requires one speed limit across that chain and rejects disconnected paths.

A native Node includes the connector ID in `links` and declares its movement:

```json
"junctionMovements": [
  {"incomingLinkId": 4, "outgoingLinkId": 6, "connectorLinkId": 9}
]
```

The connector uses an ordinary `hybrid.actor.Link` resource and city-map edge label,
with both node dependencies pointing to Node 2. Its LinkState contains:

```json
{
  "from": 2, "to": 2, "length": 7.0,
  "simulationMode": "MICRO", "lanes": 1,
  "speedLimit": 18.0, "freeSpeed": 18.0,
  "junctionGeometry": {
    "nodeId": 2, "incomingLinkId": 4, "outgoingLinkId": 6,
    "lengthMeters": 7.0, "speedLimitMps": 5.0,
    "shape": [{"x": 0, "y": 0}, {"x": 3, "y": 0}, {"x": 3, "y": 4}]
  }
}
```

The approach road's `exitSpeedLimitsMps` is `{"6": 5.0}`. Routes contain the
original road IDs 4 and 6; they must not include connector 9. Movement pairs and
connector ownership must be unique within the Node. Lengths and speeds must be
positive and finite; Link length/speed must agree with its geometry. Scenario
authors must keep the Node mapping, connector endpoints and approach speed map
consistent. Metric coordinates share the network's projected coordinate system,
not latitude/longitude. Rounded shape length can differ slightly from measured
lane length; interpolation normalizes arclength so both endpoints remain exact.

Connector entry/update/exit reports include `junction`, `junction_x` and
`junction_y`. Node emits `junction_connector_admitted` through `report()` with
vehicle, connector, target road and tick. No additional storage path is introduced.

## Protocol and replay audit

The access request remains consistency-critical. Node always replies with Red,
Full or an available grant, retaining the original request sequence. Red consumes
the awaited response and schedules a verified retry. Full keeps the same awaited
identity and a checkpointed FIFO obligation; a freed slot or green transition
issues the terminal grant. Cancellation matches the original road ID even though
the queue is keyed by connector. A grant installs the checkpointed pending
connector only after the stale-response guard accepts it.

`Movable.enterLink` consumes that pending connector before touching the road queue
or cyclic Bus cursor. Ordinary Link entry acknowledgment, micro updates and leave
completion resolve the same branches as road traversal. A Bus stop at the approach
node is not repeated on connector exit. Connector capacity registration/freeing is
fire-and-forget; the Node never waits for a vehicle to answer before granting it.

No runtime ownership, dispatch or report-commit boundary changes. The new fields
live in model state or message payloads; model output and transport use existing
positive/anti identities. Rollback restores the pending route, current-connector
flag, turn-speed limit and queued Node obligation. An anti-message retracts the
corresponding grant/admission report through ordinary history. Actor interning
copies the inherited fields, and private-vehicle reset clears them between trips.
Cold independent codecs exercise non-default approach/target IDs, connector grants
and metric coordinates. Production migration remains disabled.

## Acceptance

- Geometry/route-item validation, ordinary and delayed Node grants, capacity
  cancellation and checkpoint restoration, including a cyclic Bus cursor.
- Turn approach braking and connector speed/distance without duplicate credit.
- Independent cold-peer serialization of the added request, grant and lane state.
- Straight/left/right movements against SUMO with internal links enabled, matching
  compiled lane lengths, shapes and turn speeds. Do not infer internal distance
  from center-to-center road geometry or count it twice.
- Same-input conservative and optimistic report comparison, plus the existing
  point-node and mixed transit regressions. Check final physical outcomes and
  acknowledged storage completion.

SUMO's [intersection model](https://sumo.dlr.de/userdoc/Simulation/Intersections.html)
and [network format](https://sumo.dlr.de/docs/Networks/SUMO_Road_Networks.html)
describe the internal lanes used as the external geometry reference. Inputs,
commands, executable hashes and results must be retained before acceptance.

Accepted evidence is retained in
[`validation/junction-geometry-2026-10-24.json`](validation/junction-geometry-2026-10-24.json).
The full suite passes **635 Scala tests in 133 suites**, followed by six focused
cold-codec tests using non-default new ID fields. All **11 Python harness tests**
pass. The unchanged main executable's SHA-256 is
`ab7d501e8ed104c1511386bc36732f744fc763201bb9bd7079a948ea4c1a0045`.
Build log: `/tmp/htc-junction-build.log`; codec follow-up:
`/tmp/htc-junction-cold-codec.log`; raw campaign:
`/tmp/htc-junction-20261024/`. Temporary files can disappear after reboot; tracked
generators, acceptance scripts and the retained record provide reproduction.

All 12 real-SUMO runs pass, covering 52 completed cars under both policies. Two
mixed MICRO transit runs on two JVMs also pass: 39 connector entries/exits, 118
total Bus link entries and 117 completed exits (one road remains active at the
horizon), four completed Person schedules, seven walks, one Bus boarding and one
Subway boarding per run. The optimistic Bus run exercises 129 rollbacks. All
seven full committed report comparisons match, including all 2183 rows in the
mixed two-JVM case; only wall-clock/simulation IDs and SUMO summary CPU duration
are excluded by the existing comparator. Both execution policies reach their
acknowledged horizon completion before reporter shutdown; final owner ledgers
have matched transfers/receipts and no outgoing/accepting work. The long-running
Bus/Subway services are explicitly censored at the horizon.

The initial 240-second two-JVM conservative attempt expired during progressing
execution under parallel build/test load. Its incomplete output is excluded. A
fresh 600-second run completed in 111.43 seconds; the optimistic run completed in
128.50 seconds. These wall times are not performance benchmarks. Earlier
uncontrolled-priority diagnostics and the pre-boundary departure fixture are also
listed as excluded in the retained record.

The reference movement is protected: SUMO grants only the selected movement and
keeps other approaches red. Initial diagnostic uncontrolled-junction runs caused
SUMO priority braking that HTC does not yet model; those runs are excluded from
acceptance. The protected fixtures prevent that unrelated behavior from masking
geometry or timing differences. Signal fixtures add 30 seconds green/30 seconds
red with no yellow, matching the existing HTC signal contract. This validates
geometry and turn speed, not competing traffic or safety at shared conflict areas.

The real-SUMO comparison retains absolute timestamps, complete tripinfo durations,
continuous route distance and matching-link position metrics. Physical checks
require one connector per road-pair crossing, correct path coordinates, connector
and approach turn speeds, finite traversal time, zero artificial node delay,
matched carried velocity, exact distance and elapsed-link accounting, and valid
signal intervals. The existing bounded duration tolerance remains one global tick
per physical link plus one SUMO step; it is not a calibration target.

### Reproduction

Use fresh output directories and a local SUMO/netconvert installation:

```bash
sbt test assembly
python3 -m unittest discover -s scripts/scenario -p test_validate_sumo_micro.py -v
python3 scripts/scenario/validate_sumo_micro.py \
  --jar target/scala-3.3.5/hyperbolic-time-chamber-1.24.3.jar \
  --output /tmp/junction-left-de --case crossing --junction-geometry --turn left \
  --policy discrete-event --base-port 36900
python3 scripts/scenario/validate_sumo_micro.py \
  --jar target/scala-3.3.5/hyperbolic-time-chamber-1.24.3.jar \
  --output /tmp/junction-left-tw --case crossing --junction-geometry --turn left \
  --policy time-warp --base-port 37200
python3 scripts/scenario/compare_policy_reports.py \
  --conservative /tmp/junction-left-de/htc/reports \
  --optimistic /tmp/junction-left-tw/htc/reports --output /tmp/junction-left-parity.json
```

Repeat with `--turn straight` and `right`, `--case signal --turn right`, and
`--case signal-boundary --turn straight`. Omitting `--junction-geometry` runs the
previous point-node contract. Seed 42, six departures spaced ten seconds apart
(one departure at 12 seconds in the point-node boundary case and 13 seconds with
geometry), 0.1 second physics, whole-second runtime boundaries and a 180-second
horizon are fixed by the generator. The geometry boundary departure accounts for
the compiled approach's shorter stop-line length: SUMO crosses just before red,
while HTC rounds the same crossing to tick 30.

The native mixed regression is generated with:

```bash
python3 scripts/scenario/generate_walking_validation_scenario.py \
  --output-dir /tmp/junction-bus --mode MICRO --junction-geometry
python3 scripts/scenario/run_cluster_validation.py \
  --jar target/scala-3.3.5/hyperbolic-time-chamber-1.24.3.jar \
  --simulation /tmp/junction-bus/discrete-event.json --output /tmp/junction-bus-de \
  --nodes 2 --base-port 41100 --timeout 600
python3 scripts/scenario/validate_walking_reports.py \
  --simulation /tmp/junction-bus/discrete-event.json --reports /tmp/junction-bus-de/reports \
  --log /tmp/junction-bus-de/runtime.log --output /tmp/junction-bus-de/acceptance.json
```

Repeat for `time-warp.json`, then compare complete report multisets. The fixture
forces the cyclic Bus corridor through two opposite-direction 10 m connectors.
Expected completed Person distances are 800, 800, 1710 and 500 m, including a
410 m Bus ride, a 700 m Subway ride and 2700 m total walking. The validator checks
repeated connector traversal, incoming/outgoing road sequence, matching grant and
entry multiplicity, physical boarding/alighting distances and final daily totals.

Fractional crossing carryover remains the next timing improvement. Conflict
priority/gap acceptance, downstream blocking across shared conflict areas,
multi-lane turning and turn-aware routing costs require separate model work.
This explicit geometry contract is opt-in; there is no general city-network
geometry importer in this milestone.

### Real-SUMO measurements

Both policies produce the following results against SUMO 1.27.1. These are
protected single-lane movements with one selected route, not cross-traffic tests.

| Case | Connector length / speed | Mean route-distance RMSE | Mean speed RMSE | Maximum trip-time error |
|---|---:|---:|---:|---:|
| Straight | 14.40 m / 13.89 m/s | 10.127 m | 0.00023 m/s | 1.7 s |
| Left | 14.20 m / 8.00 m/s | 2.812 m | 0.319 m/s | 1.2 s |
| Right | 9.03 m / 6.51 m/s | 4.169 m | 0.532 m/s | 1.2 s |
| Right with signal queue | 9.03 m / 6.51 m/s | 3.951 m | 0.721 m/s | 1.9 s |
| Straight at green/red boundary | 14.40 m / 13.89 m/s | 10.127 m | 0.00023 m/s | 1.7 s |

External road lengths are 192.80 m and 92.80 m. Total physical route distances
are 300.00 m straight, 299.80 m left and 294.63 m right. Both engines report these
same completed distances. Left-turn import follows two internal SUMO lanes with
offsets 0 and 4.07 m. Turn approaches reach 8.00 and 6.51 m/s before the connector;
the signal queue additionally contains stopped vehicles that accelerate on green.

In the boundary fixture, SUMO enters the connector at 29.6 s and the next road at
30.6 s. HTC enters at tick 30, exits at tick 32 and finishes at tick 39, with no
extra Node wait. Signal clearance survives the rounded boundary. The separate
point-node boundary regression retains a maximum 1.7 s duration difference.

The straight movement's near-zero speed RMSE and nonzero route-distance RMSE show
the remaining cross-link time quantization: each connector/road starts at an
integer tick. Geometry adds a physical boundary and cannot remove this timing
loss. Earlier point-junction measurements use different physical networks and
must not be presented as a before/after geometry calibration result.
