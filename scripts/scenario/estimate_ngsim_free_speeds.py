#!/usr/bin/env python3
"""Per-vehicle free-flow speed population (protocol docs/MICRO_NGSIM_DESIRED_SPEED_PROTOCOL_2026_11_19.md)."""
import argparse
import json
import statistics
from collections import defaultdict
from pathlib import Path

FT = .3048


def estimate(pages):
    rows = defaultdict(list)
    by_time = defaultdict(list)
    for path in pages:
        for r in json.loads(Path(path).read_text()):
            if r['direction'] == '2':
                lane = int(r['lane_id']) if r['lane_id'].isdigit() else -1
                key = int(r['vehicle_id'])
                row = (int(r['frame_id']), float(r['local_y'])*FT, float(r['v_vel'])*FT, lane, int(r['movement']))
                rows[key].append(row)
                by_time[row[0]].append((row[1], lane, key))
    population = []
    for vid, track in rows.items():
        if any(r[4] != 1 or r[3] not in (0, 1, 2) for r in track):
            continue
        q = [r[2] for r in track if r[2] > 1.0 and not any(r[1] < s <= r[1] + 40 and (lane == r[3] or lane == 0)
             for s, lane, v in by_time[r[0]] if v != vid)]
        if len(q) >= 50:
            q.sort()
            population.append(dict(vehicle=vid, rows=len(q), free_speed_mps=q[min(len(q)-1, int(.9*len(q)))]))
    return population


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--pages', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    population = estimate(sorted(a.pages.glob('page-*.json')))
    v = sorted(x['free_speed_mps'] for x in population)
    result = dict(protocol='docs/MICRO_NGSIM_DESIRED_SPEED_PROTOCOL_2026_11_19.md', n=len(v),
        median=round(statistics.median(v), 2) if v else None, p10=round(v[len(v)//10], 2) if v else None,
        p90=round(v[-1-len(v)//10], 2) if v else None, population=population)
    with a.output.open('x') as f:
        json.dump(result, f, indent=2); f.write('\n')
    print(json.dumps({k: x for k, x in result.items() if k != 'population'}, indent=1))
