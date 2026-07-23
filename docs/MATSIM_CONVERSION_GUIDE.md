# MATSim → Native Plan Conversion Guide

> This is a **specification for an external, out-of-repo script** — not a description of anything
> implemented in `hyperbolic-time-chamber`. There is no MATSim reader in this engine, and there is
> not meant to be one. See [PERSON_AGENT.md §3](PERSON_AGENT.md#3-modelo-de-plano-modelhybridentitystateplan)
> and [SCENARIO_MODELING.md](SCENARIO_MODELING.md) for the native format this document converts
> *into*; nothing here changes either of those.

---

## 1. Overview

### Why this is a separate script, not an engine feature

Architectural decision, taken at the start of the `Person`/plan redesign and reaffirmed since:
**the engine never reads MATSim plans directly.** `Person`/`PersonState` only ever load the native
format — the flat `List[PlanElement]` (`Activity`/`WalkLeg`/`PrivateVehicleLeg`/`TransitLeg`/
`PendingDecision`) documented in `PERSON_AGENT.md` §3 and `SCENARIO_MODELING.md` §4.9/§14. A
MATSim-plan importer is therefore, by design, a **standalone conversion script** that runs once,
offline, before a scenario is handed to HTC — not a data source type the engine's loader
(`LoadDataManager`/`ProgressiveLoadDataManager`) understands.

A conceptual extension point for this — a `PlanSource`/`PlanImporter` trait that any external plan
source (native or MATSim) would satisfy by producing the in-memory native format — was sketched in
an earlier design phase but **was never implemented** and does not exist in the codebase today. It
is mentioned here only as a reference framing: whoever writes the script does not need to implement
that Scala trait (it is a separate process, most likely Python, not a JVM plugin) — what matters is
that the script's **output shape** matches exactly what that trait would have produced: valid
native-format JSON, ready for `Person`/`LoadDataManager` to consume with **no further conversion
step**.

### Input / output contract

| | Description |
|---|---|
| **Input** | A MATSim plans file (typically `output_plans.xml`, or any XML conforming to `plans-v4.dtd`) for one or more `<person>` elements, each containing one `<plan>` (selected/executed plan). |
| **Output** | One or more native-format JSON files (`persons_N.json`, per `SCENARIO_MODELING.md` §2/§4.9) — each `PersonState.originalPlan` a flat `List[PlanElement]`, loadable by HTC exactly as any hand-authored native scenario is today. |
| **Not produced** | `PersonState.cursor` (seeded by `Person.internStateStrings` from `originalPlan` at first load — see `PERSON_AGENT.md` §14's note on `cursor`), and never a `PendingDecision` (§2.7 below). |

### Required auxiliary inputs (the script cannot infer these)

The script needs more than the MATSim plan file alone. It needs the **target HTC scenario's**:

- `city_map.json` (or equivalent node/edge source) — for coordinate→node snapping (§2.2).
- `simulation.json`'s `tickDuration`/time configuration — for `HH:MM:SS`→tick conversion (§2.4).
- A stop-correspondence table mapping MATSim `facilityId`/`stopId` → HTC `BusStop`/`SubwayStation`
  actor IDs — for `pt` legs (§2.7). This is a required external input, not something the script can
  derive from the MATSim file by itself.

If any of these three is missing, the corresponding class of `<activity>`/`<leg>` cannot be
converted correctly — see §5.

---

## 2. Field-by-Field Mapping

### 2.1 `<activity type>` → `Activity.activityType`

Direct string copy. No transformation. (MATSim's `"pt interaction"` activity type is the one
exception — see §2.7, it does not become an `Activity` at all.)

### 2.2 `<activity x, y>` → `Activity.nodeId` (snapping)

MATSim activities carry continuous `x`/`y` coordinates (in whatever CRS the MATSim scenario uses).
HTC activities reference a discrete `nodeId` from the target scenario's road-network graph. The
script must **snap** each activity's coordinate to a node in the *target* HTC `city_map.json`
(never the MATSim network — the two networks are not assumed to share topology or IDs; see §2.6).

Recommended strategy: nearest-node-by-distance —

1. Reproject MATSim `x`/`y` into the same coordinate system used by the target `city_map.json`'s
   `nodes.<id>.lat`/`lon` (§5 of `SCENARIO_MODELING.md`) if they differ.
2. For each activity coordinate, compute distance (haversine if working in WGS-84 lat/lon,
   Euclidean if both are in the same projected CRS) to every candidate node, and take the nearest.
   A spatial index (k-d tree/grid) is strongly recommended over a linear scan once the target
   network has more than a few hundred nodes.
3. Log the snap distance. A large snap distance (e.g. hundreds of meters) is a signal that the
   MATSim scenario's geography does not correspond well to the target HTC network — surface this
   as a warning per activity, and consider aborting the conversion if snap distances are
   systematically large (a coordinate system mismatch is a much likelier explanation than a
   genuinely sparse target network).

### 2.3 `end_time` / `dur` / `max_dur` → `EndTimeSpec`

`EndTimeSpec` has exactly two constructors (`PERSON_AGENT.md` §3, `EndTimeSpec.scala`):

```scala
final case class AtTick(tick: Tick) extends EndTimeSpec       // absolute tick
final case class Duration(ticks: Tick) extends EndTimeSpec    // ticks after arrival
```

MATSim's `<activity>` can carry `end_time` (absolute time-of-day the activity ends), `dur`/
`max_dur` (a duration), or — ambiguously — both at once. **Priority rule for the conversion**
(this was an open question in `PERSON_AGENT.md`'s design; this guide settles it for the converter):

1. If `end_time` is present, emit `AtTick(tick)` — convert `end_time` via §2.4. This is the
   engine's own default assumption for most scenario activities (`SCENARIO_MODELING.md` examples
   are all `AtTick`) and is the more deterministic of the two: an absolute tick is unambiguous
   regardless of how long the activity actually dwelled once `LatenessPolicy` resolves the real
   departure.
2. Else if `dur` or `max_dur` is present (and `end_time` is not), emit `Duration(ticks)` — convert
   the `HH:MM:SS` duration to a tick count via the same time-unit conversion (§2.4), *not* via
   tick-of-day math (a duration has no time-of-day component to convert).
3. **If both `end_time` and `dur`/`max_dur` are present, `end_time` wins.** Emit `AtTick` and
   **log a warning** naming the person/activity and the discarded `dur`/`max_dur` value — this is
   silent data loss otherwise, and whoever reviews the conversion log should be able to audit how
   often it happened.
4. If neither is present (valid for the final activity of a plan, which in MATSim often has no end
   time because the day simply ends), the script must supply some sentinel end time (e.g. the
   target scenario's `endTick`) — document this fallback loudly in the script's own output/log
   rather than defaulting silently, since it is filling in behavior MATSim's plan did not specify.

### 2.4 `HH:MM:SS` → tick

MATSim times in `plans-v4.dtd` output are wall-clock durations/times-of-day in `HH:MM:SS` (seconds
since midnight, e.g. `"08:00:00"` = 28800s). The native format has no notion of wall-clock time —
only integer ticks (`Tick` = `Long`).

**This conversion is not universal — it depends on the target HTC scenario's time configuration**,
specifically `simulation.json`'s `tickDuration` (`SCENARIO_MODELING.md` §3; the manifest example
uses `"timeUnit": "seconds"`, `"tickDuration": 1.0`, i.e. 1 tick = 1 second, which is also the
convention `PERSON_AGENT.md` §8.2 assumes for its walk-time model). Given seconds-since-midnight
`s` and the target scenario's `tickDuration` (in seconds/tick):

```
tick = round(s / tickDuration)
```

The script **must be given** the target scenario's `tickDuration` (and `startTick`/`endTick` if
activities need to be clipped or a fallback end time supplied per §2.3.4) as an explicit parameter
— it cannot be inferred from the MATSim plan file, and assuming `tickDuration = 1.0` silently when
the target scenario uses something else will silently misplace every activity/leg in time.

### 2.5 `<leg mode>` → `ConcreteMode`

| MATSim `mode` | `ConcreteMode` |
|---|---|
| `car` | `Car` |
| `bike` | `Bicycle` |
| `walk` | `Walk` |
| `motorcycle` (if present in the source MATSim scenario — not a stock MATSim mode, but some scenarios add it) | `Motorcycle` |
| `pt` | `Bus` or `Subway` — **cannot be determined from `mode` alone**; must be resolved from which transit line/route the leg's `<route>` references, cross-checked against the stop-correspondence table (§2.7). A `pt` leg whose route cannot be resolved to a known bus or subway line is a hard conversion error, not a default-to-`Bus` guess. |

Any MATSim mode string not in this table (e.g. custom modes some scenarios define, like
`freight`, `taxi`) has no `ConcreteMode` counterpart today — the script must reject or explicitly
flag such legs rather than force-mapping them to something plausible-looking.

### 2.6 Road leg route → `WalkLeg`/`PrivateVehicleLeg.precomputedRoute`

MATSim's `<route>` for `car`/`bike`/`walk` legs is a link-ID sequence (`startLink`, a
whitespace-separated middle sequence, `endLink`) in the **MATSim network's** own link/node ID
space. The native `precomputedRoute: Option[List[(String, String)]]` field is a list of
`(linkId, nodeId)` pairs in the **target HTC network's** ID space (`PERSON_AGENT.md` §14 example:
`[["htcaid:link;9001", "htcaid:node;301"], ...]`).

