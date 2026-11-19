#!/usr/bin/env python3
"""Peachtree NB intersection 1 (10th St) development scenario: approach, connector box, exit, measured signal.

Slice 1: only northbound vehicles that are through-only (movement 1, lanes 0/1/2) near the intersection.
Everything excluded is counted in scenario_metadata.json; nothing is silently dropped. Cross traffic, the
opposing flow, turns and the queue from the next intersection are NOT modelled in this slice.
Development use only (exposed data, hypotheses explicit).
"""
import argparse
import hashlib
import json
import math
import sys
from collections import defaultdict
from pathlib import Path

from ngsim_actor_approach import actor
from ngsim_observed_signals import compile_program, read_changes

FT = .3048
# Corridor coordinate s = NGSIM local_y in metres. Stations of intersection 1 (see MICRO_NGSIM_CORRIDOR_AUDIT):
STOP_BAR_M = 38.774       # manual ortho estimate for the middle lane (photo-stopbar.json), +/- 0.76 m source accuracy
EXIT_START_M = 71.7       # start of section 2 (NGSIM section/intersection partition)
EXIT_END_M = 197.7        # end of section 2 (start of intersection 2 region)
A, C, E, NODE_S, NODE_J, NODE_K, SIGNAL, ORIGIN = 11, 12, 13, 1, 2, 3, 700, 701


