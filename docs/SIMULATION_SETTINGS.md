# Configuration in simulation.json

A scenario can now carry HTC engine settings in its `settings` object. Every existing `htc.*`
configuration leaf is supported (217 packaged settings in this revision). Old scenarios can omit this object; omitted keys retain the
loaded application configuration, environment substitutions and built-in defaults.

```json
{
  "name": "example",
  "description": "Scenario-owned engine configuration",
  "startTick": 0,
  "duration": 3600,
  "startRealTime": "2025-01-01T00:00:00.000",
  "timeUnit": "seconds",
  "timeStep": 1,
  "randomSeed": 42,
  "actorsDataSources": [],
  "settings": {
    "htc": {
      "time-manager": {
        "conservative-transport": "direct",
        "scheduling-strategy": "auto"
      },
      "report-manager": {"enabled-strategies": ["json"]}
    },
    "htc.actor-trace.enabled": false
  }
}
```

This is a configuration example. Populate the scenario's actor sources and maps to run a mobility
experiment. Nested objects and dotted keys are equivalent; declaring the same leaf both ways is
an error. Use JSON booleans, numbers and arrays. Duration/memory options that use HOCON strings
retain those strings and units.

## Precedence and defaults

1. REST API setting overrides, if explicitly supplied.
2. `simulation.json.settings`.
3. Loaded deployment configuration, including `HTC_*` environment substitutions and JVM options.
4. Packaged `application.conf` defaults.

Deleting an API override reveals the scenario value. Loading another scenario replaces the entire
scenario layer: values omitted in the new scenario do not inherit the previous scenario's values.
The API reports scenario values with source `simulation_json`. Changing a setting read once at
startup requires a new process; this feature does not make such settings dynamically mutable.

Existing scenario fields still own duration, clock and resource paths: `duration`, `tickDuration`,
`cityMapFile`, `transitMapFile` and `transitRoutesFile`. A settings entry that contradicts one of
these fields is rejected. For duration and clock, this includes their scenario defaults. Put clock
changes in `tickDuration`, so fractional-clock actor compatibility validation still runs.
`htc.simulation.config-file` selects the input file externally and is forbidden inside that file.

Unknown leaf keys, nulls, incompatible JSON types, invalid enumerated options and ambiguous dotted/
nested duplicates fail before publishing the scenario layer. Individual subsystems additionally
validate their own ranges and dependencies at startup (for example, accounted routing worker IDs).

See [the complete defaults manifest](simulation-settings-defaults.json). It lists the packaged HTC
configuration leaves and values, independent of this machine's environment. Secret fields are
redacted. This is a reference manifest, not a replacement scenario; do not paste its duration or
file-selection entries into a scenario with different top-level fields.

## Startup and distributed execution

With `HTC_SIMULATION_CONFIG_FILE=/path/to/simulation.json`, file settings load before metrics,
node identity, API setup and shard initialization. Each JVM independently loads and validates the
same file. Shard startup installs settings before reading allocation and routing configuration.
Use the same engine build and scenario on every worker, with paths available on every JVM.

API preparation retains its existing per-JVM preparation/acknowledgment contract: a singleton's
process-local registry does not configure other workers. Settings supplied after a process has
started cannot reopen an API or metrics port or rebuild already initialized process-wide caches.
Restart the process when changing those settings. Keep per-worker forced node ranks and ports in
deployment configuration unless each process has an appropriate scenario; a shared nonnegative
`htc.node.rank` would assign every worker the same rank.

Pekko/JVM deployment settings (dispatchers, remoting bind addresses, serializer registrations,
heap and CPU limits) remain startup configuration outside the `htc.*` scenario settings namespace.
Host identity environment variables such as `HOSTNAME`/`CLUSTER_NAME` still identify the host.

## Mobility parameters and comparison experiments

Global routing, reporting, synchronization, loading and infrastructure options belong in `settings`.
For causal dynamic routing, set `htc.routing.cache-strategy` to `accounted` and
`htc.routing.accounted.worker-ids` to the exact IDs of EAGER, STATIC `hybrid.actor.RoutingService`
sources. Merely changing the cache strategy without providing the actors fails startup validation.

Per-person mode-choice weights, available modes, decision strategy and per-vehicle/link physics
remain in their actor input records. Their case-class defaults apply when omitted. Existing
`modelPlugins`, `calculationCache`, `partial`, `modifications` and `loadFilter` retain their typed
scenario blocks. They are not renamed into undocumented `htc.*` keys. Thus mode choice and routing
still require their existing actor configuration; adding this settings block does not activate them
automatically.

This change centralizes configuration without changing traffic equations or capacity units.
Original MESO link `capacity` remains an occupancy reference (vehicles), while
`mesoCapacity.flowVehiclesPerHour` is vehicles/hour and `mesoCapacity.storageVehicles` is vehicles.
The MATSim capacity adapter and the revised experimental protocol require separate validation;
previous comparison results are not corrected by configuration loading alone.

## Export reproducible settings

From the engine directory:

```bash
sbt 'runMain org.interscity.htc.core.util.ScenarioSettingsExport defaults docs/simulation-settings-defaults.json'
sbt 'runMain org.interscity.htc.core.util.ScenarioSettingsExport effective /path/to/simulation.json /path/to/effective-settings.json'
```

The second command validates and installs the scenario layer in an offline process and exports the
resolved HTC settings, with secrets redacted. It does not run a simulation or include API overrides
from another process. Keep the effective manifest, original scenario, actor inputs, engine hash
and launch configuration together for each experiment. The running API's `GET /api/v1/settings`
shows additional overrides supplied to that process.

## Corrective MESO experiments

`htc.mobility.meso.entry-density` defaults to `inclusive`. The opt-in `existing-vehicles`
convention evaluates an entrant's density speed at `max(0, inclusive_count - 1)`. This prevents
self-induced jam speed on a one-slot sampled road; aggregate routing publications still use the
occupied link. It changes the entry-density convention, not the linear density equation.

`htc.mobility.meso.bicycle-speed-mps` defaults to 5.56.
`htc.mobility.mode-choice.private-cost-model` defaults to `shared-dynamic`;
`car-dynamic-bicycle-static` gives bicycles immutable distance-based route costs while cars
continue to use live dynamic costs. This remains an approximate private-mode time estimator.

The fixed-route corrective comparison uses cars only, no choice or rerouting, geometric density
reference S, and the existing separate flow/storage controller. Flow is source vehicles/hour
times the flow factor. Storage is `max(1, ceil(length * source_lanes / cell_size * storage_factor))`
vehicles. QSim's additional effective-storage rules and HTC's downstream entry holding remain
model differences; these parameters do not certify QSim equivalence. Historical evaluated
protocols and results are retained unchanged.
