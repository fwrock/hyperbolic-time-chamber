# VTRC 22-R7: methodology assessment for HTC MESO

Reviewed: 2026-11-18. This is a source assessment and adaptation proposal, not
an assertion that HTC has passed the report's procedure.

## Source identification

Mo Zhao and Justice Appiah (2021). *Methodology for Calibration and Validation
of Mesoscopic Traffic Simulation Models*. Virginia Transportation Research
Council, FHWA/VTRC 22-R7 (August 2021).

- [USDOT bibliographic record](https://rosap.ntl.bts.gov/view/dot/60302).
- [Official PDF](https://rosap.ntl.bts.gov/view/dot/60302/dot_60302_DS1.pdf).
- Local reviewed filename: `dot_60302_DS1.pdf`, supplied from the user's Downloads.
- Reviewed SHA-256, matching the USDOT catalog:
  `61c3c0c08be70df3b73263520300c92bee5694dafd8852ac1906d60edc693f30`.
- Physical PDF length: 45 pages. References below use the report's printed page
  numbers; printed page 15 is physical PDF page 23.
- The publication explicitly says it is not a standard, specification or regulation.
  Its observations about available guidance describe its 2021 context.

## Seven-step procedure and HTC adaptation

The proposed procedure is described on printed pages 15–18.

| Step | Report's purpose | HTC adaptation |
|---|---|---|
| 1: measures/criteria | Define observable performance consistent with the application | Freeze travel-time, flow, speed, congestion and coverage endpoints |
| 2: data | Collect calibration and validation observations | Audit synchronized sensors/probes, controls and independent partitions |
| 3: parameters | Identify relevant parameters and credible bounds | Map only implemented HTC controls with correct units |
| 4: disaggregate calibration | Estimate speed-density relationships for links or similar road groups | Assess native speed/occupancy behavior and road-class grouping |
| 5: subnetwork calibration | Evaluate connected segments with limited route-choice ambiguity | Isolate admission, discharge, storage and boundary effects |
| 6: system calibration | Search parameter combinations, optionally using a response surface | Record a reproducible search; rerun candidate settings in real HTC |
| 7: external validation | Compare with field observations outside calibration | Reserve a final untouched recording partition after candidate selection |

Link-group calibration can reduce the parameter burden relative to fitting every
road independently. Limited-route-choice subnetworks help isolate supply dynamics
from uncertain OD estimation. These design principles are useful even though HTC
and Aimsun implement different traffic models.

## Empirical case study

The demonstration uses Aimsun Next 8.4.1 on an I-95 network in Richmond and
Chesterfield County, Virginia (printed pages 9–11, 18–30). It covers more than
13 miles, seven interchanges and 25 arterial/ramp intersections. The simulated
period is 06:30–08:30, with a 07:15–08:15 peak-hour focus.

Reported observations include:

- A year of speed and occupancy observations from 15 mainline detectors,
  aggregated at 15-minute intervals. Ramp data were not used because speeds were
  missing (printed page 20).
- INRIX section speed/travel-time observations in 15-minute intervals for 2016.
- Link volumes, turning counts at 11 critical intersections and entering counts
  at 12 other intersections from an earlier I-95 conditions study.

The case used a peak-hour I-95 northbound travel time of 739 s for system
calibration. Five system parameters were explored using 50 Latin hypercube design
points. The fitted travel-time response surface had R-squared about 0.4 and was
used to propose candidate configurations for simulator evaluation (printed pages
24–25). Fifty runs and five parameters are case choices, not universal sample or
budget requirements for HTC.

Congested detector observations were insufficient for robust local speed-density
estimation, so published values from another study were used; arterial parameters
also depended on prior work (printed pages 21–22). This limits how strongly the
case isolates empirical identification of every parameter.

## Reported acceptance and limitations

The case compared hourly volumes using GEH and VDOT TOSAM percentage criteria.
Its summary reports more than 85% of observed link/turn peak-hour volumes with
GEH below 5, and all below 10. It also reports acceptable freeway travel times
under the cited TOSAM criterion. Turning movements met the TOSAM percentage
tolerances in 79% of cases, below the 85% requirement, despite further OD
adjustment attempts (printed pages 26–30).

Do not flatten this mixed result into “all validation passed.” The authors
recommend considering separate mesoscopic criteria and acknowledge a single-case
evidence base requiring additional work (printed pages 30–31). Their thresholds
are application-specific references, not universal scientific acceptance limits.

The report selects among six candidate configurations using volume results
described as validation, and describes further recalibration attempts. For an
HTC campaign, observations used to choose candidates or adjust OD must be treated
as model-selection/calibration evidence. A separate final test must remain
untouched. This is an HTC design recommendation, not a claim that the report
itself used such a three-part split.

## Transfer to HTC: use and exclusions

Adopt the hierarchical sequence, explicit observations, model-parameter audit and
multi-scale evaluation. Preserve stricter final-test independence. Add time-varying
congestion, recovery and spillback measurements so matching hourly counts does
not hide incorrect traffic dynamics.

Do not transplant Aimsun's speed-density equation, parameter bounds, signal
reaction, gap acceptance or look-ahead controls as if they were native HTC
parameters. HTC's occupancy-based travel law, rate/storage controls, routing
costs and entry/destination conventions require their own audit. Fitting demand
or free speeds cannot establish mechanisms absent from the model.

The PDF supplies a method and published case summaries. It does not supply a
complete machine-readable experiment with the raw detector/probe observations,
network/control inputs and independent recording splits needed for HTC. Public
access to the report does not establish access to INRIX or underlying agency
records. No raw-data availability is asserted here.

## Implementation route

Follow the [MESO empirical campaign](MESO_EMPIRICAL_VALIDATION_PLAN.md): first
audit field-data feasibility and native actor readiness; then calibrate segments
and a small subnetwork; freeze a protocol; evaluate untouched corridor recordings;
and report transfer separately. Existing [MATSim comparisons](../../tools/matsim-validation/README.md)
remain supporting model-comparison evidence.
