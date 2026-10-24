#!/usr/bin/env python3
"""Fresh, fail-closed HTC MICRO versus real SUMO trajectory and node-timing comparison."""
import argparse
from collections import Counter
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
    'long-route': ([100.0] * 12, [0], False),
}


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2, allow_nan=False) + '\n')


def input_hashes(root):
    files=list((root/'data').glob('*.json'))
    files += [root/name for name in ('canonical.json','discrete-event.json','time-warp.json')]
    if (root/'experiment.json').exists(): files.append(root/'experiment.json')
    files += [p for p in (root/'sumo').glob('*') if p.suffix in ('.xml','.sumocfg')
              and p.name not in ('fcd.xml','tripinfo.xml')]
    return {str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(files)}


def generate(root, case, junction_geometry=False, turn="straight", parameters=None):
    parameters = parameters or {}
    for key,value in dict(accelMps2=2.6,decelMps2=4.5,reactionSeconds=1,minGapM=2,vehicleLengthM=4.5,sumoSigma=0).items():
        assert parameters.get(key,value)==value, ('unsupported physics override',key)
    lengths, departures, signal = CASES[case]
    lengths = list(parameters.get('roadLengths', lengths))
    departures = list(parameters.get('departuresSeconds', departures))
    quantum = float(parameters.get('runtimeTickSeconds', 1.0))
    physics_step = float(parameters.get('physicsStepSeconds', 0.1))
    carry = bool(parameters.get('carryRemainder', False))
    seed = int(parameters.get('seed', 42))
    horizon = float(parameters.get('horizonSeconds', 180))
    speed_limit_kmh = float(parameters.get('speedLimitKmh',50.0))
    initial_speeds = list(parameters.get('initialSpeedsMps',[0.0]*len(departures)))
    assert math.isfinite(speed_limit_kmh) and speed_limit_kmh > 0
    assert len(initial_speeds)==len(departures) and all(math.isfinite(v) and 0<=v<=speed_limit_kmh/3.6+1e-9 for v in initial_speeds)
    assert lengths and all(math.isfinite(x) and x > 0 for x in lengths)
    assert departures and departures == sorted(set(departures)) and departures[0] >= 0
    assert 0 < quantum <= 1 and physics_step > 0 and abs(quantum / physics_step - round(quantum / physics_step)) < 1e-8
    assert not carry or abs(physics_step - quantum) < 1e-9
    def ticks(seconds):
        value = seconds / quantum
        assert abs(value-round(value)) < 1e-7, ('time is not on the selected runtime clock',seconds,quantum)
        return round(value)
    if junction_geometry and case == 'signal-boundary' and 'departuresSeconds' not in parameters:
        # The compiled stop-line approach is shorter: retain a crossing just before red at t=30.
        departures = [13]
    if junction_geometry and len(lengths) != 2:
        raise ValueError('junction geometry requires a two-road case')
    import generate_tiny_validation_scenario as tiny
    tiny._ID_REGISTRY = tiny.IdRegistry()
    build_scenario(root, str(root), 1, len(lengths) + 1, 0, len(departures), 0, 0, 0, 0,
                   0.0, None, 2, 'travel-time', seed)
    def read(name):
        return json.loads((root / 'data' / name).read_text())
    nodes, roads, cars = read('nodes_0.json'), read('links_0.json'), read('cars_0.json')
    forward = []
    for road in roads:
        state = road['data']['content']
        state.update(simulationMode='MICRO', length=lengths[min(state['from'], state['to']) - 1],
                     speedLimit=speed_limit_kmh, freeSpeed=speed_limit_kmh)
        if state['from'] < state['to']:
            forward.append(road)
    forward.sort(key=lambda r: r['data']['content']['from'])
    for car, depart, initial_speed in zip(cars, departures, initial_speeds):
        state = car['data']['content']
        state.update(startTick=ticks(depart), scheduleOnTimeManager=True, origin=1, destination=len(nodes),
                     status='RouteWaiting', currentNode=1,
                     precomputedRoute=[dict(linkId=r['id'], nodeId=r['data']['content']['to']) for r in forward])
        if initial_speed: state['journey']=dict(sumoArrivalSpeed=initial_speed)
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
    config.update(duration=ticks(horizon), endTick=ticks(horizon), tickDuration=quantum, id='sumo-micro-' + case,
                  randomSeed=seed)
    for policy in ('discrete-event', 'time-warp'):
        write_json(root / (policy + '.json'), dict(config, timeManagerType=policy))
    canonical = dict(case=case, lengths=lengths, departures=departures, signal=signal,
        vehicles=[str(r['id']) for r in cars], links=[str(r['id']) for r in forward],
        speedLimitMps=speed_limit_kmh/3.6, nodeGeometry='point junction; no internal links', seed=seed,
        initialSpeedsMps=initial_speeds,
        runtimeTickSeconds=quantum, physicsStepSeconds=physics_step, carryRemainder=carry,
        comparisonStepSeconds=parameters.get('comparisonStepSeconds', 1.0 if quantum == 1 else quantum),
        horizonSeconds=horizon)
    write_json(root / 'canonical.json', canonical)
    road_links = list(canonical['links'])
    sumo = root / 'sumo'
    sumo.mkdir()
    def xml(name, element):
        ET.indent(element)
        ET.ElementTree(element).write(sumo/name, encoding='utf-8', xml_declaration=True)
    nod = ET.Element('nodes')
    x = 0
    for i in range(len(nodes)):
        nx, ny = x, 0
        if junction_geometry and i == 2 and turn != 'straight':
            nx, ny = lengths[0], lengths[1] * (1 if turn == 'left' else -1)
        ET.SubElement(nod, 'node', id=str(i+1), x=str(nx), y=str(ny),
                      type='traffic_light' if (signal or junction_geometry) and i == 1 else 'priority')
        if i < len(lengths): x += lengths[i]
    extra_arms = []
    if junction_geometry:
        endpoints = {'straight': (lengths[0]+200, 0), 'left': (lengths[0], 200), 'right': (lengths[0], -200)}
        for direction, (nx, ny) in endpoints.items():
            if direction == turn: continue
            node = 'arm_' + direction
            ET.SubElement(nod, 'node', id=node, x=str(nx), y=str(ny), type='dead_end')
            extra_arms.append((node, 'arm_edge_' + direction))
    xml('nodes.xml', nod)
    edges = ET.Element('edges')
    for link in (roads if junction_geometry else forward):
        state = link['data']['content']
        attributes = {'id':str(link['id']), 'from':str(state['from']), 'to':str(state['to']),
                      'numLanes':'1', 'speed':str(speed_limit_kmh/3.6)}
        if not junction_geometry: attributes['length'] = str(state['length'])
        ET.SubElement(edges, 'edge', attributes)
    for node, edge in extra_arms:
        ET.SubElement(edges, 'edge', {'id':edge, 'from':'2', 'to':node, 'numLanes':'1', 'speed':str(speed_limit_kmh/3.6)})
        ET.SubElement(edges, 'edge', {'id':edge+'_in', 'from':node, 'to':'2', 'numLanes':'1', 'speed':str(speed_limit_kmh/3.6)})
    xml('edges.xml', edges)
    command = ['netconvert','--node-files',str(sumo/'nodes.xml'),'--edge-files',str(sumo/'edges.xml'),
               '--output-file',str(sumo/'net.xml'),'--no-turnarounds']
    if not junction_geometry: command += ['--no-internal-links']
    if signal and not junction_geometry:
        tls = ET.Element('tlLogics')
        logic = ET.SubElement(tls,'tlLogic',id='2',type='static',programID='0',offset='0')
        ET.SubElement(logic,'phase',duration='30',state='G' * (1 + len(extra_arms)))
        ET.SubElement(logic,'phase',duration='30',state='r' * (1 + len(extra_arms)))
        xml('tls.xml', tls)
        command += ['--tllogic-files',str(sumo/'tls.xml')]
    result = subprocess.run(command,capture_output=True,text=True)
    (sumo/'netconvert.log').write_text(result.stdout+result.stderr)
    result.check_returncode()
    net = ET.parse(sumo/'net.xml').getroot()
    actual = {edge.attrib['id']:float(edge.find('lane').attrib['length']) for edge in net.findall('edge') if edge.find('lane') is not None}
    if not junction_geometry:
        assert all(math.isclose(actual[link], length,abs_tol=1e-6) for link,length in zip(canonical['links'],lengths))
    else:
        for road in roads:
            road['data']['content']['length'] = actual[str(road['id'])]
        logic = next(tl for tl in net.findall('tlLogic') if tl.get('id') == '2')
        count = len(logic.find('phase').get('state'))
        selected = {int(c.get('linkIndex')) for c in net.findall('connection')
                    if c.get('from') == road_links[0] and c.get('to') == road_links[1] and c.get('tl') == '2'}
        for phase_element in list(logic.findall('phase')): logic.remove(phase_element)
        ET.SubElement(logic,'phase',duration='30' if signal else '180',
                      state=''.join('G' if i in selected else 'r' for i in range(count)))
        if signal: ET.SubElement(logic,'phase',duration='30',state='r'*count)
        xml('net.xml',net)
    if junction_geometry:
        connection = next(c for c in net.findall('connection') if c.get('from') == road_links[0] and c.get('to') == road_links[1])
        via = connection.attrib['via']
        parts=[]
        seen=set()
        while via:
            assert via not in seen, 'cyclic SUMO junction path'
            seen.add(via)
            internal = next(edge for edge in net.findall('edge') if any(l.get('id') == via for l in edge.findall('lane')))
            lane = next(l for l in internal.findall('lane') if l.get('id') == via)
            parts.append((internal,lane))
            following=next(c for c in net.findall('connection') if c.get('from')==internal.get('id')
                           and c.get('fromLane')==lane.get('index') and c.get('to')==road_links[1])
            via=following.get('via')
        length=sum(float(lane.get('length')) for _,lane in parts)
        speeds={float(lane.get('speed')) for _,lane in parts}
        assert len(speeds)==1, 'geometry pass requires one speed limit per movement'
        speed=next(iter(speeds))
        points=[]
        for _,lane in parts:
            segment=[dict(x=float(point.split(',')[0]), y=float(point.split(',')[1])) for point in lane.get('shape').split()]
            if points:
                assert math.hypot(points[-1]['x']-segment[0]['x'],points[-1]['y']-segment[0]['y'])<0.02, 'disconnected SUMO internal path'
                segment=segment[1:]
            points.extend(segment)
        connector_id = max(r['id'] for r in nodes + roads + cars) + (3 if signal else 1)
        geometry = dict(nodeId=2, incomingLinkId=int(road_links[0]), outgoingLinkId=int(road_links[1]),
                        lengthMeters=length, speedLimitMps=speed, shape=points)
        lane_map={}
        offset=0.0
        for internal,lane in parts:
            lane_map[lane.get('id')]=dict(linkId=str(connector_id),offset=offset)
            offset+=float(lane.get('length'))
        from generate_tiny_validation_scenario import link_actor
        connector = link_actor(connector_id, 2, 2, length, speed*3.6)
        connector['data']['content'].update(simulationMode='MICRO', junctionGeometry=geometry)
        roads.append(connector)
        forward[0]['data']['content']['exitSpeedLimitsMps'] = {road_links[1]: speed}
        nodes[1]['data']['content']['junctionMovements'] = [dict(incomingLinkId=int(road_links[0]),
            outgoingLinkId=int(road_links[1]), connectorLinkId=connector_id)]
        nodes[1]['data']['content']['links'].append(connector_id)
        write_json(root/'data/links_0.json', roads)
        write_json(root/'data/nodes_0.json', nodes)
        write_json(root/'data/city_map.json', city_map_payload(nodes, roads, []))
        canonical.update(roadLinks=road_links, links=[road_links[0],str(connector_id),road_links[1]],
            lengths=[actual[road_links[0]],length,actual[road_links[1]]], centerlineLengths=lengths, junctionGeometry=geometry, turn=turn,
            nodeGeometry='explicit internal connector; non-conflicting single movement',
            sumoLaneMap=lane_map, referenceControl='protected selected movement; all other approaches red')
        write_json(root/'canonical.json', canonical)
    for road in roads:
        road['data']['content'].update(microTimeStep=physics_step,
            microTicksPerGlobalTick=round(quantum/physics_step), carryMicroRemainder=carry)
    write_json(root/'data/links_0.json', roads)
    if signal:
        signals = read('traffic_signals_0.json')
        for actor in signals:
            state=actor['data']['content']
            for key in ('startTick','cycleDuration','offset'): state[key]=ticks(state[key])
            for phase in state['phases']:
                for key in ('greenStart','greenDuration'): phase[key]=ticks(phase[key])
            for value in state['signalStates'].values():
                for key in ('remainingTime','nextTick'): value[key]=ticks(value[key])
        write_json(root/'data/traffic_signals_0.json',signals)
    if quantum != 1:
        supported={'hybrid.actor.Node','hybrid.actor.Link','hybrid.actor.Car','hybrid.actor.TrafficSignal'}
        config['actorsDataSources']=[s for s in config['actorsDataSources'] if s['classType'] in supported]
        config['postLoadRegistrationClasses']=[]
    for policy in ('discrete-event','time-warp'):
        write_json(root/(policy+'.json'),dict(config,timeManagerType=policy))
    routes = ET.Element('routes')
    ET.SubElement(routes,'vType',id='car',carFollowModel='Krauss',accel='2.6',decel='4.5',
                  minGap='2',tau='1',length='4.5',sigma='0',speedFactor='1',speedDev='0',maxSpeed=str(speed_limit_kmh/3.6))
    for vehicle, depart, initial_speed in zip(canonical['vehicles'],departures,initial_speeds):
        item=ET.SubElement(routes,'vehicle',id=vehicle,type='car',depart=str(depart),departSpeed=str(initial_speed),departPos='0')
        ET.SubElement(item,'route',edges=' '.join(road_links))
    xml('routes.xml', routes)
    cfg = ET.Element('configuration')
    for group, settings in [('input',{'net-file':'net.xml','route-files':'routes.xml'}),
        ('time',{'end':str(horizon),'step-length':str(physics_step)}),
        ('processing',{'time-to-teleport':'-1','collision.action':'warn'}),
        ('output',{'fcd-output':'fcd.xml','tripinfo-output':'tripinfo.xml','precision':'6',
                   'fcd-output.attributes':'id,speed,pos,lane,odometer' + (',x,y' if junction_geometry else '')})]:
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
        vehicle, tick = str(data['car_id']), round(float(data['tick']) * canonical.get('runtimeTickSeconds',1.0),9)
        link = str(data['link_id'])
        if label == 'enter_micro_link': position,speed,priority = data.get('initial_position',0.0),data['initial_velocity'],0
        elif label == 'micro_update': position,speed,priority = data['position'],data['velocity'],1
        else: position,speed,priority = data['final_position'],data['final_velocity'],2
        key=(vehicle,tick)
        sample=dict(time=tick,vehicle=vehicle,link=link,position=position,distance=offsets[link]+position,
                    routeOffset=offsets[link],speed=speed,priority=priority)
        # At a same-tick link transition, the greatest route distance is authoritative.
        previous=samples.get(key)
        if previous is None or (sample['distance'],sample['routeOffset'],priority) > (previous['distance'],previous['routeOffset'],previous['priority']): samples[key]=sample
    return samples


