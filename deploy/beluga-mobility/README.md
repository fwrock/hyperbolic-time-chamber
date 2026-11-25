# Beluga mobility scheduling experiment

Standalone Linux amd64 engine deployment. This runs the current mobility MESO
implementation with infrastructure scheduling enabled for MESO and MICRO. It does
not replace the interactive platform, UI, or SimEDaPE images.

Build on the development machine from this repository:

```bash
python3 deploy/beluga-mobility/package.py
```

The resulting `../dist/beluga-mobility-<date>-<commit>.tar.gz` contains the engine
JAR, Docker image and pinned Redpanda image, source revision and image manifest,
checksums, both Compose topologies and three portable scenarios: `smoke` (4x4,
40 cars, MESO), `micro-smoke` (4x4, 40 cars, MICRO), and `meso-300` (8x8, 300 cars,
MESO). All use the mobility namespace.
The adjacent checksum verifies the complete archive. Nothing is pushed to a
registry or deployed remotely by the packaging command.

On Beluga, copy the archive and its `.sha256` file into the same directory:

```bash
sha256sum -c beluga-mobility-*.tar.gz.sha256
tar -xzf beluga-mobility-<date>-<commit>.tar.gz
cd beluga-mobility-<date>-<commit>
sha256sum -c SHA256SUMS
docker load -i images.tar
bash run.sh distributed on config -q
bash run.sh distributed on up -d
bash run.sh distributed on logs -f
```

`on` enables `LEAVE_WITHOUT_REPLY`, `LINK_OWNED_EXIT`, `LINK_REQUESTS_ACCESS`,
`LINK_LEAVES_AT_GRANT`, `LINK_PERMITS` and `MICRO_DELEGATED_SCHEDULING`, all under
the `HTC_MOBILITY_` prefix.
`DELEGATED_ENTRY_SCHEDULING` remains false: deferred replies with Link-owned exits
are the selected MESO flow. `off` disables all seven flags for a control run. Engine
defaults outside this experiment remain unchanged.

MICRO delegation keeps the Link's physics clock and per-step updates. The Link
asks the Node at the actual exit and combines the answer with the exit report.
Retained spillback footprints still wait for downstream admission before cleanup.
The earlier `beluga-mobility-2026.11.25-9d5fc73` archive predates MICRO delegation;
these source instructions describe the next build from the updated engine.

Use `single` instead of `distributed` for one JVM. The four-JVM deployment is on
one physical host with host networking, fixed membership and migration disabled.
All JVMs run the same image, mobility namespace and direct conservative runtime,
with node-local scheduling. Each JVM defaults to 28 CPUs, 32 GiB maximum heap,
40 GiB container memory and four scheduler workers. Edit `.env` for the actual
allocation before starting; the single profile also uses this per-JVM envelope.

Run one topology/arm at a time: they share host ports. Broker ports are 39092
(Kafka), 19644 (admin), 43145 (RPC), 18081 (schema registry), 18082 (HTTP proxy). Engine metrics are
19101 through 19104, cluster ports 2600 through 2603 and management ports 18558
through 18561. These differ from the interactive Beluga platform's usual ports.
An existing service using these ports must be stopped or the package adapted.

For another scenario, edit `HTC_SCENARIOS_DIR` to its parent directory and
`HTC_SCENARIO` to its folder name. The scenario must use `mobility` class types
and data paths accessible under `/scenarios`. Existing hybrid scenarios need
conversion before use. The bundled smoke input already uses portable paths.

Collect evidence before stopping a run:

```bash
bash run.sh distributed on logs --no-color > run-on.log
curl -fsS http://127.0.0.1:19101/metrics > node1-on.prom
# Repeat for ports 19102, 19103 and 19104 in the distributed profile.
bash run.sh distributed on cp node1:/reports ./reports-on
bash run.sh distributed on stop
```

Reports are in a shared named volume isolated by topology and arm. Save each
run's reports and metrics separately before repeating; reusing a project also
reuses its volumes. `down` removes containers without deleting report volumes.
Completion must be established from simulation logs and outputs; a running JVM
alone does not prove success. Compare completed trips, distances, waiting and
travel times against repeated flag-off controls, then compare message counts,
CPU and wall time. The congested model has baseline variation, so one pair does
not establish equivalence. SUMO/MATSim validation of mobility remains pending.
