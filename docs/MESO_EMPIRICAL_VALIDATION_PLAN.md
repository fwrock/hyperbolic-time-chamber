# MESO empirical validation campaign

Date: 2026-11-18. Status: proposed campaign and development kickoff, not a frozen
empirical protocol or an empirical acceptance result.

## Objective

Evaluate production HTC MESO against independent field observations for declared
road classes, traffic regimes and network boundaries. Progress from individual
segments to interacting subnetworks and a corridor. Evaluate fixed-route traffic
dynamics before making separate claims about route choice or mode choice.

This is the MESO companion to the [MICRO campaign](MICRO_EMPIRICAL_VALIDATION_PLAN.md).
The modes share independent evaluation, provenance and failure-handling principles,
but have different primary observables: MESO emphasizes flows, speeds, travel
times, accumulation and congestion dynamics rather than vehicle-following traces.
Passing MESO gates does not establish MICRO, transit or mode-choice validity.

## Evidence available and its limits

| Evidence | Established scope | Limit |
|---|---|---|
| [MESO policy checks](MESO_POLICY_VALIDATION_2026_10_23.md) | Bounded trip, link and runtime consistency | Not a comparison with field observations |
| [Separate flow/storage acceptance](MESO_FLOW_STORAGE.md) | Admission, storage, flow budgets, destination release and checkpoint obligations | Operational checks; incomplete physical spillback and merge behavior |
| [Berlin MATSim harness](../../tools/matsim-validation/README.md) | Paired simulator execution, route/distance audits and descriptive traffic comparisons | MATSim output is a model reference, not empirical truth |
| [Corrected fixed-route run, 2026-11-18](../../output/pdf/matsim-fixed-corrected-1-20261118/report.md) | 4,822 paired completions; journey MAE 206.088 s and RMSE 357.256 s versus MATSim | Previously inspected demand, one realization, supplied routes |
| [Adaptive car/bicycle comparison](../../output/pdf/matsim-adaptive-20-20261118/htc-matsim-adaptive-20/htc-matsim-adaptive-20-detailed-report.md) | 20 MATSim iterations versus one HTC execution with native adaptive choices | Different mechanisms and outcomes; not observed mode shares or field validity |

Platform-level artifacts may be absent from a standalone engine checkout. Do not
pool the corrected 4,822-trip run with historical 5,000-trip versions or describe
their differences as controlled treatment effects without verifying matched inputs.

No completed independent empirical MESO campaign was identified in the reviewed
records. An imported real-city network and a calibrated source scenario do not
transfer empirical validity automatically to a derived HTC experiment.

## Methodological basis

