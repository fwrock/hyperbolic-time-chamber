# Cross-JVM message and time measurement

The optional `RemoteTrafficInstrument` counts outgoing and incoming Artery
envelopes at the serialization/deserialization boundary. This includes scheduler,
runtime and cluster traffic that model-level `NodeIdentity` counters do not cover.
It is disabled unless explicitly added to Pekko's instrument list:

```hocon
pekko.remote.artery.advanced.instruments = [
  "org.interscity.htc.core.metrics.RemoteTrafficInstrument"
]
```

Use identical instrumentation on every benchmark JVM and for both strategies.
No metadata is written, no serialization timing is requested and no protocol
acknowledgements are added. The implementation uses Pekko 2.0.0's internal
monitoring SPI; recheck it when changing that dependency. The source contract is
[Pekko RemoteInstrument](https://pekko.apache.org/api/pekko/2.0.0/org/apache/pekko/remote/artery/RemoteInstrument.html).

## What the counters mean

Prometheus metrics are `htc_remote_messages_total{direction,category}` and
`htc_remote_serialized_bytes_total{direction,category}`. Direction is `sent` or
`received`; categories are bounded:

| Category | Envelope classification |
|---|---|
| model | Direct interactions or raw model interaction events, including recognized wrappers |
| scheduling | Spontaneous/root dispatch, callback effects, accounted receipts, schedule/finish controls |
| coordination | Other recognized conservative, partition and runtime protocol messages |
| reporting | Recognized report payload/delivery/flush messages |
| other | Cluster infrastructure and all unrecognized envelopes |

Each envelope is classified once. A `DirectProcessed` envelope containing several
schedule/report effects is one scheduling message, not one message per embedded
effect. Envelope unwrapping is depth-bounded. Local actor sends never reach this
instrument. Categories support interpretation; their sum is the all-envelope total.

Sum **sent** counters across JVMs to count cross-JVM transfers once. Adding sent
and received double-counts the same transfer. These are serialized envelope bytes
including Pekko headers, not TCP packets or total NIC bytes. TCP/IP framing,
retransmission below Artery, Kafka and HTTP are outside this measurement.

Final `remote_transport_summary` report rows carry process identity, direction,
category, messages, serialized bytes and boundary/scope metadata. ReportManager
collects them alongside the existing execution timing snapshots from each JVM and
drains them through the ordinary report sinks. This avoids relying on a sampled
Prometheus value that may precede the end of a short simulation.

The scope is process-cumulative until each JVM's final timing capture: it includes
startup, clustering and progressive loading, excludes subsequent teardown and is
not a simultaneous global network snapshot. Active simulation messages have already
settled, but final capture/summary traffic and some background traffic fall outside
the snapshots. Instrumentation overhead is not zero and can depend on message rate.

## Paired comparison runner

`scripts/scenario/benchmark_node_local_scheduler.py` runs two JVMs sequentially for
each strategy, with alternating pair order, optional excluded warm-up pairs and
repeated measured pairs. It uses the same executable/input and equal CPU affinity,
heap and configured worker budgets. The new strategy also has a node dispatcher;
its overhead is included. Every run must pass the existing person-owned Car mobility
validator before its measurements are accepted.

```bash
python3 scripts/scenario/benchmark_node_local_scheduler.py \
  --jar target/scala-3.3.5/hyperbolic-time-chamber-3.10.0.jar \
  --simulation /tmp/my-scenario/simulation.json \
  --output /tmp/scheduler-comparison \
  --workers-per-node 3 --repetitions 3 --warmup-pairs 1 \
  --base-port 28600 --timeout 180 --cpu-ids 0,1,2,3
```

Use a MESO-only or MICRO-only generated Person/Car scenario and specify `--mode
MICRO` for the latter. CPU IDs must be available and distinct; inspect topology
when interpreting logical versus physical CPU counts. A fresh output directory is
required. Each node is limited to two logical CPUs and 2 GiB maximum heap. JSON
reporting uses the same batch size for both strategies; Prometheus scraping is
disabled during the measurement, while external process sampling remains enabled.

The runner retains raw launch configurations, reports, executable hashes, process
samples, per-run measurements, mobility outcomes, medians and observed ranges.
`simulationSeconds` is the GTM's settled monotonic `simulation_loop` stopwatch.
`completionSeconds` measures launch to engine stop notification, excluding harness
teardown. Startup and simulation time must be presented separately. Synchronization
time, CPU seconds and simultaneous peak RSS provide additional context.

Independent sharding placement and same-tick arrival order can vary between runs.
The runner records per-type/node placement counts and checks paired trip counts and
distances; completion tick differences remain visible in the retained outcomes.
Equal logical CPU budgets do not guarantee an idle host. The single-host loopback
pilot is not evidence of multi-host network latency or city-scale speedup. Small
repetition counts and instrumented timing require appropriately limited conclusions.
