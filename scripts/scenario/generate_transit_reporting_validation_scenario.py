#!/usr/bin/env python3
"""Generate seed-42 transit cases with unequal rail legs and final wakes beyond the cap."""
import argparse
import copy
import json
from pathlib import Path
from generate_hybrid_scenario import build_scenario


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--mode', choices=('MESO', 'MICRO'), default='MESO')
    parser.add_argument('--vehicles', type=int, choices=(1, 2), default=2)
    parser.add_argument('--horizon', type=int, default=1000)
    args = parser.parse_args()
    root = args.output_dir.resolve()
    build_scenario(root, str(root), 2, 2, 2, 0, 0, 0, 1, 1,
                   0.0, None, 2, 'raptor', 42)
    def read(name):
        return json.loads((root / name).read_text())
    def write(name, value):
        (root / name).write_text(json.dumps(value, indent=2) + '\n')
    roads = read('data/links_0.json')
    for link in roads:
        link['data']['content']['simulationMode'] = args.mode
    write('data/links_0.json', roads)
    buses = read('data/bus_stations_0.json')
    buses[0]['data']['content']['interval'] = 20
    if args.vehicles == 2:
        second = copy.deepcopy(buses[0]['data']['content']['buses'][0])
        second['actorId'] = 1001
        buses[0]['data']['content']['buses'].append(second)
    write('data/bus_stations_0.json', buses)
    stations = read('data/subway_stations_0.json')
    garage = next(s['data']['content'] for s in stations if s['data']['content']['garage'])
    line = next(iter(garage['lines']))
    garage['lines'][line]['interval'] = 20
    if args.vehicles == 2:
        second = copy.deepcopy(garage['subways'][line][0])
        second['actorId'] = 1002
        garage['subways'][line].append(second)
    write('data/subway_stations_0.json', stations)
    rail = read('data/rail_links_0.json')
    for link in rail:
        if link['data']['content']['from'] == 1:
            link['data']['content']['length'] = 1400.0
    write('data/rail_links_0.json', rail)
    routes = read('data/transit_routes.json')
    for route in routes:
        route['headwaySeconds'] = 20
        route['stops'].reverse()
        for position, stop in enumerate(route['stops']):
            stop['travelTimeFromPrevSeconds'] = 0 if position == 0 else (64 if route['stopType'] == 'subway' else 60)
    write('data/transit_routes.json', routes)
    persons = read('data/persons_0.json')
    for person, mode, origin, destination in zip(persons, ('Bus', 'Subway'), (4, 1), (1, 3)):
        state = person['data']['content']
        state['originalPlan'] = [
            {'kind': 'Activity', 'activityType': 'home', 'nodeId': origin,
             'endTime': {'kind': 'AtTick', 'tick': 10}},
            {'kind': 'PendingDecision', 'decision': {'allowedModes': [mode], 'strategyId': 'raptor'}},
            {'kind': 'Activity', 'activityType': 'work', 'nodeId': destination,
             'endTime': {'kind': 'AtTick', 'tick': 500}}]
        state['modeChoiceWeights']['includedModes'] = [mode.lower()]
        state['modeChoiceWeights']['maxAccessDistanceM'] = 1.0
    write('data/persons_0.json', persons)
    config = read('simulation.json')
    config.update(duration=args.horizon, endTick=args.horizon, description=__doc__,
                  dynamicActorTypes=['hybrid.actor.Bus','hybrid.actor.Subway'])
    for policy in ('discrete-event', 'time-warp'):
        configured = copy.deepcopy(config)
        configured.update(id=f'transit-reporting-{args.mode.lower()}-{policy}', timeManagerType=policy)
        write(policy + '.json', configured)
    (root / 'simulation.json').unlink()
    (root / 'reporting.conf').write_text('htc.report-manager.kafka.micro-update-report-every=1\n')
    print(root)


if __name__ == '__main__':
    main()
