#!/usr/bin/env python3
"""Check the G03 merge fixture invariants and optionally compare committed events with a reference run."""
import argparse
import json
from collections import defaultdict
from pathlib import Path

from analyze_ngsim_actor_approach import canonical, events

IDS = [str(i) for i in range(101, 107)]


def check(run, reference=None):
    rows = events(run)
    result = json.loads((run/'result.json').read_text())
    enter = defaultdict(dict)
    leave = defaultdict(dict)
    for r in rows:
        d = r['data']
        if r['event_type'] == 'enter_micro_link':
            enter[d['car_id']].setdefault(d['link_id'], []).append((int(r['tick']), d['lane']))
        elif r['event_type'] == 'leave_micro_link':
            leave[d['car_id']].setdefault(d['link_id'], []).append(int(r['tick']))
    waits = defaultdict(list)
    for r in rows:
        if r['event_type'] == 'micro_exit_wait':
            waits[r['data']['car_id']].append(r['data'])
    trips = [r['data'] for r in rows if r['event_type'] == 'sumo_tripinfo']
    admissions = sorted((enter[c][12][0][0], c) for c in IDS if 12 in enter.get(c, {}))
    delayed = {c: enter[c][12][0][0] - leave[c][11][0] for c in IDS
               if 11 in leave.get(c, {}) and 12 in enter.get(c, {}) and enter[c][12][0][0] > leave[c][11][0]}
    stacked = defaultdict(list)
    for c, ws in waits.items():
        for w in ws:
            stacked[(int(w['tick']), w['lane'])].append((w['position'], float(w['vehicle_length'])))
    checks = dict(
        engine_completed=result['engineCompleted'] and not result['runtimeFailed'] and not any(result['engineErrors']),
        each_car_enters_each_link_once=all(len(enter[c].get(11, [])) == 1 and len(enter[c].get(12, [])) == 1 for c in IDS),
        each_car_leaves_each_link_once=all(len(leave[c].get(11, [])) == 1 and len(leave[c].get(12, [])) == 1 for c in IDS),
        all_journeys_complete=len(trips) == 6 and all(not r.get('horizon_truncated') and r.get('arrival', -1) >= 0 for r in trips),
        merged_into_one_lane=all(enter[c][12][0][1] == 0 for c in IDS),
        admissions_strictly_ordered=len({t for t, _ in admissions}) == len(admissions),
        some_admission_was_delayed_many_ticks=any(v >= 50 for v in delayed.values()),
        wait_samples_equal_delay=all(len(waits[c]) == v for c, v in delayed.items()),
        footprint_released_at_admission=all(max(int(w['tick']) for w in waits[c]) <= enter[c][12][0][0] for c in delayed),
        retained_cars_do_not_overlap=all(
            all(a[0]-b[0] >= b[1]-1e-9 for a, b in zip(sorted(v, reverse=True), sorted(v, reverse=True)[1:]))
            for v in stacked.values()))
    if reference:
        checks['committed_physical_events_equal'] = canonical(rows) == canonical(events(reference))
    return dict(run=str(run.resolve()), nodes=result['nodes'], policy=result['policy'], checks=checks,
        passed=all(checks.values()), delays_ticks=delayed, physical_events=len(canonical(rows)))


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
