#!/usr/bin/env python3
"""Run frozen MICRO experiments; retain failed scientific criteria separately from engine failures."""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import random
import subprocess
import sys

from sumo_scientific_metrics import traffic_metrics, paired_seed_summary


def write(path, value):
    path.write_text(json.dumps(value,indent=2,allow_nan=False)+'\n')


def experiment_cases(protocol, stage):
    defaults=protocol['defaultParameters']
    if stage=='held-out':
        return [(dict(defaults,**case),policy) for case in protocol['heldOutCases']
                for policy in ('discrete-event','time-warp')]
    if stage=='stochastic':
        spec=protocol['stochasticReplications']
        jobs=[]
        for seed in spec['seeds']:
            rng=random.Random(seed)
            departures=[0.0]
            for _ in range(spec['vehicles']-1):
                headway=spec['headwaySeconds']+rng.uniform(-spec['headwayJitterSeconds'],spec['headwayJitterSeconds'])
                departures.append(round(departures[-1]+headway,1))
            case=dict(defaults,**{k:spec[k] for k in ('case','turn','junctionGeometry','roadLengths')},
                      id=spec['id']+'-'+str(seed),seed=seed,departuresSeconds=departures)
            jobs.append((case,'discrete-event'))
            if seed==spec['seeds'][0]: jobs.append((case,'time-warp'))
        return jobs
    spec=protocol['convergence']
    jobs=[]
    for quantum in spec['runtimeTickSeconds']:
        case=dict(defaults,id='convergence-'+str(quantum),case=spec['case'],
            roadLengths=spec['roadLengths'],departuresSeconds=spec['departuresSeconds'],
            runtimeTickSeconds=quantum,carryRemainder=False,
            comparisonStepSeconds=1.0)
        jobs.append((case,'discrete-event'))
    carried=dict(jobs[-1][0],id='convergence-carry',carryRemainder=True)
    jobs.extend((carried,p) for p in ('discrete-event','time-warp'))
    return jobs


def run(args):
    root=args.output.resolve()
    root.mkdir(parents=True,exist_ok=False)
    protocol_bytes=args.protocol.read_bytes()
    protocol=json.loads(protocol_bytes)
    digest=hashlib.sha256(protocol_bytes).hexdigest()
    (root/'protocol.json').write_bytes(protocol_bytes)
    jobs=experiment_cases(protocol,args.stage)
    def execute(item):
        index,(case,policy)=item
        suffix='de' if policy=='discrete-event' else 'tw'
        name=case['id']+'-'+suffix
        parameters=dict(case,protocolSha256=digest,
                        comparisonStepSeconds=case.get('comparisonStepSeconds',protocol['acceptanceCriteria']['comparisonSamplingSeconds']))
        path=root/(name+'-parameters.json')
        write(path,parameters)
        command=[sys.executable,str(Path(__file__).with_name('validate_sumo_micro.py')),
            '--jar',str(args.jar.resolve()),'--output',str(root/name),'--case',case['case'],
            '--policy',policy,'--base-port',str(args.base_port+index*100),'--timeout',str(args.timeout),
            '--turn',case.get('turn','straight'),'--experiment',str(path),'--measure-only']
        if case.get('junctionGeometry',False): command.append('--junction-geometry')
        write(root/(name+'-command.json'),command)
        with (root/(name+'.log')).open('w') as log:
            result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
        if result.returncode:
            record=dict(name=name,stage=args.stage,policy=policy,seed=case['seed'],measurementValid=False,
                        passed=False,exitCode=result.returncode,command=command)
        else:
            comparison=json.loads((root/name/'comparison.json').read_text())
            metrics=traffic_metrics(root/name,comparison,protocol)
            record=dict(name=name,stage=args.stage,policy=policy,seed=case['seed'],measurementValid=True,
                passed=metrics['passed'],regressionChecksPassed=comparison['passed'],
                regressionChecks=comparison['modelChecks'],metrics=metrics,command=command,
                canonical=comparison['canonical'],jarSha256=comparison['jarSha256'],
                rollbacks=comparison['rollbacks'],inputSha256=comparison['inputSha256'])
            write(root/name/'scientific-metrics.json',metrics)
        print(json.dumps(dict(name=name,measurementValid=record['measurementValid'],passed=record['passed'])),flush=True)
        return record
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.parallel) as pool:
        records=list(pool.map(execute,enumerate(jobs)))
    comparisons=[]
    for case,policy in jobs:
        if policy!='time-warp': continue
        a,b=root/(case['id']+'-de'),root/(case['id']+'-tw')
        output=root/(case['id']+'-policy-comparison.json')
        command=[sys.executable,str(Path(__file__).with_name('compare_policy_reports.py')),
                 '--conservative',str(a/'htc/reports'),'--optimistic',str(b/'htc/reports'),'--output',str(output)]
        result=subprocess.run(command,capture_output=True,text=True)
        comparisons.append(dict(case=case['id'],**(json.loads(output.read_text()) if output.exists()
                           else dict(passed=False,error=result.stdout+result.stderr))))
    record=dict(stage=args.stage,protocolSha256=digest,protocolVersion=protocol['version'],
        allMeasurementsValid=all(r['measurementValid'] for r in records),
        allScientificCriteriaPassed=all(r['passed'] for r in records),
        allPolicyComparisonsPassed=all(r['passed'] for r in comparisons),
        experiments=records,policyComparisons=comparisons)
    if args.stage=='stochastic' and record['allMeasurementsValid']:
        record['pairedSeedInference']=paired_seed_summary([r for r in records if r['policy']=='discrete-event'])
    write(root/'campaign.json',record)
    # A failed equivalence hypothesis remains a valid scientific result; engine/measurement failures do not.
    return 0 if record['allMeasurementsValid'] and record['allPolicyComparisonsPassed'] else 1


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--protocol',type=Path,default=Path(__file__).with_name('sumo_scientific_protocol_v1.json'))
    parser.add_argument('--stage',choices=('convergence','held-out','stochastic'),required=True)
    parser.add_argument('--base-port',type=int,required=True)
    parser.add_argument('--parallel',type=int,choices=(1,2,3),default=3)
    parser.add_argument('--timeout',type=int,default=360)
    args=parser.parse_args()
    raise SystemExit(run(args))


if __name__=='__main__': main()
