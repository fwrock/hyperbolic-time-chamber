#!/usr/bin/env python3
"""Engineering fixture for G03+G04+G05 with real Node and Link actors; not empirical traffic.

An uncontrolled junction: priority movement P (road 11 -> connector 30 -> road 12) and a yielding
movement Y (road 21 -> connector 20 -> road 22) whose connector paths cross. With --no-conflict the Node
has no conflict rule (a control that must show overlapping crossings).
"""
import argparse
import json
from pathlib import Path
import sys

from ngsim_actor_approach import actor

PRIORITY_STARTS = [1 + 80*k for k in range(6)]          # tick of departure on road 11 (8 s headway)
YIELD_STARTS = [20, 100, 110, 310, 330, 480]             # tick of departure on road 21
CRITICAL_GAP_S, CONFLICT_OFFSET_M = 3.0, 8.0


def generate(output, policy, conflict=True, end_tick=1800):
    if output.exists():
        raise FileExistsError(output)
    workspace = Path(__file__).resolve().parents[3]
    sys.path.insert(0, str(workspace/'tools/scenario-db-converter'))
    from scenario_output_writer import shard_and_write_actors, write_city_map_json, build_simulation_json
    actors, nodes_city, edges = [], [], []
    node_links = {1: [11], 2: [12, 22, 30, 20], 3: [], 4: [21], 5: []}
    for node, links in node_links.items():
        state = dict(startTick=0, scheduleOnTimeManager=False, latitude=33.78, longitude=-84.38+node*.001, links=links)
        if node == 2:
            state['junctionMovements'] = [dict(incomingLinkId=11, outgoingLinkId=12, connectorLinkId=30),
                                          dict(incomingLinkId=21, outgoingLinkId=22, connectorLinkId=20)]
            if conflict:
                state['conflicts'] = [dict(connectorA=20, connectorB=30, priorityConnector=30,
                    source='engineering fixture: yielding movement 21->22 crosses priority movement 11->12',
                    gapAcceptance=dict(criticalGapSeconds=CRITICAL_GAP_S, conflictOffsetM=CONFLICT_OFFSET_M))]
        actors.append(actor('Node', node, state))
        nodes_city.append(dict(id=node, resourceId=str(node), classType='hybrid.actor.Node',
                               latitude=33.78, longitude=-84.38+node*.001))
    # id, from, to, length, lookahead target(s)
    links = [(11, 1, 2, 60., dict(downstreamConnectors={12: 30})), (21, 4, 2, 60., dict(downstreamConnectors={22: 20})),
             (30, 2, 2, 20., {}), (20, 2, 2, 15., {}), (12, 2, 3, 60., {}), (22, 2, 5, 60., {})]
    for link, a, b, length, extra in links:
        state = dict(startTick=0, scheduleOnTimeManager=False, **{'from': a}, to=b, length=length, lanes=1,
            speedLimit=50., freeSpeed=50., currentSpeed=50., capacity=20., registered=[], simulationMode='MICRO',
            microTimeStep=.1, microTicksPerGlobalTick=1, carryMicroRemainder=True, stopLineGapM=1. if link in (11, 21) else 0.)
        if link in (11, 21, 30, 20):
            state.update(downstreamLookahead=True, downstreamSpillback=True)
        state.update(extra)
        if link in (30, 20):
            state['junctionGeometry'] = dict(nodeId=2, incomingLinkId=11 if link == 30 else 21,
                outgoingLinkId=12 if link == 30 else 22, lengthMeters=length, speedLimitMps=50/3.6,
                shape=[dict(x=0, y=0), dict(x=length, y=0)])
        actors.append(actor('Link', link, state, {'from_node': dict(id=a, classType='hybrid.actor.Node'),
                                                'to_node': dict(id=b, classType='hybrid.actor.Node')}))
        edges.append(dict(source_id=a, target_id=b, weight=length,
                          label=dict(id=link, resourceId=str(link), classType='hybrid.actor.Link', length=length)))
    streams = [(100000, 1, 3, 11, 12, 2, 3, PRIORITY_STARTS), (200000, 4, 5, 21, 22, 2, 5, YIELD_STARTS)]
    for base, origin, destination, road_in, road_out, via, end, starts in streams:
        for k, start in enumerate(starts):
            actors.append(actor('Car', base + k, dict(startTick=start, origin=origin, destination=destination,
                actorType='Car', size=4.5, precomputedRoute=[dict(linkId=road_in, nodeId=via), dict(linkId=road_out, nodeId=end)],
                microDepartPosition=0., microDepartSpeed=10.)))
    data = output/'data'
    counts = shard_and_write_actors(actors, data)
    write_city_map_json(dict(directed=True, nodes=nodes_city, edges=edges), data)
    simulation = build_simulation_json(output.name, 'Junction conflict regression',
        'Synthetic engineering control for G03/G04/G05', counts, str(output.parent), end_tick=end_tick, tick_duration=.1,
        settings={'htc.time-manager.conservative-transport': 'channel-closed'})
    simulation['timeManagerType'] = policy
    (output/'simulation.json').write_text(json.dumps(simulation, indent=2)+'\n')


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--policy', choices=['discrete-event', 'time-warp'], default='discrete-event')
    p.add_argument('--no-conflict', action='store_true')
    p.add_argument('--end-tick', type=int, default=1800)
    a = p.parse_args()
    generate(a.output.resolve(), a.policy, not a.no_conflict, a.end_tick)
