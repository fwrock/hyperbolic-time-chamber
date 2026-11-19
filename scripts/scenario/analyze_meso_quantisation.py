#!/usr/bin/env python3
"""Measure MESO tick quantisation: elapsed time per link and per trip against the continuous travel time reported at link entry."""
import argparse
import json
import statistics
from collections import defaultdict
from pathlib import Path


def analyze(run, tick_seconds=1.0):
    enter, leave = {}, {}
    for path in sorted(Path(run).glob('reports/*/*.jsonl')):
        for line in path.read_text().splitlines():
            r = json.loads(line)
            d = r['data']
            if r['event_type'] == 'enter_link': enter[(d['car_id'], d['link_id'])] = (int(r['tick']), float(d['travel_time']))
            elif r['event_type'] == 'leave_link': leave[(d['car_id'], d['link_id'])] = int(r['tick'])
    per_link = []
    trips = defaultdict(lambda: [0.0, None, None])
    for (car, link), (tick, t) in enter.items():
        out = leave.get((car, link))
        if out is None: continue
        per_link.append((out - tick) * tick_seconds - t)
        trip = trips[car]; trip[0] += t
        trip[1] = tick if trip[1] is None else min(trip[1], tick)
        trip[2] = out if trip[2] is None else max(trip[2], out)
    totals = [((c[2] - c[1]) * tick_seconds, c[0]) for c in trips.values()]
    diff = [a - b for a, b in totals]
    return dict(links=len(per_link), mean_extra_per_link_s=round(statistics.mean(per_link), 3), median_extra_per_link_s=round(statistics.median(per_link), 3),
        min=round(min(per_link), 3), max=round(max(per_link), 3), trips=len(totals), mean_trip_s=round(statistics.mean(a for a, _ in totals), 1),
        mean_continuous_trip_s=round(statistics.mean(b for _, b in totals), 1), mean_extra_per_trip_s=round(statistics.mean(diff), 2),
        relative_extra_pct=round(100*statistics.mean(diff)/statistics.mean(b for _, b in totals), 2))


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run', type=Path, required=True)
    p.add_argument('--tick-seconds', type=float, default=1.0)
    a = p.parse_args()
    print(json.dumps(analyze(a.run, a.tick_seconds), indent=1))
