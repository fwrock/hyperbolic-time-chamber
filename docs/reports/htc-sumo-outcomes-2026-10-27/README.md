# HTC and SUMO traffic outcome similarity

[Read the nine-page PDF](htc-sumo-traffic-outcomes-2026-10-27.pdf).
It evaluates simulated traffic outcomes using six chart sets and explanatory
metric tables. The supported finding is strong numerical agreement within the
declared tolerances for the tested matched single-lane cases. The stopped
vehicle-time discrepancy and the limits of that conclusion remain explicit.

`outcome-metrics.json` retains the exact metrics, source hashes, chart hashes,
illustrative vehicle selection and historical case records. Standalone PNG and
SVG charts are in `figures/`. `verification.json` records artifact bindings and
visual inspection of all nine rendered pages.

The current examples use the first measured conservative one-JVM run of each
case from the existing 2026-10-27 campaign. The signal trace illustrates the
vehicle with the longest observed SUMO approach stop, selected independently
of prediction fit. Aggregate comparisons include every vehicle. Historical v2
geometry and demand-seed results are identified separately and not counted as
new independent observations.

The generator is `scripts/scenario/build_outcome_report.py`. To reproduce charts
from the retained raw evidence, extract
`docs/validation/comparison-2026-10-27/raw-evidence.zip` into a fresh directory,
then run:

```bash
python3 scripts/scenario/build_outcome_report.py \
  --study /path/to/extracted/evidence --charts
python3 scripts/scenario/build_outcome_report.py --pdf
```

The chart stage uses Matplotlib. The PDF stage uses ReportLab; in Codex it was
executed with the bundled document Python runtime. The original source records
under `docs/reports/htc-comparison-2026-10-27` and frozen v2 protocol must remain
available. PDF rendering and page inspection used Poppler. This report reuses
retained observations and changes no simulator physics or scientific thresholds.
