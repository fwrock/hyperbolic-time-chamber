#!/usr/bin/env python3
"""Generate an autonomous native-actor DEVELOPMENT approach from exposed Peachtree data.

This is an integration experiment, not a reserved empirical acceptance cohort.
The supplied road boundary and speed cap are explicit assumptions until surveyed.
"""
import argparse
import hashlib
import json
import sys
from pathlib import Path

from ngsim_observed_signals import compile_program, read_changes


def actor(kind, identity, content, dependencies=None):
    return dict(id=identity, typeActor=f'hybrid.actor.{kind}',
                data=dict(dataType=f'model.hybrid.entity.state.{kind}State', content=content),
                dependencies=dependencies or {})


def generate(args):
    workspace = Path(__file__).resolve().parents[3]
    sys.path.insert(0, str(workspace / 'tools/scenario-db-converter'))
    from scenario_output_writer import shard_and_write_actors, write_city_map_json, build_simulation_json
    output = args.output.resolve()
    if output.exists():
        raise FileExistsError(output)
    if args.boundary_m <= 0 or args.speed_limit_kmh <= 0 or args.start_frame >= args.end_frame:
        raise ValueError('Invalid physical bounds')
    rows, sources = [], []
    for path in sorted(args.raw.glob('page-*.json')):
        sources.append(dict(path=str(path.resolve()), sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
        rows.extend(json.loads(path.read_text()))
    origins = {int(row['global_time']) - 100 * int(row['frame_id']) for row in rows}
    if len(origins) != 1:
        raise ValueError('Use exactly one recording origin; vehicle IDs are recording-local')
    first, trajectories = {}, {}
    for row in sorted(rows, key=lambda row: (int(row['frame_id']), int(row['vehicle_id']))):
        frame, identity = int(row['frame_id']), int(row['vehicle_id'])
        if (args.start_frame <= frame <= args.end_frame and row['section_id'] == '1'
                and row['direction'] == '2'):
            trajectories.setdefault(identity, []).append(row)
            first.setdefault(identity, row)
    if not first:
        raise ValueError('No northbound approach vehicles in selected window')
    if any(row['lane_id'] not in ['1', '2'] for row in first.values()):
        raise ValueError('Unsupported entry lane/bay: cannot silently exclude or delay approach vehicles')
    if any(row['movement'] != '1' for group in trajectories.values() for row in group):
        raise ValueError('This development approach only covers through movements')
    signal = compile_program({701: read_changes(args.signal)['Thru']},
        [dict(origin=701, nodeId=2, incomingLinkId=11, outgoingLinkId=12, laneIds=[])],
        args.start_frame, args.end_frame)
    actors = []
    for identity, links in [(1, [11]), (2, [12]), (3, [])]:
        state = dict(startTick=0, scheduleOnTimeManager=False, latitude=33.78,
                     longitude=-84.38 + identity * .001, links=links)
        if identity == 2:
            state['movementSignals'] = [dict(incomingLinkId=11, outgoingLinkId=12, origin=701, laneIds=[])]
        actors.append(actor('Node', identity, state))
    for identity, source, target, length in [(11, 1, 2, args.boundary_m), (12, 2, 3, 300.)]:
        state = dict(startTick=0, scheduleOnTimeManager=False, **{'from': source}, to=target,
            length=length, lanes=2, speedLimit=args.speed_limit_kmh, capacity=3600.,
            freeSpeed=args.speed_limit_kmh, currentSpeed=args.speed_limit_kmh, registered=[],
            simulationMode='MICRO', microTimeStep=.1, microTicksPerGlobalTick=1,
            microEntry=dict(enabled=True))
        if identity == 11:
            state['signalControls'] = [dict(targetLinkId=12, laneIds=[])]
        actors.append(actor('Link', identity, state, {
            'from_node': dict(id=source, classType='hybrid.actor.Node'),
            'to_node': dict(id=target, classType='hybrid.actor.Node')}))
    actors.append(actor('TrafficSignal', 700, dict(startTick=0, nodes=[2], program=signal),
                        {'node': dict(id=2, classType='hybrid.actor.Node')}))
    subjects = []
    for identity, row in sorted(first.items()):
        frame = int(row['frame_id'])
        position, speed = float(row['local_y']) * .3048, float(row['v_vel']) * .3048
        if not 0 <= position < args.boundary_m:
            raise ValueError(f'Initial position outside assumed road: {identity}')
        native = 10000 + identity
        actors.append(actor('Car', native, dict(startTick=frame - args.start_frame,
            origin=1, destination=3, actorType='Car', size=float(row['v_length']) * .3048,
            precomputedRoute=[dict(linkId=11, nodeId=2), dict(linkId=12, nodeId=3)],
            microDepartPosition=position, microDepartSpeed=speed,
            microDepartLane=2 - int(row['lane_id']))))
        subjects.append(dict(source_vehicle=identity, native_vehicle=native, source_class=row['v_class'],
            frame=frame, position_m=position, speed_mps=speed, lane=2-int(row['lane_id']),
            initial_snapshot=frame == args.start_frame))
    data = output / 'data'
    counts = shard_and_write_actors(actors, data)
    city = dict(directed=True, nodes=[dict(id=n, resourceId=str(n), classType='hybrid.actor.Node',
        latitude=33.78, longitude=-84.38+n*.001) for n in [1, 2, 3]], edges=[
        dict(source_id=a, target_id=b, weight=length,
             label=dict(id=i, resourceId=str(i), classType='hybrid.actor.Link', length=length))
        for i, a, b, length in [(11, 1, 2, args.boundary_m), (12, 2, 3, 300.)]])
    write_city_map_json(city, data)
    simulation = build_simulation_json(output.name, 'Peachtree autonomous actor development approach',
        'Exposed data; boundary and speed assumptions; no observed leader forcing; not final validation',
        counts, str(output.parent), end_tick=args.end_frame-args.start_frame, tick_duration=.1,
        settings={'htc.time-manager.conservative-transport': 'channel-closed'})
    simulation['timeManagerType'] = args.policy
    (output / 'simulation.json').write_text(json.dumps(simulation, indent=2)+'\n')
    manifest = dict(status='DEVELOPMENT_ONLY', recording_origin=next(iter(origins)),
        start_frame=args.start_frame, end_frame=args.end_frame, subjects=subjects, sources=sources,
        signal_source=dict(path=str(args.signal.resolve()), sha256=hashlib.sha256(args.signal.read_bytes()).hexdigest()),
        assumptions=dict(boundary_m=args.boundary_m, speed_limit_kmh=args.speed_limit_kmh,
            geometry='Explicit approach boundary, NOT a verified surveyed stop line',
            downstream='Uncongested 300 m sink; no finite junction or conflicting traffic',
            source_class='Measured lengths; all vehicles use native Car dynamics',
            lane_changing='Disabled; native lane initialization from source lane IDs',
            control='Observed northbound through aspects, not inferred from departures'))
    (output / 'scenario_metadata.json').write_text(json.dumps(manifest, indent=2)+'\n')
    (output / 'GENERATION_REPORT.md').write_text(
        '# Peachtree actor development approach\n\n'
        f'{len(subjects)} subjects, native actors, 0.1 s clock, measured signal transitions.\n\n'
        'Development only. Geometry and speed assumptions are explicit in scenario_metadata.json. '
        'No empirical acceptance claim; turning conflicts, downstream blockage and lane changing remain outside this run.\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--raw', type=Path, required=True)
    parser.add_argument('--signal', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--start-frame', type=int, default=600)
    parser.add_argument('--end-frame', type=int, default=1600)
    parser.add_argument('--boundary-m', type=float, required=True)
    parser.add_argument('--speed-limit-kmh', type=float, required=True)
    parser.add_argument('--policy', choices=['discrete-event', 'time-warp'], default='discrete-event')
    generate(parser.parse_args())


if __name__ == '__main__':
    main()
