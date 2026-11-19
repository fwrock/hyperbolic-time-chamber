#!/usr/bin/env python3
"""Left-turn slice analysis (protocol docs/MICRO_NGSIM_LEFT_TURN_PROTOCOL_2026_11_19.md): observed vs model, no thresholds."""
import argparse
import hashlib
import json
import statistics
from collections import defaultdict
from pathlib import Path

from analyze_ngsim_actor_approach import events
from ngsim_left_turn_slice import A, B, CL, CS, E, LEFT_LEN, NB_BOX_END, NB_STOP, SB_STOP, SINK, X, Y_C
from ngsim_observed_signals import read_changes

FT = .3048
FRAC = (Y_C - NB_STOP) / (NB_BOX_END - NB_STOP)


def aspect_at(events_, frame):
    known = [(f, p) for f, p in events_ if f <= frame]
    return max(known)[1] if known else None


def summary(v):
    v = sorted(v)
    return None if not v else dict(n=len(v), median=round(statistics.median(v), 2), p15=round(v[int(.15*len(v))], 2), min=round(v[0], 2), max=round(v[-1], 2))


def analyze(scenario, run, signals_dir, suffix):
    meta = json.loads((scenario/'scenario_metadata.json').read_text())
    start = meta['start_frame']
    sb_sig = read_changes(signals_dir/f'Peachtree_10th_SB_{suffix}.txt')['Thru']
    rows = events(run)
    enter, pos_samples, leave = {}, defaultdict(list), {}
    for r in rows:
        d, t = r['data'], int(r['tick'])
        if r['event_type'] == 'enter_micro_link':
            enter[int(d['car_id']), int(d['link_id'])] = t
            pos_samples[int(d['car_id']), int(d['link_id'])].append((t, d['initial_position']))
        elif r['event_type'] == 'micro_update':
            pos_samples[int(d['car_id']), int(d['link_id'])].append((t, d['position']))
        elif r['event_type'] == 'leave_micro_link':
            leave[int(d['car_id']), int(d['link_id'])] = t
    def reach(car, link, at):
        hit = next((t for t, p in sorted(pos_samples.get((car, link), [])) if p >= at), None)
        return hit if hit is not None else leave.get((car, link))
    sb_arr = sorted(t for (car, link) in [k for k in enter if k[1] == CS] for t in [reach(car, CS, SB_STOP - Y_C)] if t is not None)
    lefts = [s for s in meta['subjects'] if s['category'] == 'left']
    # observed, same operator as the estimator
    nb_rows, sb_rows = defaultdict(list), defaultdict(list)
    for src in meta['sources']:
        path = Path(src['path'])
        if hashlib.sha256(path.read_bytes()).hexdigest() != src['sha256']: raise ValueError('Raw source changed')
        for r in json.loads(path.read_text()):
            row = (int(r['frame_id']), float(r['local_y'])*FT, r['int_id'], r['movement'])
            if r['direction'] == '2': nb_rows[int(r['vehicle_id'])].append(row)
            elif r['direction'] == '4': sb_rows[int(r['vehicle_id'])].append(row)
    obs_arr = []
    for t in sb_rows.values():
        t.sort(); box = [r for r in t if r[2] == '1']
        if box and all(r[3] == '1' for r in box):
            hit = next((r[0] for r in t if r[1] <= Y_C), None)
            if hit is not None: obs_arr.append(hit - start)
    obs_arr.sort()
    per, g_model, g_obs, g_model_perm, g_obs_perm, stop_err, waits = [], [], [], [], [], [], []
    for s in lefts:
        car, vid = s['native_vehicle'], s['source_vehicle']
        t_obs_rows = sorted(nb_rows[vid])
        obs_stop = next((f - start for f, y, _, _ in t_obs_rows if y >= NB_STOP), None)
        obs_c = next((f - start for f, y, _, _ in t_obs_rows if y >= Y_C), t_obs_rows[-1][0] - start if t_obs_rows else None)
        m_enter = enter.get((car, CL)); m_c = reach(car, CL, FRAC*LEFT_LEN)
        row = dict(source_vehicle=vid, observed_stop_bar_tick=obs_stop, model_enter_left_connector_tick=m_enter, model_conflict_tick=m_c, observed_conflict_tick=obs_c)
        if obs_stop is not None and m_enter is not None: stop_err.append((m_enter-obs_stop)*.1)
        permissive_obs = obs_c is not None and aspect_at(sb_sig, obs_c + start) in ('Green', 'Yellow')
        permissive_mod = m_c is not None and aspect_at(sb_sig, m_c + start) in ('Green', 'Yellow')
        if m_c is not None:
            nxt = next((a for a in sb_arr if a > m_c), None)
            if nxt is not None:
                g = (nxt-m_c)*.1; g_model.append(g); row['model_gap_s'] = round(g, 2)
                if permissive_mod: g_model_perm.append(g)
        if obs_c is not None:
            nxt = next((a for a in obs_arr if a > obs_c), None)
            if nxt is not None:
                g = (nxt-obs_c)*.1; g_obs.append(g); row['observed_gap_s'] = round(g, 2)
                if permissive_obs: g_obs_perm.append(g)
        row['observed_permissive'], row['model_permissive'] = permissive_obs, permissive_mod
        per.append(row)
    def intervals(conn, exit_link, cars):
        return [(enter[c, conn], enter[c, exit_link]) for c in cars if (c, conn) in enter and (c, exit_link) in enter]
    cl = intervals(CL, SINK, {k[0] for k in enter if k[1] == CL})
    cs = intervals(CS, X, {k[0] for k in enter if k[1] == CS})
    violations = sum(1 for a in cl for b in cs if a[0] < b[1] and b[0] < a[1])
    return dict(status='DEVELOPMENT_ONLY', conflict_rule=meta['conflict'], left_turners_with_approach=len(lefts),
        left_first_seen_in_box=meta['categories'].get('left_first_seen_in_box', 0), model_entered_left_connector=sum(1 for p in per if p['model_enter_left_connector_tick'] is not None),
        stop_bar_crossing_error_s=summary(stop_err), observed_gap_s_all=summary(g_obs), model_gap_s_all=summary(g_model),
        observed_gap_s_permissive=summary(g_obs_perm), model_gap_s_permissive=summary(g_model_perm),
        model_gaps_below_critical=sum(1 for g in g_model if g < meta['critical_gap_s']), critical_gap_s=meta['critical_gap_s'],
        exclusion_violations_left_vs_sb_through=violations, left_connector_occupations=len(cl), sb_through_connector_occupations=len(cs), per_vehicle=per)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--scenario', type=Path, required=True)
    p.add_argument('--run', type=Path, required=True)
    p.add_argument('--signals', type=Path, required=True)
    p.add_argument('--suffix', default='0400-0415')
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    out = analyze(a.scenario, a.run, a.signals, a.suffix)
    with a.output.open('x') as f:
        json.dump(out, f, indent=2); f.write('\n')
    print(json.dumps({k: v for k, v in out.items() if k != 'per_vehicle'}, indent=1))
