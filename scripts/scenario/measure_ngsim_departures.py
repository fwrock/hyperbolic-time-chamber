#!/usr/bin/env python3
"""Measure queue-leader departures (protocol docs/MICRO_NGSIM_DEPARTURE_PROTOCOL_2026_11_19.md) on observed or simulated data."""
import argparse
import json
import statistics
from collections import defaultdict
from pathlib import Path

from analyze_ngsim_actor_approach import events

FT = .3048
STOP_BARS = [38.774, 196.17, 359.74, 600.6]
V_STOP, V_GO, WINDOW, DIST, MAX_T = .5, 3.0, 8.0, 30.0, 20.0


def episodes(track_by_vehicle, leader_of):
    """track: list of (frame, s, v, lane, movement, vehicle). Returns one episode per vehicle and stop bar."""
    found = []
    for vid, track in track_by_vehicle.items():
        for bar in STOP_BARS:
            for j, (f, s, v, lane, mv, _) in enumerate(track):
                if v > V_GO and bar - WINDOW <= s <= bar + 60:
                    starts = [i for i in range(j) if track[i][2] <= V_STOP and bar - WINDOW <= track[i][1] <= bar and leader_of(vid, track[i])]
                    if not starts:
                        break
                    i = starts[-1]
                    k = next((m for m in range(i, len(track)) if track[m][1] >= track[i][1] + DIST), None)
                    if k is None: break
                    t = (track[k][0] - track[i][0]) * .1
                    seg = track[i:k+1]
                    if t <= MAX_T and all(r[3] in (0, 1, 2) and r[4] == 1 for r in seg) and t > 0:
                        found.append(dict(vehicle=vid, bar=bar, t30_s=t, v30_mps=track[k][2], accel_mps2=2*DIST/t**2))
                    break
    return found


def geometric_leader(tracks):
    """Protocol emenda 1: no other vehicle in the same lane (or lane 0) with the front in (x, x + 40] m at the same instant."""
    by_time = defaultdict(list)
    for vid, t in tracks.items():
        for row in t: by_time[row[0]].append((row[1], row[3], vid))
    def leader_of(vid, row):
        return not any(row[1] < s <= row[1] + 40 and (lane == row[3] or lane == 0) for s, lane, v in by_time[row[0]] if v != vid)
    return leader_of


def observed(paths):
    tracks = defaultdict(list)
    for path in paths:
        for r in json.loads(Path(path).read_text()):
            if r['direction'] == '2':
                key = int(r['vehicle_id'])
                tracks[key].append((int(r['frame_id']), float(r['local_y'])*FT, float(r['v_vel'])*FT,
                                    int(r['lane_id']) if r['lane_id'].isdigit() else -1, int(r['movement']), key))
    for t in tracks.values(): t.sort()
    return tracks, geometric_leader(tracks)


def simulated(scenario, run):
    meta = json.loads((scenario/'scenario_metadata.json').read_text())
    layout = {l['id']: l for l in meta['layout']}
    pts = {}
    for r in events(run):
        d = r['data']
        if r['event_type'] in ('micro_update', 'enter_micro_link', 'leave_micro_link'):
            link = int(d['link_id'])
            pos = d.get('position', d.get('initial_position', d.get('final_position')))
            vel = d.get('velocity', d.get('initial_velocity', d.get('final_velocity')))
            if pos is None or vel is None: continue
            pts[int(d['car_id']), int(r['tick'])] = (layout[link]['offset'] + pos, vel, int(d.get('lane', 1)))
    tracks = defaultdict(list)
    for (vid, tick), (s, v, lane) in pts.items():
        tracks[vid].append((tick, s, v, 2 - lane if lane in (0, 1) else lane, 1, vid))
    for t in tracks.values(): t.sort()
    return tracks, geometric_leader(tracks)


def summarize(found):
    out = {}
    for key, name in [('t30_s', 'm1_time_30m_s'), ('v30_mps', 'm2_speed_at_30m_mps'), ('accel_mps2', 'm3_equivalent_accel_mps2')]:
        v = sorted(e[key] for e in found)
        out[name] = None if not v else dict(n=len(v), median=round(statistics.median(v), 2),
            p10=round(v[len(v)//10], 2), p90=round(v[-1-len(v)//10], 2))
    return out


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest='mode', required=True)
    o = sub.add_parser('observed'); o.add_argument('--pages', type=Path, required=True); o.add_argument('--output', type=Path, required=True)
    s = sub.add_parser('simulated'); s.add_argument('--scenario', type=Path, required=True); s.add_argument('--run', type=Path, required=True)
    s.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    if a.mode == 'observed':
        tracks, leader = observed(sorted(a.pages.glob('page-*.json')))
    else:
        tracks, leader = simulated(a.scenario, a.run)
    found = episodes(tracks, leader)
    result = dict(mode=a.mode, protocol='docs/MICRO_NGSIM_DEPARTURE_PROTOCOL_2026_11_19.md', episodes=len(found),
                  by_bar={str(b): sum(1 for e in found if e['bar'] == b) for b in STOP_BARS}, **summarize(found), detail=found)
    with a.output.open('x') as f:
        json.dump(result, f, indent=2); f.write('\n')
    print(json.dumps({k: v for k, v in result.items() if k != 'detail'}, indent=1))