def sumo_samples(path, canonical=None):
    samples={}
    for _, elem in ET.iterparse(path,events=('end',)):
        if elem.tag != 'timestep': continue
        tick=float(elem.attrib['time'])
        step=(canonical or {}).get('comparisonStepSeconds',1.0)
        if abs(tick/step-round(tick/step)) < 1e-6:
            for v in elem.findall('vehicle'):
                a=v.attrib
                samples[(a['id'],round(tick,9))]=dict(time=tick,vehicle=a['id'],link=(canonical or {}).get('sumoLaneMap',{}).get(a['lane'],{}).get('linkId',(canonical or {}).get('sumoLinkMap',{}).get(a['lane'].rsplit('_',1)[0],a['lane'].rsplit('_',1)[0])),
                    position=float(a['pos'])+(canonical or {}).get('sumoLaneMap',{}).get(a['lane'],{}).get('offset',0.0),distance=float(a['odometer']),speed=float(a['speed']))
        elem.clear()
    return samples


def compare(rows, fcd, tripinfo, canonical):
    htc, sumo = trajectory(rows,canonical), sumo_samples(fcd,canonical)
    trips={r.attrib['id']:r.attrib for r in ET.parse(tripinfo).getroot().findall('tripinfo')}
    first_sumo_link_tick = {}
    for _, elem in ET.iterparse(fcd,events=('end',)):
        if elem.tag != 'timestep': continue
        for vehicle in elem.findall('vehicle'):
            raw_link = vehicle.attrib['lane'].rsplit('_',1)[0]
            link = canonical.get('sumoLaneMap',{}).get(vehicle.attrib['lane'],{}).get('linkId',canonical.get('sumoLinkMap',{}).get(raw_link,raw_link))
            first_sumo_link_tick.setdefault((vehicle.attrib['id'],link),float(elem.attrib['time']))
        elem.clear()
    result=[]
    for vehicle, departure in zip(canonical['vehicles'],canonical['departures']):
        complete=[r['data'] for r in rows if r['event_type']=='sumo_tripinfo' and str(r['data']['vehicle_id'])==vehicle]
        assert len(complete)==1 and not complete[0].get('horizon_truncated',False), (vehicle,'missing actual HTC completion')
        assert vehicle in trips, (vehicle,'missing actual SUMO completion')
        a,b=complete[0],trips[vehicle]
        assert math.isclose(a['depart'],departure,abs_tol=1e-8) and math.isclose(float(b['depart']),departure,abs_tol=1e-8), 'departure mismatch must be explicit'
        assert math.isclose(a['routeLength'],sum(canonical['lengths']),abs_tol=1e-6)
        if canonical.get('junctionGeometry'):
            assert math.isclose(float(b['routeLength']),sum(canonical['lengths']),abs_tol=1e-5), 'junction distance counted twice or omitted'
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
            htcLinkEntryPositions=[r.get('initial_position',0.0) for r in entries],
            htcExitSpeeds=[r['final_velocity'] for r in leaves]))
        result[-1]['sumoLinkEntryTimes']=[first_sumo_link_tick.get((vehicle,link)) for link in canonical['links']]
    assert {k[0] for k in htc}=={k[0] for k in sumo}==set(canonical['vehicles']), 'missing/unexpected vehicles'
    return dict(vehicles=result,meanRouteDistanceRmseM=sum(r['routeDistanceRmseM'] for r in result)/len(result),
                maxAbsDurationDelta=max(abs(r['durationDelta']) for r in result),
                meanSpeedRmseMps=sum(r['speedRmseMps'] for r in result)/len(result))


