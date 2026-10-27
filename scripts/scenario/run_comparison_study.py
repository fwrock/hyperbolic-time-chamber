#!/usr/bin/env python3
"""Freeze and run the bounded HTC/SUMO/policy/rebalancing report campaign."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import random
import shutil
import subprocess
import sys
import time

from benchmark_metrics import ProcessMonitor
from compare_policy_reports import reports
from validate_balancing_traffic import validate as validate_balance
from validate_sumo_micro import generate, compare, model_checks, input_hashes

SCRIPTS = Path(__file__).resolve().parent
REPO = SCRIPTS.parent.parent


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def write(path, value):
    Path(path).write_text(json.dumps(value, indent=2, allow_nan=False)+'\n')


def command(args, log, timeout=None):
    with Path(log).open('w') as out:
        return subprocess.run(list(map(str,args)),stdout=out,stderr=subprocess.STDOUT,timeout=timeout).returncode


def freeze(root, jar):
    root.mkdir(parents=True,exist_ok=False)
    inputs=root/'inputs'
    inputs.mkdir()
    protocol=json.loads((SCRIPTS/'sumo_scientific_protocol_v2.json').read_text())
    params=dict(protocol['defaultParameters'],comparisonStepSeconds=.1)
    cases={
        'free':dict(params,case='free'),
        'following':dict(params,case='following'),
        'signal':dict(params,**next(v for v in protocol['heldOutCases'] if v['id']=='v2-signal-right')),
    }
    for label, experiment in cases.items():
        import generate_hybrid_scenario as hybrid
        hybrid._ID_REGISTRY = hybrid.IdRegistry()
        generate(inputs/label,experiment['case'],experiment.get('junctionGeometry',False),
                 experiment.get('turn','straight'),experiment)
        write(inputs/label/'experiment.json',experiment)
    for label in ('initial','progressive'):
        args=[sys.executable,SCRIPTS/'generate_balancing_validation_scenario.py','--output-dir',inputs/label]
        if label=='progressive': args+=['--late-cohort']
        code=command(args,root/f'generate-{label}.log')
        assert code==0, f'generation failed {label}'
    configs=[]
    for case in cases:
        configs.append(dict(id=f'{case}-sumo',kind='sumo',case=case,nodes=0,policy='sumo'))
        for nodes in (1,2):
            for policy in ('discrete-event','time-warp'):
                configs.append(dict(id=f'{case}-{policy}-{nodes}',kind='traffic',case=case,nodes=nodes,policy=policy))
    for case in ('initial','progressive'):
        for placement in ('reference','migration'):
            configs.append(dict(id=f'{case}-{placement}',kind='balance',case=case,nodes=2,
                                policy='discrete-event',placement=placement))
    rng=random.Random(20261027)
    order=[]
    for repeat in range(6):
        block=configs.copy();rng.shuffle(block)
        order += [dict(config=v['id'],repeat=repeat,warmup=repeat==0) for v in block]
    cpu_ids=sorted(os.sched_getaffinity(0))[:4]
    assert len(cpu_ids)==4
    source_files=list(SCRIPTS.glob('*.py'))+[REPO/'src/main/scala/core/actor/manager/time/ChannelClosedGlobalTimeManager.scala']
    manifest=dict(version=1,status='frozen-before-campaign',date=datetime.now(timezone.utc).isoformat(),
        purpose='Descriptive local-host performance and correctness comparisons; no new empirical validation',
        jar=str(jar),jarSha256=sha(jar),sourceSha256={str(p.relative_to(REPO)):sha(p) for p in source_files},
        inputs={str(p.relative_to(root)):sha(p) for p in inputs.rglob('*') if p.is_file()},
        cpuIds=cpu_ids,totalMaxHeapGiB=4,timeoutSeconds=300,sampleIntervalSeconds=.25,
        repetitions=5,warmups=1,orderSeed=20261027,configurations=configs,order=order,
        host=dict(platform=platform.platform(),cpu=Path('/proc/cpuinfo').read_text(),
                  memory=Path('/proc/meminfo').read_text(),affinity=sorted(os.sched_getaffinity(0)),
                  java=subprocess.run(['java','-version'],capture_output=True,text=True).stderr,
                  sumo=subprocess.check_output(['sumo','--version'],text=True)),
        definitions=dict(wall='monotonic launch to acknowledged runtime completion plus stop notification, before cleanup',
                         cpu='HTC final live proc counters before teardown; SUMO wait4 user plus system',
                         rss='HTC simultaneous sampled sum; SUMO wait4 process high-water RSS',
                         pair='same repeat, case and output settings',
                         ratios='descriptive, conditional on acceptance; individual values, median and range',
                         failures='retained; no automatic replacement; 300 second limit each',
                         migration='fixed placement and rebalance enabled differ only in rebalance interval'))
    write(root/'manifest.json',manifest)
    write(root/'manifest-seal.json',dict(sha256=sha(root/'manifest.json')))
    return manifest


def sumo_run(root, inputs, cfg, cpu_ids):
    run=root/'sumo';run.mkdir()
    cmd=['taskset','-c',','.join(map(str,cpu_ids)),'sumo','-c',str(inputs/'sumo/scenario.sumocfg'),
         '--seed','42','--fcd-output',str(run/'fcd.xml'),'--tripinfo-output',str(run/'tripinfo.xml'),
         '--summary-output',str(run/'summary.xml'),'--duration-log.statistics','true','--no-step-log','true']
    write(run/'command.json',cmd)
    started=time.monotonic()
    monitor=ProcessMonitor(run,started)
    usage=None
    with (run/'run.log').open('w') as stream:
        p=subprocess.Popen(cmd,stdout=stream,stderr=subprocess.STDOUT,cwd=inputs/'sumo')
        monitor.add(p.pid)
        while time.monotonic()-started<300:
            pid,status,rusage=os.wait4(p.pid,os.WNOHANG)
            if pid:
                p.returncode=os.waitstatus_to_exitcode(status);usage=rusage;break
            time.sleep(.005)
        elapsed=time.monotonic()-started
        m=monitor.finish()
        if p.returncode is None:
            p.kill();p.wait()
    if usage:
        m.update(cpuSeconds=usage.ru_utime+usage.ru_stime,peakAggregateRssBytes=usage.ru_maxrss*1024,
                 cpuMeasurement='wait4 user plus system CPU',memoryMeasurement='wait4 process high-water RSS')
    m['completionSeconds']=elapsed if usage else None
    log=(run/'run.log').read_text()
    canonical=json.loads((inputs/'canonical.json').read_text())
    import xml.etree.ElementTree as ET
    trips=ET.parse(run/'tripinfo.xml').getroot().findall('tripinfo') if usage and p.returncode==0 else []
    coverage={t.get('id') for t in trips}==set(canonical['vehicles']) and all(float(t.get('arrival'))>=0 for t in trips)
    passed=bool(usage and p.returncode==0 and coverage and 'collision' not in log.lower() and 'emergency braking' not in log.lower())
    result=dict(returncode=p.returncode,passed=passed,measurement=m,completionCoverage=coverage)
    write(run/'result.json',result)
    return result


def htc_run(root, inputs, cfg, cpu_ids):
    if cfg['kind']=='balance':
        simulation=inputs/'simulation.json'
        extra=inputs/(cfg['placement']+'.conf')
    else:
        simulation=inputs/(cfg['policy']+'.json')
        extra=root/'reporting.conf'
        extra.write_text('htc.report-manager.kafka.micro-update-report-every=1\n')
    cmd=[sys.executable,SCRIPTS/'run_cluster_validation.py','--jar',MANIFEST['jar'],
         '--simulation',simulation,'--output',root/'htc','--base-port','26000','--nodes',str(cfg['nodes']),
         '--extra-config',extra,'--timeout','300','--measure','--cpu-ids',','.join(map(str,cpu_ids))]
    write(root/'command.json',list(map(str,cmd)))
    code=command(cmd,root/'runner.log',timeout=340)
    result=json.loads((root/'htc/result.json').read_text()) if (root/'htc/result.json').exists() else {}
    log=(root/'htc/runtime.log').read_text() if (root/'htc/runtime.log').exists() else ''
    passed=code==0 and result.get('engineCompleted',False) and not result.get('runtimeFailed',True)
    checks={}
    try:
        assert passed,'runtime or launcher failure'
        for marker in ('[ERROR]','[STALL','[GTM-STALL]','[RUNTIME-STALL]','null state','Replay did not consume all recorded reads'):
            assert marker not in log,marker
        if cfg['kind']=='balance':
            checks=validate_balance(simulation,root/'htc',False,False)
            checks['migrationExercised']=bool(checks['verifiedMigrations'])
            checks['activeMigrationExercised']=bool(checks['activeCarRestores'])
        else:
            assert list((root/'htc/reports').rglob('*.jsonl')),'no reports'
            rows=[json.loads(line) for p in (root/'htc/reports').rglob('*.jsonl') for line in p.read_text().splitlines() if line]
            canonical=json.loads((inputs/'canonical.json').read_text())
            reference=STUDY_ROOT/'runs'/f"r{CURRENT_REPEAT}-{cfg['case']}-sumo"/'sumo'
            assert (reference/'tripinfo.xml').exists(),'reference pending'
            metrics=compare(rows,reference/'fcd.xml',reference/'tripinfo.xml',canonical)
            checks=model_checks(rows,metrics,canonical)
            write(root/'physical-comparison.json',dict(modelChecks=checks,**metrics))
            assert all(checks.values()),'physical model checks failed'
    except (AssertionError,ValueError,KeyError) as error:
        passed=False
        checks['failure']=str(error)
    result.update(passed=passed,physicalChecks=checks,launcherReturncode=code)
    return result


def finalize(root, manifest):
    records=[]
    for scheduled in manifest['order']:
        p=root/'runs'/f"r{scheduled['repeat']}-{scheduled['config']}"/'study-result.json'
        if p.exists(): records.append(json.loads(p.read_text()))
    # Physical validation is deferred until each block's SUMO reference exists.
    for record in records:
        cfg=next(c for c in manifest['configurations'] if c['id']==record['config'])
        if cfg['kind']!='traffic':continue
        run=Path(record['directory']); result=record['result']
        if not result.get('engineCompleted') or result.get('runtimeFailed') or any(result.get('engineErrors',[1])):continue
        try:
            rows=[json.loads(line) for p in (run/'htc/reports').rglob('*.jsonl') for line in p.read_text().splitlines() if line]
            canonical=json.loads((root/'inputs'/cfg['case']/'canonical.json').read_text())
            ref=root/'runs'/f"r{record['repeat']}-{cfg['case']}-sumo"/'sumo'
            metrics=compare(rows,ref/'fcd.xml',ref/'tripinfo.xml',canonical)
            checks=model_checks(rows,metrics,canonical)
            write(run/'physical-comparison.json',dict(modelChecks=checks,**metrics))
            result['physicalChecks']=checks;result['passed']=all(checks.values())
        except (AssertionError,ValueError,KeyError,FileNotFoundError) as error:
            result['passed']=False;result['physicalChecks']={'failure':str(error)}
        write(run/'study-result.json',record)
    parity=[]
    for repeat in range(6):
        for case in ('free','following','signal','initial','progressive'):
            group=[r for r in records if r['repeat']==repeat and r['case']==case and r['kind']!='sumo']
            reference=next((r for r in group if r['config'].endswith('discrete-event-1') or r['config'].endswith('reference')),None)
            if not reference:continue
            try: expected=reports(Path(reference['directory'])/'htc/reports')
            except ValueError:continue
            for row in group:
                try: passed=reports(Path(row['directory'])/'htc/reports')==expected
                except ValueError:passed=False
                parity.append(dict(repeat=repeat,case=case,config=row['config'],reference=reference['config'],passed=passed))
                row['result']['reportParity']=passed
                row['result']['passed']=row['result'].get('passed',False) and passed
                write(Path(row['directory'])/'study-result.json',row)
    for p,h in manifest['inputs'].items():assert sha(root/p)==h,p
    write(root/'results.json',dict(manifestSha256=sha(root/'manifest.json'),records=records,parity=parity,
        attempts=len(records),plannedAttempts=len(manifest['order']),
        allAttemptsAccountedFor=len(records)==len(manifest['order'])))
    return records


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--jar',type=Path)
    parser.add_argument('--freeze',action='store_true')
    parser.add_argument('--run',action='store_true')
    parser.add_argument('--finalize',action='store_true')
    args=parser.parse_args()
    STUDY_ROOT=args.output.resolve()
    if args.freeze:
        assert args.jar
        MANIFEST=freeze(STUDY_ROOT,args.jar.resolve())
    else:MANIFEST=json.loads((STUDY_ROOT/'manifest.json').read_text())
    assert sha(STUDY_ROOT/'manifest.json')==json.loads((STUDY_ROOT/'manifest-seal.json').read_text())['sha256']
    if args.run:
        assert sha(MANIFEST['jar'])==MANIFEST['jarSha256']
        for path,h in MANIFEST['sourceSha256'].items():assert sha(REPO/path)==h,path
        for task in MANIFEST['order']:
            CURRENT_REPEAT=task['repeat']
            cfg=next(c for c in MANIFEST['configurations'] if c['id']==task['config'])
            root=STUDY_ROOT/'runs'/f"r{CURRENT_REPEAT}-{cfg['id']}"
            if (root/'study-result.json').exists():continue
            root.mkdir(parents=True,exist_ok=False)
            print(datetime.now(timezone.utc).isoformat(), 'START',root.name,flush=True)
            started=time.monotonic()
            try:
                result=(sumo_run if cfg['kind']=='sumo' else htc_run)(root,STUDY_ROOT/'inputs'/cfg['case'],cfg,MANIFEST['cpuIds'])
            except Exception as error:
                result=dict(passed=False,exception=f'{type(error).__name__}: {error}')
            record=dict(config=cfg['id'],kind=cfg['kind'],case=cfg['case'],repeat=CURRENT_REPEAT,
                        warmup=task['warmup'],directory=str(root),attemptWallSeconds=time.monotonic()-started,result=result)
            write(root/'study-result.json',record)
            print(datetime.now(timezone.utc).isoformat(),'DONE',root.name,'runtime=',result.get('engineCompleted',result.get('passed')),flush=True)
        assert sha(MANIFEST['jar'])==MANIFEST['jarSha256']
        finalize(STUDY_ROOT,MANIFEST)
    elif args.finalize:finalize(STUDY_ROOT,MANIFEST)