def load(raw):
    rows, sources = [], []
    for path in sorted(raw.glob('page-*.json')):
        sources.append(dict(path=str(path.resolve()), sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
        rows.extend(r for r in json.loads(path.read_text()) if r['direction'] == '2')
    return rows, sources


def locate(s):
    """Return (link, position) of a corridor station, or None inside the box (unsupported as a start)."""
    if s < STOP_BAR_M:
        return A, s
    if s < EXIT_START_M:
        return None
    return E, s - EXIT_START_M


def generate(args):
    workspace = Path(__file__).resolve().parents[3]
    sys.path.insert(0, str(workspace/'tools/scenario-db-converter'))
    from scenario_output_writer import shard_and_write_actors, write_city_map_json, build_simulation_json
    output = args.output.resolve()
    if output.exists():
        raise FileExistsError(output)
    rows, sources = load(args.raw)
    origins = {int(r['global_time']) - 100*int(r['frame_id']) for r in rows}
    if len(origins) != 1:
        raise ValueError('Use exactly one recording origin; vehicle IDs are recording-local')
    by_vehicle = defaultdict(list)
    for r in rows:
        if args.start_frame <= int(r['frame_id']) <= args.end_frame and float(r['local_y'])*FT <= EXIT_END_M:
            by_vehicle[int(r['vehicle_id'])].append(r)
    subjects, excluded = {}, defaultdict(list)
    for vid, track in sorted(by_vehicle.items()):
        track.sort(key=lambda r: int(r['frame_id']))
        movements = {r['movement'] for r in track}
        lanes = {r['lane_id'] for r in track}
        first = track[0]
        place = locate(float(first['local_y'])*FT)
        if movements != {'1'}: excluded['not_through_only'].append(vid)
        elif not lanes <= {'0', '1', '2'}: excluded['pocket_or_unknown_lane'].append(vid)
        elif place is None: excluded['first_observed_inside_box'].append(vid)
        elif first['lane_id'] not in ('1', '2'): excluded['first_observed_lane_unknown'].append(vid)
        else: subjects[vid] = (first, place)
    if not subjects:
        raise ValueError('No eligible vehicles')
    signal = compile_program({ORIGIN: read_changes(args.signal)['Thru']},
        [dict(origin=ORIGIN, nodeId=NODE_J, incomingLinkId=A, outgoingLinkId=E, laneIds=[])], args.start_frame, args.end_frame)
    speed = args.speed_limit_kmh
    actors = []
    for node, links in [(NODE_S, [A]), (NODE_J, [E, C]), (NODE_K, [])]:
        state = dict(startTick=0, scheduleOnTimeManager=False, latitude=33.78, longitude=-84.38+node*.001, links=links)
        if node == NODE_J:
            state['movementSignals'] = [dict(incomingLinkId=A, outgoingLinkId=E, origin=ORIGIN, laneIds=[])]
            state['junctionMovements'] = [dict(incomingLinkId=A, outgoingLinkId=E, connectorLinkId=C)]
        actors.append(actor('Node', node, state))
    box = EXIT_START_M - STOP_BAR_M
    for link, a, b, length in [(A, NODE_S, NODE_J, STOP_BAR_M), (C, NODE_J, NODE_J, box), (E, NODE_J, NODE_K, EXIT_END_M-EXIT_START_M)]:
        state = dict(startTick=0, scheduleOnTimeManager=False, **{'from': a}, to=b, length=length, lanes=2, speedLimit=speed,
            freeSpeed=speed, currentSpeed=speed, capacity=3600., registered=[], simulationMode='MICRO', microTimeStep=.1,
            microTicksPerGlobalTick=1, carryMicroRemainder=True)
        if link in (A, C):
            state.update(downstreamLookahead=True, downstreamSpillback=True,
                laneConnections=[dict(outgoingLinkId=E if link == A else E, fromLane=k, toLane=k) for k in range(2)])
        if link == A:
            state.update(signalControls=[dict(targetLinkId=E, laneIds=[])], downstreamConnectors={E: C},
                         microEntry=dict(enabled=True, initialSnapshotTick=0) if args.initial_snapshot else dict(enabled=True))
        if link == C:
            state['junctionGeometry'] = dict(nodeId=NODE_J, incomingLinkId=A, outgoingLinkId=E, lengthMeters=length,
                speedLimitMps=speed/3.6, shape=[dict(x=0, y=0), dict(x=length, y=0)])
        actors.append(actor('Link', link, state, {'from_node': dict(id=a, classType='hybrid.actor.Node'),
                                                'to_node': dict(id=b, classType='hybrid.actor.Node')}))
    actors.append(actor('TrafficSignal', SIGNAL, dict(startTick=0, nodes=[NODE_J], program=signal),
                        {'node': dict(id=NODE_J, classType='hybrid.actor.Node')}))
    manifest_subjects = []
    for vid, (first, (link, position)) in sorted(subjects.items()):
        frame = int(first['frame_id'])
        lane = 2 - int(first['lane_id'])
        native = 10000 + vid
        route = [dict(linkId=A, nodeId=NODE_J), dict(linkId=E, nodeId=NODE_K)] if link == A else [dict(linkId=E, nodeId=NODE_K)]
        actors.append(actor('Car', native, dict(startTick=frame-args.start_frame, origin=NODE_S if link == A else NODE_J,
            destination=NODE_K, actorType='Car', size=float(first['v_length'])*FT, precomputedRoute=route,
            microDepartPosition=position, microDepartSpeed=float(first['v_vel'])*FT, microDepartLane=lane)))
        manifest_subjects.append(dict(source_vehicle=vid, native_vehicle=native, first_frame=frame, link=link,
            position_m=position, speed_mps=float(first['v_vel'])*FT, lane=lane, length_m=float(first['v_length'])*FT,
            initial_snapshot=frame == args.start_frame))
    data = output/'data'
    counts = shard_and_write_actors(actors, data)
    city = dict(directed=True, nodes=[dict(id=n, resourceId=str(n), classType='hybrid.actor.Node', latitude=33.78,
        longitude=-84.38+n*.001) for n in (NODE_S, NODE_J, NODE_K)], edges=[dict(source_id=a, target_id=b, weight=l,
        label=dict(id=i, resourceId=str(i), classType='hybrid.actor.Link', length=l))
        for i, a, b, l in [(A, NODE_S, NODE_J, STOP_BAR_M), (C, NODE_J, NODE_J, box), (E, NODE_J, NODE_K, EXIT_END_M-EXIT_START_M)]])
    write_city_map_json(city, data)
    simulation = build_simulation_json(output.name, 'Peachtree NB intersection 1 development scenario',
        'Exposed data; through-only slice; geometry and speed assumptions explicit', counts, str(output.parent),
        end_tick=args.end_frame-args.start_frame, tick_duration=.1,
        settings={'htc.time-manager.conservative-transport': 'channel-closed'})
    simulation['timeManagerType'] = args.policy
    (output/'simulation.json').write_text(json.dumps(simulation, indent=2)+'\n')
    (output/'scenario_metadata.json').write_text(json.dumps(dict(status='DEVELOPMENT_ONLY', recording_origin=next(iter(origins)),
        start_frame=args.start_frame, end_frame=args.end_frame, subjects=manifest_subjects, sources=sources,
        signal_source=dict(path=str(args.signal.resolve()), sha256=hashlib.sha256(args.signal.read_bytes()).hexdigest()),
        assumptions=dict(stop_bar_m=STOP_BAR_M, stop_bar_source='ortho manual estimate, +/-0.76 m, not surveyed',
            exit_start_m=EXIT_START_M, exit_end_m=EXIT_END_M, speed_limit_kmh=speed, speed_source='assumption (30 mph)' if abs(speed-48.28)<.1 else 'explicit parameter',
            box_length_m=box, lanes='2 through lanes; lane index = 2 - NGSIM lane id',
            slice='through-only NB; no cross traffic, opposing flow, turns, pockets, or next-intersection queue'),
        exclusions={k: dict(count=len(v), vehicles=v) for k, v in excluded.items()},
        eligible=len(subjects)), indent=2)+'\n')
    print(json.dumps(dict(eligible=len(subjects), excluded={k: len(v) for k, v in excluded.items()}), indent=1))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--raw', type=Path, required=True)
    p.add_argument('--signal', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--start-frame', type=int, default=140)
    p.add_argument('--end-frame', type=int, default=9000)
    p.add_argument('--speed-limit-kmh', type=float, default=48.28)
    p.add_argument('--policy', choices=['discrete-event', 'time-warp'], default='discrete-event')
    p.add_argument('--initial-snapshot', action='store_true')
    generate(p.parse_args())


if __name__ == '__main__':
    main()