def geometry_checks(rows, canonical):
    geometry=canonical.get('junctionGeometry')
    if not geometry: return {}
    connector=canonical['links'][1]
    labels=('enter_micro_link','micro_update','leave_micro_link')
    samples=[r for r in rows if r['event_type'] in labels and str(r['data']['link_id'])==connector]
    shape=geometry['shape']
    segments=[(a,b,math.hypot(b['x']-a['x'],b['y']-a['y'])) for a,b in zip(shape,shape[1:])]
    shape_length=sum(s[2] for s in segments)
    coordinates_ok=True
    speed_ok=True
    for row in samples:
        data=row['data']
        position=data.get('initial_position',0.0) if row['event_type']=='enter_micro_link' else data.get('position',data.get('final_position'))
        speed=data.get('velocity',data.get('final_velocity',data.get('initial_velocity')))
        speed_ok &= speed<=geometry['speedLimitMps']+1e-9
        target=position/geometry['lengthMeters']*shape_length
        prefix=0.0
        on_path=False
        for start,end,length in segments:
            if not length: continue
            x,y=data.get('junction_x'),data.get('junction_y')
            if x is None or y is None: break
            fraction=((x-start['x'])*(end['x']-start['x'])+(y-start['y'])*(end['y']-start['y']))/length**2
            residual=math.hypot(x-start['x']-fraction*(end['x']-start['x']),y-start['y']-fraction*(end['y']-start['y']))
            if -1e-7<=fraction<=1+1e-7 and residual<1e-6 and abs(prefix+fraction*length-target)<1e-6: on_path=True
            prefix+=length
        coordinates_ok &= on_path
    admissions=[r for r in rows if r['event_type']=='junction_connector_admitted']
    selected=Counter((str(r['data'].get('vehicle_id')),str(r['data'].get('connector_link_id')),
                      str(r['data'].get('target_link_id'))) for r in admissions)
    expected=Counter((vehicle,connector,str(geometry['outgoingLinkId'])) for vehicle in canonical['vehicles'])
    return dict(junctionSelection=selected==expected,
                junctionCoordinates=bool(samples) and coordinates_ok,junctionSpeed=speed_ok)


