#!/usr/bin/env python3
"""Check walking route geometry, timing, transfers and daily totals from scenario inputs."""
import argparse
from collections import Counter
import heapq
import json
import math
from pathlib import Path


def validate(args):
    config = json.loads(args.simulation.read_text())
    def entities(kind):
        return [record for source in config['actorsDataSources'] if source['classType'] == 'hybrid.actor.' + kind
                for record in json.loads(Path(source['dataSource']['info']['path']).read_text())]
    road = {r['id']: r['data']['content'] for r in entities('Link')}
    rail = {r['id']: r['data']['content'] for r in entities('RailLink')}
    def shortest(graph, origin, destination):
        queue, visited = [(0.0, origin)], set()
        while queue:
            distance, node = heapq.heappop(queue)
            if node == destination:
                return distance
            if node in visited:
                continue
            visited.add(node)
            for link in graph.values():
                if link['from'] == node:
                    heapq.heappush(queue, (distance + link['length'], link['to']))
        raise AssertionError('unreachable expected leg')
    def walking_distance(leg):
        route = leg.get('precomputedRoute')
        if route is None:
            return shortest(road, leg['originNodeId'], leg['destinationNodeId'])
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
                length = shortest(road if mode == 'Bus' else rail, leg['boardingStop']['nodeId'], leg['alightingStop']['nodeId'])
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
