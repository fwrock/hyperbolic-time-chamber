#!/usr/bin/env python3
"""Synthetic load for timing the MICRO downstream-leader path (G02); not empirical traffic.

Independent corridors node->approach link->node->exit link->node. The approach link looks ahead
onto the exit link; with --lanes 2 explicit lane connections and MOBIL lane changing are enabled.
"""
import argparse
import json
from pathlib import Path
import sys

from ngsim_actor_approach import actor


def generate(output, policy, corridors, cars, lanes, end_tick):
    if output.exists():
        raise FileExistsError(output)
    workspace = Path(__file__).resolve().parents[3]
    sys.path.insert(0, str(workspace/'tools/scenario-db-converter'))
    from scenario_output_writer import shard_and_write_actors, write_city_map_json, build_simulation_json
    actors, nodes, edges = [], [], []
    for c in range(corridors):
        n1, n2, n3, l1, l2 = [c*10 + k for k in range(1, 6)]
        for node, links in [(n1, [l1]), (n2, [l2]), (n3, [])]:
            actors.append(actor('Node', node, dict(startTick=0, latitude=33.78, longitude=-84.38+node*1e-5, links=links)))
            nodes.append(dict(id=node, resourceId=str(node), classType='hybrid.actor.Node',
                              latitude=33.78, longitude=-84.38+node*1e-5))
        for link, a, b, length in [(l1, n1, n2, 300.), (l2, n2, n3, 300.)]:
            state = dict(startTick=0, **{'from': a}, to=b, length=length, lanes=lanes, speedLimit=50., freeSpeed=50.,
                         capacity=1000., simulationMode='MICRO', microTimeStep=.1, microTicksPerGlobalTick=1,
                         downstreamLookahead=link == l1)
            if link == l1 and lanes > 1:
                state['laneConnections'] = [dict(outgoingLinkId=l2, fromLane=k, toLane=k) for k in range(lanes)]
                state['laneChanging'] = dict(model='Mobil')
            actors.append(actor('Link', link, state, {'from': dict(id=a, classType='hybrid.actor.Node'),
                                                       'to': dict(id=b, classType='hybrid.actor.Node')}))
            edges.append(dict(source_id=a, target_id=b, weight=length,
                              label=dict(id=link, resourceId=str(link), classType='hybrid.actor.Link', length=length)))
        for k in range(cars):
            identity = 100000 + c*1000 + k
            actors.append(actor('Car', identity, dict(startTick=1 + k*30, origin=n1, destination=n3, actorType='Car',
                size=4.5, precomputedRoute=[dict(linkId=l1, nodeId=n2), dict(linkId=l2, nodeId=n3)],
                microDepartPosition=0., microDepartLane=k % lanes)))
    data = output/'data'
    counts = shard_and_write_actors(actors, data)
    write_city_map_json(dict(directed=True, nodes=nodes, edges=edges), data)
    simulation = build_simulation_json(output.name, 'MICRO lane-path leader timing load',
        'Synthetic engineering load, not empirical traffic', counts, str(output.parent),
        end_tick=end_tick, tick_duration=.1, settings={'htc.time-manager.conservative-transport': 'channel-closed'})
    simulation['timeManagerType'] = policy
    (output/'simulation.json').write_text(json.dumps(simulation, indent=2)+'\n')


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--policy', choices=['discrete-event', 'time-warp'], default='discrete-event')
    p.add_argument('--corridors', type=int, default=40)
    p.add_argument('--cars', type=int, default=25)
    p.add_argument('--lanes', type=int, default=1)
    p.add_argument('--end-tick', type=int, default=1200)
    a = p.parse_args()
    generate(a.output.resolve(), a.policy, a.corridors, a.cars, a.lanes, a.end_tick)
