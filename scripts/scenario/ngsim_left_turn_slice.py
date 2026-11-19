#!/usr/bin/env python3
"""Peachtree intersection 1 (10th): NB with a left pocket + SB through, permissive NB left yielding to SB through (G03/G04/G05 with data).

Development scenario on exposed data; assumptions are explicit in scenario_metadata.json and the protocol
docs/MICRO_NGSIM_LEFT_TURN_PROTOCOL_2026_11_19.md. Cross traffic, pedestrians and SB turning movements are not modelled.
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
# Sites with both a northbound stop line and a southbound stop line measured (stop lines in NGSIM local_y metres). The domain of a site runs from the
# previous northbound box end to the next northbound stop line; coordinates are shifted so the northbound road starts at 0 (southbound is mirrored).
SITES = {
    '10th': dict(int_id='1', road_start=0.0, nb_stop=38.774, nb_box_end=71.7, nb_exit_end=196.17, sb_stop=72.6, nb_stem='Peachtree_10th_NB', sb_stem='Peachtree_10th_SB'),
    '11th': dict(int_id='2', road_start=71.7, nb_stop=196.17, nb_box_end=235.8, nb_exit_end=359.74, sb_stop=236.9, nb_stem='Peachtree_11th_NB', sb_stem='Peachtree_11th_SB'),
    '12th': dict(int_id='3', road_start=235.8, nb_stop=359.74, nb_box_end=377.2, nb_exit_end=483.8, sb_stop=380.4, nb_stem='Peachtree_12th_NB', sb_stem='Peachtree_12th_SB'),
}


def configure(g):
    """Set the module geometry (shifted coordinates) from a site description; also used by the analyser from scenario metadata."""
    off = g['road_start']
    globals().update(OFF=off, INT_ID=g['int_id'], NB_STOP=g['nb_stop']-off, NB_BOX_END=g['nb_box_end']-off, NB_EXIT_END=g['nb_exit_end']-off,
                     SB_STOP=g['sb_stop']-off)
    globals().update(SB_ROAD_LEN=NB_EXIT_END-SB_STOP, SB_CONN_LEN=SB_STOP-NB_STOP, SB_EXIT_LEN=NB_STOP, Y_C=(NB_STOP+SB_STOP)/2)


configure(SITES['10th'])
LEFT_LEN, SINK_LEN = 30.0, 60.0
N_START, N_J, N_END, S_START, S_END, L_END = 1, 2, 3, 4, 5, 6
A, CT, E, CL, SINK = 11, 12, 13, 14, 15
B, CS, X = 21, 22, 23
O_NB_THRU, O_NB_LEFT, O_SB_THRU = 701, 702, 703


def union_events(left, thru):
    """Permissive left: Green if left or thru is Green, else Yellow if either is Yellow, else Red."""
    frames = sorted({f for f, _ in left} | {f for f, _ in thru})
    def at(events, f):
        known = [(g, p) for g, p in events if g <= f]
        return max(known)[1] if known else None
    out = []
    for f in frames:
        a, b = at(left, f), at(thru, f)
        phase = 'Green' if 'Green' in (a, b) else 'Yellow' if 'Yellow' in (a, b) else 'Red'
        if not out or out[-1][1] != phase: out.append((f, phase))
    return out


def load(raw):
    rows, sources = [], []
    for path in sorted(raw.glob('page-*.json')):
        sources.append(dict(path=str(path.resolve()), sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
        rows.extend(r for r in json.loads(path.read_text()) if r['direction'] in ('2', '4'))
    return rows, sources


def generate(args):
    workspace = Path(__file__).resolve().parents[3]
    sys.path.insert(0, str(workspace/'tools/scenario-db-converter'))
    from scenario_output_writer import shard_and_write_actors, write_city_map_json, build_simulation_json
    output = args.output.resolve()
    if output.exists(): raise FileExistsError(output)
    rows, sources = load(args.raw)
    by_vehicle = defaultdict(list)
    for r in rows:
        by_vehicle[(r['direction'], int(r['vehicle_id']))].append(r)
    configure(SITES[args.site])
    site = SITES[args.site]
    nb_events = read_changes(args.signals/f"{site['nb_stem']}_{args.suffix}.txt")
    sb_events = read_changes(args.signals/f"{site['sb_stem']}_{args.suffix}.txt")
    program = compile_program({O_NB_THRU: nb_events['Thru'], O_NB_LEFT: union_events(nb_events.get('Left', []), nb_events['Thru']),
        O_SB_THRU: sb_events['Thru']},
        [dict(origin=O_NB_THRU, nodeId=N_J, incomingLinkId=A, outgoingLinkId=E, laneIds=[0, 1]),
         dict(origin=O_NB_LEFT, nodeId=N_J, incomingLinkId=A, outgoingLinkId=SINK, laneIds=[2]),
         dict(origin=O_SB_THRU, nodeId=N_J, incomingLinkId=B, outgoingLinkId=X, laneIds=[])], args.start_frame, args.end_frame)
    speed = args.speed_limit_kmh
    gap = dict(criticalGapSeconds=args.critical_gap, conflictOffsetM=SB_STOP - Y_C, publishPermission=args.publish_permission)
    actors = []
    node_links = {N_START: [A], N_J: [E, CT, SINK, CL, X, CS], N_END: [], S_START: [B], S_END: [], L_END: []}
    for node, links in node_links.items():
        st = dict(startTick=0, scheduleOnTimeManager=False, latitude=33.78, longitude=-84.38+node*.001, links=links)
        if node == N_J:
            st['junctionMovements'] = [dict(incomingLinkId=A, outgoingLinkId=E, connectorLinkId=CT),
                dict(incomingLinkId=A, outgoingLinkId=SINK, connectorLinkId=CL), dict(incomingLinkId=B, outgoingLinkId=X, connectorLinkId=CS)]
            st['movementSignals'] = [dict(incomingLinkId=A, outgoingLinkId=E, origin=O_NB_THRU, laneIds=[0, 1]),
                dict(incomingLinkId=A, outgoingLinkId=SINK, origin=O_NB_LEFT, laneIds=[2]),
                dict(incomingLinkId=B, outgoingLinkId=X, origin=O_SB_THRU, laneIds=[])]
            if args.batch_decisions: st['batchAllRequests'] = True
            if not args.no_conflict:
                st['conflicts'] = [dict(connectorA=CL, connectorB=CS, priorityConnector=CS,
                    source='engineering protocol: NB permissive left yields to SB through at the same signalised intersection', gapAcceptance=gap)]
        actors.append(actor('Node', node, st))
    links = [(A, N_START, N_J, NB_STOP, 3), (CT, N_J, N_J, NB_BOX_END-NB_STOP, 2), (E, N_J, N_END, NB_EXIT_END-NB_BOX_END, 3),
             (CL, N_J, N_J, LEFT_LEN, 1), (SINK, N_J, L_END, SINK_LEN, 1), (B, S_START, N_J, SB_ROAD_LEN, 2),
             (CS, N_J, N_J, SB_CONN_LEN, 2), (X, N_J, S_END, SB_EXIT_LEN, 2)]
    for link, a, b, length, lanes in links:
        st = dict(startTick=0, scheduleOnTimeManager=False, **{'from': a}, to=b, length=length, lanes=lanes, speedLimit=speed,
            freeSpeed=speed, currentSpeed=speed, capacity=3600., registered=[], simulationMode='MICRO', microTimeStep=.1,
            microTicksPerGlobalTick=1, carryMicroRemainder=True, microEntry=dict(enabled=True, initialSnapshotTick=0) if (args.initial_snapshot and link in (A, B)) else dict(enabled=True))
        if link == A:
            st.update(downstreamLookahead=True, downstreamSpillback=True, downstreamConnectors={E: CT, SINK: CL},
                laneConnections=[dict(outgoingLinkId=E, fromLane=0, toLane=0), dict(outgoingLinkId=E, fromLane=1, toLane=1),
                                 dict(outgoingLinkId=SINK, fromLane=2, toLane=0)],
                signalControls=[dict(targetLinkId=E, laneIds=[0, 1]), dict(targetLinkId=SINK, laneIds=[2])])
        if link == B:
            st.update(downstreamLookahead=True, downstreamSpillback=True, downstreamConnectors={X: CS},
                laneConnections=[dict(outgoingLinkId=X, fromLane=0, toLane=0), dict(outgoingLinkId=X, fromLane=1, toLane=1)],
                signalControls=[dict(targetLinkId=X, laneIds=[])])
        if link in (CT, CL, CS):
            nxt = {CT: E, CL: SINK, CS: X}[link]
            st.update(downstreamLookahead=True, downstreamSpillback=True)
            if link != CL:
                st['laneConnections'] = [dict(outgoingLinkId=nxt, fromLane=0, toLane=0), dict(outgoingLinkId=nxt, fromLane=1, toLane=1)]
            inc, out = {CT: (A, E), CL: (A, SINK), CS: (B, X)}[link]
            st['junctionGeometry'] = dict(nodeId=N_J, incomingLinkId=inc, outgoingLinkId=out, lengthMeters=length,
                speedLimitMps=speed/3.6, shape=[dict(x=0, y=0), dict(x=length, y=0)])
        actors.append(actor('Link', link, st, {'from_node': dict(id=a, classType='hybrid.actor.Node'), 'to_node': dict(id=b, classType='hybrid.actor.Node')}))
    actors.append(actor('TrafficSignal', 700, dict(startTick=0, nodes=[N_J], program=program), {'node': dict(id=N_J, classType='hybrid.actor.Node')}))
    subjects, excluded, manifest = {}, defaultdict(list), []
    for (direction, vid), track in sorted(by_vehicle.items()):
        nb = direction == '2'
        sp = lambda r: float(r['local_y'])*FT - OFF if nb else NB_EXIT_END - (float(r['local_y'])*FT - OFF)
        seg = [r for r in track if args.start_frame <= int(r['frame_id']) <= args.end_frame and 0.0 <= sp(r) <= NB_EXIT_END
               and r['lane_id'] in ('0', '1', '2', '11')]  # rows with artefact lane ids (9999, 3, 1061) are dropped, not the vehicle
        if not seg: continue
        seg.sort(key=lambda r: int(r['frame_id']))
        lanes = {r['lane_id'] for r in seg}
        if not nb and '11' in lanes: excluded['4:sb_left_pocket_not_modelled'].append(vid); continue
        lead = 0
        roadlen = NB_STOP if nb else SB_ROAD_LEN
        while lead < len(seg)-1 and seg[lead]['lane_id'] == '0' and sp(seg[lead]) < roadlen: lead += 1
        seg = seg[lead:]
        first, last = seg[0], seg[-1]
        s0, box_i = sp(first), lambda r: r['int_id'] == INT_ID
        lane_row = next((r for r in seg if r['lane_id'] in ('1', '2', '11')), None)
        if lane_row is None: excluded[direction+':never_in_lane'].append(vid); continue
        road_end = NB_STOP if nb else SB_ROAD_LEN
        if s0 < road_end and first['lane_id'] in ('1', '2', '11'):
            link, pos, entry = (A if nb else B), s0, 'road'
        elif box_i(first) or road_end <= s0:
            link, pos, entry = (E if nb else X), 0.0, 'side_entry'
        else: excluded[direction+':unplaceable'].append(vid); continue
        left = nb and box_i(last) and last['movement'] == '2'
        if nb and '11' in lanes and not left: excluded['2:pocket_lane_not_left'].append(vid); continue
        if left and entry != 'road':
            category, route, arrival = 'left_first_seen_in_box', [SINK], None   # injected on the exit of the left turn; no decision modelled
            link, pos = SINK, 0.0
        elif left: category, route, arrival = 'left', [A, SINK], None
        elif box_i(last):
            category = 'turn_off_proxy'; route = [A, E] if nb else [B, X]; arrival = 3.0
        elif sp(last) < NB_EXIT_END - 5:
            end_s = sp(last)
            road_len, conn_end = (NB_STOP, NB_BOX_END) if nb else (SB_ROAD_LEN, SB_ROAD_LEN+SB_CONN_LEN)
            if end_s <= road_len:                       # track ends on the approach road
                category, route, arrival = 'ends_in_road', ([A] if nb else [B]), end_s
            elif end_s < conn_end:                      # track ends inside the box although not labelled as such: same proxy as a turn-off
                category, route, arrival = 'turn_off_proxy', ([A, E] if nb else [B, X]), 3.0
            else:                                       # track ends on the exit road
                category, route, arrival = 'ends_in_road', ([A, E] if nb else [B, X]), end_s - conn_end
                if arrival <= 0: category, arrival = 'through', None
        else:
            category, route, arrival = 'through', ([A, E] if nb else [B, X]), None
        if entry == 'side_entry': route = [route[-1]] if route[-1] in (E, X) else route
        lane = 2 if lane_row['lane_id'] == '11' else 2-int(lane_row['lane_id'])
        if left: lane = 0 if entry != 'road' else 2
        if len(route) == 1 and route[0] in (E, X) and arrival is None: pass
        subjects[(direction, vid)] = dict(first=first, link=link, pos=pos, entry=entry, category=category, route=route, arrival=arrival, lane=lane)
    nodes_of = {A: N_J, E: N_END, SINK: L_END, B: N_J, X: S_END}
    for (direction, vid), sub in sorted(subjects.items()):
        first = sub['first']; frame = int(first['frame_id']); native = (10000 if direction == '2' else 20000) + vid
        start = frame-args.start_frame if sub['entry'] != 'side_entry' else max(1, frame-args.start_frame)
        origin = N_START if (direction == '2' and sub['link'] == A) else S_START if (direction == '4' and sub['link'] == B) else N_J
        dest = L_END if sub['category'].startswith('left') else N_END if direction == '2' else S_END
        extra = {'microArrivalPosition': sub['arrival']} if sub['arrival'] is not None else {}
        actors.append(actor('Car', native, dict(startTick=start, origin=origin, destination=dest, actorType='Car',
            size=float(first['v_length'])*FT, precomputedRoute=[dict(linkId=l, nodeId=nodes_of[l]) for l in sub['route']],
            microDepartPosition=sub['pos'], microDepartSpeed=float(first['v_vel'])*FT, microDepartLane=sub['lane'],
            microAcceleration=args.max_acceleration, **extra)))
        manifest.append(dict(direction=direction, source_vehicle=vid, native_vehicle=native, first_frame=frame, category=sub['category'],
            entry=sub['entry'], link=sub['link'], position_m=sub['pos'], lane=sub['lane'], route=sub['route']))
    data = output/'data'
    counts = shard_and_write_actors(actors, data)
    nodes = [dict(id=n, resourceId=str(n), classType='hybrid.actor.Node', latitude=33.78, longitude=-84.38+n*.001) for n in node_links]
    edges = [dict(source_id=a, target_id=b, weight=l, label=dict(id=i, resourceId=str(i), classType='hybrid.actor.Link', length=l)) for i, a, b, l, _ in links]
    write_city_map_json(dict(directed=True, nodes=nodes, edges=edges), data)
    sim = build_simulation_json(output.name, 'Peachtree 10th left-turn slice', 'Exposed data; G03/G04/G05 development', counts, str(output.parent),
        end_tick=args.end_frame-args.start_frame, tick_duration=.1, settings={'htc.time-manager.conservative-transport': 'channel-closed'})
    sim['timeManagerType'] = args.policy
    (output/'simulation.json').write_text(json.dumps(sim, indent=2)+'\n')
    (output/'scenario_metadata.json').write_text(json.dumps(dict(status='DEVELOPMENT_ONLY', start_frame=args.start_frame, end_frame=args.end_frame,
        subjects=manifest, sources=sources, site=args.site, geometry=SITES[args.site], conflict=not args.no_conflict, publish_permission=args.publish_permission, critical_gap_s=args.critical_gap, conflict_offset_m=SB_STOP-Y_C,
        left_connector_m=LEFT_LEN, categories={c: sum(1 for s in subjects.values() if s['category'] == c) for c in sorted({s['category'] for s in subjects.values()})},
        by_direction={d: sum(1 for k in subjects if k[0] == d) for d in ('2', '4')}, exclusions={k: dict(count=len(v), vehicles=v) for k, v in excluded.items()},
        assumptions=dict(max_acceleration_mps2=args.max_acceleration, speed_limit_kmh=speed, left_connector_length_m=LEFT_LEN,
            critical_gap_source=args.critical_gap_source, notes='SB turning movements, cross traffic and pedestrians are not modelled; NB left turners always use the pocket lane')), indent=2)+'\n')
    print(json.dumps(dict(included=len(subjects), by_direction={d: sum(1 for k in subjects if k[0] == d) for d in ('2', '4')},
        categories={c: sum(1 for s in subjects.values() if s['category'] == c) for c in sorted({s['category'] for s in subjects.values()})},
        excluded={k: len(v) for k, v in excluded.items()}), indent=1))


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--raw', type=Path, required=True)
    p.add_argument('--signals', type=Path, required=True)
    p.add_argument('--site', choices=sorted(SITES), default='10th')
    p.add_argument('--suffix', default='0400-0415')
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--start-frame', type=int, default=140)
    p.add_argument('--end-frame', type=int, default=9000)
    p.add_argument('--speed-limit-kmh', type=float, default=48.28)
    p.add_argument('--max-acceleration', type=float, default=1.72)
    p.add_argument('--critical-gap', type=float, default=3.0)
    p.add_argument('--critical-gap-source', default='protocol fixture default 3.0 s (midday estimate inconclusive: n=2 permissive lefts)')
    p.add_argument('--no-conflict', action='store_true')
    p.add_argument('--batch-decisions', action='store_true', help='Node decides all junction requests in a deterministic batch at the next tick')
    p.add_argument('--publish-permission', action='store_true', help='anticipatory braking: the Node publishes the conflict permission to the approach Link')
    p.add_argument('--policy', choices=['discrete-event', 'time-warp'], default='discrete-event')
    p.add_argument('--initial-snapshot', action='store_true')
    generate(p.parse_args())