**Recommended default: leave `precomputedRoute = None`.** Rationale: the MATSim network the
original plan was routed against and the target HTC `city_map.json` are not guaranteed to have the
same topology, the same link IDs, or even the same node granularity — translating a MATSim link
sequence into a valid HTC `(linkId, nodeId)` sequence is itself a full re-routing problem, not a
simple ID rename, and doing it naively risks producing a `precomputedRoute` that references stale
or non-existent links in the target network (see §4's validation checklist). Leaving it `None` lets
`GPSUtil`/the relevant handler (`PersonWalkingTripHandler` for walk, the vehicle actor for private
modes) recompute a valid route against the target network at execution time — slower per-leg, but
correct by construction. Only populate `precomputedRoute` if the target network is verified to be
topologically identical to the MATSim network the plan was computed against (e.g. both derived from
the same OSM extract with a shared ID scheme) — an assumption the script should require the caller
to assert explicitly, not infer.

### 2.7 `<leg mode="pt">` route → `TransitLeg`

```scala
final case class TransitLeg(
  mode: ConcreteMode,          // Bus | Subway
  line: String,
  boardingStop: StopRef,       // StopRef(actorId, actorClassType, nodeId)
  alightingStop: StopRef
)
```

MATSim's `pt` route (shape varies by which `org.matsim.pt` extension version produced the plan, but
typically carries `transitRouteId`, `accessFacilityId`, `egressFacilityId`, `boardingTime`, and
similar) identifies stops by MATSim **facility/stop IDs**. The native `StopRef.actorId` must be a
real `BusStop`/`SubwayStation` actor ID that exists in the **target HTC scenario** (its
`bus_stops_N.json`/`subway_stations_N.json`, or equivalently its `transit_map.json` per
`SCENARIO_MODELING.md` §5).

