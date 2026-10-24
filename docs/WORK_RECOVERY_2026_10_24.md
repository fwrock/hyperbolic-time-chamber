# Work recovery — 2026-10-24

The latest completed milestone is
[finite MICRO junction geometry](JUNCTION_GEOMETRY.md), on top of the pushed
point-node fixes in `a06c1b6`. Fixed Link connectors model internal movement length,
path coordinates and turn speed, with approach braking and checkpointed Node
selection. The cyclic Bus cursor and original road request identities are preserved.

`sbt test assembly` passes 635 tests in 133 suites; a subsequent six-test cold-codec
run with non-default new IDs and all 11 Python harness tests pass. Executable SHA-256:
`ab7d501e8ed104c1511386bc36732f744fc763201bb9bd7079a948ea4c1a0045`.
Build log: `/tmp/htc-junction-build.log`. All 12 real-SUMO runs and two mixed MICRO
transit runs on two JVMs pass; all seven full-report policy comparisons match.
The Bus traverses 39 connectors per run, and the optimistic case exercises 129
rollbacks. Full retained commands, input/output hashes, measurements and exclusions:
[`validation/junction-geometry-2026-10-24.json`](validation/junction-geometry-2026-10-24.json).
Raw inputs/output: `/tmp/htc-junction-20261024/`; `/tmp` may disappear on reboot.

The next timing improvement is fractional cross-link carryover. Conflict priority,
gap acceptance, multi-lane turning, turn-aware routing costs and general city-map
geometry import remain separate model work. The accepted geometry reference uses
protected movements; it does not establish accuracy for competing cross traffic.
See the newest `KNOWN_GAPS.md` section before resuming broader calibration.

The interrupted worktree was recovered on top of commit `240da8e` (cross-JVM codecs,
scenario resources and warm-up readiness). The unfinished milestone was an accounted
dynamic-routing adapter. The validated routing milestone was committed and pushed
as `6b59a5d` on `feat/time-warp`.

The walking-distance follow-up now checkpoints measured routes, counts completed
walks in daily totals and preserves distance across multimodal leg transitions.
Its full suite passes 614 tests in 131 suites. All ten bounded simulator cases
pass, with seven identical full-report comparisons, including delayed two-JVM
MESO execution. See [the walking contract](WALKING_DISTANCE.md) and
[accepted evidence](validation/walking-distance-2026-10-24.json). Build log:
`/tmp/htc-walking-full-build.log`; generated validation inputs and raw output:
`/tmp/htc-walking-20261024/`.

Walking was committed and pushed as `85d9343`. The subsequent real-SUMO audit and
MICRO boundary fixes are described in
[the current comparison contract](SUMO_MICRO_VALIDATION_2026_10_24.md).
Its accepted-build log is `/tmp/htc-sumo-accepted-build.log`; raw old/current
comparisons and exact per-run commands are under `/tmp/htc-sumo-audit-20261024/`.
The final build passes 626 Scala tests in 132 suites and seven Python measurement
tests. Ten real-SUMO runs and two mixed MICRO regressions pass; six complete
conservative/optimistic report comparisons match. Accepted evidence is retained in
`docs/validation/sumo-micro-2026-10-24.json`, including the isolated 30-tick node-wait
reproducer and its correction. Remaining crossing errors include whole-tick
rounding; finite geometry was addressed by the subsequent opt-in milestone above.
Broader SUMO calibration remains open.

The preceding horizon/transit reporting milestone was committed and pushed as
`c49b213` and is described in
[the reporting contract](HORIZON_AND_TRANSIT_REPORTING.md). Its full test run passes
605 tests across 130 suites (`/tmp/htc-horizon-pt-full-tests.log`). All 14 simulator
cases pass independent physical acceptance, with eight matching full-report
comparisons, including delayed two-JVM MICRO transit. The accepted record is
`docs/validation/horizon-pt-2026-10-24.json`; raw logs and generated inputs are under
`/tmp/htc-horizon-pt-20261024/`. See
[validation and reproduction](HORIZON_TRANSIT_VALIDATION_2026_10_24.md).

## Completed routing milestone

Read [accounted routing](ACCOUNTED_ROUTING.md), then its
[validation record](ACCOUNTED_ROUTING_VALIDATION_2026_10_24.md). The new adapter uses
ordinary routing LPs and the existing policy ledgers, with checkpointed costs,
pending requests, correlated replies and rollback cancellation. The recovered
implementation was completed with worker-source validation, independent cold-peer
codec coverage, anti-message tests and consistent empty/occupied MICRO costs.

`sbt test assembly` passes 596 tests in 128 suites. Final build log:
`/tmp/htc-routing-recovery-final-build.log`. The executable is
`target/scala-3.3.5/hyperbolic-time-chamber-1.24.3.jar`.
All eight final MESO/MICRO policy/JVM cases pass strict physical acceptance, and
all six report comparisons match. The repository retains the machine-readable
results in `docs/validation/accounted-routing-2026-10-24.json`.

Raw scenario logs, generated inputs, exact per-node launch commands and reports are
under `/tmp/htc-routing-recovery-20261024/`. `/tmp` may disappear on reboot; the
repository retains generation/validation scripts and the validation record so the
campaign can be reproduced. Earlier failed fixture/harness attempts in that directory
are not accepted evidence; use the cases named in the final validation record.

## Remaining work after this milestone

- Censored SUMO records contain confirmed terminal fields, without invented
  waiting/lane/speed statistics or partial-walk distance. Horizon finalization,
  PT/Subway reporting and completed walking distance are covered above.
- Replicated routing maps and full snapshots need measured memory/throughput
  validation before making city-scale or million-actor claims.
- Multi-host partitions, coordinated recovery, ownership migration and arbitrary
  external live feedback remain outside the supported execution scope. Migration
  stays disabled under the current project priority.

Use the latest dated sections at the top of `KNOWN_GAPS.md`; older historical
entries include superseded findings and test counts.
