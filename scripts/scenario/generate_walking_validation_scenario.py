#!/usr/bin/env python3
"""Seed-42 actual-route walking, consecutive walks, and Bus/Subway transfer validation."""
import argparse
import copy
import json
from pathlib import Path
from generate_hybrid_scenario import build_scenario, city_map_payload


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--mode', choices=('MESO', 'MICRO'), default='MESO')
    parser.add_argument('--horizon', type=int, default=1800)
    parser.add_argument('--case', choices=('mixed', 'boundary'), default='mixed')
    args = parser.parse_args()
    root = args.output_dir.resolve()
    build_scenario(root, str(root), 3, 3, 4, 0, 0, 0, 1, 1, 0.0, None, 2, 'travel-time', 42)
    def read(name):
        return json.loads((root / name).read_text())
    def write(name, value):
        (root / name).write_text(json.dumps(value, indent=2) + '\n')
    roads = read('data/links_0.json')
    for road in roads:
        road['data']['content']['simulationMode'] = args.mode
    write('data/links_0.json', roads)
    edges = {(r['data']['content']['from'], r['data']['content']['to']): r['id'] for r in roads}
    def walk(nodes, precomputed=True):
        return dict(kind='WalkLeg', originNodeId=nodes[0], destinationNodeId=nodes[-1],
                    precomputedRoute=[[edges[a, b], b] for a, b in zip(nodes, nodes[1:])] if precomputed else None)
    def activity(node, tick, label):
        return dict(kind='Activity', activityType=label, nodeId=node, endTime=dict(kind='AtTick', tick=tick))
    stops = read('data/bus_stops_0.json')
    for stop, node in zip(stops, (2, 6)):
        stop['data']['content']['nodeId'] = node
        stop['dependencies']['node']['id'] = node
    write('data/bus_stops_0.json', stops)
    buses = read('data/bus_stations_0.json')
    buses[0]['data']['content'].update(origin=2, destination=6, interval=20,
                                      busStops={str(s['id']): s['data']['content']['nodeId'] for s in stops})
    buses[0]['dependencies'] = {str(n): dict(id=n, classType='hybrid.actor.Node') for n in (2, 6)}
    write('data/bus_stations_0.json', buses)
    stations = read('data/subway_stations_0.json')
    garage, terminal = stations
    garage_state = garage['data']['content']
    line = next(iter(garage_state['lines']))
    garage_state['lines'][line]['interval'] = 20
    terminal['data']['content']['nodeId'] = 8
    terminal['dependencies']['node']['id'] = 8
    garage_state['linesRoute'][line][0]['stationNode']['nodeId'] = 8
    write('data/subway_stations_0.json', stations)
    rails = read('data/rail_links_0.json')
    for rail, origin, destination, length in zip(rails, (5, 8), (8, 5), (700.0, 1100.0)):
        rail['data']['content'].update({'from': origin, 'to': destination, 'length': length})
        rail['dependencies']['from_node']['id'] = origin
        rail['dependencies']['to_node']['id'] = destination
    write('data/rail_links_0.json', rails)
    write('data/city_map.json', city_map_payload(read('data/nodes_0.json'), roads, rails))
    node_by_stop = {s['id']: s['data']['content']['nodeId'] for s in stops + stations}
    nodes = {n['id']: n['data']['content'] for n in read('data/nodes_0.json')}
    transit_map = read('data/transit_map.json')
    for stop in transit_map:
        node = node_by_stop[stop['actorId']]
        stop['nodeId'] = node
        # Coordinates come from the generated node records, preserving routing consistency.
        for coordinate in ('latitude', 'longitude'):
            if coordinate in nodes[node]:
                stop[coordinate] = nodes[node][coordinate]
    write('data/transit_map.json', transit_map)
    routes = read('data/transit_routes.json')
    for route in routes:
        route['headwaySeconds'] = 20
        if route['stopType'] == 'subway':
            route['stops'][1]['travelTimeFromPrevSeconds'] = 32
    write('data/transit_routes.json', routes)
    def transit(mode, line, boarding, alighting):
        def ref(record):
            return dict(actorId=record['id'], actorClassType=record['typeActor'], nodeId=record['data']['content']['nodeId'])
        return dict(kind='TransitLeg', mode=mode, line=line, boardingStop=ref(boarding), alightingStop=ref(alighting))
    plans = [
        [activity(1, 10, 'home'), walk([1, 4, 5, 6, 3]), activity(3, 1500, 'work')],
        [activity(3, 10, 'home'), walk([3, 7], False), activity(7, 1500, 'work')],
        [activity(1, 10, 'home'), walk([1, 2]), transit('Bus', buses[0]['data']['content']['buses'][0]['label'], *stops),
         walk([6, 5]), transit('Subway', line, garage, terminal), walk([8, 9]), activity(9, 1500, 'work')],
        [activity(1, 10, 'home'), walk([1, 2]), walk([2, 3]), activity(3, 1500, 'work')]]
    persons = read('data/persons_0.json')
    if args.case == 'boundary':
        persons = persons[:1]
        plans = [[activity(1, 10, 'home'), walk([1, 2]), activity(2, 1500, 'work')]]
    for person, plan in zip(persons, plans):
        person['data']['content']['originalPlan'] = plan
    write('data/persons_0.json', persons)
    config = read('simulation.json')
    config.update(duration=args.horizon, endTick=args.horizon, description=__doc__,
                  dynamicActorTypes=['hybrid.actor.Bus', 'hybrid.actor.Subway'])
    for policy in ('discrete-event', 'time-warp'):
        configured = copy.deepcopy(config)
        configured.update(id=f'walking-{args.case}-{args.mode.lower()}-{policy}', timeManagerType=policy)
        write(policy + '.json', configured)
    (root / 'simulation.json').unlink()
    print(root)


if __name__ == '__main__':
    main()
