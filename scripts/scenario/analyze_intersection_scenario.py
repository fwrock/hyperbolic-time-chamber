#!/usr/bin/env python3
"""Compare a simulated intersection-1 run with the observed NGSIM trajectories (development only).

Corridor coordinate s = local_y (m). Simulated s: approach position, stop bar + connector position, exit start + exit position.
The model's early exit is held at the end of the exit link (absorbing), so no observed sample is dropped for a model exit.
No thresholds and no tuning: diagnostics for development.
"""
import argparse
import hashlib
import json
import math
import statistics
from pathlib import Path

from analyze_ngsim_actor_approach import canonical, events
from ngsim_intersection_scenario import A, C, E, EXIT_END_M, EXIT_START_M, FT, STOP_BAR_M

OFFSET = {A: 0.0, C: STOP_BAR_M, E: EXIT_START_M}


def pct(values, q):
    values = sorted(values)
    return values[min(len(values)-1, int(q*len(values)))] if values else None


def analyze(scenario, run, compare=None):
    meta = json.loads((scenario/'scenario_metadata.json').read_text())
    start, end = meta['start_frame'], meta['end_frame']
    rows = events(run)
    sim, exits, entered_box, arrivals = {}, {}, {}, {}
    for r in rows:
        d, tick, label = r['data'], int(r['tick']), r['event_type']
        if label in ('enter_micro_link', 'micro_update', 'leave_micro_link'):
            native, link = int(d['car_id']), int(d['link_id'])
            if label == 'enter_micro_link':
                position = d['initial_position']
                if link == C: entered_box.setdefault(native, tick)
            elif label == 'leave_micro_link':
                position = d['final_position']
                if link == E: exits[native] = tick
            else:
                position = d['position']
            sim[native, tick] = OFFSET[link] + position
        elif label == 'sumo_tripinfo':
            pass
    subjects = {s['source_vehicle']: s for s in meta['subjects']}
    observed = {}
    for source in meta['sources']:
        path = Path(source['path'])
        if hashlib.sha256(path.read_bytes()).hexdigest() != source['sha256']:
            raise ValueError('Raw source changed since generation')
        for row in json.loads(path.read_text()):
            vid, frame = int(row['vehicle_id']), int(row['frame_id'])
            s = float(row['local_y'])*FT
            if row['direction'] == '2' and vid in subjects and subjects[vid]['first_frame'] <= frame <= end and s <= EXIT_END_M:
                observed.setdefault(vid, []).append((frame-start, s))
    errors, missing = [], 0
    stop_err, exit_err, per_vehicle = [], [], []
    for vid, subject in sorted(subjects.items()):
        native = subject['native_vehicle']
        verrs = []
        for tick, s in observed.get(vid, []):
            predicted = EXIT_END_M if exits.get(native, end+1) <= tick else sim.get((native, tick))
            if predicted is None: missing += 1
            else: verrs.append(predicted - s)
        errors.extend(verrs)
        obs = observed.get(vid, [])
        obs_stop = next((t for t, s in obs if s >= STOP_BAR_M), None)
        obs_exit = next((t for t, s in obs if s >= EXIT_END_M - 0.5), None)
        if obs_stop is not None and native in entered_box and obs and obs[0][1] < STOP_BAR_M:
            stop_err.append((entered_box[native]-obs_stop)*.1)
        if obs_exit is not None and native in exits:
            exit_err.append((exits[native]-obs_exit)*.1)
        per_vehicle.append(dict(source_vehicle=vid, samples=len(obs),
            rmse_m=math.sqrt(sum(e*e for e in verrs)/len(verrs)) if verrs else None,
            model_completed=native in exits))
    def summary(values):
        return None if not values else dict(n=len(values), mean=round(statistics.mean(values), 2),
            median=round(statistics.median(values), 2), p10=round(pct(values, .1), 2), p90=round(pct(values, .9), 2))
    result = dict(status='DEVELOPMENT_ONLY', subjects=len(subjects), model_completed=sum(1 for v in per_vehicle if v['model_completed']),
        observed_samples=sum(len(v) for v in observed.values()), missing_prediction_samples=missing,
        position_rmse_m=math.sqrt(sum(e*e for e in errors)/len(errors)) if errors else None,
        position_error_m=summary(errors), stop_bar_crossing_error_s=summary(stop_err),
        exit_end_crossing_error_s=summary(exit_err), per_vehicle=per_vehicle,
        interpretation='Signed errors are model minus observed; positive time error = the model crosses later. '
            'Development evidence on exposed data; through-only slice ignores turns, cross traffic, opposing flow and downstream queues.',
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
    print(json.dumps({k: out[k] for k in out if k != 'per_vehicle'}, indent=1))
