#!/usr/bin/env python3
"""Audit all development-approach subjects without dropping early model exits."""
import argparse
import hashlib
import json
import math
from collections import Counter
from pathlib import Path


def events(root):
    return [json.loads(line) for path in sorted(root.glob('reports/*/*.jsonl'))
            for line in path.read_text().splitlines()]


def canonical(rows):
    labels = {'micro_update', 'enter_micro_link', 'leave_micro_link',
              'signal_phase_change', 'signal_program_stage', 'sumo_tripinfo'}
    return sorted(json.dumps(dict(tick=row['tick'], event_type=row['event_type'], data=row['data']),
                             sort_keys=True) for row in rows if row['event_type'] in labels)


def analyze(scenario, run, compare=None):
    metadata = json.loads((scenario / 'scenario_metadata.json').read_text())
    boundary = metadata['assumptions']['boundary_m']
    start, end = metadata['start_frame'], metadata['end_frame']
    rows = events(run)
    predictions, admissions, exits = {}, {}, {}
    for event in rows:
        data, tick = event['data'], int(event['tick'])
        label = event['event_type']
        if label in ['enter_micro_link', 'micro_update', 'leave_micro_link']:
            identity = int(data['car_id'])
            if int(data['link_id']) != 11:
                continue
            if label == 'enter_micro_link':
                admissions[identity] = data
                position = data['initial_position']
            elif label == 'leave_micro_link':
                exits[identity] = tick
                position = boundary
            else:
                position = data['position']
            predictions[identity, tick] = position
    subjects = {subject['source_vehicle']: subject for subject in metadata['subjects']}
    observed, crossings = {}, {}
    for source in metadata['sources']:
        path = Path(source['path'])
        if hashlib.sha256(path.read_bytes()).hexdigest() != source['sha256']:
            raise ValueError('Raw source changed since generation')
        for row in json.loads(path.read_text()):
            identity, frame = int(row['vehicle_id']), int(row['frame_id'])
            if identity not in subjects or not start <= frame <= end:
                continue
            subject = subjects[identity]
            if frame < subject['frame']:
                continue
            position = float(row['local_y']) * .3048
            if row['section_id'] == '1' and row['direction'] == '2':
                observed.setdefault(identity, []).append((frame-start, position))
            if position >= boundary and row['direction'] == '2':
                crossings[identity] = min(crossings.get(identity, frame-start), frame-start)
    metrics, all_errors, missing = [], [], 0
    for identity, subject in sorted(subjects.items()):
        native = subject['native_vehicle']
        admission = admissions.get(native)
        errors = []
        for tick, position in observed.get(identity, []):
            predicted = boundary if exits.get(native, end+1) <= tick else predictions.get((native, tick))
            if predicted is None:
                missing += 1
            else:
                errors.append((predicted-position)**2)
        all_errors.extend(errors)
        metrics.append(dict(source_vehicle=identity, native_vehicle=native,
            observed_approach_samples=len(observed.get(identity, [])), predicted_samples=len(errors),
            absorbing_boundary_position_rmse_m=math.sqrt(sum(errors)/len(errors)) if errors else None,
            entry_state_matches=admission is not None and all([
                abs(admission['initial_position']-subject['position_m']) < 1e-9,
                abs(admission['initial_velocity']-subject['speed_mps']) < 1e-9,
                admission['lane'] == subject['lane'], admission['tick'] == subject['frame']-start]),
            model_boundary_tick=exits.get(native), source_boundary_tick=crossings.get(identity),
            boundary_timing_error_seconds=(exits[native]-crossings[identity])*.1
                if native in exits and identity in crossings else None))
    expected = []
    program = json.loads((scenario/'data/traffic_signals_0.json').read_text())[0]['data']['content']['program']
    tick = 0
    for stage in program['stages']:
        if tick <= end-start:
            expected.append((tick, 'Green' if 701 in stage['green'] else 'Yellow' if 701 in stage['yellow'] else 'Red'))
        tick += round(stage['durationSeconds']*10)
    actual = sorted((int(row['tick']), row['data']['phase_state'])
        for row in rows if row['event_type']=='signal_phase_change')
    result = dict(status='DEVELOPMENT_ONLY', subject_count=len(subjects), subjects=metrics,
        event_counts=dict(Counter(row['event_type'] for row in rows)),
        missing_prediction_samples=missing, source_approach_samples=sum(len(v) for v in observed.values()),
        absorbing_boundary_position_rmse_m=math.sqrt(sum(all_errors)/len(all_errors)) if all_errors else None,
        observed_signal_trace_matches=actual == expected,
        signal_trace=dict(expected=expected, actual=actual),
        interpretation='Integration and model diagnostics only. Source boundary timing is not a surveyed stop-line measurement. '
            'Absorbing progress retains observed approach samples after an early model exit. '
            'No speed RMSE comparing incompatible observation operators, no pass/fail thresholds, no tuning on this recording.',
        limitations=metadata['assumptions'])
    if compare:
        first, second = canonical(rows), canonical(events(compare))
        result['committed_physical_events_match_comparison'] = first == second
        result['comparison_event_counts'] = [len(first), len(second)]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scenario', type=Path, required=True)
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--compare', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = analyze(args.scenario, args.run, args.compare)
    with args.output.open('x') as stream:
        json.dump(result, stream, indent=2)
        stream.write('\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
