# Bounded MICRO spillback and blocked handoff

Opt-in `LinkState.downstreamSpillback` retains the footprint of a Car whose
front bumper reached a Link boundary while downstream Node admission is pending.
The existing capacity protocol decides when the Car can enter the next Link.
The approach continues its ordinary physics ticks and represents that Car as a
stopped leader until the Car's `LeaveLinkData` releases the footprint.

The default is `false`. Enable it together with `downstreamLookahead` on every
road and connector in the tested route. It requires single-lane MICRO, one
physics step per runtime tick, matching clocks and static Car routes. Entry by
another vehicle type is rejected. An entry with insufficient rear-bumper space
fails explicitly; this work does not implement an origin-injection queue.

Acceptance recorded **2026-10-26**: all reserved cases and exact report comparisons
pass. The final build passes **775 Scala tests in 153 suites**, **34 Python tests**
and assembly. The [machine record](validation/micro-spillback-2026-10-26.json)
retains protocol/input/source hashes, executable identity, commands, outcomes and
excluded development evidence.

```json
{
  "simulationMode": "MICRO",
  "lanes": 1,
  "microTimeStep": 0.1,
  "microTicksPerGlobalTick": 1,
  "carryMicroRemainder": true,
  "downstreamLookahead": true,
  "downstreamSpillback": true,
  "stopLineGapM": 1.0
}
```

## Ownership, timing and replies

| Exchange | Obligation and completion |
|---|---|
| Link physics → `MicroLeaveLinkData` | One proactive notification. The Link completes its spontaneous event immediately and keeps scheduling while lane footprints remain. `VehicleInLane.pendingExitTick` prevents another leave or kinematic update for that footprint. |
| Car → Node `RequestLinkAccessData` | Existing correlated admission exchange. The Car registers its awaited reply and finishes its current dispatch with that obligation recorded. `Full` retains the original request identity and leaves the Car dormant. |
| Node → Car capacity grant | The existing queue drains on a freed slot, with the original `requestSeq` and connector selection. The Car consumes the wait and schedules its next action. No round-trip through the waiting Car is needed to produce the grant. |
| Car → old Link `LeaveLinkData` | Fire-and-forget cleanup after a proactive MICRO leave. Remove the retained footprint, registration and timing maps; free exactly one capacity slot. No second `MicroLeaveLinkData` is emitted. |
| Modeled cancellation | Cancel the buffered Node request, send ordinary old-Link cleanup, consume the Car's awaited reply and settle its capacity waiting time before terminal intent. Both sends use normal positive/anti transport. |
| Boundary occupancy publication | Existing fire-and-forget exchange. Retained footprints participate in nearest-rear selection. Receiving a publication adds no reply obligation or new spontaneous tick. |

The exit notification preserves the physical crossing speed and same-tick
remainder. A capacity stop clears the Car's carried speed; a delayed grant cannot
reuse the exit-tick remainder. The retained lane footprint itself has zero
velocity. Physics skips it but still integrates followers. In this opt-in mode,
a geometric front-to-rear cap preserves the 2 m gap even when a count-based gate
abruptly stops a leader faster than bounded car-following deceleration can follow.
That hard safety correction is not evidence of calibrated emergency braking.

`LeaveLinkData` and next-Link entry occur through the existing ordered runtime.
No Time Manager protocol, GVT rule or report-commit rule changes. Entries and
observations retain the previous interval contract: new entries are integrated
after their entry tick, and downstream physics uses publications strictly before
the integrating tick. A same-tick successful handoff adds no artificial dwell.

## Checkpoint and reporting contract

`pendingExitTick` lives in the typed `VehicleInLane` stored in `LinkState`.
`CarJourneyState` retains the old Link identity needed for cancellation cleanup
and the capacity-wait start tick. Existing Node state retains capacity counters,
FIFO requests, clearance, reply sequence and connector selection. Existing Link
snapshot hooks preserve entry/waiting maps and scheduled tick state. No new
behavior-affecting actor-local field is introduced.

Rollback before the exit restores a moving lane vehicle; rollback before cleanup
restores the stopped footprint and its registration. Ordinary anti-messages
retract undone notifications, capacity releases, requests and grants. Reports
remain buffered and committed by the existing runtime. JSON checkpoint and cold
codec tests cover the new fields alongside the restored correlated Node grant.

`leave_micro_link` records the front reaching the boundary and credits exactly
the completed Link distance once. `micro_exit_wait` supplies subsequent stationary
physical samples until cleanup. It is a Link report, with `car_id`, `link_id`,
`position`, `velocity`, `vehicle_length`, `lane`, `exit_tick` and raw `tick`.
Link summaries include retained footprints in running/halting counts. The Car
adds the elapsed capacity stop to journey waiting time when granted or canceled.
This corrects Car capacity-wait accounting for existing admission waits as well;
the opt-in switch controls lane retention and its physical spacing guard.
At an authorized horizon cut, existing reporting-only finalization emits
censored journeys with arrival `-1` and completed-Link distance. It sends no
cleanup messages and credits no unfinished Link distance.
Car emission accumulators still close at proactive MICRO exit; capacity dwell
does not yet contribute idle-emission integration. These tests establish no new
emissions accuracy claim.