Use Zhao and Appiah's **Methodology for Calibration and Validation of Mesoscopic
Traffic Simulation Models**, FHWA/VTRC 22-R7 (August 2021), as the organizing
reference. The [source assessment](MESO_VTRC_22_R7_REVIEW.md) records its procedure,
case study, data needs, limitations and the differences to HTC. The report is
[available from USDOT](https://rosap.ntl.bts.gov/view/dot/60302).

Its sequence is: define measures and criteria; collect observations; identify
parameters; calibrate segments; calibrate subnetworks; calibrate the system; and
validate against external observations. Adopt that sequence while reserving a
final untouched evaluation partition after candidate model selection.

## Track matrix

| Track | Inputs supplied | Field outputs scored | Claim |
|---|---|---|---|
| A: segment | Geometry, initial occupancy, upstream arrivals and known controls | Flow, mean speed, travel-time distribution and accumulation | Segment traffic response under declared boundaries |
| B: subnetwork/bottleneck | Connected geometry, arrivals, turning inputs or fixed routes, controls and downstream restrictions | Discharge flow, queue/accumulation profile, congestion onset and recovery | Interacting-link traffic dynamics |
| C: corridor | Audited network, OD/departures or measured boundary demand, initial state and controls | Time-dependent travel times, screenline counts, flow/speed profiles and congestion duration | Autonomous internal traffic evolution conditional on external inputs |
| D: route choice, separately | OD/departures, feasible alternatives and declared information/utility assumptions | Observed paths, turning shares and redistribution | Route-choice behavior beyond fixed-route traffic propagation |

No repeated resetting of internal traffic states to field observations during
scored autonomous runs. Supplied routes and boundary arrivals remain legitimate
inputs but restrict what is validated. Aggregate observation windows must match
the simulator measurement windows and definitions.

## Data feasibility and independence

Inventory candidate sites before selecting the final experiment. Required items:

- Time-stamped link/turn counts, speed measurements and probe/re-identification
  travel times with common dates, clock alignment and stated coverage.
- Road lengths, lanes, connectivity, speed limits, detector locations and actual
  signal or restriction schedules where relevant.
- Boundary inflow, initial traffic and downstream conditions. Queue or occupancy
  evidence is needed for claims about congestion and queue propagation.
- Separate recording days/blocks for development, calibration, candidate selection
  and final evaluation, with overlap and repeated-vehicle dependencies audited.
- Source URLs, licenses/access restrictions, raw hashes, units, quality flags and
  retention rights. The VTRC PDF is a methodological source, not the raw test bed.

Treat all already inspected HTC/MATSim cases and retained NGSIM episodes as
development. The VTRC detector and INRIX observations must not be assumed publicly
downloadable because the report is public. Verify access before choosing Richmond
as an experimental site. If data are inadequate, select another site or narrow
the claim; do not manufacture a passing data gate.

Occupancy is not directly density. Any conversion requires sensor geometry and
vehicle-length assumptions with uncertainty. NGSIM retained subsets contain
selected trajectories and must not be treated as complete road occupancy or demand.
Aggregate them for density/flow only if spatial and temporal coverage is proven.

## HTC parameter and structural audit

Audit the active implementation and exported settings before calibration:

| HTC concern | Required treatment |
|---|---|
| Density-speed reference | `capacity` denotes an occupancy reference in vehicles, not hourly flow; preserve units and document road-class mapping |
| Free speed | Document km/h engine conventions and convert observed/source m/s consistently |
| Separate discharge | `flowVehiclesPerHour` is a rate; distinguish it from storage and the speed-law reference |
| Physical storage | `storageVehicles` is resident vehicle count; audit geometry, lane rounding and any population scaling |
| Entry density | Record whether the entering vehicle contributes to speed selection; one-slot links are particularly sensitive |
| Routing cost | Distinguish BPR cost publication from the law that determines actual vehicle travel |
| Destination/origin conventions | Audit physical traversal, admission delay, final release and zero-distance source boundaries |
| Downstream blocking | Current separate-capacity entry holding releases upstream space before downstream admission; it is not retained physical spillback |
| Junctions | Identify supported signal, merge, priority and turn behavior rather than assuming Aimsun/QSim semantics |

Inspect [SpeedUtil](../src/main/scala/model/hybrid/util/SpeedUtil.scala),
[scenario settings](SIMULATION_SETTINGS.md) and [flow/storage](MESO_FLOW_STORAGE.md)
when freezing a configuration. Calibrate only implemented, identifiable controls.
Do not copy Aimsun reaction times, look-ahead parameters or its speed-density
equation into HTC without a separate model change and scientific justification.

A systematic congested-regime failure may be structural. Full upstream spillback
or missing movement arbitration cannot be established by adjusting free speed or
fitting average travel time. Report absent mechanisms explicitly and require new
engineering and held-out evidence after adding them.

## Calibration sequence

1. **Development audit:** verify units, conservation, clocks, boundaries,
   measurement operators and scenario conversion. Diagnose errors before fitting.
2. **Segment calibration:** fit or assess the native speed/occupancy response by
   road class, with sufficient free-flow and congested observations. Freeze the
   grouping rule and parameter bounds; avoid unconstrained per-link overfitting.
3. **Subnetwork calibration:** choose a domain with limited route-choice ambiguity.
   Test admission, connected-link travel, discharge and storage jointly.
4. **System calibration:** define a reproducible search budget, objective,
   parameters, seeds and stopping rule. Latin hypercube sampling and an optional
   surrogate are possible methods, not required assumptions. Check candidate
   configurations in the real simulator, not only a fitted surrogate.
5. **Candidate selection:** select and freeze the final configuration using its
   designated selection partition. Keep default and calibrated results separate.
6. **Final field evaluation:** run frozen code/configuration on untouched days or
   recordings and report every declared endpoint and coverage failure.
7. **Transfer:** where feasible, repeat on another declared site or regime without
   retuning. Same-site held-out success and cross-site transfer are distinct claims.

Demand estimation is itself fitting. If OD matrices are adjusted to internal
counts, those counts are calibration evidence and cannot simultaneously be called
independent validation targets. Document what is estimated versus measured.

## Metrics, criteria and uncertainty

Primary metrics must reflect the application and data quality. Proposed families:

| Family | Candidate metrics and cautions |
|---|---|
| Travel time | Bias, MAE/RMSE, distribution quantiles and time-dependent profiles; report completed and censored cohorts |
| Counts/flow | Absolute/relative errors and GEH on declared hourly flows; match units/windows and handle low or zero counts explicitly |
| Speed | Consistent space-mean or detector point-speed comparisons; do not interchange them silently |
| Congestion | Queue/accumulation profiles, bottleneck throughput, onset, duration and dissipation |
| Integrity | Vehicle conservation, route/distance accounting, storage bounds, admission delays, missing outputs and horizon states |

For hourly modeled and observed flows m and o, GEH = sqrt(2(m-o)^2/(m+o)). Declare
the both-zero case explicitly. The VTRC study's GEH and percentage thresholds are
reference examples, not automatic HTC acceptance criteria. Do not apply their
hourly-volume thresholds unchanged to arbitrary short-window counts. Agreement
with MATSim is secondary to absolute agreement with independent field data.

Before final evaluation, specify endpoint tolerances, aggregation weights,
measurement uncertainty, minimum eligible recordings/regimes and whether gates
apply to means, quantiles, confidence bounds or coverage. Use development variance
and intended precision/power to justify sample requirements. Keep congested and
uncongested results separate. Select simulation replications with a predefined
precision rule; if a fixed scenario is deterministic, repeated identical seeds
do not provide field uncertainty. Use recordings/days or justified blocks as
independent units and account for within-recording dependence.

## Milestones and acceptance gates

| Milestone | Deliverable | Gate |
|---|---|---|
| M1: evidence/data audit | Site inventory, source receipts, claim matrix and partition plan | Inputs and independent outcomes available for the proposed scope |
| M2: actor preflight | Bounded native MESO scenarios with exact commands and invariants | Correct clocks, conservation, flow/storage and destination accounting |
| M3: development calibration | Segment/subnetwork results, parameter audit and sensitivity record | Measurement validity and identifiable parameters; structural failures retained |
| M4: protocol freeze | Dataset/splits, executable/configuration, thresholds and analysis plan | All final-run decisions resolved before examining final predictions |
| M5: held-out campaign | Field predictions, all metrics/intervals and gate outcomes | Predeclared empirical criteria evaluated without tuning |
| M6: transfer/reporting | Separate transfer result and reproducible evidence bundle | Claims restricted to actually successful tested domains |

Data inadequacy produces an inconclusive data gate. Measurement, engine and
scientific failures have separate statuses. Never drop an unsuccessful recording,
unfinished trip or troublesome regime to improve the final score. Repairs and
model revisions retain their failed predecessors; inspected final data become
development when they influence the revision.

## Initial development experiment

Start with a fresh execution of the existing bounded destination-ready-time
engineering protocol: four cases (origin flow, storage, horizon and destination
arrival), each at two fixed seeds, in real HTC and MATSim. This establishes
current-build operational readiness; it is not fresh field validation, even if
all cases pass. Preserve the protocol's historical density-reference mapping
and label its limits instead of silently correcting a frozen experiment.

From the platform root, using a new output directory:

```bash
python3 tools/matsim-validation/run_capacity_acceptance.py \
  --jar hyperbolic-time-chamber/target/scala-3.3.5/hyperbolic-time-chamber-3.10.0.jar \
  --protocol tools/matsim-validation/protocols/native-meso-flow-storage-v3-destination.json \
  --output experiments/meso-empirical-kickoff-20261118/actor-preflight
```

Use the exact recorded JAR hash; a version label alone does not identify an
executable built from local modifications. Required runner binaries and source
vehicle types are described in the [MATSim harness](../../tools/matsim-validation/README.md).
The output retains predictions, commands, checks and failure status. Local startup
settings such as the runner's port must be available. Record the actual transport
policy and all inherited environment overrides. The experiment output is a
development artifact until reviewed and summarized in a subsequent record.

In parallel as a workstream, complete the field-data feasibility inventory. Do not
start tuning on presumed holdouts while the independent-data split is undecided.

## Evidence package and reporting

Retain source/partition manifests and hashes; raw and processed observations;
quality/exclusion logs; sensor-to-link mappings; measurement definitions; input
scenarios; calibration/search histories; frozen protocols; code/configuration and
dependency hashes; commands; actor reports; every prediction; per-recording and
per-seed metrics; uncertainty; gate outcomes; figures; and attribution/access terms.

Report default, calibrated, comparative and transferred configurations separately.
State which inputs were supplied and which outcomes were predicted. A completed
campaign can correctly conclude rejection or insufficient evidence. Do not label
a literature-derived procedure or a MATSim agreement score as empirical validation.
