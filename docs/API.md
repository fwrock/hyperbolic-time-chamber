# Simulator Management REST API

The HTC simulator exposes an optional HTTP API for managing the simulation lifecycle, configuring scenarios, and overriding runtime settings — without rebuilding or restarting the container.

> **Default behaviour is unchanged.** When the API is disabled (default), the simulator starts automatically using `HTC_SIMULATION_CONFIG_FILE` / `application.conf` / env vars exactly as before.

---

## Enabling the API

| Method | Value |
|---|---|
| Environment variable | `HTC_API_ENABLED=true` |
| `application.conf` | `htc.api.enabled = true` |

Port (default `8080`):

| Method | Value |
|---|---|
| Environment variable | `HTC_API_PORT=8080` |
| `application.conf` | `htc.api.port = 8080` |

Scenarios directory (default `/app/simulations`):

| Method | Value |
|---|---|
| Environment variable | `HTC_SCENARIOS_DIR=/app/simulations` |
| `application.conf` | `htc.api.scenarios-dir = "/app/simulations"` |

When the API is enabled the simulator starts in **Idle** state and waits for a `POST /api/v1/simulation/start` before executing.

---

## Base URL

```
http://<host>:<HTC_API_PORT>/api/v1
```

All responses are `application/json`.

---

## Simulation Status

| Value | Meaning |
|---|---|
| `Idle` | API enabled, waiting for `/start` |
| `Loading` | Actors being created and registered |
| `Running` | Ticks advancing |
| `Paused` | Ticks halted, state preserved |
| `Stopped` | Simulation ended (naturally or via `/stop`) |

---

## Endpoints

### Health

#### `GET /api/v1/health`

Liveness check.

**Response `200`**
```json
{
  "status": "ok",
  "simulationStatus": "Idle",
  "apiConfigLoaded": false
}
```

---

### Simulation — Scenario Config

#### `GET /api/v1/simulation/config`

Returns the active scenario configuration and where it came from.

**Response `200`**
```json
{
  "source": "api_override",
  "config": { ...Simulation object... }
}
```

`source` values: `api_override` | `file_or_env` | `none`

---

#### `PUT /api/v1/simulation/config`

Loads a `Simulation` JSON as an API override. Takes priority over `HTC_SIMULATION_CONFIG_FILE` and `application.conf` when `/start` is called.

**Request body** — full `Simulation` JSON:
```json
{
  "name": "toulouse_1pct",
  "description": "Toulouse 1% scenario",
  "startTick": 0,
  "duration": 86400,
  "timeUnit": "seconds",
  "timeStep": 1,
  "startRealTime": "2024-01-01T08:00:00",
  "actorsDataSources": [...]
}
```

**Response `200`**
```json
{ "status": "ok", "name": "toulouse_1pct" }
```

**Response `400`** — invalid JSON body
```json
{ "status": "error", "message": "..." }
```

---

#### `DELETE /api/v1/simulation/config`

Clears the API config override. Subsequent `/start` calls will resolve the scenario from `HTC_SIMULATION_CONFIG_FILE` or `application.conf`.

**Response `200`**
```json
{ "status": "ok", "message": "Scenario override cleared" }
```

---

### Simulation — Lifecycle

#### `GET /api/v1/simulation/status`

Returns current status and tick metrics.

**Response `200`**
```json
{
  "status": "Running",
  "currentTick": 3420,
  "progressRatio": 0.0396,
  "apiConfigLoaded": true,
  "settingOverrides": 2
}
```

---

#### `POST /api/v1/simulation/start`

Starts the simulation. Accepts an optional JSON body to set a scenario file and/or override settings at start time.

**Request body** (all fields optional):
```json
{
  "configFile": "/app/simulations/toulouse/simulation.json",
  "settings": {
    "htc.time-manager.total-instances": "64",
    "htc.time-manager.max-instances-per-node": "8",
    "htc.report-manager.json.batch-size": "200"
  }
}
```

