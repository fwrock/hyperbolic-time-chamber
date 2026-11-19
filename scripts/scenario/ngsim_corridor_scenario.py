#!/usr/bin/env python3
"""Peachtree NB corridor development scenario: a chain of signalised intersections, through traffic only (slice).

Stations are NGSIM local_y metres. Each intersection k has a stop bar and a box end (where the next road starts).
Only northbound vehicles that are through-only (movement 1, lanes 0/1/2) and first observed on a road (not inside a box)
enter; every exclusion is counted. Cross traffic, opposing flow, turns and pockets are not modelled in this slice.
Development use only: exposed data, hypotheses explicit.
"""
import argparse
import hashlib
import json
import sys
from collections import defaultdict
from pathlib import Path

from ngsim_actor_approach import actor
from ngsim_observed_signals import compile_program, read_changes

FT = .3048
# (name, stop bar m, box end m, signal file stem). Stop bar of 1: manual ortho estimate (photo-stopbar.json, +/-0.76 m);
# others: median front of stopped leaders at observed red (+/- ~2 m: the same method over-estimates the photo value of 1 by 0.6-2.5 m).
INTERSECTIONS = [('10th', 38.774, 71.7, 'Peachtree_10th_NB'), ('11th', 196.17, 235.8, 'Peachtree_11th_NB'),
                 ('12th', 359.74, 377.2, 'Peachtree_12th_NB')]
NODE_START, NODE_END = 1, 20


