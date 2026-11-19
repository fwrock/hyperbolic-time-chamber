#!/usr/bin/env python3
"""Engineering fixture for G03: two upstream lanes merge into one downstream lane whose queue
delays admission for many ticks while retained footprints stay upstream. Not empirical traffic."""
import argparse
import json
from pathlib import Path
import sys

from ngsim_actor_approach import actor

# (id, lane on link 11, position on link 11)
CARS = [(101, 0, 40.), (102, 1, 34.), (103, 0, 28.), (104, 1, 22.), (105, 0, 16.), (106, 1, 10.)]


def generate(output, policy, end_tick=2000):
    if output.exists():
        raise FileExistsError(output)
    workspace = Path(__file__).resolve().parents[3]
    sys.path.insert(0, str(workspace/'tools/scenario-db-converter'))
    from scenario_output_writer import shard_and_write_actors, write_city_map_json, build_simulation_json
    actors = []
    for node, links in [(1, [11]), (2, [12]), (3, [])]:
        state = dict(startTick=0, latitude=33.78, longitude=-84.38+node*.001, links=links)
        if node in [2, 3]:
            state['movementSignals'] = [dict(incomingLinkId=11 if node == 2 else 12,
                outgoingLinkId=12 if node == 2 else 0, origin=701 if node == 2 else 702)]
        actors.append(actor('Node', node, state))
    for link, a, b, length, lanes in [(11, 1, 2, 60., 2), (12, 2, 3, 20., 1)]:
        state = dict(startTick=0, **{'from': a}, to=b, length=length, lanes=lanes, speedLimit=50., freeSpeed=50.,
            capacity=100., simulationMode='MICRO', microTimeStep=.1, microTicksPerGlobalTick=1,
            carryMicroRemainder=True, signalControls=[dict(targetLinkId=12 if link == 11 else 0)])
        if link == 11:
            state.update(downstreamLookahead=True, downstreamSpillback=True,
                         laneConnections=[dict(outgoingLinkId=12, fromLane=0, toLane=0),
                                          dict(outgoingLinkId=12, fromLane=1, toLane=0)])
        else:
            state.update(stopLineGapM=17., microEntry=dict(enabled=True))
        actors.append(actor('Link', link, state, {'from': dict(id=a, classType='hybrid.actor.Node'),
                                               'to': dict(id=b, classType='hybrid.actor.Node')}))
    for identity, lane, position in CARS:
        route = [dict(linkId=11, nodeId=2), dict(linkId=12, nodeId=3)]
        actors.append(actor('Car', identity, dict(startTick=0, origin=1, destination=3, actorType='Car', size=4.5,
            precomputedRoute=route, microDepartPosition=position, microDepartLane=lane)))
    program = dict(control='Fixed', stages=[dict(durationSeconds=2., green=[]),
        dict(durationSeconds=8., green=[701]), dict(durationSeconds=30., green=[702])], reportMovementChanges=True,
        movements=[dict(origin=701, nodeId=2, incomingLinkId=11, outgoingLinkId=12),
                   dict(origin=702, nodeId=3, incomingLinkId=12, outgoingLinkId=0)])
    actors.append(actor('TrafficSignal', 700, dict(startTick=0, nodes=[2, 3], program=program),
        {'node2': dict(id=2, classType='hybrid.actor.Node'), 'node3': dict(id=3, classType='hybrid.actor.Node')}))
    data = output/'data'
    counts = shard_and_write_actors(actors, data)
    city = dict(directed=True, nodes=[dict(id=n, resourceId=str(n), classType='hybrid.actor.Node',
        latitude=33.78, longitude=-84.38+n*.001) for n in [1, 2, 3]], edges=[
        dict(source_id=a, target_id=b, weight=length, label=dict(id=i, resourceId=str(i),
            classType='hybrid.actor.Link', length=length)) for i, a, b, length in [(11, 1, 2, 60.), (12, 2, 3, 20.)]])
    write_city_map_json(city, data)
    simulation = build_simulation_json(output.name, 'Multi-lane merge handoff regression',
        'Synthetic engineering control: queued downstream admission delays retained handoffs',
        counts, str(output.parent), end_tick=end_tick, tick_duration=.1,
        settings={'htc.time-manager.conservative-transport': 'channel-closed'})
    simulation['timeManagerType'] = policy
    (output/'simulation.json').write_text(json.dumps(simulation, indent=2)+'\n')


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--policy', choices=['discrete-event', 'time-warp'], default='discrete-event')
    p.add_argument('--end-tick', type=int, default=2000)
    a = p.parse_args()
    generate(a.output.resolve(), a.policy, a.end_tick)
