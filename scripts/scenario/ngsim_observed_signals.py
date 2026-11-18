#!/usr/bin/env python3
"""Compile recorded NGSIM frame changes into native, non-repeating-within-horizon stages."""
import argparse
import csv
import hashlib
import json
from pathlib import Path


def read_changes(path):
    with Path(path).open(newline='', encoding='utf-8-sig') as stream:
        reader = csv.DictReader(stream, delimiter='\t' if Path(path).suffix.lower() == '.txt' else ',')
        names = reader.fieldnames or []
        movements = [name.removeprefix('BG_') for name in names if name.startswith('BG_')]
        expected = {prefix + movement for movement in movements for prefix in ['BG_', 'BY_', 'BR_']}
        if not movements or set(names) != expected:
            raise ValueError('Unsupported observed signal columns')
        events = {movement: {} for movement in movements}
        for row in reader:
            for movement in movements:
                values = [(row[prefix + movement] or '').strip() for prefix in ['BG_', 'BY_', 'BR_']]
                present = [(int(value), phase) for value, phase in zip(values, ['Green', 'Yellow', 'Red']) if value]
                frames = [frame for frame, _ in present]
                if any(frame < 0 for frame in frames) or any(a > b for a, b in zip(frames, frames[1:])):
                    raise ValueError('Observed green/yellow/red frame ordering is invalid')
                # The source contains zero-duration greens (BG == BY). The later
                # phase wins within that row; there is no positive green interval.
                for frame, phase in dict(present).items():
                    old = events[movement].get(frame)
                    if old is not None and old != phase:
                        raise ValueError('Conflicting observed aspects at the same frame')
                    events[movement][frame] = phase
    return {movement: sorted(values.items()) for movement, values in events.items()}


def compile_program(changes, movements, start_frame, end_frame, frame_seconds=.1):
    if not 0 <= start_frame < end_frame or frame_seconds != .1:
        raise ValueError('NGSIM signal compilation requires a positive window and 0.1 s frames')
    origins = [movement['origin'] for movement in movements]
    if set(origins) != set(changes) or len(set(origins)) != len(origins):
        raise ValueError('Every native movement needs exactly one observed signal source')
    aspects = {}
    for origin, events in changes.items():
        known = [(frame, phase) for frame, phase in events if frame <= start_frame]
        if not known:
            raise ValueError('Initial observed aspect is unknown; choose a covered window')
        aspects[origin] = max(known)[1]
    # One extra frame holds the last observed aspect beyond the inclusive simulation
    # horizon. A native cyclic program must never replay the source during scored time.
    boundaries = sorted({start_frame, end_frame + 1} |
        {frame for events in changes.values() for frame, _ in events if start_frame < frame <= end_frame})
    stages = []
    for frame, following in zip(boundaries, boundaries[1:]):
        for origin, events in changes.items():
            aspects[origin] = max((f, phase) for f, phase in events if f <= frame)[1]
        stages.append(dict(durationSeconds=(following - frame) * frame_seconds,
                           green=sorted(origin for origin, phase in aspects.items() if phase == 'Green'),
                           yellow=sorted(origin for origin, phase in aspects.items() if phase == 'Yellow')))
    return dict(control='Fixed', stages=stages, movements=movements, reportMovementChanges=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--movement', choices=['Thru', 'Left'], required=True)
    parser.add_argument('--origin', type=int, required=True)
    parser.add_argument('--node', type=int, required=True)
    parser.add_argument('--incoming', type=int, required=True)
    parser.add_argument('--outgoing', type=int, required=True)
    parser.add_argument('--start-frame', type=int, required=True)
    parser.add_argument('--end-frame', type=int, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    changes = read_changes(args.source)
    movement = dict(origin=args.origin, nodeId=args.node, incomingLinkId=args.incoming,
                    outgoingLinkId=args.outgoing, laneIds=[])
    program = compile_program({args.origin: changes[args.movement]}, [movement], args.start_frame, args.end_frame)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.output.exists():
        raise FileExistsError(args.output)
    args.output.write_text(json.dumps(dict(program=program,
        provenance=dict(source=str(args.source.resolve()), sha256=hashlib.sha256(args.source.read_bytes()).hexdigest(),
            start_frame=args.start_frame, end_frame=args.end_frame, frame_seconds=.1,
            initial_aspect='Last independently observed source transition; never inferred from vehicle departures')),
        indent=2) + '\n')


if __name__ == '__main__':
    main()
