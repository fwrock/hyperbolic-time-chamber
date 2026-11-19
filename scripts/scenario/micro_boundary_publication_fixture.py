#!/usr/bin/env python3
"""Create a bounded actor regression for causal boundary publication, not empirical traffic."""
import argparse
import json
from pathlib import Path
import sys

from ngsim_actor_approach import actor


def generate(output, policy, initial_snapshot=False, spillback=False, handoff=False):
    if output.exists():
        raise FileExistsError(output)
    workspace = Path(__file__).resolve().parents[3]
    sys.path.insert(0, str(workspace/'tools/scenario-db-converter'))
    from scenario_output_writer import shard_and_write_actors, write_city_map_json, build_simulation_json
    actors = []
    for node, links in [(1,[11]),(2,[12]),(3,[])]:
        state = dict(startTick=0,latitude=33.78,longitude=-84.38+node*.001,links=links)
        if node in [2,3]:
            state['movementSignals'] = [dict(incomingLinkId=11 if node==2 else 12,
                outgoingLinkId=12 if node==2 else 0,origin=701 if node==2 else 702)]
        actors.append(actor('Node',node,state))
    for link,a,b,length in [(11,1,2,50.),(12,2,3,20.)]:
        state = dict(startTick=0,**{'from':a},to=b,length=length,lanes=1,speedLimit=50.,
            freeSpeed=50.,capacity=100.,simulationMode='MICRO',microTimeStep=.1,
            microTicksPerGlobalTick=1,downstreamLookahead=link==11,
            signalControls=[dict(targetLinkId=12 if link==11 else 0)])
        if link==12:
            state['stopLineGapM']=17.
        if spillback:
            state['carryMicroRemainder']=True
            if link==11: state['downstreamSpillback']=True
        if initial_snapshot:
            state['microEntry']=dict(enabled=True,initialSnapshotTick=0)
        actors.append(actor('Link',link,state,{'from':dict(id=a,classType='hybrid.actor.Node'),
                                            'to':dict(id=b,classType='hybrid.actor.Node')}))
    for identity,origin,position in ([(101,1,35.),(102,1,20.)] if handoff else [(101,1,35.),(102,1,20.),(103,2,3.)]):
        route=[dict(linkId=11,nodeId=2),dict(linkId=12,nodeId=3)] if origin==1 else [dict(linkId=12,nodeId=3)]
        # The fixture tests boundary transport, not same-tick interior injection arbitration.
        actors.append(actor('Car',identity,dict(startTick=1 if identity==102 and not initial_snapshot else 0,origin=origin,destination=3,
            actorType='Car',size=4.5,precomputedRoute=route,microDepartPosition=position)))
    program=dict(control='Fixed',stages=[dict(durationSeconds=2.,green=[]),
        dict(durationSeconds=3.1,green=[701])],reportMovementChanges=True,
        movements=[dict(origin=701,nodeId=2,incomingLinkId=11,outgoingLinkId=12),
                   dict(origin=702,nodeId=3,incomingLinkId=12,outgoingLinkId=0)])
    actors.append(actor('TrafficSignal',700,dict(startTick=0,nodes=[2,3],program=program),
        {'node2':dict(id=2,classType='hybrid.actor.Node'),'node3':dict(id=3,classType='hybrid.actor.Node')}))
    data=output/'data';counts=shard_and_write_actors(actors,data)
    city=dict(directed=True,nodes=[dict(id=n,resourceId=str(n),classType='hybrid.actor.Node',
        latitude=33.78,longitude=-84.38+n*.001) for n in [1,2,3]],edges=[
        dict(source_id=a,target_id=b,weight=length,label=dict(id=i,resourceId=str(i),
            classType='hybrid.actor.Link',length=length)) for i,a,b,length in [(11,1,2,50.),(12,2,3,20.)]])
    write_city_map_json(city,data)
    simulation=build_simulation_json(output.name,'Causal boundary publication regression',
        'Synthetic engineering control: stopped downstream tail blocks two upstream cars',
        counts,str(output.parent),end_tick=400 if handoff else 50,tick_duration=.1,
        settings={'htc.time-manager.conservative-transport':'channel-closed'})
    simulation['timeManagerType']=policy
    (output/'simulation.json').write_text(json.dumps(simulation,indent=2)+'\n')
    (output/'scenario_metadata.json').write_text(json.dumps(dict(status='ENGINEERING_ONLY',
        initial_snapshot=initial_snapshot,
        expected_admission_ticks=[0,0 if initial_snapshot else 1,0],
        expectations=['Admissions at requested positions 35/20/3 m and declared ticks.',
            'Three admitted subjects, no road exit, all journeys horizon-censored.',
            'Downstream front remains at 3 m; upstream leading front never exceeds 46.5 m.',
            'Committed physical events match the retained engine under the same controlled input.']),indent=2)+'\n')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--policy',choices=['discrete-event','time-warp'],default='discrete-event')
    parser.add_argument('--initial-snapshot',action='store_true')
    parser.add_argument('--handoff',action='store_true',help='no downstream blocker, longer horizon: cars cross the 11->12 boundary')
    parser.add_argument('--spillback',action='store_true',help='retain exits on the approach (needs no --initial-snapshot with engines before G03)')
    args=parser.parse_args()
    generate(args.output.resolve(),args.policy,args.initial_snapshot,args.spillback,args.handoff)
