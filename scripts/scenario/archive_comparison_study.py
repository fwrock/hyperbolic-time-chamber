#!/usr/bin/env python3
"""Retain a complete finished comparison campaign with hashes and source provenance."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zipfile

REPO=Path(__file__).resolve().parents[2]


def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def write(p,v):Path(p).write_text(json.dumps(v,indent=2,allow_nan=False)+'\n')


def archive(study,output,report):
    output.mkdir(parents=True,exist_ok=True)
    ledger=json.loads((study/'results.json').read_text())
    assert ledger['allAttemptsAccountedFor']
    manifest=json.loads((study/'manifest.json').read_text())
    assert ledger['manifestSha256']==sha(study/'manifest.json')
    for p,h in manifest['inputs'].items():assert sha(study/p)==h,p
    for p,h in manifest['sourceSha256'].items():assert sha(REPO/p)==h,p
    executable=REPO/'artifacts/htc-comparison-2026-10-27/htc-comparison.jar'
    executable.parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(manifest['jar'],executable)
    assert sha(executable)==manifest['jarSha256']
    for name in ('manifest.json','manifest-seal.json','results.json'):
        shutil.copyfile(study/name,output/name)
    source=output/'source-snapshot.zip'
    tracked=subprocess.check_output(['git','ls-files','-z'],cwd=REPO).decode().split('\0')
    candidates={REPO/p for p in tracked if p and (p.startswith(('src/','project/','scripts/scenario/','.agents/')) or p in ('build.sbt','AGENTS.md','.gitignore'))}
    candidates|={p for root in ('src','scripts/scenario') for p in (REPO/root).rglob('*')
                  if p.is_file() and p.suffix in ('.scala','.py','.json','.conf','.sbt','.proto','.avsc') and '__pycache__' not in p.parts}
    candidates|={REPO/'scripts/scenario/archive_comparison_study.py',REPO/'scripts/scenario/build_comparison_report.py'}
    candidates|={p for p in (REPO/'docs/validation').glob('*.json')}
    candidates|={REPO/'docs/validation/figures/micro-field-v2-2026-10-26.png'}
    candidates|={p for p in (REPO/'docs').glob('*.md')}
    with zipfile.ZipFile(source,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for p in sorted(candidates):
            if p.is_file():z.write(p,str(p.relative_to(REPO)))
        diff=subprocess.check_output(['git','diff','--binary'],cwd=REPO)
        z.writestr('workspace-uncommitted.patch',diff)
        z.writestr('git-head.txt',subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO))
    engineering=study/'engineering';engineering.mkdir(exist_ok=True)
    for path in [Path('/tmp/htc-comparison-build-20261027.log'),Path('/tmp/htc-comparison-python-tests.log'),Path('/tmp/htc-comparison-campaign-20261027.log'),Path('/tmp/htc-sumo-memory-audit-20261027.log')]:
        if path.exists():shutil.copyfile(path,engineering/path.name)
    preparation=study/'preparation';preparation.mkdir(exist_ok=True)
    previous=Path('/tmp/htc-comparison-preparation-failed-20261027/inputs/signal/sumo/netconvert.log')
    if previous.exists():shutil.copyfile(previous,preparation/'failed-netconvert.log')
    write(preparation/'events.json',dict(beforeFrozenManifest=True,simulationAttempts=0,events=[
        'Repository output directory was not writable; selected a fresh temporary campaign directory.',
        'Multi-case generator shared an ID registry and failed netconvert; resetting it per independent fixture corrected generation before freezing.']))
    raw=output/'raw-evidence.zip'
    members={}
    with zipfile.ZipFile(raw,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for p in sorted(study.rglob('*')):
            if not p.is_file():continue
            rel=str(p.relative_to(study));members[rel]=sha(p);z.write(p,rel)
        z.writestr('member-sha256.json',json.dumps(members,indent=2))
        z.writestr('README.txt','Complete bounded HTC comparison campaign. r0 is excluded warm-up; r1-r5 measured.\nThe manifest is frozen and hashed. Paths inside configs preserve the original execution paths.\nRelocating inputs requires a new explicit path mapping and executable identity; do not silently overwrite raw configs.\n')
    for p in (raw,source):
        with zipfile.ZipFile(p) as z:assert z.testzip() is None,p
    record=dict(date=datetime.now(timezone.utc).isoformat(),allAttemptsAccountedFor=True,
        attempts=ledger['attempts'],manifestSha256=sha(output/'manifest.json'),
        archives={p.name:dict(path=str(p.relative_to(REPO)),sha256=sha(p),bytes=p.stat().st_size) for p in (raw,source)},
        durableExecutable=dict(path=str(executable.relative_to(REPO)),sha256=sha(executable),bytes=executable.stat().st_size),
        reportArtifacts={str(p.relative_to(REPO)):dict(sha256=sha(p),bytes=p.stat().st_size) for p in sorted(report.rglob('*'))
            if p.is_file() and not p.is_symlink() and 'analysis-views' not in p.parts},
        testEvidence={str(p):sha(p) for p in [Path('/tmp/htc-comparison-build-20261027.log'),Path('/tmp/htc-comparison-python-tests.log')] if p.exists()},
        reportSources={str(p.relative_to(REPO)):sha(p) for p in [REPO/'scripts/scenario/build_comparison_report.py',REPO/'scripts/scenario/archive_comparison_study.py']})
    write(output/'evidence-index.json',record)
    print(json.dumps(record,indent=2))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--study',required=True,type=Path);p.add_argument('--output',required=True,type=Path);p.add_argument('--report',required=True,type=Path)
    args=p.parse_args();archive(args.study.resolve(),args.output.resolve(),args.report.resolve())
