#!/usr/bin/env python3
"""Accepted gaps of northbound left turns at intersection 1 (protocol docs/MICRO_NGSIM_LEFT_TURN_PROTOCOL_2026_11_19.md)."""
import argparse
import json
import statistics
from collections import defaultdict
from pathlib import Path

from ngsim_observed_signals import read_changes

FT = .3048
NB_STOP, SB_STOP = 38.774, 72.6
Y_C = (NB_STOP + SB_STOP) / 2


def aspect_at(events, frame):
    known = [(f, p) for f, p in events if f <= frame]
    return max(known)[1] if known else None


def estimate(pages, signal_sb):
    nb, sb = defaultdict(list), defaultdict(list)
    for path in pages:
        for r in json.loads(Path(path).read_text()):
            row = (int(r['frame_id']), float(r['local_y'])*FT, r['int_id'], r['movement'])
            if r['direction'] == '2': nb[int(r['vehicle_id'])].append(row)
            elif r['direction'] == '4': sb[int(r['vehicle_id'])].append(row)
    arrivals = []
    for vid, t in sb.items():
        t.sort()
        box = [r for r in t if r[2] == '1']
        if box and all(r[3] == '1' for r in box):
            hit = next((r[0] for r in t if r[1] <= Y_C), None)
            if hit is not None: arrivals.append(hit)
    arrivals.sort()
    sb_events = read_changes(signal_sb)['Thru']
    gaps = []
    for vid, t in nb.items():
        t.sort()
        if not t or t[-1][2] != '1' or t[-1][3] != '2': continue
        tl = next((r[0] for r in t if r[1] >= Y_C), t[-1][0])
        if aspect_at(sb_events, tl) not in ('Green', 'Yellow'): continue
        nxt = next((a for a in arrivals if a > tl), None)
        if nxt is not None: gaps.append(dict(vehicle=vid, t_left=tl, gap_s=(nxt - tl) * .1))
    return gaps, len(arrivals)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--pages', type=Path, required=True)
    p.add_argument('--signal-sb', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    gaps, sb_arrivals = estimate(sorted(a.pages.glob('page-*.json')), a.signal_sb)
    v = sorted(g['gap_s'] for g in gaps)
    result = dict(protocol='docs/MICRO_NGSIM_LEFT_TURN_PROTOCOL_2026_11_19.md', y_conflict_m=Y_C, conflict_offset_m=round(SB_STOP - Y_C, 2),
        sb_through_arrivals=sb_arrivals, permissive_left_turns=len(v),
        median_gap_s=round(statistics.median(v), 2) if v else None, p15_gap_s=round(v[int(.15*len(v))], 2) if v else None,
        conclusive=len(v) >= 8, critical_gap_s=round(v[int(.15*len(v))], 2) if len(v) >= 8 else 3.0, gaps=gaps)
    with a.output.open('x') as f:
        json.dump(result, f, indent=2); f.write('\n')
    print(json.dumps({k: x for k, x in result.items() if k != 'gaps'}, indent=1))
