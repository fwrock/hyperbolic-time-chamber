# HTC traffic fidelity and runtime comparison report

The complete English research report compares HTC with SUMO, conservative with
optimistic execution, and conservative fixed placement with rebalancing. It
includes traffic metrics, execution times, CPU, memory, load and CPU imbalance,
verified relocation, pause costs, historical failures and empirical limitations.

- [PDF report](reports/htc-comparison-2026-10-27/htc-comparison-report-2026-10-27.pdf)
- [Editable Word report](reports/htc-comparison-2026-10-27/htc-comparison-report-2026-10-27.docx)
- [All attempts](reports/htc-comparison-2026-10-27/all-attempts.csv)
- [Configuration summaries](reports/htc-comparison-2026-10-27/configuration-summary.csv)
- [Metrics and paired ratios](reports/htc-comparison-2026-10-27/summary.json)
- [Standalone PNG and SVG figures](reports/htc-comparison-2026-10-27/figures)
- [Evidence archive index](validation/comparison-2026-10-27/evidence-index.json)

For the focused evaluation of whether the simulated traffic outcomes are similar,
see [HTC and SUMO traffic outcome similarity](reports/htc-sumo-outcomes-2026-10-27/htc-sumo-traffic-outcomes-2026-10-27.pdf),
with six chart sets and detailed explanations.

## Completed measurements

The frozen manifest contains 19 configurations, one excluded warm-up and five
measured repetitions each. **All 114 planned attempts are accounted for; all 95
measured attempts pass their designated runtime and physical checks.** All 96
report comparisons pass, including the designated reference self-comparisons.

| Workload | SUMO elapsed median | HTC conservative 1 JVM | HTC optimistic 1 JVM |
|---|---:|---:|---:|
| Free flow | 0.131 s | 13.669 s | 14.378 s |
| Following | 0.126 s | 13.349 s | 16.077 s |
| Signal right turn | 0.201 s | 16.361 s | 24.144 s |

These are output-inclusive, small-workload measurements on one shared host.
Four logical CPUs and a total 4 GiB maximum Java heap are held fixed when changing
JVM count. Startup, detailed output and coordination costs are included. Host
load averages during measured runs range from 17.17 to 32.35; affinity does not
reserve exclusive CPUs. No city-scale or multi-machine speedup is established.

Conservative rebalancing has paired median elapsed changes of **+7.1%** for the
initial fixture and **+3.8%** for the progressive fixture. Individual ratios vary
widely and remain visible in the report. Each treatment run verifies physical
shard movement; each progressive treatment restores five Cars during road
occupancy. Median completed pause totals are 2.611 and 2.583 seconds respectively.
Initial placement, physical movement and active restoration are distinct checks.
Optimistic migration remains unsupported.

The original campaign consumes 63.4 minutes of recorded attempt time, including
9.5 minutes of warm-ups and harness cleanup. Ratios use completion time before
cleanup instead of the legacy elapsed field. All planned attempts were retained;
none was automatically replaced to obtain a favorable result.

## Memory measurement audit

The original SUMO `wait4` high-water RSS includes the large Python fork image
before exec. Those measurements remain immutable in the raw evidence. A separate
frozen **18-attempt RSS audit** uses GNU time's small post-exec process to launch
SUMO, with one warm-up and five measured repetitions for all three cases. Every
supplemental trip record matches its original case/repetition exactly. Only the
native SUMO RSS series uses the supplement; original elapsed and CPU values are
unchanged. Native SUMO median peaks are approximately 33 MiB. The source table
retains the original high-water value and names the replacement measurement source.

## Scientific interpretation

The representative signal experiment has trip-time MAE 0.061 s, route-position
RMSE 0.493 m, speed RMSE 0.106 m/s, queue-count RMSE 0.191 vehicles and matched
headway MAE 0.019 s. Every scheduled vehicle completes. The fixed-horizon cohort
completion rate is not saturated capacity.

The new runs reuse released cases for performance measurement. They are not new
held-out scientific observations. The historical v1 demand failures remain
failures. The later frozen v2 comparisons and the conditional field assessment
retain their original decisions. Field v2 remains inconclusive at eight of ten
required following episodes and four of five required queues.

## Reproduction

```bash
sbt -J-XX:ActiveProcessorCount=4 test assembly
python3 scripts/scenario/run_comparison_study.py \
  --output /tmp/htc-comparison-fresh \
  --jar target/scala-3.3.5/hyperbolic-time-chamber-3.2.0.jar --freeze
python3 scripts/scenario/run_comparison_study.py \
  --output /tmp/htc-comparison-fresh --run
python3 scripts/scenario/audit_sumo_memory.py --study /tmp/htc-comparison-fresh
python3 scripts/scenario/build_comparison_report.py \
  --study /tmp/htc-comparison-fresh \
  --output /tmp/htc-comparison-fresh-report --analyze
```

Use the bundled document runtime for the builder's `--docx` stage and the document
skill's renderer for the corresponding PDF. Source snapshots, raw observations,
commands, process and Prometheus samples, reports, hashes and engineering logs
are retained in [the evidence archives](validation/comparison-2026-10-27).
The executable also has a durable local copy at
`artifacts/htc-comparison-2026-10-27/htc-comparison.jar`; its identity is recorded in
the evidence index. Rebuilding a later source revision creates a new executable
and must not silently reuse the original measurement identity.

Engineering checks pass **780 Scala tests in 154 suites**, **56 Python tests**,
assembly and `git diff --check`. The Word document and exported PDF are rendered
and visually inspected before delivery. Traffic physics and scientific criteria
are unchanged; Scala changes add observational migration timing at existing
freeze and resume acknowledgements.
