# Separate MESO flow and storage

This optional Car MESO feature preserves HTC's existing occupancy/density speed function.
It adds admission and discharge limits; it does not replace travel with MATSim QSim queues.
Existing inputs without `mesoCapacity` retain their previous behavior.

A Link input can contain:

```json
{
  "simulationMode": "MESO",
  "capacity": 100,
  "mesoCapacity": {
    "flowVehiclesPerHour": 1800,
    "storageVehicles": 12
  }
}
```

`capacity` remains the density-speed reference in vehicles. `storageVehicles` limits physical
resident Cars. `flowVehiclesPerHour` is a separate discharge rate for movements into another road. Final destination arrivals release storage without consuming that rate budget. These are vehicle counts,
not variable passenger-car-equivalent weights. The initial implementation supports Cars and
one-second clocks. MICRO inputs reject this option.

Storage-full arrivals wait FIFO outside the link. A real release admits the next waiting
arrival; no storage polling or retry timeout is needed. This Link owns the storage counter,
so it does not register the legacy Node capacity proxy. Node signal checks still apply.
An upstream vehicle releases its old link before waiting in the downstream entry queue:
this bounded holding-buffer model does **not** retain upstream spillback footprints.

Discharge uses a fractional budget replenished once per elapsed simulation interval, capped
at one interval's rate. A positive balance admits one vehicle and may become negative until
replenished. For example, 1800 vehicles/hour permits one crossing every two seconds after
initial credit; 5400/hour permits alternating integer counts averaging 1.5 per second.
Idle time cannot accumulate an unlimited burst. Pending through-discharge requests retain physical
storage until acknowledged. The last road still uses its density-based travel time; only extra destination discharge waiting is bypassed. Through-flow is serviced before waiting origin-end admissions.

For an origin at the end of a source activity link, a Car may set `mesoOriginLinkId` and include
that source link first in its fixed route. Only its initial entry is a zero-distance origin
boundary: flow is debited on admission, and its exit is not charged again. The bounded origin
holding buffer has `ceil(flowVehiclesPerHour / 3600)` slots (at least one). Its residents do
not occupy the link's physical road storage. Later visits to the same link are normal road
traversals. The density law still applies on subsequent physical roads.

Actual departure is recorded on successful admission, preserving the scheduled departure.
Completed and horizon summaries expose `scheduledDepart`, `actualDepart` and `departDelay`;
unadmitted horizon trips keep actual departure/delay at -1 and remain `not_departed`.
Origin labels are `meso_origin_admitted` / `meso_origin_left`; they are excluded from physical
route/distance totals. `meso_capacity_admitted` / `meso_capacity_released` report actual storage
and flow-budget commits; the old generic flow-request reports are labelled as requests.

All pending sender IDs, shard IDs, request sequences, payloads, flow credit, origin residents
and next wake tick are checkpointed in `LinkState.mesoCapacityState`. Car origin progress is
also checkpointed. Flow wakes are physical eligibility times, not execution watchdogs. No
actor blocks or awaits a future. Cold codec, JSON, replay and real migration-hook tests cover
these obligations. Run the same new executable on every process of a simulation.

## MATSim adapter

`tools/matsim-validation` protocols can set `native_capacity_model: "separate"` and
`effective_cell_size_m: 7.5`. The adapter writes the Link/Car fields above, keeps original
schedules and fixed roads, and separates origin admission from physical traversal accounting.
Source-scaled hourly flow is imported in vehicles/hour. Geometric storage uses
`max(1, ceil(length * lanes / cell_size * storage_factor))`. The density reference remains the
legacy adapter value, so the feature does not silently recalibrate that speed law.

This is not full QSim equivalence: kinematic-wave storage floors, exit-buffer order, merging,
upstream spillback and density travel remain explicit differences. The frozen v1 Berlin report
is unchanged. `berlin-car-meso-v2-capacity.json` defines a separate development comparison.
The reserved operational cases are declared in `native-meso-flow-storage-v1.json`; execute:

```sh
python tools/matsim-validation/run_capacity_acceptance.py \
  --jar /path/to/new/engine.jar --output /path/to/fresh/results
```


## Berlin development comparison

The v2 5,000-trip comparison completed in
`tools/matsim-validation/output/berlin-meso-v2-capacity-20261116-r2/`.
Both simulators completed all trips; physical distance MAE and route discrepancies are zero.
Storage limits pass on 1,051,814 native snapshots. Journey-time MAE is 275.433 s,
versus the earlier legacy comparison's 245.647 s. Mean entry delays are 22.446 s in HTC
and 10.052 s in MATSim. These results show the admission/storage controls executing;
they do not demonstrate improved agreement or QSim equivalence. The report has 31 figures.
The interrupted first attempt is excluded from the reported native results.


## Destination arrival correction

Final arrivals are identified by the existing entry route hint (`nextLinkId == 0`),
which Car sends only after its remaining road route is depleted. The Link stores this
intent in `mesoCapacityState.destinationVehicles`; it survives JSON, cold-codec and
migration snapshots. Arrivals release physical storage immediately after their scheduled
density-based traversal and do not debit, refill or bypass anyone else's through-flow quota.
Origin admission remains metered and origin ends remain precharged.

The reserved ready-time checks are in
`tools/matsim-validation/protocols/native-meso-flow-storage-v3-destination.json`.
Both low-capacity destination cases require zero additional native wait beyond reported
travel time. This checks endpoint behavior directly; total QSim throughput also depends
on kinematic-wave inflow, so it cannot alone isolate destination discharge.
The earlier failed throughput-oracle attempt is retained as development evidence.


The completed corrected Berlin run is
`tools/matsim-validation/output/berlin-meso-v3-destination-20261116/`.
All 5,000 trips complete in both models. Every native final arrival has zero discharge
wait and zero flow debit; storage limits pass on 1,051,814 committed snapshots.
Journey-time MAE improves from 275.433 s to 249.854 s, while native mean origin delay
falls from 22.446 s to 12.125 s (MATSim 10.052 s). Source selection/network/plans/vehicles
are byte-identical and reference journey times are unchanged. Remaining density,
origin/inflow, storage and merge differences are still reported explicitly.