def model_checks(rows, metrics, canonical):
    quantum=canonical.get('runtimeTickSeconds',1.0)
    phases=sorted((r['data']['tick'],r['data']['phase_state']) for r in rows if r['event_type']=='signal_phase_change')
    timing_ok=True
    momentum_ok=True
    for vehicle in canonical['vehicles']:
        entries=sorted((r['data'] for r in rows if r['event_type']=='enter_micro_link' and str(r['data']['car_id'])==vehicle),key=lambda d:d['tick'])
        leaves=sorted((r['data'] for r in rows if r['event_type']=='leave_micro_link' and str(r['data']['car_id'])==vehicle),key=lambda d:d['tick'])
        timing_ok &= all(math.isclose(r['travel_time_seconds'],(r['tick']-entry['tick'])*quantum,abs_tol=1e-8) for entry,r in zip(entries,leaves))
        momentum_ok &= all(math.isclose(entry['initial_velocity'],min(leave['final_velocity'],canonical['speedLimitMps']),abs_tol=1e-9)
                           for leave,entry in zip(leaves,entries[1:]))
    checks=dict(noArtificialNodeDelay=all(delay==0 for v in metrics['vehicles'] for delay in v['htcNodeDelayTicks']),
        crossingMomentum=momentum_ok and all(speed>0 for v in metrics['vehicles'] for speed in v['htcExitSpeeds']),
        elapsedLinkTime=timing_ok,
        inclusiveSignalPhases=phases==([(round(tick/quantum),'Green' if tick%60==0 else 'Red') for tick in range(0,int(canonical.get('horizonSeconds',180))+1,30)] if canonical['signal'] else []),
        noRedIntervalCrossing=not canonical['signal'] or all(((tick-1)*quantum+1e-8)%60<30 for v in metrics['vehicles'] for tick in v['htcLinkExitTicks'][:1]),
        tripTimeWithinClockResolution=metrics['maxAbsDurationDelta']<=len(canonical['links'])*quantum+canonical.get('physicsStepSeconds',0.1)+1e-8)
    checks.update(geometry_checks(rows,canonical))
    if canonical.get('junctionGeometry'):
        limit=canonical['junctionGeometry']['speedLimitMps']
        checks['turnSpeedOnApproach']=all(v['htcExitSpeeds'][0]<=limit+0.45+1e-9 for v in metrics['vehicles'])
        checks['finiteJunctionTraversal']=all((v['htcLinkExitTicks'][1]-v['htcLinkEntryTicks'][1])*quantum >=
            (canonical['lengths'][1]-v.get('htcLinkEntryPositions',[0,0,0])[1])/limit-1e-9 for v in metrics['vehicles'])
    return checks


