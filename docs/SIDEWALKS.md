# Link sidewalks

A road `LinkState` now owns an immutable `sidewalk` configuration, separate from its
vehicle lanes. Both MESO and MICRO Links accept the same optional field in `data.content`:

```json
"sidewalk": { "enabled": true, "width": 1.8, "gap": 0.3 }
```

`width` is the width of each sidewalk in metres; `gap` is its separation from the vehicle
carriageway. Width must be finite and positive; gap must be finite and nonnegative. Both
sides use this configuration. Missing fields keep the legacy defaults above. Disabling a
sidewalk means `enabled: false`, not setting its width to zero. Vehicle lane counts,
MICRO lane queues, vehicle capacity and vehicle routing do not include sidewalk space.

The routing `EdgeGraph` label carries the same `sidewalk` object. Pedestrian CSR and ALT
source graphs exclude disabled sidewalks but retain every node, including isolated ones.
Enabled sidewalk links remain bidirectional for walking regardless of vehicle direction.
Precomputed walks through a disabled sidewalk fail route measurement rather than being
accepted or silently shortened. Existing measured-distance, arrival, horizon and checkpoint
rules continue to apply. Walking remains mesoscopic at 1.4 m/s; this change does not add
pedestrian queues, crowding, microscopic avoidance or crossing-priority rules.

## Scenario and data contract

A Link actor and its routing-map label must contain matching sidewalk configuration, just
as their link identity and length must agree. The Scenario Editor validates sidewalk
settings and precomputed walking access, then publishes matching actor/map metadata.
Authors creating scenario packages manually must mirror nondefault sidewalk settings into
the corresponding `city_map.json` edge labels.

SQLite city-map conversion preserves explicit configuration in an optional `sidewalk` TEXT
column containing the JSON object. The engine and converter still read older databases
without that column, applying legacy defaults. Actor-simulation SQLite content already
preserves the nested Link object without schema changes. Link state copies and checkpoint
JSON retain sidewalk settings; the default immutable configuration is shared across Links.

Replay ETL exports `sidewalk_enabled`, `sidewalk_width_m`, and `sidewalk_gap_m` in `links.arrow`.
HTC View uses these values for sidewalk access, placement and width. Older artifacts retain
nominal defaults. Regenerate replay geometry to apply nondefault settings to an existing
artifact. This configuration is static for a run; it is not a runtime closure protocol.

## Validation

The full engine suite passes 832 tests and `sbt test assembly` produces
`target/scala-3.3.5/hyperbolic-time-chamber-3.2.0.jar`. Focused coverage checks legacy JSON,
custom dimensions, state copies/checkpoint round-trips, separate MICRO vehicle lanes,
disabled walking edges, reverse walking, isolated nodes, and new/legacy SQLite maps.
Additional checks cover editor publication, converter round-trips and replay Arrow export.

The bounded real-engine validation closes both directed sidewalk links between nodes 1 and 2
on a 3×3 grid. A computed walk departing at tick 10 takes the remaining 600 m route instead
of the blocked 200 m connection, arriving at tick 439 (`10 + ceil(600/1.4)`). Machine-readable
evidence is in [validation/link-sidewalk-2026-11-02.json](validation/link-sidewalk-2026-11-02.json).
The harness stops its JVMs after acknowledged engine completion; a process kill return code
is not evidence of model failure or clean application shutdown.
