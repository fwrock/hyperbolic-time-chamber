#!/usr/bin/env python3
"""Engineering fixture for MESO tick quantisation: a line of short links, free flow, no signals. Not empirical traffic."""
import argparse
import json
import random
import sys
from pathlib import Path

from ngsim_actor_approach import actor


def generate(output, links=30, cars=20, seed=7, end_tick=4000):
    if output.exists():
        raise FileExistsError(output)
    workspace = Path(__file__).resolve().parents[3]
    sys.path.insert(0, str(workspace/'tools/scenario-db-converter'))
    from scenario_output_writer import shard_and_write_actors, write_city_map_json, build_simulation_json
    rng = random.Random(seed)
    lengths = [round(rng.uniform(60., 350.), 1) for _ in range(links)]
    actors, nodes, edges = [], [], []
    for n in range(1, links+2):
        actors.append(actor('Node', n, dict(startTick=0, scheduleOnTimeManager=False, latitude=33.78, longitude=-84.38+n*.001, links=[n] if n <= links else [])))
        nodes.append(dict(id=n, resourceId=str(n), classType='hybrid.actor.Node', latitude=33.78, longitude=-84.38+n*.001))
    for i, length in enumerate(lengths, start=1):
        actors.append(actor('Link', i, dict(startTick=0, scheduleOnTimeManager=False, **{'from': i}, to=i+1, length=length, lanes=1, speedLimit=50., freeSpeed=50.,
            currentSpeed=50., capacity=1000., registered=[], simulationMode='MESO'),
            {'from_node': dict(id=i, classType='hybrid.actor.Node'), 'to_node': dict(id=i+1, classType='hybrid.actor.Node')}))
        edges.append(dict(source_id=i, target_id=i+1, weight=length, label=dict(id=i, resourceId=str(i), classType='hybrid.actor.Link', length=length)))
    route = [dict(linkId=i, nodeId=i+1) for i in range(1, links+1)]
    for k in range(cars):
        actors.append(actor('Car', 1000+k, dict(startTick=1+k*40, origin=1, destination=links+1, actorType='Car', size=4.5, precomputedRoute=route)))
    counts = shard_and_write_actors(actors, output/'data')
    write_city_map_json(dict(directed=True, nodes=nodes, edges=edges), output/'data')
    sim = build_simulation_json(output.name, 'MESO tick quantisation fixture', 'Synthetic engineering control', counts, str(output.parent), end_tick=end_tick, tick_duration=1.0,
        settings={'htc.time-manager.conservative-transport': 'channel-closed'})
    sim['timeManagerType'] = 'discrete-event'
    (output/'simulation.json').write_text(json.dumps(sim, indent=2)+'\n')
    (output/'fixture.json').write_text(json.dumps(dict(lengths=lengths, cars=cars, free_flow_total_m=sum(lengths)), indent=1)+'\n')


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--links', type=int, default=30)
    p.add_argument('--cars', type=int, default=20)
    a = p.parse_args()
    generate(a.output.resolve(), a.links, a.cars)