**There is no way to derive this mapping automatically from the MATSim file alone.** The script
requires, as an explicit external input, a correspondence table (`facilityId`/`stopId` →
`{actorId, actorClassType, nodeId}`) supplied by whoever runs the conversion — this table is
scenario-specific (it depends on how the target HTC transit network was built) and cannot be
inferred from either side's data alone. Treat a missing or incomplete correspondence table as a
hard failure for the affected `pt` leg, not a "best guess."

`line` should be set to the value matching the target scenario's own line-label convention (the
label a `BusStop`/`SubwayStation`/`Bus`/`Subway` actor already agrees on, per `SCENARIO_MODELING.md`
§4.5's note that `BusStop`/`BusStation`/`Bus` must share the same `label` string) — this typically
comes bundled with the same correspondence table, since a MATSim `transitRouteId` and an HTC line
label are two different naming schemes for what is conceptually the same route.

### 2.8 `"pt interaction"` activities — discarded, not converted

MATSim represents a multimodal PT trip as an interleaved sequence like:

```
activity → leg(walk) → activity("pt interaction") → leg(pt) → activity("pt interaction") → leg(walk) → activity
```

The `"pt interaction"` activities are **artifacts of MATSim's representation of a transfer** — they
mark where one leg of a multi-leg trip hands off to the next, not real dwelling by the person. They
must be **dropped entirely** from the output `PlanElement` list — they never become an `Activity`.
The native format has no need for such a marker: a walk→transit→walk sequence is already
representable as three consecutive `AtomicLeg`s in the same flat list, with no activity separating
them (exactly how `RemainingQueue.dropCurrentLegRun` — see `PERSON_AGENT.md` §3 — treats a
contiguous run of `AtomicLeg`s as one trip already). Converting `"pt interaction"` into a
zero-duration `Activity` would be actively wrong: it would make the engine treat the transfer as a
real dwelling stop subject to `LatenessPolicy`/mode-choice, which it is not.

