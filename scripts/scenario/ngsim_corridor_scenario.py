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
# Southbound (direction 4) in mirrored coordinates s' = Y0 - local_y, so the same chain code applies. The physical box is the same region as
# for northbound: SB stop bar = estimate from stopped leaders at observed red (p10 of leader fronts: 72.6 m and 236.9 m; +/-~3 m), box end =
# northbound stop bar. Order of encounter: 11th then 10th. SB 12th/14th are not set up here.
Y0_SB = 359.74
INTERSECTIONS_SB = [('11th', Y0_SB-236.9, Y0_SB-196.17, 'Peachtree_11th_SB', '2'), ('10th', Y0_SB-72.6, Y0_SB-38.774, 'Peachtree_10th_SB', '1')]
INTERSECTIONS_NB = [(n, a, b, c, str(i+1)) for i, (n, a, b, c) in enumerate(INTERSECTIONS)]
NODE_START, NODE_END = 1, 20


def direction_table(direction):
    if direction == '2':
        return INTERSECTIONS_NB, 0.0, 1.0
    if direction == '4':
        return INTERSECTIONS_SB, Y0_SB, -1.0
    raise ValueError('direction must be 2 (northbound) or 4 (southbound)')


def load(raw, direction='2'):
    rows, sources = [], []
    for path in sorted(raw.glob('page-*.json')):
        sources.append(dict(path=str(path.resolve()), sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
        rows.extend(r for r in json.loads(path.read_text()) if r['direction'] == direction)
    return rows, sources


def build_layout(k, exit_end, table=None):
    table = table or INTERSECTIONS
    """Road/connector table with corridor offsets. Roads end at stop bars, connectors span stop bar to box end."""
    links, start = [], 0.0
    for i in range(k):
        _, stop, box_end = table[i][0], table[i][1], table[i][2]
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
    table, origin_s, sign = direction_table(args.direction)
    S = lambda r: origin_s + sign*float(r['local_y'])*FT
    exit_end = args.exit_end_m if args.exit_end_m else (Y0_SB if args.direction == '4' else (table[k][1] if k < len(table) else None))
    if exit_end is None:
        raise ValueError('Provide --exit-end-m')
    layout = build_layout(k, exit_end, table)
    roads = [l for l in layout if l['role'] == 'road']
    rows, sources = load(args.raw, args.direction)
    origins = {int(r['global_time']) - 100*int(r['frame_id']) for r in rows}
    if len(origins) != 1:
        raise ValueError('Use exactly one recording origin')
    by_vehicle = defaultdict(list)
    for r in rows:
        if args.start_frame <= int(r['frame_id']) <= args.end_frame and 0.0 <= S(r) <= exit_end:
            by_vehicle[int(r['vehicle_id'])].append(r)
    subjects, excluded = {}, defaultdict(list)
    box_ranges = [(table[i][1], table[i][2]) for i in range(k)]
    box_ids = [table[i][4] for i in range(k)]
    def box_of(r):
        """Intersection box index (0-based) of a row, or None."""
        if r['int_id'] in box_ids:
            return box_ids.index(r['int_id'])
        s_ = S(r)
        for i, (lo, hi) in enumerate(box_ranges):
            if lo <= s_ < hi: return i
        return None
    for vid, track in sorted(by_vehicle.items()):
        track.sort(key=lambda r: int(r['frame_id']))
        # Leading rows labelled lane 0 (inside a box) that already lie on a road, e.g. one frame at the domain edge, are not an entry state.
        lead = 0
        while lead < len(track)-1 and track[lead]['lane_id'] == '0' and locate(layout, S(track[lead])) is not None:
            lead += 1
        track = track[lead:]
        first = track[0]
        place = locate(layout, S(first))
        lanes = {r['lane_id'] for r in track}
        through = {r['movement'] for r in track} == {'1'}
        if not lanes <= {'0', '1', '2'}:
            excluded['pocket_or_unknown_lane'].append(vid)
        elif not args.include_all:
            if not through: excluded['not_through_only'].append(vid)
            elif place is None: excluded['first_observed_inside_box'].append(vid)
            elif first['lane_id'] not in ('1', '2'): excluded['first_observed_lane_unknown'].append(vid)
            else: subjects[vid] = dict(first=first, link=place[0], position=place[1], category='through_only', end=None)
        else:
            last = track[-1]
            first_box, last_box = box_of(first), box_of(last)
            lane_row = next((r for r in track if r['lane_id'] in ('1', '2')), None)
            if lane_row is None:
                excluded['never_in_lane_1_or_2'].append(vid); continue
            if place is not None and first['lane_id'] in ('1', '2'):
                link, position, entry = place[0], place[1], 'road'
            elif first_box is not None:
                road_after = roads[first_box+1]
                link, position, entry = road_after, 0.0, 'side_entry'
            else:
                excluded['first_observed_unplaceable'].append(vid); continue
            if place is not None and first['lane_id'] in ('1', '2') and through:
                category = 'through_only'
            elif entry == 'side_entry':
                category = 'side_entry'
            else:
                category = 'other_road_entry'
            end = None
            if last_box is not None and roads[last_box+1]['offset'] >= link['offset']:
                end = dict(kind='turn_off_proxy', road=roads[last_box+1], position=3.0)
            elif last_box is None and S(last) < exit_end - 5:
                end_place = locate(layout, S(last))
                if end_place is not None:
                    end = dict(kind='ends_in_road', road=end_place[0], position=end_place[1])
            if end is not None and end['road']['offset'] < link['offset']:
                excluded['end_before_start'].append(vid); continue
            subjects[vid] = dict(first=first, link=link, position=position, category=category, end=end, lane_row=lane_row)
    if not subjects:
        raise ValueError('No eligible vehicles')
    speed = args.speed_limit_kmh
    actors, programs = [], []
    node_of = {}
    for i in range(k+1):
        node_of[i] = 10 + i + 1 if i < k else NODE_END
    junction_node = lambda i: 11 + i
    for i in range(k):
        name, stop, box_end, stem = table[i][:4]
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
            # Queued admission is required to enter a spillback Link when its lane may be physically full (G03 contract).
            state['microEntry'] = dict(enabled=True)
            state['junctionGeometry'] = dict(nodeId=junction_node(i), incomingLinkId=layout[2*i]['id'],
                outgoingLinkId=layout[2*i+2]['id'], lengthMeters=link['length'], speedLimitMps=speed/3.6,
                shape=[dict(x=0, y=0), dict(x=link['length'], y=0)])
        actors.append(actor('Link', link['id'], state, {'from_node': dict(id=a, classType='hybrid.actor.Node'),
                                                      'to_node': dict(id=b, classType='hybrid.actor.Node')}))
    for i, program in programs:
        actors.append(actor('TrafficSignal', 700+i, dict(startTick=0, nodes=[junction_node(i)], program=program),
                            {'node': dict(id=junction_node(i), classType='hybrid.actor.Node')}))
    manifest = []
    speeds = None
    if args.speed_population:
        import random
        population = [x['free_speed_mps'] for x in json.loads(args.speed_population.read_text())['population']]
        rng = random.Random(args.speed_seed)
        speeds = {vid: rng.choice(population) for vid in sorted(subjects)}  # population draw; never the vehicle's own observation
    for vid, sub in sorted(subjects.items()):
        first, link, position = sub['first'], sub['link'], sub['position']
        lane_src = sub.get('lane_row', first)
        frame, lane, native = int(first['frame_id']), 2-int(lane_src['lane_id']), 10000+vid
        end = sub['end']
        last_road = end['road'] if end else roads[-1]
        path = [l for l in roads if link['offset'] <= l['offset'] <= last_road['offset']]
        route = [dict(linkId=l['id'], nodeId=junction_node(l['intersection']) if l['intersection'] < k else NODE_END) for l in path]
        origin_node = endpoints(link)[0]
        extra = {}
        if end is not None:
            extra['microArrivalPosition'] = end['position']
        # A synthesized side-street injection is not a measured position: it never takes part in the exact initial snapshot (tick 0).
        start_tick = frame-args.start_frame if sub['category'] != 'side_entry' else max(1, frame-args.start_frame)
        actors.append(actor('Car', native, dict(startTick=start_tick, origin=origin_node, destination=NODE_END,
            actorType='Car', size=float(first['v_length'])*FT, precomputedRoute=route, microDepartPosition=position,
            microDepartSpeed=float(first['v_vel'])*FT, microDepartLane=lane,
            **({'microAcceleration': args.max_acceleration} if args.max_acceleration else {}),
            **({'microDesiredMaxSpeed': speeds[vid]} if speeds else {}), **extra)))
        manifest.append(dict(source_vehicle=vid, native_vehicle=native, first_frame=frame, link=link['id'], position_m=position,
            speed_mps=float(first['v_vel'])*FT, lane=lane, length_m=float(first['v_length'])*FT, initial_snapshot=frame == args.start_frame,
            category=sub['category'], end=(dict(kind=end['kind'], road=end['road']['id'], position_m=end['position']) if end else None)))
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
    signal_sources = [dict(intersection=table[i][0], path=str((args.signals/f'{table[i][3]}_{args.suffix}.txt').resolve()),
        sha256=hashlib.sha256((args.signals/f'{table[i][3]}_{args.suffix}.txt').read_bytes()).hexdigest()) for i in range(k)]
    (output/'scenario_metadata.json').write_text(json.dumps(dict(status='DEVELOPMENT_ONLY', recording_origin=next(iter(origins)),
        start_frame=args.start_frame, end_frame=args.end_frame, layout=layout, intersections=[dict(name=n, stop_bar_m=s, box_end_m=b)
        for n, s, b, *_ in table[:k]], exit_end_m=exit_end, direction=args.direction, s_origin=origin_s, s_sign=sign, subjects=manifest, sources=sources, signal_sources=signal_sources,
        assumptions=dict(desired_speed=('population draw, seed %d, from %s' % (args.speed_seed, args.speed_population)) if args.speed_population else 'road limit for all', max_acceleration_mps2=args.max_acceleration or 'engine default 2.6', speed_limit_kmh=speed, speed_source='assumption (30 mph)' if abs(speed-48.28) < .1 else 'explicit parameter',
            stop_bar_sources='int 1: ortho manual estimate +/-0.76 m; others: stopped-leader median at observed red, +/-~2 m',
            lanes='2 through lanes; lane index = 2 - NGSIM lane id',
            slice='through-only NB; no cross traffic, opposing flow, turns, pockets, or the intersection after the last modelled one'),
        exclusions={n: dict(count=len(v), vehicles=v) for n, v in excluded.items()}, eligible=len(subjects),
        categories={c: sum(1 for x in subjects.values() if x['category'] == c) for c in sorted({x['category'] for x in subjects.values()})},
        include_all=args.include_all), indent=2)+'\n')
    print(json.dumps(dict(intersections=k, eligible=len(subjects), excluded={n: len(v) for n, v in excluded.items()}), indent=1))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--raw', type=Path, required=True)
    p.add_argument('--signals', type=Path, required=True, help='directory with Peachtree_<street>_NB_<suffix>.txt files')
    p.add_argument('--suffix', default='0400-0415')
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--direction', choices=['2', '4'], default='2', help='2 northbound, 4 southbound (mirrored coordinates)')
    p.add_argument('--intersections', type=int, default=2)
    p.add_argument('--exit-end-m', type=float)
    p.add_argument('--start-frame', type=int, default=342)
    p.add_argument('--end-frame', type=int, default=9000)
    p.add_argument('--speed-limit-kmh', type=float, default=48.28)
    p.add_argument('--max-acceleration', type=float, help='Car maximum acceleration, m/s^2 (default: engine default 2.6)')
    p.add_argument('--speed-population', type=Path, help='JSON from estimate_ngsim_free_speeds.py; per-vehicle desired speed drawn from it')
    p.add_argument('--speed-seed', type=int, default=1)
    p.add_argument('--include-all', action='store_true', help='protocol docs/MICRO_NGSIM_ALL_NB_PROTOCOL_2026_11_19.md')
    p.add_argument('--policy', choices=['discrete-event', 'time-warp'], default='discrete-event')
    p.add_argument('--initial-snapshot', action='store_true')
    generate(p.parse_args())


if __name__ == '__main__':
    main()