def run(args):
    root=args.output.resolve()
    root.mkdir(parents=True,exist_ok=False)
    parameters=json.loads(args.experiment.read_text()) if getattr(args,'experiment',None) else {}
    assert parameters.get('case',args.case)==args.case, 'experiment and CLI case disagree'
    assert parameters.get('turn',args.turn)==args.turn, 'experiment and CLI turn disagree'
    assert parameters.get('junctionGeometry',args.junction_geometry)==args.junction_geometry, 'experiment and CLI geometry disagree'
    if parameters: write_json(root/'experiment.json',parameters)
    canonical=generate(root,args.case,args.junction_geometry,args.turn,parameters)
    sumo=root/'sumo'
    command=['sumo','-c','scenario.sumocfg','--seed',str(canonical['seed'])]
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
    record=dict(case=args.case,policy=args.policy,seed=canonical['seed'],jarSha256=runner['jarSha256'],
        sumoVersion=subprocess.check_output(['sumo','--version'],text=True).splitlines()[0],
        canonical=canonical,rollbacks=log.count('[TW-ROLLBACK]'),measurementValid=True,
        modelChecks=checks,passed=all(checks.values()),**metrics)
    record['inputSha256']=input_hashes(root)
    record['parameters']=parameters
    record['harnessSha256']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    write_json(root/'comparison.json',record)
    print(json.dumps(record,indent=2))
    assert args.measure_only or record['passed'], 'model checks failed; comparison.json preserves the measurements'


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar',required=True,type=Path)
    parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--case',choices=CASES,required=True)
    parser.add_argument('--junction-geometry',action='store_true')
    parser.add_argument('--turn',choices=('straight','left','right'),default='straight')
    parser.add_argument('--policy',choices=('discrete-event','time-warp'),default='discrete-event')
    parser.add_argument('--base-port',type=int,default=28500)
    parser.add_argument('--timeout',type=int,default=180)
    parser.add_argument('--measure-only',action='store_true',help='Record an old model baseline even when model checks fail; simulator/measurement failures still fail')
    parser.add_argument('--experiment',type=Path,help='Frozen physical inputs and clock settings for a scientific benchmark case')
    args=parser.parse_args()
    run(args)


if __name__=='__main__': main()