### 2.9 No leg ever becomes a `PendingDecision`

Every `<leg>` in a MATSim plan already specifies a chosen `mode` and (for `pt`) a chosen route — a
MATSim plan is, definitionally, a **fully resolved** plan (this is what "plan" means in MATSim: the
output of its own mode-choice/routing process, not an intent to choose later). The native format's
`PendingDecision(decision: ModeDecisionRequest)` exists specifically for legs whose mode is *not yet*
decided and should be resolved dynamically at simulation time by a `ModeDecisionEngine`
(`PERSON_AGENT.md` §6). **The converter must never emit a `PendingDecision`.** If a user wants
dynamic mode-choice for some portion of a converted plan, that is a manual edit to the native JSON
after conversion (replacing some `AtomicLeg`s with a `PendingDecision`) — not something the MATSim
conversion step does or infers on its own.

---

## 3. Worked Example

### 3.1 MATSim input (excerpt)

A trip from home to work by car, then later a multimodal return trip (walk → pt → walk):

```xml
<person id="42">
  <plan selected="yes">
    <activity type="home" x="326000.0" y="7393000.0" end_time="08:00:00" />
    <leg mode="car" dep_time="08:00:00" trav_time="00:18:00">
      <route type="links" start_link="1001" end_link="1050">1001 1012 1033 1050</route>
    </leg>
    <activity type="work" x="330500.0" y="7396200.0" end_time="17:00:00" />
    <leg mode="walk" dep_time="17:00:00" trav_time="00:04:00" />
    <activity type="pt interaction" x="330400.0" y="7396100.0" max_dur="00:00:00" />
    <leg mode="pt" dep_time="17:04:00" trav_time="00:22:00">
      <route type="default_pt">
        <transitRouteId>blue_line</transitRouteId>
        <accessFacilityId>fac_station_A</accessFacilityId>
        <egressFacilityId>fac_station_B</egressFacilityId>
      </route>
    </leg>
    <activity type="pt interaction" x="326100.0" y="7392950.0" max_dur="00:00:00" />
    <leg mode="walk" dep_time="17:26:00" trav_time="00:03:00" />
    <activity type="home" x="326000.0" y="7393000.0" />
  </plan>
</person>
```

Assume the target HTC scenario has `tickDuration = 1.0` (1 tick = 1 second), and:

- `x=326000.0, y=7393000.0` snaps to `htcaid:node;60609822` (home)
- `x=330500.0, y=7396200.0` snaps to `htcaid:node;4922987596` (work)
- Correspondence table: `fac_station_A → {actorId: "htcaid:subwaystation;station_A", actorClassType: "hybrid.actor.SubwayStation", nodeId: "htcaid:node;700"}`, `fac_station_B → {actorId: "htcaid:subwaystation;station_B", ..., nodeId: "htcaid:node;701"}`, and `blue_line` is the target scenario's own `line` label for that route.

