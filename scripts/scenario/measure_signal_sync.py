#!/usr/bin/env python3
"""Signal-sync indicators I1 (departure delay after green) and I2 (stop-bar crossings after red onset); protocol docs/MICRO_NGSIM_SIGNAL_SYNC_PROTOCOL_2026_11_19.md."""
import argparse
import json
import statistics
from collections import defaultdict
from pathlib import Path

from ngsim_observed_signals import read_changes

FT = .3048
APPROACHES = [('10th', 38.774, 'Peachtree_10th_NB'), ('11th', 196.17, 'Peachtree_11th_NB'), ('12th', 359.74, 'Peachtree_12th_NB'), ('14th', 600.6, 'Peachtree_14th_NB')]


def q(v, p):
    v = sorted(v)
    return round(v[min(len(v)-1, int(p*len(v)))], 2) if v else None


def measure(pages, signals, suffix):
    tracks = defaultdict(list)
    for path in pages:
        for r in json.loads(Path(path).read_text()):
            if r['direction'] == '2':
                tracks[int(r['vehicle_id'])].append((int(r['frame_id']), float(r['local_y'])*FT, float(r['v_vel'])*FT,
                    int(r['lane_id']) if r['lane_id'].isdigit() else -1))
    for t in tracks.values(): t.sort()
    at = defaultdict(list)
    for vid, t in tracks.items():
        for f, y, v, lane in t: at[f].append((y, lane, vid))
    last = max(f for t in tracks.values() for f, *_ in t)
    out = {}
    for name, b, stem in APPROACHES:
        events = read_changes(signals/f'{stem}_{suffix}.txt')['Thru']
        starts_green = [f for f, p in events if p == 'Green' and f <= last]
        starts_red = [f for f, p in events if p == 'Red' and f <= last]
        i1 = []
        for g in starts_green:
            leaders = [(vid, y, lane) for y, lane, vid in at.get(g, []) if b-8 <= y <= b]
            for vid, y, lane in leaders:
                row = next(((f, yy, vv) for f, yy, vv, l in tracks[vid] if f == g), None)
                if row is None or row[2] > .5: continue
                if any(y < y2 <= y+40 and (l2 == lane or l2 == 0) for y2, l2, v2 in at[g] if v2 != vid): continue
                dep = next((f for f, yy, vv, l in tracks[vid] if f >= g and vv > 1.0), None)
                if dep is not None and dep <= g+150: i1.append((dep-g)*.1)
        i2 = []
        for r in starts_red:
            for vid, t in tracks.items():
                prior = next((y for f, y, v, l in t if f == r-100), None)
                if prior is None or not (b-60 <= prior < b): continue
                cross = next((f for f, y, v, l in t if y >= b and r-100 <= f <= r+150), None)
                if cross is not None: i2.append((cross-r)*.1)
        after = [x for x in i2 if x > 0]
        out[name] = dict(green_starts=len(starts_green), I1=dict(n=len(i1), median=q(i1, .5), p10=q(i1, .1), p90=q(i1, .9)),
            red_starts=len(starts_red), I2=dict(crossers=len(i2), after_red=len(after), median_after=q(after, .5), p90_after=q(after, .9), max_after=round(max(after), 2) if after else None))
    return out


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--pages', type=Path, required=True)
    p.add_argument('--signals', type=Path, required=True)
    p.add_argument('--suffix', required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    result = measure(sorted(a.pages.glob('page-*.json')), a.signals, a.suffix)
    with a.output.open('x') as f:
        json.dump(result, f, indent=2); f.write('\n')
    for k, v in result.items(): print(k, 'I1', v['I1'], '| I2', v['I2'])
