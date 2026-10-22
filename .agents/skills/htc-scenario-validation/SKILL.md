---
name: htc-scenario-validation
description: Validate HTC mobility behavior with a small generated scenario and actual simulator output. Use for end-to-end regression of trips, vehicles, transit, MICRO behavior, or time modes; not for unit tests alone.
---

# HTC scenario validation

Use `scripts/scenario/generate_hybrid_scenario.py` for a bounded scenario unless the user supplied one. Set an explicit `--seed` and a temporary `--output-dir`; inspect `--help` for options before generating. Preserve the scenario files and command used for reproducibility.

Derive expected persons, trips, modes, links, and transit services from the generated configuration and entity files. An event count alone is insufficient: compare completed journeys, boardings, vehicle traversal, and MICRO updates where applicable. A scenario without cars cannot exercise car MICRO behavior even when it contains MICRO links. Compare expected and observed values per case, identify missing reports and exceptions, and separate an engine failure from a scenario that never activated the behavior.

Keep the run small and bounded. When testing a synchronization fix, check the relevant stall/Time Manager completion logs as well as the reported outcomes. Report exact inputs, simulator mode, seed, expected and observed results, and the location of output artifacts. Do not claim a city-scale or performance conclusion from a smoke scenario.
