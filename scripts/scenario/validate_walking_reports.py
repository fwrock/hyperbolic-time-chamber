#!/usr/bin/env python3
"""Check walking route geometry, timing, transfers and daily totals from scenario inputs."""
import argparse
from collections import Counter
import heapq
import json
import math
from pathlib import Path


def shortest_distance(graph, origin, destination, movements=None):
    movements = movements or {}
    queue, visited = [(0.0, origin, 0)], set()
    while queue:
        distance, node, previous = heapq.heappop(queue)
        if node == destination:
            return distance
        if (node, previous) in visited:
            continue
        visited.add((node, previous))
        for link_id, link in graph.items():
            if link['from'] == node and not link.get('junctionGeometry'):
                extra = movements.get((previous, link_id), 0.0)
                heapq.heappush(queue, (distance + link['length'] + extra, link['to'], link_id if movements else 0))
    raise AssertionError('unreachable expected leg')


def validate(args):
    config = json.loads(args.simulation.read_text())
    def entities(kind):
        return [record for source in config['actorsDataSources'] if source['classType'] == 'hybrid.actor.' + kind
                for record in json.loads(Path(source['dataSource']['info']['path']).read_text())]
    road = {r['id']: r['data']['content'] for r in entities('Link')}
    rail = {r['id']: r['data']['content'] for r in entities('RailLink')}
    movements = {(m['incomingLinkId'], m['outgoingLinkId']): road[m['connectorLinkId']]['length']
                 for node in entities('Node') for m in node['data']['content'].get('junctionMovements', [])}
    def walking_distance(leg):
        route = leg.get('precomputedRoute')
        if route is None:
            return shortest_distance(road, leg['originNodeId'], leg['destinationNodeId'])
        node, distance = leg['originNodeId'], 0.0
        for link_id, target in route:
            link = road[link_id]
            assert link['from'] == node and link['to'] == target, 'invalid precomputed path'
            distance += link['length']
            node = target
        assert node == leg['destinationNodeId']
        return distance
    rows = [json.loads(line) for file in args.reports.rglob('*.jsonl') for line in file.read_text().splitlines() if line.strip()]
    assert rows and entities('Person'), 'missing reports or persons'
    def events(label, pid=None):
        return sorted((r['data'] for r in rows if r['event_type'] == label and
                       (pid is None or str(r['data'].get('person_id')) == pid)), key=lambda r: r['tick'])
    horizon = config['startTick'] + config['duration']
    expected_modes, distances, arrival_ticks = Counter(), {}, {}
    for person in entities('Person'):
        pid = str(person['id'])
        plan = person['data']['content']['originalPlan']
        assert plan[0]['kind'] == plan[-1]['kind'] == 'Activity'
        walks, rides = iter(events('person_walking_start', pid)), iter(events('person_pt_trip_completed', pid))
        ride_starts = iter(events('person_pt_trip_start', pid))
        expected_walks, expected_rides, expected_leg_metrics = [], [], []
        tick, distance, finished = plan[0]['endTime']['tick'], 0.0, True
        for leg in plan[1:-1]:
            if tick > horizon:
                finished = False
                break
            if leg['kind'] == 'WalkLeg':
                length = walking_distance(leg)
                start = next(walks)
                assert (start['origin'], start['destination'], start['tick']) == (leg['originNodeId'], leg['destinationNodeId'], tick)
                assert start['distance'] == length and start['walking_speed'] == 1.4
                arrival = tick + math.ceil(length / 1.4)
                assert start['arrival_tick'] == arrival and start['walking_time_ticks'] == arrival - tick
                if arrival > horizon:
                    finished = False
                    break
                expected_walks.append((length, arrival, arrival - tick))
                expected_leg_metrics.append(('walk', length, arrival))
                tick = arrival
            else:
                assert leg['kind'] == 'TransitLeg'
                mode = leg['mode']
                length = shortest_distance(road if mode == 'Bus' else rail, leg['boardingStop']['nodeId'],
                    leg['alightingStop']['nodeId'], movements if mode == 'Bus' else None)
                start = next(ride_starts)
                assert start['mode'] == mode and start['line'] == leg['line'] and start['tick'] == tick
                ride = next(rides)
                assert ride['line'] == leg['line'] and ride['alighting_node'] == leg['alightingStop']['nodeId']
                assert ride['tick'] > tick and ride['travel_time'] == ride['tick'] - tick
                assert ride['distance_traveled'] == length
                tick = ride['tick']
                expected_rides.append(ride)
                expected_leg_metrics.append((mode.lower(), length, tick))
                expected_modes[mode] += 1
            distance += length
        assert next(walks, None) is None and next(rides, None) is None and next(ride_starts, None) is None, 'extra leg start/completion'
        completed = events('person_walking_completed', pid)
        assert [(r['distance'], r['tick'], r['travel_time']) for r in completed] == expected_walks, 'walking completion/timing'
        metrics = events('person_leg_metrics', pid)
        assert [(r['mode'], r['distance'], r['tick']) for r in metrics] == expected_leg_metrics, 'leg metrics distance/multiplicity'
        assert len(events('person_trip_metrics', pid)) == len(metrics)
        assert len({r['trip_id'] for r in metrics}) <= 1, 'trip identity changed between legs'
        activities = events('person_activity_start', pid)
        assert len(activities) == (2 if finished else 1)
        if finished:
            assert activities[-1]['node_id'] == plan[-1]['nodeId'] and activities[-1]['tick'] == tick
        summaries = events('person_schedule_complete', pid)
        assert len(summaries) == int(finished and max(tick, plan[-1]['endTime']['tick']) <= horizon)
        for summary in summaries:
            assert summary['total_distance'] == distance and summary['total_trips'] == 1
        distances[pid] = distance
        arrival_ticks[pid] = tick if finished else None
    for mode, prefix in (('Bus', 'bus'), ('Subway', 'subway')):
        assert sum(r['passengers_loaded'] for r in events(prefix + '_load_passengers')) == expected_modes[mode]
        assert sum(r['passengers_unloaded'] for r in events(prefix + '_unload_passengers')) == expected_modes[mode]
    bus_ids = {str(bus['actorId']) for station in entities('BusStation') for bus in station['data']['content']['buses']}
    micro_entries = [r for r in events('enter_micro_link') if str(r.get('bus_id')) in bus_ids]
    micro_exits = [r for r in events('leave_micro_link') if str(r.get('bus_id')) in bus_ids]
    if any(link['simulationMode'] == 'MICRO' for link in road.values()):
        assert micro_entries and micro_exits, 'MICRO Bus did not traverse infrastructure'
    connector_ids = {link_id for link_id, link in road.items() if link.get('junctionGeometry')}
    connector_entries = [r for r in micro_entries if r['link_id'] in connector_ids]
    connector_exits = [r for r in micro_exits if r['link_id'] in connector_ids]
    if connector_ids:
        assert {r['link_id'] for r in connector_entries} == connector_ids, 'Bus skipped a junction movement'
        assert len(connector_exits) >= 2 * len(connector_ids), 'Bus did not repeat its cyclic junction route'
        for bus in bus_ids:
            sequence = [r for r in micro_entries if str(r['bus_id']) == bus]
            for i, entry in enumerate(sequence):
                geometry = road[entry['link_id']].get('junctionGeometry')
                if geometry:
                    assert i > 0 and sequence[i-1]['link_id'] == geometry['incomingLinkId']
                    if i+1 < len(sequence):
                        assert sequence[i+1]['link_id'] == geometry['outgoingLinkId'], 'connector advanced the Bus route cursor'
        admitted = Counter((str(r['vehicle_id']), r['connector_link_id'], r['tick'])
                           for r in events('junction_connector_admitted') if str(r['vehicle_id']) in bus_ids)
        entered = Counter((str(r['bus_id']), r['link_id'], r['tick']) for r in connector_entries)
        assert admitted == entered, 'connector grant/entry multiplicity'
    assert not events('person_trip_aborted') and not events('person_pt_replanned') and not events('person_pt_replan_failed')
    log = args.log.read_text(errors='replace')
    for marker in ('[ERROR]', '[RUNTIME-FAILED]', '[SIMULATION-FAILED]', '[STALL', '[GTM-STALL]', '[RUNTIME-STALL]',
                   'null state', 'Replay did not consume all recorded reads'):
        assert marker not in log, marker
    stop = 'Received StopSimulationEvent. Flushing/stopping reporters'
    complete = '[ACCOUNTED-COMPLETE]' if config['timeManagerType'] == 'time-warp' else '[CONSERVATIVE-COMPLETE]'
    assert complete in log and stop in log and log.index(complete) < log.index(stop)
    return dict(passed=True, seed=config['randomSeed'], policy=config['timeManagerType'], horizon=horizon,
                personCompletedDistances=distances, personArrivalTicks=arrival_ticks,
                walkingCompletions=len(events('person_walking_completed')),
                walkingDistance=sum(r['distance'] for r in events('person_walking_completed')),
                dailySummaries=len(events('person_schedule_complete')), boardings=dict(expected_modes),
                microBusEntries=len(micro_entries), microBusExits=len(micro_exits),
                junctionBusEntries=len(connector_entries), junctionBusExits=len(connector_exits),
                physicalRows=len(rows), rollbacks=log.count('[TW-ROLLBACK]'), engineStopped=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('simulation', 'reports', 'log', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    result = validate(args)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
