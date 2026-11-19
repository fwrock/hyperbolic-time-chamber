#!/usr/bin/env python3
"""Compare a simulated corridor run with observed NGSIM trajectories (development only, no thresholds, no tuning).

s = local_y metres; simulated s = link offset + position. A model that finishes the last road is held at the corridor end
(absorbing), so no observed sample is dropped for an early model exit.
"""
import argparse
import hashlib
import json
import math
import statistics
from pathlib import Path

from analyze_ngsim_actor_approach import canonical, events

FT = .3048


def summary(values):
    if not values:
        return None
    values = sorted(values)
    q = lambda p: values[min(len(values)-1, int(p*len(values)))]
    return dict(n=len(values), mean=round(statistics.mean(values), 2), median=round(statistics.median(values), 2),
                p10=round(q(.1), 2), p90=round(q(.9), 2))


def analyze(scenario, run, compare=None):
    meta = json.loads((scenario/'scenario_metadata.json').read_text())
    layout = {l['id']: l for l in meta['layout']}
    last = meta['layout'][-1]['id']
    exit_end = meta['exit_end_m']
    start, end = meta['start_frame'], meta['end_frame']
    direction, s_origin, s_sign = meta.get('direction', '2'), meta.get('s_origin', 0.0), meta.get('s_sign', 1.0)
    rows = events(run)
    sim, enter, finished = {}, {}, {}
    for r in rows:
        d, tick, label = r['data'], int(r['tick']), r['event_type']
        if label in ('enter_micro_link', 'micro_update', 'leave_micro_link'):
            native, link = int(d['car_id']), int(d['link_id'])
            if label == 'enter_micro_link':
                position = d['initial_position']
                enter[native, link] = tick
            elif label == 'leave_micro_link':
                position = d['final_position']
                if link == last: finished[native] = tick
            else:
                position = d['position']
            sim[native, tick] = layout[link]['offset'] + position
    subjects = {s['source_vehicle']: s for s in meta['subjects'] if s.get('category', 'through_only') == 'through_only'}
    observed = {}
    for source in meta['sources']:
        path = Path(source['path'])
        if hashlib.sha256(path.read_bytes()).hexdigest() != source['sha256']:
            raise ValueError('Raw source changed since generation')
        for row in json.loads(path.read_text()):
            vid, frame, s = int(row['vehicle_id']), int(row['frame_id']), s_origin + s_sign*float(row['local_y'])*FT
            if row['direction'] == direction and vid in subjects and subjects[vid]['first_frame'] <= frame <= end and s <= exit_end:
                observed.setdefault(vid, []).append((frame-start, s))
    errors, missing = [], 0
    raw_stop, raw_road = {i['name']: [] for i in meta['intersections']}, {}
    stop_err = {i['name']: [] for i in meta['intersections']}
    slips = {i['name']: [0, 0, 0] for i in meta['intersections']}      # model later >20 s, within 5 s, total
    road_obs = {}
    road_model = {}
    for vid, subject in sorted(subjects.items()):
        native, obs = subject['native_vehicle'], observed.get(vid, [])
        for tick, s in obs:
            predicted = exit_end if finished.get(native, end+1) <= tick else sim.get((native, tick))
            if predicted is None: missing += 1
            else: errors.append(predicted - s)
        first_s = obs[0][1] if obs else None
        for idx, inter in enumerate(meta['intersections']):
            connector = [l for l in meta['layout'] if l['role'] == 'connector' and l['intersection'] == idx][0]
            t_obs = next((t for t, s in obs if s >= inter['stop_bar_m']), None)
            if t_obs is not None and first_s is not None and first_s < inter['stop_bar_m'] and (native, connector['id']) in enter:
                e = (enter[native, connector['id']]-t_obs)*.1
                stop_err[inter['name']].append(e)
                raw_stop[inter['name']].append([vid, round(e, 3)])
                slips[inter['name']][2] += 1
                slips[inter['name']][0] += e > 20
                slips[inter['name']][1] += abs(e) <= 5
        # time on each road between the previous box end and the next stop bar (queue wait included)
        for idx, inter in enumerate(meta['intersections'][:-1]):
            nxt = meta['intersections'][idx+1]
            a = next((t for t, s in obs if s >= inter['box_end_m']), None)
            b = next((t for t, s in obs if s >= nxt['stop_bar_m']), None)
            road = [l for l in meta['layout'] if l['role'] == 'road' and l['intersection'] == idx+1][0]
            conn = [l for l in meta['layout'] if l['role'] == 'connector' and l['intersection'] == idx+1][0]
            if a is not None and b is not None and (native, road['id']) in enter and (native, conn['id']) in enter:
                road_obs.setdefault(nxt['name'], []).append((b-a)*.1)
                road_model.setdefault(nxt['name'], []).append((enter[native, conn['id']]-enter[native, road['id']])*.1)
                raw_road.setdefault(nxt['name'], []).append([vid, round((b-a)*.1, 3), round((enter[native, conn['id']]-enter[native, road['id']])*.1, 3)])
    result = dict(status='DEVELOPMENT_ONLY', subjects=len(subjects), model_completed=len(finished), observed_samples=sum(len(v) for v in observed.values()),
        missing_prediction_samples=missing, position_rmse_m=math.sqrt(sum(e*e for e in errors)/len(errors)) if errors else None,
        stop_bar_crossing_error_s={k: summary(v) for k, v in stop_err.items()},
        stop_bar_slips={k: dict(model_one_cycle_later=v[0], within_5s=v[1], vehicles=v[2]) for k, v in slips.items()},
        inter_intersection_road_time_s={k: dict(observed=summary(road_obs[k]), model=summary(road_model[k])) for k in road_obs},
        interpretation='Signed errors are model minus observed (positive = model later). Development evidence on exposed data; through-only slice.',
        raw=dict(stop_bar_error_s=raw_stop, road_time_s_observed_model=raw_road),
        assumptions=meta['assumptions'], exclusions={k: v['count'] for k, v in meta['exclusions'].items()})
    if compare:
        result['committed_physical_events_match_comparison'] = canonical(rows) == canonical(events(compare))
    return result


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--scenario', type=Path, required=True)
    p.add_argument('--run', type=Path, required=True)
    p.add_argument('--compare', type=Path)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    out = analyze(a.scenario, a.run, a.compare)
    with a.output.open('x') as f:
        json.dump(out, f, indent=2); f.write('\n')
    print(json.dumps({k: out[k] for k in out if k not in ('assumptions',)}, indent=1))