- `configFile` — absolute path to a `Simulation` JSON file. When omitted, uses the API config override or `HTC_SIMULATION_CONFIG_FILE`.
- `settings` — `htc.*` config-path keys mapped to string values. Overrides `application.conf` and env vars for this run. See the [Settings catalog](#settings-catalog) below.

**Response `200`**
```json
{ "status": "ok", "message": "Simulation start triggered" }
```

**Response `409`** — simulation is not in `Idle` state
```json
{ "status": "error", "message": "Cannot start: simulation is already Running" }
```

---

#### `POST /api/v1/simulation/pause`

Pauses tick advancement. The simulation state is preserved. Only valid when `Running`.

**Response `200`**
```json
{ "status": "ok", "message": "Simulation paused" }
```

**Response `409`**
```json
{ "status": "error", "message": "Cannot pause: simulation is Idle" }
```

---

#### `POST /api/v1/simulation/resume`

Resumes a paused simulation. Only valid when `Paused`.

**Response `200`**
```json
{ "status": "ok", "message": "Simulation resumed" }
```

---

#### `POST /api/v1/simulation/stop`

Stops the simulation and triggers a graceful shutdown of all manager singletons.

**Response `200`**
```json
{ "status": "ok", "message": "Simulation stopped" }
```

---

### Scenarios

The scenarios endpoints allow browsing simulation scenarios available on disk, reading their metadata, and loading one into memory before starting the simulation.

Scenarios are sub-directories under the configured `HTC_SCENARIOS_DIR`, each containing:
- `simulation.json` — required: the full `Simulation` config
- `metadata.json` — optional: human-readable descriptor

**`metadata.json` format** (all fields optional):
```json
{
  "description": "Toulouse morning peak — 1% sample",
  "version": "2.1",
  "author": "HTC Team",
  "tags": ["toulouse", "urban", "1pct"],
  "notes": "Requires at least 4 worker nodes"
}
```

**Directory layout expected:**
```
/app/simulations/
  toulouse_1pct/
    simulation.json
    metadata.json
  sao_paulo_10pct/
    simulation.json
    metadata.json
```

---

#### `GET /api/v1/scenarios`

Lists all scenarios found in the configured directory.

**Response `200`**
```json
{
  "scenariosDir": "/app/simulations",
  "count": 2,
  "scenarios": [
    {
      "name": "toulouse_1pct",
      "hasMetadata": true,
      "meta": {
        "description": "Toulouse morning peak — 1% sample",
        "version": "2.1",
        "author": "HTC Team",
        "tags": ["toulouse", "urban", "1pct"],
        "notes": null
      },
      "simulationName": "toulouse morning peak",
      "simulationDescription": "1% vehicle sample, morning peak",
      "duration": 7200,
      "timeUnit": "seconds",
      "startTick": 0,
      "endTick": null
    }
  ]
}
```

---

#### `GET /api/v1/scenarios/{name}`

Returns the full detail for a single scenario: metadata + complete `Simulation` config.

**Response `200`**
```json
{
  "name": "toulouse_1pct",
  "hasMetadata": true,
  "meta": { "description": "...", "tags": ["toulouse"], "author": "HTC Team" },
  "simulation": { ...full Simulation object... }
}
```

**Response `404`** — scenario not found
```json
{ "status": "error", "message": "Scenario 'xyz' not found in '/app/simulations'" }
```

---

#### `POST /api/v1/scenarios/{name}/load`

Loads the scenario's `simulation.json` into the API config registry. This is the **recommended way** to select a scenario — equivalent to doing a `PUT /api/v1/simulation/config` with the file contents, but without having to send the entire JSON body. After calling this endpoint, the scenario is active and a `POST /api/v1/simulation/start` will use it.

**Response `200`**
```json
{
  "status": "ok",
  "message": "Scenario loaded — call POST /api/v1/simulation/start to begin",
  "name": "toulouse_1pct",
  "simulationName": "toulouse morning peak"
}
```

**Response `404`** — scenario not found
```json
{ "status": "error", "message": "Scenario 'xyz' not found in '/app/simulations'" }
```

---

### Settings

The settings endpoints expose the `htc.*` parameters that are normally set via env vars in the docker-compose file. Overrides apply on top of env vars and `application.conf`.

#### `GET /api/v1/settings`

Returns all known settings with their current effective value and the source that determined it.

**Response `200`**
```json
{
  "settings": [
    {
      "key": "htc.time-manager.total-instances",
      "envVar": "HTC_TIME_MANAGER_INSTANCES",
      "description": "Total LocalTM routees across the cluster",
      "defaultValue": "128",
      "currentValue": "256",
      "source": "env_var"
    },
    ...
  ]
}
```

`source` values: `api_override` | `env_var` | `application_conf`

---

#### `GET /api/v1/settings/{key}`

Returns a single setting. `{key}` is the `htc.*` config path, URL-encoded.

**Example**
```
GET /api/v1/settings/htc.time-manager.total-instances
```

**Response `200`**
```json
{
  "key": "htc.time-manager.total-instances",
  "envVar": "HTC_TIME_MANAGER_INSTANCES",
  "currentValue": "128",
  "source": "application_conf"
}
```

---

#### `PUT /api/v1/settings`

Sets multiple settings at once. Body is a flat JSON object with `htc.*` keys.

**Request body**
```json
{
  "htc.time-manager.total-instances": "64",
  "htc.time-manager.max-instances-per-node": "4",
  "htc.report-manager.json.batch-size": "1000"
}
```

**Response `200`**
```json
{ "status": "ok", "updated": 3 }
```

---

#### `PUT /api/v1/settings/{key}`

Sets a single setting. Body is the raw string value (with or without quotes).

**Example**
```
PUT /api/v1/settings/htc.time-manager.total-instances
Body: 256
```

**Response `200`**
```json
{
  "status": "ok",
  "key": "htc.time-manager.total-instances",
  "value": "256"
}
```

---

#### `DELETE /api/v1/settings`

Clears all API setting overrides. Effective values revert to env vars / `application.conf`.

**Response `200`**
```json
{ "status": "ok", "message": "All setting overrides cleared" }
```

---

#### `DELETE /api/v1/settings/{key}`

Clears the API override for a single setting.

**Response `200`**
```json
{ "status": "ok", "message": "Override cleared for htc.time-manager.total-instances" }
```

---

## Settings Catalog

| Config path | Env var | Default | Description |
|---|---|---|---|
| `htc.time-manager.total-instances` | `HTC_TIME_MANAGER_INSTANCES` | `128` | Total LocalTM routees across the cluster |
| `htc.time-manager.max-instances-per-node` | `HTC_TIME_MANAGER_PER_NODE` | `4` | Max LocalTM instances per node |
| `htc.time-manager.lookahead-window` | `HTC_TIME_MANAGER_LOOKAHEAD` | `1` | Look-ahead window in ticks |
| `htc.time-manager.window-size` | `HTC_TIME_MANAGER_WINDOW_SIZE` | `1` | Window size in ticks |
| `htc.time-manager.metrics-log-interval` | `HTC_TIME_MANAGER_METRICS_INTERVAL` | `500` | Metrics log interval in ticks |
| `htc.time-manager.actor-timeout-ms` | `HTC_TIME_MANAGER_ACTOR_TIMEOUT_MS` | `180000` | Actor timeout in milliseconds |
| `htc.time-manager.sync-timeout-ms` | `HTC_TIME_MANAGER_SYNC_TIMEOUT_MS` | `30000` | Sync timeout in milliseconds |
| `htc.time-manager.verbose-logging` | `HTC_TIME_MANAGER_VERBOSE_LOGGING` | `true` | Enable verbose time manager logging |
| `htc.time-manager.snapshot-interval` | `HTC_TIME_MANAGER_SNAPSHOT_INTERVAL` | `10000000` | Snapshot interval in ticks |
| `htc.report-manager.enabled` | `HTC_REPORT_ENABLED` | `true` | Enable report manager |
| `htc.report-manager.json.number-of-instances` | `HTC_REPORT_JSON_INSTANCES` | `64` | Total JSON reporter instances |
| `htc.report-manager.json.number-of-instances-per-node` | `HTC_REPORT_JSON_PER_NODE` | `16` | JSON reporter instances per node |
| `htc.report-manager.json.batch-size` | `HTC_REPORT_JSON_BATCH_SIZE` | `500` | JSON reporter batch size |
| `htc.load-balance-manager.enabled` | `HTC_LOAD_BALANCE_ENABLED` | `false` | Enable load balance manager |
| `htc.load-balance-manager.strategy` | `HTC_LOAD_BALANCE_STRATEGY` | `default` | Load balance strategy: `hybrid` \| `default` \| `disabled` |
| `htc.simulation.config-file` | `HTC_SIMULATION_CONFIG_FILE` | _(none)_ | Path to simulation JSON configuration file |
| `htc.simulation.id` | `HTC_SIMULATION_ID` | _(none)_ | Human-readable ID for output files and reports. Defaults to simulation name. |
| `htc.mobility.city-map-file` | `HTC_MOBILITY_CITY_MAP_FILE` | `city_map.json` | Path to the city map JSON (Node/Link graph). **Required for mobility simulations** (Car, Bus, Bicycle, Motorcycle, etc.). |

---

## Configuration Priority

When multiple sources define the same setting, the highest-priority source wins:

```
1. API override   (PUT /api/v1/settings or settings field in /start body)
2. Env var        (e.g. HTC_TIME_MANAGER_INSTANCES in docker-compose)
3. application.conf defaults
```

Scenario config follows the same hierarchy:

```
1. configFile field in POST /api/v1/simulation/start body
2. POST /api/v1/scenarios/{name}/load  or  PUT /api/v1/simulation/config  (ApiConfigRegistry)
3. HTC_SIMULATION_CONFIG_FILE env var
4. htc.simulation.config-file in application.conf
```

---

## Typical Workflow (API mode)

### Option A — Browse and load a scenario from disk (recommended)

```bash
BASE=http://localhost:8080/api/v1

# 1. See what scenarios are available
curl $BASE/scenarios

# 2. Inspect a specific scenario (metadata + full simulation config)
curl $BASE/scenarios/toulouse_1pct

# 3. Load the chosen scenario into memory
curl -X POST $BASE/scenarios/toulouse_1pct/load

# 4. Optionally edit the loaded config before starting
#    (GET returns the config currently in memory)
curl $BASE/simulation/config
#    Modify what you need, then PUT it back:
curl -X PUT $BASE/simulation/config \
     -H "Content-Type: application/json" \
     -d '{...modified Simulation JSON...}'

# 5. Optionally tweak infrastructure settings
curl -X PUT $BASE/settings \
     -H "Content-Type: application/json" \
     -d '{"htc.time-manager.total-instances":"64"}'

# 6. Start the simulation
curl -X POST $BASE/simulation/start

# 7. Monitor progress
curl $BASE/simulation/status

# 8. Pause / resume if needed
curl -X POST $BASE/simulation/pause
curl -X POST $BASE/simulation/resume

# 9. Stop
curl -X POST $BASE/simulation/stop
```

### Option B — Upload the scenario JSON directly

```bash
# Upload scenario config
curl -X PUT $BASE/simulation/config \
     -H "Content-Type: application/json" \
     -d @simulation.json

# Start
curl -X POST $BASE/simulation/start
```

### Start with everything in one call

```bash
curl -X POST $BASE/simulation/start \
     -H "Content-Type: application/json" \
     -d '{
       "configFile": "/app/simulations/toulouse/simulation.json",
       "settings": {
         "htc.time-manager.total-instances": "128",
         "htc.report-manager.json.batch-size": "1000"
       }
     }'
```

---

## docker-compose example

```yaml
environment:
  HTC_API_ENABLED: "true"
  HTC_API_PORT: "8080"
  HTC_API_CORS_ORIGINS: "http://localhost:3000"
  # Directory with scenario sub-folders (each containing simulation.json + optional metadata.json)
  HTC_SCENARIOS_DIR: "/app/simulations"
  # HTC_SIMULATION_CONFIG_FILE is now optional — scenario can be selected via API
ports:
  - "8080:8080"
volumes:
  - ./simulations:/app/simulations:ro
```

---

## Endpoint Summary

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/health` | Liveness check |
| `GET` | `/api/v1/scenarios` | List all available scenarios |
| `GET` | `/api/v1/scenarios/{name}` | Full detail: metadata + simulation config |
| `POST` | `/api/v1/scenarios/{name}/load` | Load scenario into memory |
| `GET` | `/api/v1/simulation/config` | Get active scenario config (and source) |
| `PUT` | `/api/v1/simulation/config` | Set scenario config from request body |
| `DELETE` | `/api/v1/simulation/config` | Clear scenario API override |
| `GET` | `/api/v1/simulation/status` | Status + tick metrics |
| `POST` | `/api/v1/simulation/start` | Start simulation |
| `POST` | `/api/v1/simulation/pause` | Pause simulation |
| `POST` | `/api/v1/simulation/resume` | Resume simulation |
| `POST` | `/api/v1/simulation/stop` | Stop simulation |
| `GET` | `/api/v1/settings` | List all settings + effective values |
| `GET` | `/api/v1/settings/{key}` | Get a single setting |
| `PUT` | `/api/v1/settings` | Set multiple settings |
| `PUT` | `/api/v1/settings/{key}` | Set a single setting |
| `DELETE` | `/api/v1/settings` | Clear all setting overrides |
| `DELETE` | `/api/v1/settings/{key}` | Clear a single setting override |
