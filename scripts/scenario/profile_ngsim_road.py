#!/usr/bin/env python3
"""Speed profile along the road between box 1 and stop bar 2 (protocol docs/MICRO_NGSIM_SPEED_PROFILE_PROTOCOL_2026_11_19.md)."""
import argparse
import json
import statistics
from collections import defaultdict
from pathlib import Path

from analyze_ngsim_actor_approach import events

FT = .3048
LO, HI, BIN = 71.7, 196.17, 10.0


def pct(v, q):
    v = sorted(v)
    return round(v[min(len(v)-1, int(q*len(v)))], 2)


def observed(pages):
    tracks = defaultdict(list)
    for path in pages:
        for r in json.loads(Path(path).read_text()):
            if r['direction'] == '2':
                tracks[int(r['vehicle_id'])].append((int(r['frame_id']), float(r['local_y'])*FT, float(r['v_vel'])*FT,
                    int(r['lane_id']) if r['lane_id'].isdigit() else -1, int(r['movement'])))
    out = []
    for vid, t in tracks.items():
        t.sort()
        seg = [r for r in t if LO <= r[1] <= HI]
        if not seg or seg[0][1] > 80 or seg[-1][1] < 190: continue
        if any(r[4] != 1 or r[3] not in (0, 1, 2) for r in seg): continue
        out.append([(s, v) for _, s, v, _, _ in seg])
    return out


def simulated(scenario, run):
    meta = json.loads((scenario/'scenario_metadata.json').read_text())
    layout = {l['id']: l for l in meta['layout']}
    tracks = defaultdict(list)
    keep = {x['native_vehicle'] for x in meta['subjects'] if x.get('category', 'through_only') == 'through_only'}
    for r in events(run):
        d = r['data']
        if r['event_type'] == 'micro_update' and int(d['car_id']) in keep:
            tracks[int(d['car_id'])].append((int(r['tick']), layout[int(d['link_id'])]['offset'] + d['position'], d['velocity']))
    out = []
    for vid, t in tracks.items():
        t.sort()
        seg = [(s, v) for _, s, v in t if LO <= s <= HI]
        if seg and seg[0][0] <= 80 and seg[-1][0] >= 190: out.append(seg)
    return out


def profile(vehicles):
    strata = {'moving': [], 'slow': []}
    for seg in vehicles:
        strata['moving' if seg[0][1] >= 5.0 else 'slow'].append(seg)
    result = {}
    for name, group in strata.items():
        bins = defaultdict(list)
        for seg in group:
            for s, v in seg: bins[int((s-LO)//BIN)].append(v)
        result[name] = dict(vehicles=len(group), bins={f'{LO+k*BIN:.0f}-{min(HI, LO+(k+1)*BIN):.0f}': dict(n=len(v),
            median=pct(v, .5), p25=pct(v, .25), p75=pct(v, .75)) for k, v in sorted(bins.items())})
    return result


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--observed-pages', type=Path, nargs='+', required=True, help='name=dir pairs')
    p.add_argument('--model-runs', type=Path, nargs='+', required=True, help='scenario:run pairs')
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    out = dict(protocol='docs/MICRO_NGSIM_SPEED_PROFILE_PROTOCOL_2026_11_19.md', observed={}, model={})
    for item in a.observed_pages:
        name, directory = str(item).split('=')
        out['observed'][name] = profile(observed(sorted(Path(directory).glob('page-*.json'))))
    for item in a.model_runs:
        scenario, run = str(item).split(':')
        out['model'][Path(run).name] = profile(simulated(Path(scenario), Path(run)))
    with a.output.open('x') as f:
        json.dump(out, f, indent=2); f.write('\n')
    for name, prof in out['observed'].items():
        print('OBSERVED', name, {k: v['vehicles'] for k, v in prof.items()})
