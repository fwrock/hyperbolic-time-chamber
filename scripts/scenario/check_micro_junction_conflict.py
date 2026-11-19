#!/usr/bin/env python3
"""Check the junction conflict fixture: exclusion, gap acceptance, completion, waiting and event equality."""
import argparse
import json
from collections import defaultdict
from pathlib import Path

from analyze_ngsim_actor_approach import canonical, events

CRITICAL_GAP_S, OFFSET_M, VMAX_MPS, TICK_S = 3.0, 8.0, 50/3.6, 0.1


def intervals(enter, cars, connector, exit_road):
    """Occupancy of a connector: from its entry to the entry on the exit road, when the footprint is released."""
    return [(enter[c][connector], enter[c][exit_road], c) for c in cars if connector in enter[c] and exit_road in enter[c]]


def check(run, reference=None):
    rows = events(run)
    result = json.loads((run/'result.json').read_text())
    enter = defaultdict(dict)
    for r in rows:
        if r['event_type'] == 'enter_micro_link':
            enter[r['data']['car_id']][r['data']['link_id']] = int(r['tick'])
    priority = [str(100000 + k) for k in range(6)]
    yielding = [str(200000 + k) for k in range(6)]
    p30 = intervals(enter, priority, 30, 12)
    y20 = intervals(enter, yielding, 20, 22)
    overlaps = [(a, b) for a in y20 for b in p30 if a[0] < b[1] and b[0] < a[1]]
    gaps = []
    for ya in y20:
        later = [pb[0] for pb in p30 if pb[0] >= ya[0]]
        if later:
            gaps.append(min(later) - ya[0])
    waits = defaultdict(int)
    for r in rows:
        if r['event_type'] == 'micro_exit_wait':
            waits[r['data']['car_id']] += 1
    trips = [r['data'] for r in rows if r['event_type'] == 'sumo_tripinfo']
    min_gap_ticks = (CRITICAL_GAP_S - OFFSET_M/VMAX_MPS)/TICK_S
    checks = dict(
        engine_completed=result['engineCompleted'] and not result['runtimeFailed'] and not any(result['engineErrors']),
        all_twelve_journeys_complete=len(trips) == 12 and all(not r.get('horizon_truncated') and r.get('arrival', -1) >= 0 for r in trips),
        each_car_enters_its_three_links=all(len(enter[c]) == 3 for c in priority + yielding),
        conflicting_connectors_never_overlap=not overlaps,
        yielders_leave_gap_before_next_priority=bool(gaps) and min(gaps) >= min_gap_ticks,
        some_yielder_waited_at_the_line=any(waits[c] >= 10 for c in yielding))
    if reference:
        checks['committed_physical_events_equal'] = canonical(rows) == canonical(events(reference))
    return dict(run=str(run.resolve()), nodes=result['nodes'], policy=result['policy'], checks=checks,
        passed=all(checks.values()), overlaps=overlaps, min_gap_ticks_required=round(min_gap_ticks, 1),
        gaps_ticks=gaps, waits_ticks={c: waits[c] for c in yielding}, physical_events=len(canonical(rows)))


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run', type=Path, required=True)
    p.add_argument('--reference', type=Path)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    out = check(a.run, a.reference)
    with a.output.open('x') as f:
        json.dump(out, f, indent=2)
        f.write('\n')
    print(json.dumps(out, indent=2))
    raise SystemExit(0 if out['passed'] else 1)
