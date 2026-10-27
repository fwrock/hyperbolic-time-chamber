#!/usr/bin/env python3
"""Retain a predeclared RSS-only supplement after detecting fork high-water contamination.

Original wall/CPU/acceptance measurements are immutable. GNU time forks SUMO from
its small post-exec process, avoiding the large Python launcher's inherited RSS.
"""
import argparse
from datetime import datetime,timezone
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET


def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def write(p,v):Path(p).write_text(json.dumps(v,indent=2)+'\n')


def run(study):
    base=json.loads((study/'manifest.json').read_text())
    root=study/'sumo-rss-audit';root.mkdir(exist_ok=False)
    jobs=[v for v in base['order'] if v['config'].endswith('-sumo')]
    manifest=dict(date=datetime.now(timezone.utc).isoformat(),purpose='RSS measurement repair only; original performance and acceptance runs remain unchanged',
        reason='wait4 high-water includes the large Python process memory inherited before exec; direct SUMO RSS is not identifiable from that value',
        parentManifestSha256=sha(study/'manifest.json'),harnessSha256=sha(__file__),
        executable='/usr/bin/time',timeSha256=sha('/usr/bin/time'),cpuIds=base['cpuIds'],order=jobs,
        timeoutSeconds=300,measuredRepetitions=5,warmupRepetitions=1,
        originalMetricsPreserved=['wallSeconds','cpuSeconds','completionCoverage','committed report parity'])
    write(root/'manifest.json',manifest);write(root/'manifest-seal.json',dict(sha256=sha(root/'manifest.json')))
    records=[]
    for job in jobs:
        case=job['config'].split('-',1)[0]
        output=root/f"r{job['repeat']}-{case}";output.mkdir()
        cmd=['/usr/bin/time','-f','{"cpuUserSeconds":%U,"cpuSystemSeconds":%S,"peakRssKiB":%M,"elapsedSeconds":%e,"exitStatus":%x}',
             '-o',str(output/'resource.json'),'taskset','-c',','.join(map(str,base['cpuIds'])),
             'sumo','-c',str(study/'inputs'/case/'sumo/scenario.sumocfg'),'--seed','42',
             '--fcd-output',str(output/'fcd.xml'),'--tripinfo-output',str(output/'tripinfo.xml'),
             '--summary-output',str(output/'summary.xml'),'--duration-log.statistics','true','--no-step-log','true']
        write(output/'command.json',cmd)
        with (output/'run.log').open('w') as stream:
            result=subprocess.run(cmd,stdout=stream,stderr=subprocess.STDOUT,timeout=300)
        resource=json.loads((output/'resource.json').read_text())
        expected=ET.parse(study/'runs'/f"r{job['repeat']}-{case}-sumo/sumo/tripinfo.xml").getroot().findall('tripinfo')
        actual=ET.parse(output/'tripinfo.xml').getroot().findall('tripinfo')
        def physical(rows):return sorted((v.get('id'),v.get('depart'),v.get('arrival'),v.get('duration'),v.get('routeLength')) for v in rows)
        passed=result.returncode==0 and physical(expected)==physical(actual)
        records.append(dict(case=case,repeat=job['repeat'],warmup=job['warmup'],passed=passed,
            resource=resource,command=cmd,resourceSha256=sha(output/'resource.json')))
    write(root/'results.json',dict(records=records,allAttemptsAccountedFor=len(records)==len(jobs),
        allTripRecordsExact=all(r['passed'] for r in records),manifestSha256=sha(root/'manifest.json')))
    assert all(r['passed'] for r in records)
    print(json.dumps(records,indent=2))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--study',type=Path,required=True)
    run(p.parse_args().study.resolve())
