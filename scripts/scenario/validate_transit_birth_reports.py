#!/usr/bin/env python3
"""Validate the bounded station-created Bus/Subway scenarios against their input records."""
import argparse
from collections import Counter
import json
import math
from pathlib import Path
import re
import heapq


def validate(args):
    config = json.loads(args.simulation.read_text())
    def entities(kind):
        records = []
        for source in config['actorsDataSources']:
            if source['classType'].split('.')[-1] != kind:
                continue
            path = Path(source['dataSource']['info']['path'])
            if not path.is_absolute():
                path = args.simulation.parent / path
            assert source['dataSource']['sourceType'] == 'json', 'JSON manifests required for expected entity records'
            records.extend(json.loads(path.read_text()))
        return records
    persons = entities('Person')
    bus_stations = entities('BusStation')
    subway_stations = entities('SubwayStation')
    assert persons and bus_stations and subway_stations, 'scenario must exercise both transit modes and persons'
    assert not entities('Bus') and not entities('Subway'), 'vehicles must be created by their stations'
    buses = {str(bus['actorId']): (bus, station['data']['content']) for station in bus_stations
             for bus in station['data']['content']['buses']}
    trains = {str(train['actorId']): (train, station['data']['content']) for station in subway_stations
              if station['data']['content']['garage'] for queue in station['data']['content']['subways'].values() for train in queue}
    assert buses and trains
    links = {str(r['id']): r['data']['content'] for kind in ('Link', 'RailLink') for r in entities(kind)}
    horizon = config['startTick'] + config['duration']
    def ride_distance(mode, origin, destination):
        graph = [r['data']['content'] for r in entities('Link' if mode == 'Bus' else 'RailLink')]
        queue, visited = [(0.0, origin)], set()
        while queue:
            distance, node = heapq.heappop(queue)
            if node == destination:
                return distance
            if node in visited:
                continue
            visited.add(node)
            for link in graph:
                if link['from'] == node:
                    heapq.heappush(queue, (distance + link['length'], link['to']))
        raise AssertionError('unreachable expected transit trip')
    rows = [json.loads(line) for file in args.reports.rglob('*.jsonl') for line in file.read_text().splitlines() if line.strip()]
    def events(label):
        return [row['data'] for row in rows if row['event_type'] == label]
    assert Counter(str(r['bus_id']) for r in events('bus_created')) == Counter(buses.keys()), 'bus birth identity/count'
    assert Counter(str(r['subway_id']) for r in events('subway_created')) == Counter(trains.keys()), 'subway birth identity/count'
    assert not events('person_pt_replanned'), 'PT retry/replanning must not conceal a missed service'
    modes = Counter()
    completion_ticks = {}; person_distances = {}; expected_summaries = 0
    for person in persons:
        pid = str(person['id'])
        plan = person['data']['content']['originalPlan']
        activities = [step for step in plan if step['kind'] == 'Activity']
        decisions = [step for step in plan if step['kind'] == 'PendingDecision']
        assert len(activities) == 2 and len(decisions) == 1, 'validator scope is one PT leg per person'
        allowed = decisions[0]['decision']['allowedModes']
        assert len(allowed) == 1 and allowed[0] in ('Bus', 'Subway')
        mode = allowed[0]; modes[mode] += 1
        starts = [r for r in events('person_pt_trip_start') if str(r['person_id']) == pid]
        completed = [r for r in events('person_pt_trip_completed') if str(r['person_id']) == pid]
        summaries = [r for r in events('person_schedule_complete') if str(r['person_id']) == pid]
        actual_activities = sorted((r for r in events('person_activity_start') if str(r['person_id']) == pid), key=lambda r: r['activity_sequence'])
        expected_summary = int(activities[-1]['endTime']['tick'] <= horizon)
        expected_summaries += expected_summary
        assert len(starts) == len(completed) == 1 and len(summaries) == expected_summary, f'person {pid}: missing/duplicate PT completion'
        assert starts[0]['mode'] == mode and completed[0]['alighting_node'] == activities[-1]['nodeId']
        assert completed[0]['line'] == starts[0]['line'] and completed[0]['tick'] >= starts[0]['tick']
        expected_distance = ride_distance(mode, activities[0]['nodeId'], activities[-1]['nodeId'])
        assert math.isclose(completed[0]['distance_traveled'], expected_distance, abs_tol=1e-6), 'PT ride distance'
        for summary in summaries:
            assert summary['total_trips'] == 1
            assert math.isclose(summary['total_distance'], expected_distance, abs_tol=1e-6), 'PT daily distance'
        person_distances[pid] = expected_distance
        assert [a['node_id'] for a in actual_activities] == [a['nodeId'] for a in activities]
        completion_ticks[pid] = completed[0]['tick']
    for mode, prefix in (('Bus', 'bus'), ('Subway', 'subway')):
        loaded = sum(r['passengers_loaded'] for r in events(prefix + '_load_passengers'))
        unloaded = sum(r['passengers_unloaded'] for r in events(prefix + '_unload_passengers'))
        assert loaded == unloaded == modes[mode], (mode, 'boarding/alighting count', loaded, unloaded, modes[mode])
    horizon = config['startTick'] + config['duration']
    traversals = {}; censored = {}; missing_horizon_reports = []
    for field, vehicles in (('bus_id', buses), ('subway_id', trains)):
        for vehicle_id, (vehicle, station) in vehicles.items():
            if field == 'bus_id' and any(link.get('simulationMode') == 'MICRO' for link in links.values()):
                entered = sorted((r for r in events('enter_micro_link') if str(r.get(field)) == vehicle_id), key=lambda r: r['tick'])
                left = sorted((r for r in events('leave_micro_link') if str(r.get(field)) == vehicle_id), key=lambda r: r['tick'])
                assert entered, 'MICRO Bus never entered MICRO infrastructure'
            else:
                entered = sorted((r for r in events('enter_link') if str(r.get(field)) == vehicle_id), key=lambda r: r['tick'])
                left = sorted((r for r in events('leave_link') if str(r.get(field)) == vehicle_id), key=lambda r: r['tick'])
            assert entered and 0 <= len(entered) - len(left) <= 1, (field, vehicle_id, 'unmatched traversal')
            node = station['origin'] if field == 'bus_id' else station['nodeId']
            distance = 0.0
            for i, entry in enumerate(entered):
                link = links[str(entry['link_id'])]
                assert link['from'] == node, (vehicle_id, 'disconnected path', node, link)
                node = link['to']
                if field == 'subway_id':
                    speed = vehicle['velocity'] * max(0.5, min(1.5, vehicle.get('speedFactor', 1.0)))
                    assert entry['travel_time'] == math.ceil(link['length'] / speed), 'subway units/speed law'
                if i < len(left):
                    exit_event = left[i]
                    assert exit_event['link_id'] == entry['link_id'] and exit_event['tick'] > entry['tick']
                    if field == 'subway_id':
                        assert exit_event['tick'] >= entry['tick'] + entry['travel_time']
                    distance += link['length']
            if field == 'subway_id':
                route = station['linesRoute'][vehicle['line']]
                queue = station['subways'][vehicle['line']]
                position = next(i for i, item in enumerate(queue) if str(item['actorId']) == vehicle_id)
                line = station['lines'][vehicle['line']]
                tick = max(station['startTick'], line['nextTick']) + (position + 1) * line['interval']
                expected_entries, expected_exits = [], []
                expected_arrivals = []
                while tick <= horizon:
                    link_id = route[len(expected_entries) % len(route)]['railLinkId']
                    link = links[str(link_id)]
                    expected_entries.append((link_id, tick))
                    arrival = tick + math.ceil(link['length'] / speed)
                    if arrival <= horizon:
                        expected_arrivals.append((link_id, arrival))
                    exit_tick = arrival + vehicle['stopTime']
                    if exit_tick > horizon:
                        break
                    expected_exits.append((link_id, exit_tick))
                    tick = exit_tick + 1
                assert [(r['link_id'], r['tick']) for r in entered] == expected_entries, 'cyclic subway entry timetable'
                assert [(r['link_id'], r['tick']) for r in left] == expected_exits, 'cyclic subway exit timetable'
                arrivals = sorted((r for r in events('subway_arrival') if str(r['subway_id']) == vehicle_id), key=lambda r: r['tick'])
                assert [(r['link_id'], r['tick']) for r in arrivals] == expected_arrivals, 'physical subway arrivals'
                distance = sum(links[str(link_id)]['length'] for link_id, _ in expected_arrivals)
                assert arrivals and math.isclose(arrivals[-1]['total_distance'], distance), 'subway distance'
            completed = [r for r in events('journey_completed') if str(r.get(field)) == vehicle_id]
            sumo = [r for r in events('sumo_tripinfo') if str(r.get('vehicle_id')) == vehicle_id]
            assert len(completed) == len(sumo) == 1, 'missing/duplicate final journey or SUMO report'
            assert completed[0]['tick'] == horizon and completed[0]['completion_reason'] == 'horizon_truncated'
            assert completed[0]['horizon_truncated'] and not completed[0]['reached_destination']
            assert completed[0]['final_node'] is None, 'censored service invented an arrival node'
            assert math.isclose(completed[0]['total_distance'], distance, abs_tol=1e-6), 'completed physical distance'
            assert sumo[0]['arrival'] == -1 and sumo[0]['horizon_truncated'], 'SUMO invented a completed arrival'
            assert math.isclose(sumo[0]['routeLength'], distance, abs_tol=1e-6), 'SUMO distance'
            if len(entered) > len(left):
                assert entered[-1]['tick'] <= horizon, 'censored traversal starts beyond horizon'
            traversals[vehicle_id] = {'entries': len(entered), 'exits': len(left), 'completedLinkDistance': distance}
            censored[vehicle_id] = len(entered) - len(left)
    log = args.log.read_text(errors='replace')
    for marker in ('[ERROR]', '[RUNTIME-FAILED]', '[SIMULATION-FAILED]', '[STALL', '[GTM-STALL]', '[RUNTIME-STALL]',
                   'null state', 'Replay did not consume all recorded reads'):
        assert marker not in log, f'runtime log contains {marker}'
    stop = 'Received StopSimulationEvent. Flushing/stopping reporters'
    assert stop in log, 'engine did not stop before teardown'
    if config['timeManagerType'] == 'time-warp':
        cuts = [(int(a), int(b)) for a, b in re.findall(r'\[ACCOUNTED-GVT\] round=(\d+) frontier=(-?\d+)', log)]
        assert cuts and all(a[0] < b[0] and a[1] <= b[1] for a, b in zip(cuts, cuts[1:]))
        marker = '[ACCOUNTED-COMPLETE]'
    else:
        marker = '[CONSERVATIVE-COMPLETE]'
    assert marker in log and log.index(marker) < log.index(stop), 'unacknowledged engine completion'
    return dict(passed=True, policy=config['timeManagerType'], seed=config['randomSeed'], persons=len(persons),
                expectedTrips=len(persons), completedPTTrips=len(events('person_pt_trip_completed')), dailySummaries=len(events('person_schedule_complete')),
                expectedDailySummaries=expected_summaries, personDistances=person_distances,
                busBirths=len(buses), subwayBirths=len(trains), boardings=dict(modes), alightings=dict(modes),
                completionTicks=completion_ticks, traversals=traversals, horizonCensoredEntries=censored, missingHorizonJourneyReports=missing_horizon_reports,
                rollbacks=log.count('[TW-ROLLBACK]'), errors=0, nullStateDiscards=0, stalls=0, physicalRows=len(rows), engineStopped=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('simulation', 'reports', 'log', 'output'):
        parser.add_argument('--' + name, required=True, type=Path)
    args = parser.parse_args()
    try:
        result = validate(args)
    except (AssertionError, KeyError, ValueError, IndexError) as error:
        result = dict(passed=False, error=str(error))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