### 3.2 Native output (`persons_0.json` excerpt)

Field shapes below are confirmed against `PersonStateJsonSpec.scala`'s round-trip fixtures — `kind`
discriminators, field names, and nesting match exactly what the engine deserializes.

```json
{
  "id": "htcaid:person;42",
  "typeActor": "hybrid.actor.Person",
  "data": {
    "dataType": "model.hybrid.entity.state.PersonState",
    "content": {
      "originalPlan": [
        {
          "kind": "Activity",
          "activityType": "home",
          "nodeId": "htcaid:node;60609822",
          "endTime": { "kind": "AtTick", "tick": 28800 }
        },
        {
          "kind": "PrivateVehicleLeg",
          "mode": "Car",
          "vehicle": { "id": "htcaid:car;42_v_car", "classType": "hybrid.actor.Car" },
          "driverAttributes": {
            "aggressiveness": 0.5,
            "maxSpeedFactor": 1.0,
            "reactionTime": 1.0,
            "minGapFactor": 1.0
          }
        },
        {
          "kind": "Activity",
          "activityType": "work",
          "nodeId": "htcaid:node;4922987596",
          "endTime": { "kind": "AtTick", "tick": 61200 }
        },
        {
          "kind": "WalkLeg",
          "originNodeId": "htcaid:node;4922987596",
          "destinationNodeId": "htcaid:node;700",
          "precomputedRoute": null
        },
        {
          "kind": "TransitLeg",
          "mode": "Subway",
          "line": "blue_line",
          "boardingStop": {
            "actorId": "htcaid:subwaystation;station_A",
            "actorClassType": "hybrid.actor.SubwayStation",
            "nodeId": "htcaid:node;700"
          },
          "alightingStop": {
            "actorId": "htcaid:subwaystation;station_B",
            "actorClassType": "hybrid.actor.SubwayStation",
            "nodeId": "htcaid:node;701"
          }
        },
        {
          "kind": "WalkLeg",
          "originNodeId": "htcaid:node;701",
          "destinationNodeId": "htcaid:node;60609822",
          "precomputedRoute": null
        },
        {
          "kind": "Activity",
          "activityType": "home",
          "nodeId": "htcaid:node;60609822",
          "endTime": { "kind": "AtTick", "tick": 86400 }
        }
      ]
    }
  }
}
```

Notes on this example:

- Both `"pt interaction"` activities from the MATSim source are gone — the walk→transit→walk
  sequence is represented purely as three consecutive `AtomicLeg`s, per §2.8.
  `PrivateVehicleLeg.vehicle` had to be supplied from somewhere the MATSim plan does not carry
  (MATSim's `car` leg says nothing about a specific vehicle actor) — this is scenario-owner input,
  same category as the stop-correspondence table: the script needs an owned-vehicle assignment per
  person for every private-vehicle leg it converts, it cannot invent one.
- The final `home` activity had no `end_time` in the MATSim source (§2.3.4) — this example fills it
  with the target scenario's `endTick` (`86400`) as the documented fallback; a real script run
  should log that this activity's end time was defaulted, not inferred from the source plan.
- Every `precomputedRoute` is `null` (`None`), per the default recommended in §2.6 — the `car` leg's
  MATSim link route (`1001 1012 1033 1050`) is intentionally not translated into HTC link/node IDs.

---

## 4. Pre-flight Validation Checklist

Before the script emits its final native-format file(s), it should verify — and refuse to emit (or
clearly flag) the offending person's plan if any of these fail:

- [ ] Every `Activity.nodeId` referenced exists in the target scenario's `city_map.json` nodes.
- [ ] Every `StopRef.actorId` referenced (in every `TransitLeg.boardingStop`/`alightingStop`) exists
      among the target scenario's `BusStop`/`SubwayStation` sources (`bus_stops_N.json`,
      `subway_stations_N.json`, or `transit_map.json`).
