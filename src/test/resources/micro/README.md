# Short-road lane-release regression data

`berlin-short-road-blockers.json` preserves the road lengths, lane counts, lane
connections, selected next-road movements and selected driver parameters from the
14 blocked roads in the fresh 2026-11-16 Berlin BeST 5,000-vehicle development run.
The source road and vehicle IDs refer to that converter's native ID mapping.

Each test places a wrong-lane requester and an adjacent exit-compatible blocker
at rest 0.5 m before the road end. These positions are a synthetic reproduction,
not recovered final position snapshots. Tests run red then green, swap IDs, and
exercise zero/nonzero sigma. They require both vehicles to leave through permitted
lanes, preserve minimum spacing and use only adjacent changes. These regressions
do not claim full-network completion or fresh scientific validation.

Original scenario: [BeST Berlin](https://github.com/mosaic-addons/best-scenario),
attributed to its contributors; input data are licensed CC BY 4.0. The retained
local provenance is `tools/sumo-conversor/output/berlin-5000-current-20261116/`,
including source hashes, the source-to-native ID map and `unfinished-diagnosis.json`.
The regression resource is derived from those scenario inputs and observations.
