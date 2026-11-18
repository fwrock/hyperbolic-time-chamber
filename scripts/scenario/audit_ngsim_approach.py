#!/usr/bin/env python3
"""Audit exposed Peachtree geometry, track coverage and departure timing before calibration."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path

import numpy as np

from ngsim_observed_signals import read_changes

FEET_M = .3048


def load_recording(directory):
    rows, seen, sources, origins = [], {}, [], set()
    for path in sorted(directory.glob('page-*.json')):
        payload = path.read_bytes()
        sources.append(dict(path=str(path.resolve()), sha256=hashlib.sha256(payload).hexdigest()))
        for row in json.loads(payload):
            origins.add(int(row['global_time']) - 100 * int(row['frame_id']))
            key = (int(row['vehicle_id']), int(row['frame_id']))
            if key in seen:
                if row != seen[key]:
                    raise ValueError('Conflicting source rows for recording/vehicle/frame')
                continue
            seen[key] = row
            rows.append(row)
    if len(origins) != 1:
        raise ValueError('Exactly one recording origin is required per coordinate transform')
    return rows, dict(recording_origin=origins.pop(), sources=sources, unique_rows=len(rows))


def fit_coordinates(rows):
    """Estimate coordinate registration, never a traffic parameter; retain the recording identity."""
    selected = [r for r in rows if r['section_id'] == '1' and r['direction'] == '2']
    world = np.array([[float(r['global_x']), float(r['global_y'])] for r in selected])
    local = np.array([[float(r['local_x']), float(r['local_y'])] for r in selected])
    if len(world) < 3 or not np.isfinite(world).all() or not np.isfinite(local).all():
        raise ValueError('Insufficient finite coordinate observations')
    center = world.mean(axis=0)
    design = np.column_stack((world-center, np.ones(len(world))))
    if np.linalg.matrix_rank(design) != 3:
        raise ValueError('Coordinate observations cannot identify a planar affine transform')
    coefficients = np.linalg.lstsq(design, local, rcond=None)[0]
    error = design @ coefficients - local
    return dict(world_center_ft=center.tolist(), coefficients=coefficients.tolist(),
        samples=len(selected), vehicles=len({r['vehicle_id'] for r in selected}),
        residual_rmse_m=(np.sqrt((error**2).mean(axis=0))*FEET_M).tolist(),
        residual_max_abs_m=(np.abs(error).max(axis=0)*FEET_M).tolist(),
        interpretation='Coordinate registration within this recording and approach, not a surveyed map or behavioral fit')


def transform_points(registration, points):
    points = np.asarray(points, dtype=float)
    if points.ndim != 2 or points.shape[1] != 2 or not np.isfinite(points).all():
        raise ValueError('Finite two-dimensional world points required')
    design = np.column_stack((points-np.array(registration['world_center_ft']), np.ones(len(points))))
    return (design @ np.array(registration['coefficients'])).tolist()


def station_at_lateral(line_local_ft, lateral_ft):
    a, b = line_local_ft
    if abs(b[0]-a[0]) < 1e-9:
        raise ValueError('Transverse feature cannot define a longitudinal station')
    if not min(a[0], b[0]) <= lateral_ft <= max(a[0], b[0]):
        raise ValueError('Lane lies outside the digitized feature; extrapolation is not allowed')
    return (a[1]+(lateral_ft-a[0])*(b[1]-a[1])/(b[0]-a[0]))*FEET_M


def track_inventory(rows, start, end):
    groups = defaultdict(list)
    for row in rows:
        groups[int(row['vehicle_id'])].append(row)
    inventory = []
    for identity, group in sorted(groups.items()):
        group.sort(key=lambda r: int(r['frame_id']))
        selected = [r for r in group if start <= int(r['frame_id']) <= end
                    and r['section_id'] == '1' and r['direction'] == '2']
        if not selected:
            continue
        first = selected[0]
        frame = int(first['frame_id'])
        earlier = [r for r in group if int(r['frame_id']) < frame]
        if frame == start:
            entry = 'initial_snapshot'
        elif not earlier:
            entry = 'track_begins_inside_approach_not_verified_boundary_arrival'
        elif int(earlier[-1]['frame_id']) != frame-1:
            entry = 'observation_gap_before_entry'
        else:
            entry = 'observed_transition_into_approach'
        inventory.append(dict(vehicle=identity, first_approach_frame=frame, entry_classification=entry,
            initial_lane=first['lane_id'], initial_position_m=float(first['local_y'])*FEET_M,
            source_class=first['v_class'], source_track_first_frame=int(group[0]['frame_id']),
            source_track_rows=len(group), declared_total_frames=int(first['total_frames']),
            track_frame_gaps=sum(int(b['frame_id']) != int(a['frame_id'])+1 for a, b in zip(group,group[1:])),
            lane_counts=dict(Counter(r['lane_id'] for r in selected)),
            approach_samples=len(selected)))
    return inventory


def sustained_motion(rows, green, threshold, samples=5):
    """Diagnostic threshold sensitivity; never selects or drops a study subject."""
    run = []
    for row in sorted(rows, key=lambda r: int(r['frame_id'])):
        frame = int(row['frame_id'])
        if frame < green:
            continue
        if float(row['v_vel'])*FEET_M <= threshold or (run and frame != run[-1]+1):
            run = []
        if float(row['v_vel'])*FEET_M > threshold:
            run.append(frame)
            if len(run) == samples:
                return run[0]
    return None


def audit(args):
    if args.output.exists():
        raise FileExistsError(args.output)
    if not 0 <= args.start_frame < args.end_frame:
        raise ValueError('A positive frame window is required')
    rows, provenance = load_recording(args.raw)
    registration = fit_coordinates(rows)
    geometry = json.loads(args.geometry.read_text())
    line = transform_points(registration, geometry['stop_bar_world_endpoints_ft'])
    selected = [r for r in rows if args.start_frame <= int(r['frame_id']) <= args.end_frame]
    inventory = track_inventory(rows, args.start_frame, args.end_frame)
    signals = read_changes(args.signal)['Thru']
    green = next((frame for frame, phase in signals
                  if args.start_frame <= frame <= args.end_frame and phase == 'Green'), None)
    if green is None:
        raise ValueError('No measured through green onset within the audit window')
    stops = []
    for lane in ['1', '2', '11']:
        candidates = [r for r in selected if r['section_id']=='1' and r['direction']=='2'
                      and r['lane_id']==lane]
        if candidates:
            lateral = float(np.median([float(r['local_x']) for r in candidates]))
            stops.append(dict(source_lane=lane, median_lateral_ft=lateral,
                              photo_stop_station_m=station_at_lateral(line, lateral)))
    departures = []
    for identity in args.focus:
        group = [r for r in rows if int(r['vehicle_id']) == identity]
        before = [r for r in group if int(r['frame_id']) == green-1]
        departures.append(dict(vehicle=identity,
            position_before_green_m=float(before[0]['local_y'])*FEET_M if before else None,
            annotated_speed_before_green_mps=float(before[0]['v_vel'])*FEET_M if before else None,
            sustained_motion=[dict(threshold_mps=t, consecutive_frames=5,
                first_frame=(f := sustained_motion(group, green, t)),
                seconds_after_green=(f-green)*.1 if f is not None else None) for t in [.1, .5, 1.]]))
    result = dict(status='DEVELOPMENT_AUDIT_NOT_EMPIRICAL_ACCEPTANCE', provenance=provenance,
        window=dict(start_frame=args.start_frame,end_frame=args.end_frame),
        registration=registration, geometry_source=dict(path=str(args.geometry.resolve()),
            sha256=hashlib.sha256(args.geometry.read_bytes()).hexdigest()),
        photo_stop_bar_local_endpoints_ft=line, lane_stop_stations=stops,
        geometry_uncertainty=geometry['uncertainty'],
        inventory=inventory, entry_counts=dict(Counter(r['entry_classification'] for r in inventory)),
        domain_at_start=[{k:r[k] for k in ['vehicle_id','section_id','int_id','direction','movement','lane_id','local_y']}
            for r in selected if int(r['frame_id'])==args.start_frame and (r['section_id']=='1' or r['int_id']=='1')],
        observed_green_frame=green, departures=departures,
        limitations=['Photo stop bar is manually interpreted, not surveyed; source photo accuracy is retained.',
            'Affine residual is registration consistency, not independent ground-truth map accuracy.',
            'Track births inside the domain must not silently be relabeled as upstream arrivals.',
            'Annotated-speed departure thresholds are descriptive sensitivity, not calibrated startup delay.'])
    if args.other_recording:
        other, other_provenance = load_recording(args.other_recording)
        other_registration = fit_coordinates(other)
        other_line = transform_points(other_registration, geometry['stop_bar_world_endpoints_ft'])
        result['other_recording_registration'] = dict(recording_origin=other_provenance['recording_origin'],
            registration=other_registration, same_world_stop_bar_local_endpoints_ft=other_line,
            mean_longitudinal_offset_from_primary_m=float(np.mean(np.array(other_line)[:,1]-np.array(line)[:,1])*FEET_M),
            interpretation='Local coordinate origins are recording-specific; do not transfer a local station between sessions')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps({k:result[k] for k in ['status','entry_counts','lane_stop_stations','departures']},indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--raw', type=Path, required=True)
    parser.add_argument('--other-recording', type=Path)
    parser.add_argument('--geometry', type=Path, required=True)
    parser.add_argument('--signal', type=Path, required=True)
    parser.add_argument('--start-frame', type=int, default=600)
    parser.add_argument('--end-frame', type=int, default=1600)
    parser.add_argument('--focus', type=int, nargs='+', default=[75,95])
    parser.add_argument('--output', type=Path, required=True)
    audit(parser.parse_args())


if __name__ == '__main__':
    main()
