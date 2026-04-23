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
2. PUT /api/v1/simulation/config (ApiConfigRegistry)
3. HTC_SIMULATION_CONFIG_FILE env var
4. htc.simulation.config-file in application.conf
```

---

## Typical Workflow (API mode)

```bash
BASE=http://localhost:8080/api/v1

# 1. Upload the scenario
curl -X PUT $BASE/simulation/config \
     -H "Content-Type: application/json" \
     -d @simulation.json

# 2. Optionally tweak settings
curl -X PUT $BASE/settings \
     -H "Content-Type: application/json" \
     -d '{"htc.time-manager.total-instances":"64"}'

# 3. Start the simulation
curl -X POST $BASE/simulation/start

# 4. Monitor progress
curl $BASE/simulation/status

# 5. Pause / resume if needed
curl -X POST $BASE/simulation/pause
curl -X POST $BASE/simulation/resume

# 6. Stop
curl -X POST $BASE/simulation/stop
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
  # HTC_SIMULATION_CONFIG_FILE is now optional — can be sent via API
ports:
  - "8080:8080"
```
