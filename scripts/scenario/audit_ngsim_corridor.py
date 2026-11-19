#!/usr/bin/env python3
"""Audit the northbound Peachtree corridor (sections/intersections) before building a scenario (G01).

Development evidence on exposed data. Stop-line stations are estimated from the front of the first
stopped vehicle per lane during an observed red, not surveyed; compare with the manual ortho estimate.
"""
import argparse
import hashlib
import json
import statistics
from collections import defaultdict
from pathlib import Path

from ngsim_observed_signals import read_changes

FT = .3048
SIGNALS = {1: 'Peachtree_10th_NB', 2: 'Peachtree_11th_NB', 3: 'Peachtree_12th_NB', 5: 'Peachtree_14th_NB'}


def load(raw):
    rows, sources = [], []
    for path in sorted(raw.glob('page-*.json')):
        sources.append(dict(path=str(path.resolve()), sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
        rows.extend(r for r in json.loads(path.read_text()) if r['direction'] == '2')
    return rows, sources


def red_frames(changes, last):
    """Frames at which the observed through aspect is Red (aspect holds until its next change)."""
    events = changes['Thru']
    red = set()
    for (frame, phase), nxt in zip(events, events[1:] + [(last + 1, None)]):
        if phase == 'Red':
            red.update(range(frame, nxt[0]))
    return red


def audit(raw, signals_dir, suffix, stop_speed_ftps):
    rows, sources = load(raw)
    by_vehicle = defaultdict(list)
    for r in rows:
        by_vehicle[r['vehicle_id']].append(r)
    last_frame = max(int(r['frame_id']) for r in rows)
    summary = defaultdict(int)
    eligible, entries = {}, defaultdict(int)
    for vid, track in by_vehicle.items():
        track.sort(key=lambda r: int(r['frame_id']))
        movements = {r['movement'] for r in track}
        lanes = {r['lane_id'] for r in track}
        if movements == {'1'} and lanes <= {'0', '1', '2'}:  # lane 0 = inside an intersection box
            summary['through_only_lanes_0_1_2'] += 1
            eligible[vid] = track
            first = track[0]
            entries['south_boundary' if float(first['local_y']) * FT < 6 else
                    f"inside_section_{first['section_id']}_int_{first['int_id']}"] += 1
        elif '2' in movements: summary['has_left_movement'] += 1
        elif '3' in movements: summary['has_right_movement'] += 1
        elif lanes & {'11', '12'}: summary['uses_pocket_lane'] += 1
        elif movements == {'0'} or lanes <= {'0'}: summary['no_usable_movement_label'] += 1
        else: summary['other'] += 1
    summary['total_northbound_vehicles'] = len(by_vehicle)
    stops = defaultdict(lambda: defaultdict(list))
    approach_end = {}
    for r in rows:  # approach k is section k; its upper end is the farthest station seen in that section
        if r['int_id'] == '0':
            approach_end[r['section_id']] = max(approach_end.get(r['section_id'], 0.0), float(r['local_y']) * FT)
    for k, name in SIGNALS.items():
        path = signals_dir / f'{name}_{suffix}.txt'
        reds = red_frames(read_changes(path), last_frame)
        seen = set()
        for vid, track in eligible.items():
            for r in track:
                frame = int(r['frame_id'])
                if frame in reds and r['preceding'] == '0' and float(r['v_vel']) <= stop_speed_ftps \
                        and r['section_id'] == str(k) and r['int_id'] == '0' and r['lane_id'] in ('1', '2')\
                        and float(r['local_y']) * FT >= approach_end[str(k)] - 12.0:
                    key = (vid, r['lane_id'], frame // 200)  # one sample per vehicle per ~20 s red window
                    if key not in seen:
                        seen.add(key)
                        stops[k][r['lane_id']].append(float(r['local_y']) * FT)
    stations = {}
    for k, lanes in stops.items():
        stations[k] = {lane: dict(n=len(v), median_m=round(statistics.median(v), 2),
            p10_m=round(sorted(v)[len(v)//10], 2), p90_m=round(sorted(v)[-1-len(v)//10], 2))
            for lane, v in lanes.items() if len(v) >= 3}
    regions = {}
    for r in rows:
        if r['int_id'] != '0':
            lo, hi = regions.get(r['int_id'], (1e9, -1e9))
            y = float(r['local_y']) * FT
            regions[r['int_id']] = (min(lo, y), max(hi, y))
    sections = {}
    for r in rows:
        if r['int_id'] == '0':
            lo, hi = sections.get(r['section_id'], (1e9, -1e9))
            y = float(r['local_y']) * FT
            sections[r['section_id']] = (min(lo, y), max(hi, y))
    return dict(status='DEVELOPMENT_AUDIT_NOT_EMPIRICAL_ACCEPTANCE', sources=sources,
        stop_speed_threshold_ftps=stop_speed_ftps, vehicle_classes=dict(summary), through_eligible_entries=dict(entries),
        estimated_stop_stations_m=stations,
        intersection_regions_m={k: [round(a, 1), round(b, 1)] for k, (a, b) in sorted(regions.items())},
        section_ranges_m={k: [round(a, 1), round(b, 1)] for k, (a, b) in sorted(sections.items())},
        limitations=['Stop stations come from stopped leaders during observed red (front center), not a survey.',
            'Intersection 4 (13th) has no signal file; treated as unsignalized in this audit.',
            'NB/SB 10th signal files are byte-identical in the source; the NB file is used as given.',
            'Vehicles outside through/lanes 1-2 are counted, not modelled.'])


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--raw', type=Path, required=True)
    p.add_argument('--signals', type=Path, required=True)
    p.add_argument('--suffix', default='0400-0415')
    p.add_argument('--stop-speed-ftps', type=float, default=1.0)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    result = audit(a.raw, a.signals, a.suffix, a.stop_speed_ftps)
    with a.output.open('x') as f:
        json.dump(result, f, indent=2); f.write('\n')
    print(json.dumps({k: result[k] for k in ['vehicle_classes', 'through_eligible_entries', 'estimated_stop_stations_m',
        'intersection_regions_m', 'section_ranges_m']}, indent=1))
