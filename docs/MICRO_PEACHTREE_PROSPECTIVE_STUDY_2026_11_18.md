# Prospective MICRO component study: Peachtree

Date: 2026-11-18. This starts the first new-site empirical experiment under the
[MICRO campaign](MICRO_EMPIRICAL_VALIDATION_PLAN.md). The protocol and source code
are recorded before requesting individual trajectories from the reserved recording.
Execution completion and scientific acceptance are separate outcomes.

## Executed outcome: inconclusive recruitment gates

The prospective run completed after protocol/code publication in commit `a1d3e15`.
It acquired 457,412 unique rows for 1,222 vehicles from the reserved recording.
Nine following episodes qualified, against the fixed requirement of ten; no
queue qualified, against the requirement of five. All selected primary predictions
are complete and have nonnegative bumper gaps. Both seed-42 exact-repeat checks
pass. No target, exclusion rule or physics parameter was changed after acquisition.

| Following, nine held-out episodes | Position RMSE | Causal speed RMSE |
|---|---:|---:|
| HTC deterministic, primary | 1.82685 m | 0.70674 m/s |
| HTC stochastic, secondary five-seed average | 1.77418 m | 0.69204 m/s |
| Causal rigid spacing | 5.72716 m | 1.19780 m/s |
| Constant velocity | 15.41437 m | 3.14656 m/s |

The paired following skill comparisons pass their prespecified uncertainty checks:
the upper adjusted one-sided bound on HTC-minus-baseline position-RMSE difference
is -7.32345 m against constant velocity and -0.98945 m against causal rigid spacing.
Nevertheless, the corresponding overall conditional gate does not pass because
the recruitment requirement is unmet. Queue skill is unassessed, not evidence of
poor queue predictions: there are no selected queue episodes to predict.

There were 159 following candidate rejections: 78 lane changes, 51 class/direction/
movement changes, 26 incomplete observations, three excessive initial gaps and one
section/intersection crossing. None qualified in observation block 5; the other
nine blocks each supplied one episode. Do not retrospectively add another candidate
time or lower the required count to make this study pass.

All 194 queue candidates failed the frozen observed-release eligibility rule.
Of these, 142 had no detected eligible head transition within the search window.
The other candidates produced 52 rejected release windows: 29 lane changes,
16 incomplete station crossings, six insufficient post-crossing observation pads
and one common forecast window shorter than five seconds. These diagnostics were
examined after preserving the prospective outcome. They may inform future
development, but this recording is now inspected and must not be reused as fresh.

The [machine result](validation/micro-peachtree-prospective-2026-11-18.json)
retains gates, primary episode scores, hashes and preregistration identity. The
[local report and all nine figures](../../output/validation/micro-peachtree-20261118/report.html)
include the complete selected cohort. All frozen evidence hashes, 72 scored
case records, repeat outputs and local report links passed recomputed integrity
checks. The post-study exposure ledger has 3,696 identities, including unused
reserved-source subjects.

The next study needs a new reserved recording/dataset with demonstrated coverage
of stable following and complete queue-release chains. Develop an event-based
queue-recruitment design on now-released observations, with a fixed future search
budget, before opening that new holdout. Preserve all v1/v2/v3 outcomes; repeated
search must not become a process of reporting only the first passing cohort.

Implementation clarification: the inherited candidate-budget key applies per
kind per candidate snapshot (up to 12), with four candidate snapshots in each
90-second block. At most one episode per kind/block is selected. Bulk acquisition
uses the sealed runner's 50,000-row, 30 MB response budgets; inherited historical
per-candidate-query metadata fields are not the bulk downloader's limits. These
behaviors are defined by the source sealed before reserved acquisition.

## Claim being tested

Test conditional longitudinal predictive skill of the production HTC MICRO physics
on stable northbound through passenger-car chains in a previously unopened
Peachtree recording. Evaluate following and observed queue release separately.
The measured head leader supplies the causal boundary; initialized followers evolve
autonomously. There is no follower resetting, fitting on the reserved outcomes,
best-seed selection, or outcome-based removal of selected cases.