def load(raw):
    rows, sources = [], []
    for path in sorted(raw.glob('page-*.json')):
        sources.append(dict(path=str(path.resolve()), sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
        rows.extend(r for r in json.loads(path.read_text()) if r['direction'] == '2')
    return rows, sources


def build_layout(k, exit_end):
    """Road/connector table with corridor offsets. Roads end at stop bars, connectors span stop bar to box end."""
    links, start = [], 0.0
    for i in range(k):
        _, stop, box_end, _ = INTERSECTIONS[i]
        links.append(dict(id=10+2*i, role='road', offset=start, length=stop-start, intersection=i))
        links.append(dict(id=11+2*i, role='connector', offset=stop, length=box_end-stop, intersection=i))
        start = box_end
    links.append(dict(id=10+2*k, role='road', offset=start, length=exit_end-start, intersection=k))
    return links


def locate(layout, s):
    for link in layout:
        if link['role'] == 'road' and link['offset'] <= s < link['offset']+link['length']:
            return link, s-link['offset']
    return None


def generate(args):
    workspace = Path(__file__).resolve().parents[3]
    sys.path.insert(0, str(workspace/'tools/scenario-db-converter'))
    from scenario_output_writer import shard_and_write_actors, write_city_map_json, build_simulation_json
    output = args.output.resolve()
    if output.exists():
        raise FileExistsError(output)
    k = args.intersections
    exit_end = args.exit_end_m if args.exit_end_m else INTERSECTIONS[k][1] if k < len(INTERSECTIONS) else None
    if exit_end is None:
        raise ValueError('Provide --exit-end-m')
    layout = build_layout(k, exit_end)
    roads = [l for l in layout if l['role'] == 'road']
    rows, sources = load(args.raw)
    origins = {int(r['global_time']) - 100*int(r['frame_id']) for r in rows}
    if len(origins) != 1:
        raise ValueError('Use exactly one recording origin')
    by_vehicle = defaultdict(list)
    for r in rows:
        if args.start_frame <= int(r['frame_id']) <= args.end_frame and float(r['local_y'])*FT <= exit_end:
            by_vehicle[int(r['vehicle_id'])].append(r)
    subjects, excluded = {}, defaultdict(list)
    for vid, track in sorted(by_vehicle.items()):
        track.sort(key=lambda r: int(r['frame_id']))
        first = track[0]
        place = locate(layout, float(first['local_y'])*FT)
        if {r['movement'] for r in track} != {'1'}: excluded['not_through_only'].append(vid)
        elif not {r['lane_id'] for r in track} <= {'0', '1', '2'}: excluded['pocket_or_unknown_lane'].append(vid)
        elif place is None: excluded['first_observed_inside_box'].append(vid)
        elif first['lane_id'] not in ('1', '2'): excluded['first_observed_lane_unknown'].append(vid)
        else: subjects[vid] = (first, place)
    if not subjects:
        raise ValueError('No eligible vehicles')
    speed = args.speed_limit_kmh
    actors, programs = [], []
    node_of = {}
    for i in range(k+1):
        node_of[i] = 10 + i + 1 if i < k else NODE_END
    junction_node = lambda i: 11 + i
    for i in range(k):
        name, stop, box_end, stem = INTERSECTIONS[i]
        road_in, conn, road_out = layout[2*i], layout[2*i+1], layout[2*i+2]
        origin = 701 + i
        programs.append((i, compile_program({origin: read_changes(args.signals/f'{stem}_{args.suffix}.txt')['Thru']},
            [dict(origin=origin, nodeId=junction_node(i), incomingLinkId=road_in['id'], outgoingLinkId=road_out['id'], laneIds=[])],
            args.start_frame, args.end_frame)))
    for node, links in [(NODE_START, [layout[0]['id']]), (NODE_END, [])] + [
            (junction_node(i), [layout[2*i+2]['id'], layout[2*i+1]['id']]) for i in range(k)]:
        state = dict(startTick=0, scheduleOnTimeManager=False, latitude=33.78, longitude=-84.38+node*.001, links=links)
        if 11 <= node < 11+k:
            i = node-11
            state['movementSignals'] = [dict(incomingLinkId=layout[2*i]['id'], outgoingLinkId=layout[2*i+2]['id'], origin=701+i, laneIds=[])]
            state['junctionMovements'] = [dict(incomingLinkId=layout[2*i]['id'], outgoingLinkId=layout[2*i+2]['id'], connectorLinkId=layout[2*i+1]['id'])]
        actors.append(actor('Node', node, state))
    def endpoints(link):
        i = link['intersection']
        if link['role'] == 'connector': return junction_node(i), junction_node(i)
        a = NODE_START if i == 0 else junction_node(i-1)
        b = junction_node(i) if i < k else NODE_END
        return a, b
    for link in layout:
        a, b = endpoints(link)
        state = dict(startTick=0, scheduleOnTimeManager=False, **{'from': a}, to=b, length=link['length'], lanes=2,
            speedLimit=speed, freeSpeed=speed, currentSpeed=speed, capacity=3600., registered=[], simulationMode='MICRO',
            microTimeStep=.1, microTicksPerGlobalTick=1, carryMicroRemainder=True)
        if link['id'] != layout[-1]['id']:
            nxt = layout[layout.index(link)+1]
            state.update(downstreamLookahead=True, downstreamSpillback=True,
                laneConnections=[dict(outgoingLinkId=(layout[layout.index(link)+(2 if link['role'] == 'road' else 1)]['id']),
                                      fromLane=j, toLane=j) for j in range(2)])
            if link['role'] == 'road':
                state.update(signalControls=[dict(targetLinkId=nxt_road['id'], laneIds=[]) for nxt_road in [layout[layout.index(link)+2]]],
                    downstreamConnectors={layout[layout.index(link)+2]['id']: nxt['id']},
                    microEntry=dict(enabled=True, initialSnapshotTick=0) if args.initial_snapshot else dict(enabled=True))
        if link['id'] == layout[-1]['id']:
            state['microEntry'] = dict(enabled=True, initialSnapshotTick=0) if args.initial_snapshot else dict(enabled=True)
        if link['role'] == 'connector':
            i = link['intersection']
            state['junctionGeometry'] = dict(nodeId=junction_node(i), incomingLinkId=layout[2*i]['id'],
                outgoingLinkId=layout[2*i+2]['id'], lengthMeters=link['length'], speedLimitMps=speed/3.6,
                shape=[dict(x=0, y=0), dict(x=link['length'], y=0)])
        actors.append(actor('Link', link['id'], state, {'from_node': dict(id=a, classType='hybrid.actor.Node'),
                                                      'to_node': dict(id=b, classType='hybrid.actor.Node')}))
    for i, program in programs:
        actors.append(actor('TrafficSignal', 700+i, dict(startTick=0, nodes=[junction_node(i)], program=program),
                            {'node': dict(id=junction_node(i), classType='hybrid.actor.Node')}))
    manifest = []
    for vid, (first, (link, position)) in sorted(subjects.items()):
        frame, lane, native = int(first['frame_id']), 2-int(first['lane_id']), 10000+vid
        later = [l for l in roads if l['offset'] > link['offset']]
        route = [dict(linkId=link['id'], nodeId=junction_node(link['intersection']) if link['intersection'] < k else NODE_END)] + [
            dict(linkId=l['id'], nodeId=junction_node(l['intersection']) if l['intersection'] < k else NODE_END) for l in later]
        origin_node = endpoints(link)[0]
        actors.append(actor('Car', native, dict(startTick=frame-args.start_frame, origin=origin_node, destination=NODE_END,
            actorType='Car', size=float(first['v_length'])*FT, precomputedRoute=route, microDepartPosition=position,
            microDepartSpeed=float(first['v_vel'])*FT, microDepartLane=lane)))
        manifest.append(dict(source_vehicle=vid, native_vehicle=native, first_frame=frame, link=link['id'], position_m=position,
            speed_mps=float(first['v_vel'])*FT, lane=lane, length_m=float(first['v_length'])*FT, initial_snapshot=frame == args.start_frame))
    data = output/'data'
    counts = shard_and_write_actors(actors, data)
    nodes = [NODE_START, NODE_END] + [junction_node(i) for i in range(k)]
    city = dict(directed=True, nodes=[dict(id=n, resourceId=str(n), classType='hybrid.actor.Node', latitude=33.78,
        longitude=-84.38+n*.001) for n in nodes], edges=[dict(source_id=endpoints(l)[0], target_id=endpoints(l)[1], weight=l['length'],
        label=dict(id=l['id'], resourceId=str(l['id']), classType='hybrid.actor.Link', length=l['length'])) for l in layout])
    write_city_map_json(city, data)
    simulation = build_simulation_json(output.name, 'Peachtree NB corridor development scenario',
        'Exposed data; through-only slice; geometry and speed assumptions explicit', counts, str(output.parent),
        end_tick=args.end_frame-args.start_frame, tick_duration=.1, settings={'htc.time-manager.conservative-transport': 'channel-closed'})
    simulation['timeManagerType'] = args.policy
    (output/'simulation.json').write_text(json.dumps(simulation, indent=2)+'\n')
    signal_sources = [dict(intersection=INTERSECTIONS[i][0], path=str((args.signals/f'{INTERSECTIONS[i][3]}_{args.suffix}.txt').resolve()),
        sha256=hashlib.sha256((args.signals/f'{INTERSECTIONS[i][3]}_{args.suffix}.txt').read_bytes()).hexdigest()) for i in range(k)]
    (output/'scenario_metadata.json').write_text(json.dumps(dict(status='DEVELOPMENT_ONLY', recording_origin=next(iter(origins)),
        start_frame=args.start_frame, end_frame=args.end_frame, layout=layout, intersections=[dict(name=n, stop_bar_m=s, box_end_m=b)
        for n, s, b, _ in INTERSECTIONS[:k]], exit_end_m=exit_end, subjects=manifest, sources=sources, signal_sources=signal_sources,
        assumptions=dict(speed_limit_kmh=speed, speed_source='assumption (30 mph)' if abs(speed-48.28) < .1 else 'explicit parameter',
            stop_bar_sources='int 1: ortho manual estimate +/-0.76 m; others: stopped-leader median at observed red, +/-~2 m',
            lanes='2 through lanes; lane index = 2 - NGSIM lane id',
            slice='through-only NB; no cross traffic, opposing flow, turns, pockets, or the intersection after the last modelled one'),
        exclusions={n: dict(count=len(v), vehicles=v) for n, v in excluded.items()}, eligible=len(subjects)), indent=2)+'\n')
    print(json.dumps(dict(intersections=k, eligible=len(subjects), excluded={n: len(v) for n, v in excluded.items()}), indent=1))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--raw', type=Path, required=True)
    p.add_argument('--signals', type=Path, required=True, help='directory with Peachtree_<street>_NB_<suffix>.txt files')
    p.add_argument('--suffix', default='0400-0415')
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--intersections', type=int, default=2)
    p.add_argument('--exit-end-m', type=float)
    p.add_argument('--start-frame', type=int, default=342)
    p.add_argument('--end-frame', type=int, default=9000)
    p.add_argument('--speed-limit-kmh', type=float, default=48.28)
    p.add_argument('--policy', choices=['discrete-event', 'time-warp'], default='discrete-event')
    p.add_argument('--initial-snapshot', action='store_true')
    generate(p.parse_args())


if __name__ == '__main__':
    main()