Retaining footprints keeps blocked Links active and adds per-tick wait reports,
boundary publications and checkpoint state. No city-scale overhead or memory
bound has been measured.

## Validation protocol

The new [v1 spillback protocol](../scripts/scenario/micro_spillback_protocol_v1.json)
was frozen before implementation and reserved execution. SHA-256:

```text
43dba1baf951e7cbd0f58f2f9a21fcc9e2e0151b4f519ca8921bef2b62685b6e
```

It separates bounded engineering invariants from scientific SUMO or empirical
claims. The previous SUMO v1/v2 protocols are unchanged. A development fixture
uses a capacity-one downstream road held red until 60 s; reserved cases change
road lengths, demand, connector occupancy and the horizon. Acceptance requires
complete physical traces, front-to-rear spacing, real upstream queues, finite
admission counts, correct journeys and exact report parity. A partial trace
cannot pass just because its remaining samples have a good gap.

The first development setup failed to publish initial red, so it never exercised
blocking and is excluded. The corrected generator initializes the signal's
publication state opposite the actual tick-zero phase to force the initial
transition. The old-build baseline then exposes missing Node-resident vehicle
samples. Failures and intermediate development builds remain retained.

## Reproduction

The final executable is `/tmp/htc-spillback-final-20261026.jar`, SHA-256
`a35e9905d0719fce78027d6527f66e05a63ab818f452837ee81f710a0a70364f`.
Raw results are retained under `/tmp/htc-spillback-20261026/`; temporary artifacts
may disappear after a reboot. The repository protocol, generator, tests and machine
record retain the reproduction inputs and outcome evidence.

| Fixture | Outcome per execution | Physical and runtime evidence |
|---|---|---|
| Capacity-one development, seed 1201 | Eight completed journeys | Old build loses 3,555 blocked samples. Final build retains seven blocked Cars, continuous trajectories and a minimum 2 m gap. |
| Reserved short road, seed 1301, both policies | Ten completed journeys | Capacity never exceeds two; a stopped upstream follower remains behind the retained footprint; all 8,104 report rows match. |
| Reserved connector, seed 1409, both policies and one/two JVMs | Ten completed journeys | Connector capacity stays at one and road capacity at two. All 9,291 rows match across policy/layout comparisons. Delayed two-JVM Time Warp executes 4,468 rollbacks with cross-owner transfers. |
| Reserved blocked horizon, seed 1511, both policies | Eight censored journeys; zero completions | Complete traces through 50 s, completed-Link distances only, arrival `-1`; all 4,017 report rows match. |

Nine accepted spillback executions contain 68 completed and 16 censored journeys;
repeated policies/layouts are included and are not independent traffic samples.
The minimum observed gap in every fixture is at least 2 m within the frozen
1e-6 m comparison tolerance. All ten final-build policy/layout/compatibility
comparisons pass. Compatibility includes the unchanged v2 signal-right fixture
(9,255 rows and the original SUMO criteria under both policies), cyclic MICRO
Bus/Subway births and passenger distances (1,811 rows), and the legacy one-second
1,200 m Car route (213 rows, arrival at 98 s). These are fresh representative
regressions, not a rerun of the entire earlier v2 scientific campaign.

```bash
python3 scripts/scenario/validate_micro_spillback.py generate \
  --output /tmp/htc-spillback-reproduction --case reserved-short-road
sbt -J-XX:ActiveProcessorCount=4 test assembly
python3 scripts/scenario/run_cluster_validation.py \
  --jar target/scala-3.3.5/hyperbolic-time-chamber-3.2.0.jar \
  --simulation /tmp/htc-spillback-reproduction/discrete-event.json \
  --extra-config /tmp/htc-spillback-reproduction/reporting.conf \
  --output /tmp/htc-spillback-reproduction-de --base-port 23100 --nodes 1 --timeout 240
python3 scripts/scenario/validate_micro_spillback.py inspect \
  --scenario /tmp/htc-spillback-reproduction --run /tmp/htc-spillback-reproduction-de \
  --output /tmp/htc-spillback-reproduction-de/physical.json
```

Repeat with `time-warp.json`, a fresh output directory and separate ports; compare
the full report directories using `compare_policy_reports.py`. Use `--nodes 2
--delay-ms 1` on the cluster runner for the delayed distributed case. Other case
IDs are `development-capacity-one`, `reserved-connector` and
`reserved-horizon-blocked`. `--legacy` generates the missing-footprint baseline
without the new setting. Input hashes, protocol identity and expected outcomes
are written to `canonical.json`; run results retain executable identity and
runtime completion separately from physical acceptance.

## Claim boundaries

This represents one retained boundary footprint per vehicle along the supported
single route. Count capacity is an admission constraint, not a measured saturated
capacity. Protected, non-competing movements and an optional fixed connector do
not establish competing conflict occupancy, gap acceptance, multi-lane spillback,
arbitrary short-road chains or an origin-admission model. Waiting across a signal
change retains the existing checkpointed clearance contract. Independent field
following/discharge validation and cost measurements remain open.
