#!/usr/bin/env python3
"""Fresh, fail-closed HTC MICRO versus real SUMO trajectory and node-timing comparison."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

from generate_hybrid_scenario import build_scenario, city_map_payload

CASES = {
    'free': ([300.0], [0], False),
    'following': ([300.0], [0, 6, 12, 18], False),
    'crossing': ([200.0, 100.0], [0, 10, 20, 30, 40, 50], False),
    'signal': ([200.0, 100.0], [0, 10, 20, 30, 40, 50], True),
    'signal-boundary': ([200.0, 100.0], [12], True),
}


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2, allow_nan=False) + '\n')


def input_hashes(root):
    files=list((root/'data').glob('*.json'))
    files += [root/name for name in ('canonical.json','discrete-event.json','time-warp.json')]
    files += [p for p in (root/'sumo').glob('*') if p.suffix in ('.xml','.sumocfg')
              and p.name not in ('fcd.xml','tripinfo.xml')]
    return {str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(files)}


def generate(root, case):
    lengths, departures, signal = CASES[case]
    build_scenario(root, str(root), 1, len(lengths) + 1, 0, len(departures), 0, 0, 0, 0,
                   0.0, None, 2, 'travel-time', 42)
    def read(name):
        return json.loads((root / 'data' / name).read_text())
    nodes, roads, cars = read('nodes_0.json'), read('links_0.json'), read('cars_0.json')
    forward = []
    for road in roads:
        state = road['data']['content']
        state.update(simulationMode='MICRO', length=lengths[min(state['from'], state['to']) - 1],
                     speedLimit=50.0, freeSpeed=50.0)
        if state['from'] < state['to']:
            forward.append(road)
    forward.sort(key=lambda r: r['data']['content']['from'])
    for car, depart in zip(cars, departures):
        state = car['data']['content']
        state.update(startTick=depart, scheduleOnTimeManager=True, origin=1, destination=len(nodes),
                     status='RouteWaiting', currentNode=1,
                     precomputedRoute=[dict(linkId=r['id'], nodeId=r['data']['content']['to']) for r in forward])
        car['dependencies']={'from_node':dict(id=1,classType='hybrid.actor.Node'),
                             'to_node':dict(id=len(nodes),classType='hybrid.actor.Node')}
    for name, data in [('links_0.json', roads), ('cars_0.json', cars)]:
        write_json(root / 'data' / name, data)
    config = json.loads((root / 'simulation.json').read_text())
    if signal:
        signal_id = max(r['id'] for r in nodes + roads + cars) + 1
        phase = signal_id + 1
        junction = nodes[1]
        junction['data']['content'].update(connections={str(forward[1]['id']): dict(id=phase)},
            approachConnections={str(forward[0]['id']): dict(id=phase)})
        write_json(root / 'data' / 'traffic_signals_0.json', [dict(id=signal_id, typeActor='hybrid.actor.TrafficSignal',
            data=dict(dataType='model.hybrid.entity.state.TrafficSignalState', content=dict(startTick=0,
                cycleDuration=60, offset=0, nodes=[2], phases=[dict(origin=phase, greenStart=0,
                greenDuration=30, state='Green')], signalStates={str(phase):dict(state='Red',remainingTime=30,nextTick=0)})),
            dependencies={'2':dict(id=2,classType='hybrid.actor.Node')})])
        config['actorsDataSources'].append(dict(id='signals',classType='hybrid.actor.TrafficSignal',
            creationType='LoadBalancedDistributed',loadingStrategy='EAGER',entityLifecycle='STATIC',
            dataSource=dict(sourceType='json',info=dict(path=str(root/'data/traffic_signals_0.json')))))
    write_json(root / 'data/nodes_0.json', nodes)
    write_json(root / 'data/city_map.json', city_map_payload(nodes, roads, []))
    config.update(duration=180, endTick=180, id='sumo-micro-' + case)
    for policy in ('discrete-event', 'time-warp'):
        write_json(root / (policy + '.json'), dict(config, timeManagerType=policy))
    canonical = dict(case=case, lengths=lengths, departures=departures, signal=signal,
        vehicles=[str(r['id']) for r in cars], links=[str(r['id']) for r in forward],
        speedLimitMps=50/3.6, nodeGeometry='point junction; no internal links', seed=42)
    write_json(root / 'canonical.json', canonical)
    sumo = root / 'sumo'
    sumo.mkdir()
    def xml(name, element):
        ET.indent(element)
        ET.ElementTree(element).write(sumo/name, encoding='utf-8', xml_declaration=True)
    nod = ET.Element('nodes')
    x = 0
    for i in range(len(nodes)):
        ET.SubElement(nod, 'node', id=str(i+1), x=str(x), y='0',
                      type='traffic_light' if signal and i == 1 else 'priority')
        if i < len(lengths): x += lengths[i]
    xml('nodes.xml', nod)
    edges = ET.Element('edges')
    for i, (link, length) in enumerate(zip(forward, lengths)):
        ET.SubElement(edges, 'edge', {'id':str(link['id']), 'from':str(i+1), 'to':str(i+2),
                      'numLanes':'1', 'speed':str(50/3.6), 'length':str(length)})
    xml('edges.xml', edges)
    command = ['netconvert','--node-files',str(sumo/'nodes.xml'),'--edge-files',str(sumo/'edges.xml'),
               '--output-file',str(sumo/'net.xml'),'--no-turnarounds','--no-internal-links']
    if signal:
        tls = ET.Element('tlLogics')
        logic = ET.SubElement(tls,'tlLogic',id='2',type='static',programID='0',offset='0')
        ET.SubElement(logic,'phase',duration='30',state='G')
        ET.SubElement(logic,'phase',duration='30',state='r')
        xml('tls.xml', tls)
        command += ['--tllogic-files',str(sumo/'tls.xml')]
    result = subprocess.run(command,capture_output=True,text=True)
    (sumo/'netconvert.log').write_text(result.stdout+result.stderr)
    result.check_returncode()
    net = ET.parse(sumo/'net.xml').getroot()
    actual = {edge.attrib['id']:float(edge.find('lane').attrib['length']) for edge in net.findall('edge') if edge.find('lane') is not None}
    assert all(math.isclose(actual[link], length,abs_tol=1e-6) for link,length in zip(canonical['links'],lengths))
    routes = ET.Element('routes')
    ET.SubElement(routes,'vType',id='car',carFollowModel='Krauss',accel='2.6',decel='4.5',
                  minGap='2',tau='1',length='4.5',sigma='0',speedFactor='1',speedDev='0',maxSpeed=str(50/3.6))
    for vehicle, depart in zip(canonical['vehicles'],departures):
        item=ET.SubElement(routes,'vehicle',id=vehicle,type='car',depart=str(depart),departSpeed='0',departPos='0')
        ET.SubElement(item,'route',edges=' '.join(canonical['links']))
    xml('routes.xml', routes)
    cfg = ET.Element('configuration')
    for group, settings in [('input',{'net-file':'net.xml','route-files':'routes.xml'}),
        ('time',{'end':'180','step-length':'0.1'}),
        ('processing',{'time-to-teleport':'-1','collision.action':'warn'}),
        ('output',{'fcd-output':'fcd.xml','tripinfo-output':'tripinfo.xml','precision':'6',
                   'fcd-output.attributes':'id,speed,pos,lane,odometer'})]:
        element=ET.SubElement(cfg,group)
        for key,value in settings.items(): ET.SubElement(element,key,value=value)
    xml('scenario.sumocfg',cfg)
    return canonical


def read_htc(reports):
    return [json.loads(line) for path in reports.rglob('*.jsonl') for line in path.read_text().splitlines() if line.strip()]


def trajectory(rows, canonical):
    offsets = dict(zip(canonical['links'], [sum(canonical['lengths'][:i]) for i in range(len(canonical['links']))]))
    samples = {}
    for row in rows:
        label, data = row['event_type'], row['data']
        if label not in ('enter_micro_link','micro_update','leave_micro_link'): continue
        vehicle, tick = str(data['car_id']), float(data['tick'])
        link = str(data['link_id'])
        if label == 'enter_micro_link': position,speed,priority = 0.0,data['initial_velocity'],0
        elif label == 'micro_update': position,speed,priority = data['position'],data['velocity'],1
        else: position,speed,priority = data['final_position'],data['final_velocity'],2
        key=(vehicle,tick)
        sample=dict(time=tick,vehicle=vehicle,link=link,position=position,distance=offsets[link]+position,
                    routeOffset=offsets[link],speed=speed,priority=priority)
        # At a same-tick link transition, the greatest route distance is authoritative.
        previous=samples.get(key)
        if previous is None or (sample['distance'],sample['routeOffset'],priority) > (previous['distance'],previous['routeOffset'],previous['priority']): samples[key]=sample
    return samples


def sumo_samples(path):
    samples={}
    for _, elem in ET.iterparse(path,events=('end',)):
        if elem.tag != 'timestep': continue
        tick=float(elem.attrib['time'])
        if abs(tick-round(tick)) < 1e-6:
            for v in elem.findall('vehicle'):
                a=v.attrib
                samples[(a['id'],float(round(tick)))]=dict(time=tick,vehicle=a['id'],link=a['lane'].rsplit('_',1)[0],
                    position=float(a['pos']),distance=float(a['odometer']),speed=float(a['speed']))
        elem.clear()
    return samples


def compare(rows, fcd, tripinfo, canonical):
    htc, sumo = trajectory(rows,canonical), sumo_samples(fcd)
    trips={r.attrib['id']:r.attrib for r in ET.parse(tripinfo).getroot().findall('tripinfo')}
    first_sumo_link_tick = {}
    for _, elem in ET.iterparse(fcd,events=('end',)):
        if elem.tag != 'timestep': continue
        for vehicle in elem.findall('vehicle'):
            first_sumo_link_tick.setdefault((vehicle.attrib['id'],vehicle.attrib['lane'].rsplit('_',1)[0]),float(elem.attrib['time']))
        elem.clear()
    result=[]
    for vehicle, departure in zip(canonical['vehicles'],canonical['departures']):
        complete=[r['data'] for r in rows if r['event_type']=='sumo_tripinfo' and str(r['data']['vehicle_id'])==vehicle]
        assert len(complete)==1 and not complete[0].get('horizon_truncated',False), (vehicle,'missing actual HTC completion')
        assert vehicle in trips, (vehicle,'missing actual SUMO completion')
        a,b=complete[0],trips[vehicle]
        assert a['depart']==departure and float(b['depart'])==departure, 'departure mismatch must be explicit'
        assert math.isclose(a['routeLength'],sum(canonical['lengths']),abs_tol=1e-6)
        keys=sorted(k for k in htc.keys() & sumo.keys() if k[0]==vehicle)
        assert len(keys)>=2, (vehicle,'insufficient trajectory overlap')
        same=[k for k in keys if htc[k]['link']==sumo[k]['link']]
        def rmse(field,selected):
            return math.sqrt(sum((htc[k][field]-sumo[k][field])**2 for k in selected)/len(selected)) if selected else None
        entries=[r['data'] for r in rows if r['event_type']=='enter_micro_link' and str(r['data']['car_id'])==vehicle]
        leaves=[r['data'] for r in rows if r['event_type']=='leave_micro_link' and str(r['data']['car_id'])==vehicle]
        assert [str(r['link_id']) for r in entries]==canonical['links']
        assert [str(r['link_id']) for r in leaves]==canonical['links']
        result.append(dict(vehicle=vehicle,samples=len(keys),sameLinkSamples=len(same),differentLinkSamples=len(keys)-len(same),
            htcSamples=sum(k[0]==vehicle for k in htc),sumoSamples=sum(k[0]==vehicle for k in sumo),
            routeDistanceRmseM=rmse('distance',keys),sameLinkPositionRmseM=rmse('position',same),speedRmseMps=rmse('speed',keys),
            htcDuration=a['arrival']-a['depart'],sumoDuration=float(b['duration']),
            durationDelta=a['arrival']-a['depart']-float(b['duration']),
            htcNodeDelayTicks=[entries[i+1]['tick']-leaves[i]['tick'] for i in range(len(leaves)-1)],
            htcLinkEntryTicks=[r['tick'] for r in entries],htcLinkExitTicks=[r['tick'] for r in leaves],
            htcExitSpeeds=[r['final_velocity'] for r in leaves]))
        result[-1]['sumoLinkEntryTimes']=[first_sumo_link_tick.get((vehicle,link)) for link in canonical['links']]
    assert {k[0] for k in htc}=={k[0] for k in sumo}==set(canonical['vehicles']), 'missing/unexpected vehicles'
    return dict(vehicles=result,meanRouteDistanceRmseM=sum(r['routeDistanceRmseM'] for r in result)/len(result),
                maxAbsDurationDelta=max(abs(r['durationDelta']) for r in result),
                meanSpeedRmseMps=sum(r['speedRmseMps'] for r in result)/len(result))


def model_checks(rows, metrics, canonical):
    phases=sorted((r['data']['tick'],r['data']['phase_state']) for r in rows if r['event_type']=='signal_phase_change')
    timing_ok=True
    momentum_ok=True
    for vehicle in canonical['vehicles']:
        entries=sorted((r['data'] for r in rows if r['event_type']=='enter_micro_link' and str(r['data']['car_id'])==vehicle),key=lambda d:d['tick'])
        leaves=sorted((r['data'] for r in rows if r['event_type']=='leave_micro_link' and str(r['data']['car_id'])==vehicle),key=lambda d:d['tick'])
        timing_ok &= all(r['travel_time_seconds']==r['tick']-entry['tick'] for entry,r in zip(entries,leaves))
        momentum_ok &= all(math.isclose(entry['initial_velocity'],min(leave['final_velocity'],canonical['speedLimitMps']),abs_tol=1e-9)
                           for leave,entry in zip(leaves,entries[1:]))
    return dict(noArtificialNodeDelay=all(delay==0 for v in metrics['vehicles'] for delay in v['htcNodeDelayTicks']),
        crossingMomentum=momentum_ok and all(speed>0 for v in metrics['vehicles'] for speed in v['htcExitSpeeds']),
        elapsedLinkTime=timing_ok,
        inclusiveSignalPhases=phases==([(tick,'Green' if tick%60==0 else 'Red') for tick in range(0,181,30)] if canonical['signal'] else []),
        noRedIntervalCrossing=not canonical['signal'] or all((tick-1)%60<30 for v in metrics['vehicles'] for tick in v['htcLinkExitTicks'][:-1]),
        tripTimeWithinClockResolution=metrics['maxAbsDurationDelta']<=len(canonical['links'])+0.1)


def run(args):
    root=args.output.resolve()
    root.mkdir(parents=True,exist_ok=False)
    canonical=generate(root,args.case)
    sumo=root/'sumo'
    command=['sumo','-c','scenario.sumocfg','--seed','42']
    write_json(sumo/'command.json',command)
    result=subprocess.run(command,cwd=sumo,capture_output=True,text=True,timeout=args.timeout)
    (sumo/'run.log').write_text(result.stdout+result.stderr)
    result.check_returncode()
    assert 'collision' not in result.stderr.lower() and 'emergency braking' not in result.stderr.lower(), result.stderr
    extra=root/'reporting.conf'
    extra.write_text('htc.report-manager.kafka.micro-update-report-every=1\n')
    simulation=root/(args.policy+'.json')
    command=[sys.executable,str(Path(__file__).with_name('run_cluster_validation.py')),'--jar',str(args.jar.resolve()),
        '--simulation',str(simulation),'--output',str(root/'htc'),'--base-port',str(args.base_port),'--nodes','1',
        '--extra-config',str(extra),'--timeout',str(args.timeout)]
    write_json(root/'htc-command.json',command)
    result=subprocess.run(command,capture_output=True,text=True,timeout=args.timeout+40)
    (root/'runner.log').write_text(result.stdout+result.stderr)
    result.check_returncode()
    log=(root/'htc/runtime.log').read_text(errors='replace')
    for marker in ('[ERROR]','[STALL','[GTM-STALL]','[RUNTIME-STALL]','null state','Replay did not consume all recorded reads'):
        assert marker not in log, marker
    runner=json.loads((root/'htc/result.json').read_text())
    assert runner['engineReportedStop'] and not runner['runtimeFailed']
    rows=read_htc(root/'htc/reports')
    metrics=compare(rows,sumo/'fcd.xml',sumo/'tripinfo.xml',canonical)
    checks=model_checks(rows,metrics,canonical)
    record=dict(case=args.case,policy=args.policy,seed=42,jarSha256=runner['jarSha256'],
        sumoVersion=subprocess.check_output(['sumo','--version'],text=True).splitlines()[0],
        canonical=canonical,rollbacks=log.count('[TW-ROLLBACK]'),measurementValid=True,
        modelChecks=checks,passed=all(checks.values()),**metrics)
    record['inputSha256']=input_hashes(root)
    write_json(root/'comparison.json',record)
    print(json.dumps(record,indent=2))
    assert args.measure_only or record['passed'], 'model checks failed; comparison.json preserves the measurements'


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar',required=True,type=Path)
    parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--case',choices=CASES,required=True)
    parser.add_argument('--policy',choices=('discrete-event','time-warp'),default='discrete-event')
    parser.add_argument('--base-port',type=int,default=28500)
    parser.add_argument('--timeout',type=int,default=180)
    parser.add_argument('--measure-only',action='store_true',help='Record an old model baseline even when model checks fail; simulator/measurement failures still fail')
    args=parser.parse_args()
    run(args)


if __name__=='__main__': main()