- [ ] No `PlanElement` of kind `PendingDecision` appears anywhere in the output — this should be
      structurally impossible given the converter never constructs one (§2.9), but assert it
      explicitly as a final sanity check rather than trusting the rest of the pipeline silently.
- [ ] Activities appear in chronological order within each person's plan (monotonically
      non-decreasing resolved tick, after §2.3/§2.4's conversion) — a MATSim plan is chronological
      by construction, but the conversion (especially the `end_time`/`dur` priority rule and any
      fallback substitution) should not be trusted to preserve that without an explicit check.
- [ ] Every `PrivateVehicleLeg.vehicle` references a vehicle actually declared as owned by that
      person in the target scenario (see the worked example's note above — this is scenario-owner
      input the script consumes, not something in the MATSim file).

---

## 5. Limitations and Decisions Left to the Script's Author

This guide intentionally does not resolve everything — some choices are scenario/tooling-specific
and are better made by whoever implements the script, with knowledge of the specific MATSim source
and HTC target scenario in hand:

- **Target scenario time granularity.** This guide assumes `tickDuration` is provided as an input
  parameter (§2.4); the script's interface for accepting it (CLI flag, config file, reading
  `simulation.json` directly) is left to the implementer.
- **PT line/stop correspondence table format.** Whether this is a CSV, JSON, or embedded in a
  shared config alongside the target scenario is left to the implementer; this guide only specifies
  that it must exist and must be validated (§4), not its file format.
- **Whether to populate `precomputedRoute`.** §2.6 recommends `None` as the safe default; an
  implementer with strong guarantees that the MATSim and target HTC networks are topologically
  identical may choose to populate it instead, at their own risk.
- **Coordinate snapping tolerance/algorithm.** §2.2 recommends nearest-node-by-distance with a
  logged snap distance; tuning the acceptable threshold, or using a more sophisticated
  network-aware snap (e.g. snapping to the nearest point on a link rather than nearest node), is
  left to the implementer.
- **Fallback behavior for missing `end_time` on a plan's final activity.** §2.3.4 recommends
  falling back to the target scenario's `endTick` with a loud log; an implementer may choose a
  different sentinel if it fits their scenario's semantics better.
- **How multiple plans per MATSim person (`<plan selected="no">` alternatives) are handled.** This
  guide assumes conversion of the selected plan only (`selected="yes"`); if a scenario needs the
  unselected alternatives preserved for some other purpose, that is outside this guide's scope.

---

## 6. References

- [PERSON_AGENT.md §3](PERSON_AGENT.md#3-modelo-de-plano-modelhybridentitystateplan) — native plan model (`PlanElement`, `EndTimeSpec`, `ConcreteMode`, `StopRef`)
- [PERSON_AGENT.md §14](PERSON_AGENT.md#14-configuração-json-do-agente) — full native JSON scenario example, including the `cursor`-seeding note
- [SCENARIO_MODELING.md](SCENARIO_MODELING.md) — full scenario file layout, `simulation.json` manifest, `city_map.json`/`transit_map.json` schemas
- [src/main/scala/model/hybrid/entity/state/plan/PlanElement.scala](../src/main/scala/model/hybrid/entity/state/plan/PlanElement.scala) — authoritative Scala shape (`@JsonTypeInfo`/`@JsonSubTypes` discriminator)
- [src/main/scala/model/hybrid/entity/state/plan/EndTimeSpec.scala](../src/main/scala/model/hybrid/entity/state/plan/EndTimeSpec.scala)
- [src/main/scala/model/hybrid/entity/state/plan/RemainingQueue.scala](../src/main/scala/model/hybrid/entity/state/plan/RemainingQueue.scala) — `@JsonValue`/`@JsonCreator` note: serializes as a plain array, not an object
- [src/test/scala/model/hybrid/entity/state/PersonStateJsonSpec.scala](../src/test/scala/model/hybrid/entity/state/PersonStateJsonSpec.scala) — source-of-truth round-trip fixtures for the exact JSON shape
- MATSim `plans-v4.dtd` — source format this guide converts from (see the MATSim project's own documentation for the DTD itself; not vendored in this repo)
