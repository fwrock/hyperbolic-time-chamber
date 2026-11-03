# Microscopic observation export for SimEDaPE

The Car, Bus, Bicycle, and Motorcycle `micro_update` reports now include the
`htc.micro_observation.v1` fields produced by `model.hybrid.support.MicroObservationFields`:

| Field | Meaning |
| --- | --- |
| `time_seconds` | Physical time from the installed SimulationClock; raw ticks remain available. |
| `sample_duration_seconds` | One runtime tick in physical seconds. Skipped reports do not enlarge this duration. |
| `report_every_updates` | Configured decimation frequency. |
| `sampling` | `interval_endpoint`: final reported state, not all internal physics substeps. |
| `acceleration` | Signed longitudinal acceleration in m/s². |
| `leader_id` | Actual leader ID as a string, or null for free space/virtual signal blocker. |
| `gap` | Bumper gap in meters when an actual leader is present; otherwise null. |
| `gap_valid` | Whether a physical leader gap is available. |
| `leader_velocity` | Runtime leader velocity; interpret with `leader_id`/`gap_valid`. |

Existing position, velocity, lane, sub-tick, tick, and junction fields remain. Entry and exit
reports also carry the schema and physical timestamp. Enable updates with
`HTC_MICRO_UPDATE_REPORT_EVERY=1` and an appropriate report sink. These fields pass through
the existing `report()` pipeline, preserving runtime report commit and flush semantics.

This export adds no model state or new event/wait protocol. It does not enable lane changing,
alter vehicle motion, or claim to expose every internal substep. A consumer can use a declared
endpoint-hold approximation over the reported interval, but must retain gaps between decimated
samples and must not pad absent vehicles with zero velocity. Entry/exit residence intervals
are preferable to endpoint snapshots for link occupancy counts.

Fractional clocks retain their documented supported actor scope. Use the current runtime
capability checks; adding a physical timestamp does not extend fractional-clock support to
Bus/Bicycle/Motorcycle. A selected-link or MESO-background experiment must also distinguish
unobserved microscopic variables from measured zeros.

See [SimEDaPE experiment usage](../../simedape/docs/EXPERIMENTS.md) for ingestion, aggregation,
masked reconstruction, reduction manifests, native adapter limits, and evaluation conventions.