This experiment does not complete broad MICRO validation. It does not test actor
scheduling, autonomous signal response, lane changes, a whole corridor, safety or
absolute accuracy for an application. Absolute application tolerances are not
defined by this protocol. Report absolute errors even if comparative skill passes.

## Data access and split

The official [NGSIM catalog](https://data.transportation.gov/d/8ect-6jqj) is
accessible through the Socrata API. Metadata and aggregate recording inventories
were inspected before any reserved trajectory. Source payloads, URLs, hashes and
license/attribution are retained in the experiment directory.

Peachtree has two distinct recording-origin keys:

| Role | `global_time - 100 * frame_id` | Individual trajectory access |
|---|---:|---|
| Development | 1163018600 | First 120 seconds, then an eight-minute development expansion |
| Reserved | 1163030300 | Closed until the prospective protocol and executable/source seals are written |

**Raw `global_time` ranges overlap between these recordings.** A range-only query
can mix the partitions. Every trajectory request therefore restricts location,
recording-origin key and frame range; every returned row is checked against those
restrictions. Within-recording physical intervals use frame differences at 0.1 s.
The raw values are not assumed to be correctly encoded epoch milliseconds.

The retained Lankershim v1/v2 and US-101 pilot archives expose 1,898 composite
location/recording/vehicle identities, including rejected candidates. The expanded
development audit adds 576 Peachtree identities, yielding a pre-holdout ledger of
2,474 identities. No reserved-origin identity appears in that supplied ledger.
The check covers retained workspace sources and declared development acquisitions;
unrecorded external exposure cannot be independently verified. Recording keys do
not prove that a physical vehicle never reappears in another session.

The Peachtree metadata describes supporting signal and geometry files. Their
existence in documentation is not evidence that valid synchronized controls have
been imported. Those inputs belong to a later autonomous actor/segment study.

## Development work and changes before freezing

The first two-minute audit retrieved 24,777 rows for 154 vehicles. The first pilot
qualified two following episodes and no queue episodes; it rejected 31 candidates.
HTC position RMSE was 2.112 m, compared with 29.126 m for constant velocity and
12.275 m for the legacy current-head rigid-spacing diagnostic. These are
development results, not final acceptance evidence. Exact deterministic replay
passed. No traffic coefficient was changed.

Development coverage was expanded to the first eight minutes of the same recording
to investigate eligibility and recruitment. This acquisition contains 182,346 rows
for 576 vehicles. The reserved recording remained closed throughout this work.
Adaptive development recruitment must not be confused with changing a frozen
held-out selection after observing model errors.

The expanded development pilot qualified 12 following episodes and no queues,
with 257 candidate rejections. Mean HTC position RMSE was 1.704 m versus 18.879 m
for constant velocity and 8.231 m for causal rigid spacing. These results suggest
following recruitment is feasible but queue recruitment is uncertain. They do not
authorize relaxing the reserved ten/five gates. Deterministic replay passed.

The new causal rigid-spacing baseline uses the head position and causal speed at
the start of each interval, extrapolating that head by one 0.1 s step. It receives
the same timing information as HTC. The older diagnostic copies the current head
displacement and is kept distinct. A regression test verifies that changing a
future head frame cannot alter the preceding causal-baseline prediction.

## Prospective protocol

The [v3 protocol](../scripts/scenario/micro_empirical_protocol_v3.json) fixes:

- Frames 1–9000 of the reserved recording: ten 90-second observation blocks.
- Candidate frames 100, 200, 300 and 400 within each block; numeric identity
  ordering and a fixed candidate budget. At most one episode of each kind per
  block; no vehicle identity reuse among selected episodes.
- The inherited stable-lane/leader, passenger-car, through-movement and measurement
  rules. Following requires one nonzero road section and no recorded intersection.
- A one-second initialization prefix and ten-second following forecast. Queue
  release is aligned from observed head motion under the existing fixed rule;
  prediction lasts at most twenty seconds. Observed complete discharge is an
  eligibility condition, so the study does not estimate all-traffic discharge rates.
- Minimum ten following and five queue episodes, retaining the historical minimum
  requirements without asserting a powered population study. If recruitment fails,
  the corresponding data gate is inconclusive; do not add favorable cases or lower
  the minimum after opening the reserved recording.
- Primary deterministic sigma=0, seed 42; secondary sigma=0.5, seeds 42–46.
  Secondary metrics average separately scored seeds within episode.
- Unchanged acceleration 2.6 m/s², normal deceleration 4.5 m/s², reaction time 1 s,
  minimum gap 2 m and a transferred 56.32704 km/h model cap. The cap is a fixed
  model assumption, **not a verified Peachtree posted speed limit**.

No Peachtree traffic parameter fitting is performed. Development informed tooling,
selection feasibility and measurement design only. The executable is a hash-pinned
working-tree build of HTC 3.10.0, not a claimed clean build of a documentation commit.

## Measurements and decision rule

Primary following comparisons use position RMSE versus constant velocity and
causal rigid spacing. The queue comparison uses discharge-headway MAE versus
causal rigid spacing. Initialization is excluded. Speed uses the same causal
past-one-second position derivative for observations and predictions. Episode
metrics average queue followers; cohort metrics weight episodes equally.

Each of the three planned paired comparisons uses 10,000 fixed-seed bootstrap
resamples and a one-sided upper percentile bound at `1 - 0.05/3`. A skill comparison
passes only if that upper bound is strictly negative. Each corresponding claim
also requires its recruitment gate, complete primary prediction/crossing coverage
and nonnegative primary bumper gaps. Missing crossings invalidate affected headway
comparisons rather than disappearing from the cohort.

At most one episode of each kind occurs per block, so the within-kind resampling
unit corresponds to the observation block, not individual frames or followers.
Shared conditions and temporal dependence within the single recording remain.
These are exploratory within-recording uncertainty summaries, not proof of
population-level superiority. Comparative skill, absolute application adequacy,
and whole-model validity remain distinct.

## Execution and reproducibility

Tools:

- [Development acquisition audit](../scripts/scenario/micro_empirical_data_audit.py).
- [Development component pilot](../scripts/scenario/micro_empirical_pilot.py).
- [Prospective held-out runner](../scripts/scenario/micro_empirical_holdout.py).

From the platform root:

```bash
python3 hyperbolic-time-chamber/scripts/scenario/micro_empirical_holdout.py \
  --protocol hyperbolic-time-chamber/scripts/scenario/micro_empirical_protocol_v3.json \
  --exposure experiments/micro-empirical-v3-20261118/pre-holdout-exposure-ledger.json \
  --metadata experiments/micro-empirical-v3-20261118/metadata.json \
  --jar hyperbolic-time-chamber/target/scala-3.3.5/hyperbolic-time-chamber-3.10.0.jar \
  --output experiments/micro-empirical-v3-20261118/heldout
```

The output directory must be new. Before acquisition, the runner copies the
protocol, metadata, exposure ledger, executable and relevant analysis sources,
then writes their hashes and a timestamped seal. It seals selected observations
and rejections before producing predictions. Both deterministic and stochastic
seed-42 execution have exact-repeat checks. The shared measurement module lives
in `tools/ngsim-validation/metrics.py` in the enclosing platform workspace.

Eleven targeted Python regressions pass before reserved acquisition, including
recording isolation, duplicate conflicts, raw-source tampering, causal-baseline
timing, missing crossings and strict skill bounds. Subsequent measurement failures
must preserve the frozen predictions and failed attempts; they do not authorize
retuning or treating inspected reserved observations as fresh again.

Local artifacts are under
[`experiments/micro-empirical-v3-20261118/`](../../experiments/micro-empirical-v3-20261118/).
Raw observations and large binaries are local evidence; these links may not resolve
in a standalone engine checkout. Source observations retain USDOT/FHWA attribution
and CC BY-SA 3.0 metadata. A later result record must report every gate and must not
equate a successful run with general empirical acceptance.
