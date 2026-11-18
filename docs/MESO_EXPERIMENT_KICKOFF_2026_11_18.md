# MESO empirical campaign kickoff: 2026-11-18

This record starts the [MESO campaign](MESO_EMPIRICAL_VALIDATION_PLAN.md) with
operational preflight and field-data feasibility work. It does not report an
independent empirical validation result. The campaign documentation was published
in commit `3d56fcc`.

## Field-data feasibility

No final empirical dataset or held-out protocol has been selected or frozen.
The following inventory records availability separately from methodological value.

| Candidate | Evidence available | Missing evidence / access | Current use |
|---|---|---|---|
| Richmond I-95 / VTRC 22-R7 | Public methods, published case summaries and source identification | Complete raw detector, INRIX, geometry, controls, boundary inputs and independent partitions | Methodological reference; not an executable field dataset |
| Caltrans PeMS | Official historical sensor archive and access documentation | Authorized account/export, chosen stations/dates, quality metadata, matching geometry and boundaries | Candidate for the first freeway segment/subnetwork study |
| VDOT SmarterRoads | Public portal and FAQ metadata | Historical data coverage, sensor/probe export and access require verification | Candidate access audit only |
| Retained NGSIM selections | Existing selected raw observations and receipts | Complete spatial occupancy/demand and untouched eligible recordings not established | Previously inspected development data |
| Derived MATSim Berlin | Executable scenarios, source inputs and model outputs | Independent field outcomes for the derived experiment | Engineering/model comparison |

Caltrans documents an archive exceeding ten years and requires a registered
account. This establishes a potential acquisition route, not data access in this
workspace. No account was created, no credentials were requested and no final
observations were inspected during this metadata audit.
See [Caltrans's source description](https://dot.ca.gov/programs/traffic-operations/mpr/pems-source)
and [PeMS access](https://pems.dot.ca.gov/).

For Virginia, [SmarterRoads](https://smarterroads.vdot.virginia.gov/) and its
[FAQ](https://smarterroads.vdot.virginia.gov/faq) identify a portal to investigate.
Retrieving the page does not prove that historical detector or INRIX observations
for the VTRC case can be exported. No such raw-data availability is asserted.

Local metadata receipts preserve retrieval timestamps, raw HTML hashes and errors.
The direct scripted request to the USDOT VTRC catalog returned HTTP 403; the
catalog was accessible through the web research tool and the user-supplied PDF
checksum matched its official record. Preserve both access outcomes.

## Operational preflight

The existing `native-meso-flow-storage-v3-destination.json` engineering protocol
defines origin-flow, storage, horizon and destination-arrival cases at seeds
22003 and 22031. It runs real MATSim and production HTC actors, with no empirical
fitting or field-accuracy threshold. Its historical density-reference mapping is
retained rather than silently replaced with the newer geometric mapping.

The tested executable is the existing assembled HTC 3.10.0 JAR, SHA-256
`4e50bdd83cd7b703e1c143500e0378d1d8baf7264828d817715054b64967ab44`.
This is a working-tree build, not a claimed clean build from documentation commit
`3d56fcc`. Pre-existing source changes were not included in that documentation
commit. The frozen binary and provenance identify what actually ran.

The first attempt completed both simulators for the first case, then failed
during Parquet analysis because system Python lacked `pyarrow`. Its failed
`acceptance.json`, logs and predictions remain unchanged. A fresh attempt uses
an isolated environment with system site packages plus `pyarrow` 26.0.1; the
binary, protocol and physical configuration are unchanged. This is a measurement
environment correction, not a model correction or a scientific failure.

The launcher requests `HTC_CONSERVATIVE_TRANSPORT=direct`. Identify the actual
runtime from retained logs/settings; the request alone does not prove which
backend executed. This campaign does not make a transport-policy parity claim.

## Completed bounded result

The fresh corrected-environment attempt passes all eight cases. Each engine
completes the 12 trips in every non-censored case, giving 72 paired completed
trips across six cases. In each horizon case, the 30 selected vehicles remain
accounted for, including unfinished and not-departed vehicles; no completed pairs
are expected there. All storage, flow-budget, route/distance and applicable
destination-ready-time checks pass. The HTC runtime reports completion with zero
engine errors in every case. Applicable native terminal waits are zero.

The actual logs identify `ChannelClosedGlobalTimeManager`, despite the explicit
direct-transport environment request. These results therefore establish readiness
for the recorded channel-closed execution and do not establish a direct-backend
acceptance result. Investigate effective scenario settings before a future
transport-specific experiment; do not relabel this run from the environment alone.

The [committed machine summary](validation/meso-empirical-kickoff-2026-11-18.json)
retains all case outcomes, source receipts, dependency versions, executable and
protocol identities. This operational pass leaves independent empirical adequacy
open. No physical parameter was fitted and no field-accuracy threshold was tested.

## Artifacts and next decisions

Platform-local artifacts are in
[`experiments/meso-empirical-kickoff-20261118/`](../../experiments/meso-empirical-kickoff-20261118/):

- `kickoff.json`: executable/source hashes, environment intent and attempt history.
- `actor-preflight/`: preserved first-attempt measurement failure.
- `actor-preflight-r2/`: fresh preflight commands, scenarios, outputs and gates.
- `python-environment.json`: isolated analysis dependency versions.
- `data-feasibility.json`: candidate classifications and unresolved data gates.
- `source-metadata/receipts.json`: catalog/access retrieval receipts.

Large binaries, environments and raw outputs remain local experiment artifacts.
The committed summary records bounded results without treating those artifacts
as independent field evidence. Local links may not resolve in standalone engine
checkouts.

Next, acquire an authorized observation export and audit station/link geometry,
data completeness, time alignment, regime coverage and suitable independent
recordings. Choose the site and freeze the split before calibration or evaluating
final predictions. A passing actor preflight does not close that data gate.
