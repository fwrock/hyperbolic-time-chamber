#!/usr/bin/env python3
"""Exploratory, initialized free-flow forecasts against official NGSIM field observations."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import statistics
import subprocess
import sys
from urllib.parse import urlencode
from urllib.request import urlopen

from validate_sumo_micro import generate, read_htc, trajectory, write_json

API='https://data.transportation.gov/resource/8ect-6jqj.json'
METADATA='https://data.transportation.gov/api/views/8ect-6jqj.json'
FEET_TO_METRES=0.3048
PROTOCOL=dict(version=1,scope='Exploratory isolated free-flow component forecast, not network traffic validation',
    candidateQuery="location='us-101' and v_class='2' and preceding='0' and local_y<500 and v_vel>20",
    candidateLimit=100,maxCandidates=12,targetSubjects=3,initializationSeconds=1,predictionSeconds=10,
    sampleMilliseconds=100,selection='Chronological candidate time, then numeric vehicle ID; one candidate per vehicle/session. First three passing fixed quality checks, without examining model errors.',
    qualityChecks=['Exact duplicate removal; reject conflicting records at the same subject/time.',
        '111 contiguous 100 ms observations, increasing frame IDs and monotone longitudinal position.',
        'Passenger car, unchanged lane, and no recorded preceding vehicle throughout the interval.',
        'Finite nonnegative speeds and a positive speed fitted only from the first second.'],
    initialization='Least-squares longitudinal speed from the first 11 positions (one second). Start forecast at the last prefix position with that speed as both initial and desired cruising speed.',
    model='Actual HTC MICRO Car on an isolated 1000 m link, 0.1 s clock and physics; no parameter fitting to forecast observations.',
    evaluation='Next 100 observed positions and speeds, excluding every initialization-prefix observation.',
    acceptance='Exploratory measurements only; no empirical acceptance threshold or broad validation claim.',
    fieldSource='USDOT/FHWA NGSIM DOI 10.21949/1504477',license='CC BY-SA 3.0')


def fetch(url,path):
    with urlopen(url,timeout=45) as response:
        raw=response.read(2_000_001)
    if len(raw)>2_000_000: raise ValueError('bounded NGSIM download exceeded 2 MB')
    path.write_bytes(raw)
    return json.loads(raw),dict(url=url,sha256=hashlib.sha256(raw).hexdigest(),bytes=len(raw),file=path.name)


def quality_control(rows,start_ms):
    unique={}
    duplicates=0
    for row in rows:
        tick=int(row['global_time'])
        if tick in unique:
            if row!=unique[tick]: raise ValueError('conflicting duplicate timestamp')
            duplicates+=1
        unique[tick]=row
    times=list(range(start_ms,start_ms+11_000+1,100))
    if not all(t in unique for t in times): raise ValueError('missing 100 ms observations or insufficient forecast coverage')
    clean=[unique[t] for t in times]
    if len({r['lane_id'] for r in clean})!=1: raise ValueError('lane change in observation interval')
    if any(int(r['preceding'])!=0 or int(r['v_class'])!=2 for r in clean):
        raise ValueError('observed leader or unsupported vehicle class')
    y=[float(r['local_y'])*FEET_TO_METRES for r in clean]
    speed=[float(r['v_vel'])*FEET_TO_METRES for r in clean]
    if not all(math.isfinite(v) for v in y+speed) or min(speed)<0: raise ValueError('nonphysical observation')
    if any(b<a for a,b in zip(y,y[1:])): raise ValueError('nonmonotone longitudinal position')
    if any(int(b['frame_id'])!=int(a['frame_id'])+1 for a,b in zip(clean,clean[1:])):
        raise ValueError('nonconsecutive frame identity')
    prefix=y[:11]
    x=[i/10 for i in range(11)]
    xmean,ymean=statistics.mean(x),statistics.mean(prefix)
    initial=sum((a-xmean)*(b-ymean) for a,b in zip(x,prefix))/sum((a-xmean)**2 for a in x)
    if not 0<initial<50: raise ValueError('invalid prefix speed estimate')
    forecast=[dict(timeSeconds=(i-10)/10,positionM=y[i]-y[10],speedMps=speed[i]) for i in range(11,111)]
    derivative=[(b-a)*10 for a,b in zip(y,y[1:])]
    return dict(initialSpeedMps=initial,observations=forecast,quality=dict(
        rawRows=len(rows),uniqueRows=len(unique),exactDuplicatesRemoved=duplicates,
        forecastRows=len(forecast),lane=int(clean[0]['lane_id']),
        annotatedSpeedVsPositionDerivativeMaeMps=statistics.mean(abs(a-b) for a,b in zip(derivative,speed[1:]))))


def run(args):
    root=args.output.resolve()
    root.mkdir(parents=True,exist_ok=False)
    # Persist the selection and evaluation rules before downloading observations or seeing forecast errors.
    write_json(root/'pilot-protocol.json',PROTOCOL)
    snapshot=getattr(args,'snapshot',None)
    if snapshot:
        frozen=json.loads((snapshot/'quality-control.json').read_text())
        assert frozen['protocol']==PROTOCOL, 'snapshot uses different selection/initialization rules'
        sources=[frozen['metadataSource'],frozen['candidateSource']]+[s['source'] for s in frozen['selected']]
        for source in sources:
            name=source['file']
            assert Path(name).name==name, 'snapshot path must stay inside its directory'
            raw=(snapshot/name).read_bytes()
            assert hashlib.sha256(raw).hexdigest()==source['sha256'], 'field snapshot hash mismatch'
            (root/name).write_bytes(raw)
        metadata=json.loads((root/'metadata.json').read_text())
        metadata_source=frozen['metadataSource']
    else:
        metadata,metadata_source=fetch(METADATA,root/'metadata.json')
    columns={c.get('fieldName'):c for c in metadata['columns']}
    assert 'feet' in columns['local_y']['description'] and 'feet/second' in columns['v_vel']['description']
    assert metadata.get('licenseId')=='CC_30_BY_SA'
    query=API+'?'+urlencode({'$where':PROTOCOL['candidateQuery'],'$limit':PROTOCOL['candidateLimit']})
    if snapshot:
        candidates=json.loads((root/'candidates.json').read_text())
        candidate_source=frozen['candidateSource']
    else:
        candidates,candidate_source=fetch(query,root/'candidates.json')
    candidates=sorted(candidates,key=lambda r:(int(r['global_time']),int(r['vehicle_id'])))
    candidates=list({(r['vehicle_id'],int(r['global_time'])//900_000):r for r in reversed(candidates)}.values())
    candidates.sort(key=lambda r:(int(r['global_time']),int(r['vehicle_id'])))
    selected,rejected=[],[]
    if snapshot:
        for subject in frozen['selected']:
            data=quality_control(json.loads((root/subject['source']['file']).read_text()),subject['startMilliseconds'])
            selected.append(dict(subject=subject['subject'],startMilliseconds=subject['startMilliseconds'],
                                 source=subject['source'],**data))
        rejected=frozen['rejected']
    for index,candidate in enumerate([] if snapshot else candidates[:PROTOCOL['maxCandidates']]):
        if len(selected)>=PROTOCOL['targetSubjects']: break
        subject,start=int(candidate['vehicle_id']),int(candidate['global_time'])
        url=API+'?'+urlencode({'$where':f"location='us-101' and vehicle_id='{subject}' and global_time>={start} and global_time<={start+11_000}",
                              '$order':'global_time','$limit':2000})
        source=None
        try:
            rows,source=fetch(url,root/f'subject-{index}.json')
            if len(rows)>=2000: raise ValueError('query may be truncated')
            data=quality_control(rows,start)
        except Exception as error:
            rejected.append(dict(subject=subject,startMilliseconds=start,reason=str(error),source=source))
            continue
        selected.append(dict(subject=subject,startMilliseconds=start,source=source,**data))
    write_json(root/'quality-control.json',dict(metadataSource=metadata_source,candidateSource=candidate_source,
        selected=selected,rejected=rejected,protocol=PROTOCOL,attribution=metadata['attribution'],licenseId=metadata['licenseId']))
    results=[]
    for index,subject in enumerate(selected):
        path=root/('forecast-'+str(index))
        speed=subject['initialSpeedMps']
        parameters=dict(roadLengths=[1000.0],departuresSeconds=[0],initialSpeedsMps=[speed],speedLimitKmh=speed*3.6,
            physicsStepSeconds=0.1,runtimeTickSeconds=0.1,carryRemainder=True,horizonSeconds=10,seed=42)
        canonical=generate(path,'free',False,'straight',parameters)
        write_json(path/'empirical-initialization.json',parameters)
        extra=path/'reporting.conf';extra.write_text('htc.report-manager.kafka.micro-update-report-every=1\n')
        command=[sys.executable,str(Path(__file__).with_name('run_cluster_validation.py')),'--jar',str(args.jar.resolve()),
            '--simulation',str(path/'discrete-event.json'),'--output',str(path/'htc'),'--nodes','1',
            '--base-port',str(args.base_port+index*100),'--timeout','240','--extra-config',str(extra)]
        write_json(path/'command.json',command)
        with (path/'runner.log').open('w') as log: result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
        assert result.returncode==0, 'field forecast engine failure'
        runner=json.loads((path/'htc/result.json').read_text())
        assert runner['engineReportedStop'] and not runner['runtimeFailed']
        log=(path/'htc/runtime.log').read_text(errors='replace')
        assert not any(marker in log for marker in ('[ERROR]','[STALL','[GTM-STALL]','[RUNTIME-STALL]'))
        reports=read_htc(path/'htc/reports')
        samples=trajectory(reports,canonical)
        identity=canonical['vehicles'][0]
        differences=[]
        for observed in subject['observations']:
            predicted=samples.get((identity,observed['timeSeconds']))
            assert predicted is not None, 'missing forecast sample'
            differences.append((predicted['position']-observed['positionM'],predicted['speed']-observed['speedMps']))
        terminal=[r['data'] for r in reports if r['event_type']=='sumo_tripinfo']
        assert len(terminal)==1 and terminal[0].get('horizon_truncated') and terminal[0]['arrival']==-1
        record=dict(subject=subject['subject'],startMilliseconds=subject['startMilliseconds'],
            initializationSpeedMps=speed,forecastSamples=len(differences),
            positionRmseM=math.sqrt(statistics.mean(p*p for p,v in differences)),
            speedRmseMps=math.sqrt(statistics.mean(v*v for p,v in differences)),
            endpointPositionBiasM=differences[-1][0],quality=subject['quality'],
            runner=runner,source=subject['source'],model='HTC isolated constant-cruising-speed MICRO forecast initialized from a disjoint 1 s prefix',
            empiricalValidityEstablished=False)
        results.append(record)
        write_json(path/'empirical-metrics.json',record)
        print(json.dumps(record),flush=True)
    summary=dict(pilotCompleted=len(results)==PROTOCOL['targetSubjects'],empiricalValidityEstablished=False,
        protocol=PROTOCOL,metadataSource=metadata_source,candidateSource=candidate_source,
        licenseId=metadata['licenseId'],attribution=metadata['attribution'],results=results,rejected=rejected,
        limitations=['Exploratory three-subject isolated free-flow forecast; no interaction, queue, junction or network validation.',
            'No field-derived accuracy tolerance or global behavioral calibration.',
            'NGSIM annotation/position inconsistencies are reported, not treated as exact ground truth.',
            'Selection conditions on absence of a recorded leader and lane changes throughout the prediction interval.'])
    write_json(root/'pilot.json',summary)
    return 0 if summary['pilotCompleted'] else 1


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar',required=True,type=Path)
    parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--base-port',required=True,type=int)
    parser.add_argument('--snapshot',type=Path,help='Re-run a frozen, hash-checked observation snapshot without querying the live dataset')
    raise SystemExit(run(parser.parse_args()))


if __name__=='__main__': main()
