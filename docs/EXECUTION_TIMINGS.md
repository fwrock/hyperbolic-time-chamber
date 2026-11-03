# Execution stopwatches

HTC measures host wall-clock durations with `System.nanoTime()`. These observations do not
change simulation ticks, route search budgets, model state, rollback decisions or migration
snapshots. They describe work actually executed by the JVM, including speculative work.

| Operation label | Start → stop |
|---|---|
| `config_load` | Configuration read request → parsed configuration or failure |
| `simulation_load` | Initial eager load request → eager actors ready; excludes warm-up and post-load registration |
| `progressive_index` | Progressive preparation/preflight → all source indexes built |
| `progressive_load` | Actual adaptive window processing → actors initialized and window ready; excludes idle gaps between windows |
| `simulation_loop` | Release of simulation execution → terminal retirement and committed model-report drain; includes human pauses and input waits, excludes final timing-summary export |
| `time_manager_synchronization` | Conservative frontier collection, terminal worker barrier, migration freeze/resume barriers; optimistic freeze/snapshot/commit/retirement/report-drain rounds |
| `load_balance` | Balancer initialization including authority binding, and synchronous spatial registration/allocation planning |
| `rebalance_evaluation` | One strategy evaluation, including evaluations producing no migrations |
| `rebalance` | Conservative migration freeze request → all live owners acknowledge resume |
| `route_vehicle` | One live compact vehicle-route invocation, including cache lookup, trivial and unreachable routes |
| `route_walking` | One live compact pedestrian-route invocation with the same boundaries |

Progressive loading and routing overlap the simulation loop. Concurrent route calls are additive
wall time, **not CPU time**. Synchronization spans include protocol waits and communication;
they are not an estimate of synchronization CPU overhead. Do not sum operations to obtain
end-to-end execution time. Precomputed routes and replay-journal reads that avoid calculation
are not new route calculations. Existing GPS histograms retain their algorithm/search semantics.

## Prometheus and HTC View

All samples use one bounded `operation` label; entity, route and process IDs are never labels.

- `htc_execution_duration_seconds`: histogram, completed span count and total seconds.
- `htc_execution_duration_max_seconds`, `htc_execution_duration_last_seconds`: completed extrema/latest.
- `htc_execution_failed_total`: attempts ending in an exception or cancellation; a returned
  “no path” is a completed call and remains visible in routing failure metrics.
- `htc_execution_active`: active span count.
- `htc_execution_elapsed_seconds`: completed seconds plus elapsed time of active spans at scrape time.

Counters and summaries accumulate from process startup. Start a fresh engine process when an
isolated run total is needed. Unused operations have no row. An active first span has elapsed
seconds but no completed mean/max/last. A span closes once, even on repeated cancellation.
Startup phase gauges also use monotonic elapsed time and ignore duplicate starts/ends.

HTC View exposes **Simulator → Metrics → Stopwatches** (`Cronômetros` in Portuguese), with
per-node counts, active work, failures, elapsed/completed seconds, mean, maximum and latest.
The table is process-cumulative regardless of the selected chart-history window. Configure every
engine endpoint in `HTC_METRICS_URL` to inspect every node. CSV, JSON, LaTeX and image exports
use the table’s numeric values; section/full PDF reports include it through the existing panel
report pipeline. Older engines show an explicit unavailable message.

## Durable reports

At normal completion the Report Manager requests a timing snapshot from each current cluster
node, writes `execution_timing_summary` events through every enabled reporter, and waits for
acknowledged storage flush before marking the run complete. The timing collector starts on every
engine JVM. Reporters running on only one node still receive the summaries of other nodes.
The existing runtime report-drain protocol remains in place before this final export.

JSON/CSV/Parquet/Kafka carry normal `ReportEvent` records. The payload includes `operation`,
`process_id`, `snapshot_timestamp_ms`, `count`, `failed_count`, `active_count`, `total_seconds`,
`elapsed_seconds`, `active_seconds`, `mean_seconds`, `max_seconds`, `last_seconds`,
`scope=process_cumulative`, `clock=monotonic`, and `unit=seconds`. The report envelope identifies
the source node. Summaries are host telemetry and are not replayed as model events.

ClickHouse uses the new `execution_timings` table, created alongside the existing tables.
For example:

```sql
SELECT node, operation, count, total_seconds, mean_seconds, max_seconds, active_count
FROM htc.execution_timings
WHERE simulation_id = 'my-run'
ORDER BY node, operation;
```

Repeated snapshots from a reused JVM are cumulative: use the latest row per
`(process_id, operation)` before aggregating, and subtract a baseline to compare runs in the
same JVM. Final export explicitly fails if a required node or sink is lost; it does not claim
successful completion after a failed write.
