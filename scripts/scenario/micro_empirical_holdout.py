#!/usr/bin/env python3
"""Seal and execute a prospective conditional-component study on one unseen recording."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import gzip
import importlib.util
import io
import json
from pathlib import Path
import random
import shutil
import statistics
import subprocess
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from micro_empirical_data_audit import SOURCE, sha, unique_rows, write
from micro_empirical_pilot import HERE, WORKSPACE, causal_rigid_samples, measurement_prefixes, select


def verify_partition(rows, protocol):
    first, last = protocol['frameRange']
    for row in rows:
        frame = int(row['frame_id'])
        if (row['location'] != protocol['location'] or
                int(row['global_time']) - 100 * frame != protocol['recordingOrigin'] or
                not first <= frame <= last):
            raise ValueError('API response violates frozen holdout partition')


def acquire(protocol, root):
    first, last = protocol['frameRange']
    where = (f"location='{protocol['location']}' and global_time - 100 * frame_id = "
             f"{protocol['recordingOrigin']} and frame_id >= {first} and frame_id <= {last}")
    rows, receipts = [], []
    for page in range(12):
        url = SOURCE + '?' + urlencode({'$where': where, '$order': 'frame_id,vehicle_id',
                                         '$limit': 50000, '$offset': page * 50000})
        with urlopen(Request(url, headers={'Accept-Encoding': 'gzip'}), timeout=60) as response:
            body = response.read(30_000_001)
            encoding = response.headers.get('Content-Encoding', '')
        if len(body) > 30_000_000:
            raise ValueError('Source response exceeds fixed transport budget')
        raw = gzip.GzipFile(fileobj=io.BytesIO(body)).read(30_000_001) if encoding == 'gzip' else body
        if len(raw) > 30_000_000:
            raise ValueError('Source response exceeds fixed decoded budget')
        path = root / f'page-{page:03d}.json'
        path.write_bytes(raw)
        receipts.append(dict(url=url, file=path.name, sha256=sha(path), bytes=len(raw),
                             content_encoding=encoding, retrieved_utc=datetime.now(timezone.utc).isoformat()))
        write(root / 'requests.json', receipts)
        values = json.loads(raw)
        if not isinstance(values, list) or len(values) > 50000:
            raise ValueError('Unexpected source response')
        verify_partition(values, protocol)
        rows.extend(values)
        print(f'Reserved page {page}: {len(values)} rows', flush=True)
        if len(values) < 50000:
            unique, duplicates = unique_rows(rows)
            write(root / 'acquisition-audit.json', dict(raw_rows=len(rows), unique_rows=len(unique),
                exact_duplicates_removed=duplicates, recording_origin=protocol['recordingOrigin']))
            return unique
    raise ValueError('Source acquisition exceeds frozen page budget')


def upper_bound(values, seed, draws=10000):
    if not values:
        return None
    rng = random.Random(seed)
    bootstrap = sorted(statistics.fmean(rng.choices(values, k=len(values))) for _ in range(draws))
    return bootstrap[int((1 - .05 / 3) * (draws - 1))]


def assess(cases, followers, protocol):
    outcomes = {}
    for kind in ['following', 'queue']:
        primary = [c for c in cases if c['model'] == 'HTC' and c['variant'] == 'deterministic' and c['kind'] == kind]
        metric = 'position_rmse_m' if kind == 'following' else 'headway_mae_s'
        comparisons = {}
        models = ['Constant velocity', 'Causal rigid spacing'] if kind == 'following' else ['Causal rigid spacing']
        for model in models:
            reference = {c['episode']: c for c in cases if c['model'] == model and c['kind'] == kind}
            pairs = [(c[metric], reference[c['episode']][metric]) for c in primary]
            complete = bool(pairs) and all(a is not None and b is not None for a, b in pairs)
            delta = [a - b for a, b in pairs] if complete else []
            bound = upper_bound(delta, protocol['bootstrapSeed'])
            comparisons[model] = dict(mean_difference=statistics.fmean(delta) if delta else None,
                                     upper_one_sided_bonferroni=bound, available=complete,
                                     skill_passed=bound is not None and bound < 0)
        records = [f for f in followers if f['model'] == 'HTC' and f['variant'] == 'deterministic' and f['kind'] == kind]
        spacing = bool(records) and min(r['minimum_gap_m'] for r in records) >= 0
        data_gate = len(primary) >= protocol['requiredEpisodes'][kind]
        coverage = bool(primary) and (kind == 'following' or all(c['crossing_coverage'] == 1 for c in primary))
        outcomes[kind] = dict(episodes=len(primary), data_gate_passed=data_gate,
                             crossing_or_prediction_coverage_passed=coverage, nonoverlap_passed=spacing,
                             comparisons=comparisons,
                             conditional_skill_gate_passed=data_gate and coverage and spacing and
                             all(c['skill_passed'] for c in comparisons.values()))
    return outcomes


def run(args):
    protocol = json.loads(args.protocol.read_text())
    if protocol['classification'] != 'Prospective conditional predictive-skill study; not general empirical adequacy':
        raise ValueError('Unexpected scientific claim')
    if protocol['recordingOrigin'] == protocol['developmentRecordingOrigin']:
        raise ValueError('Development and holdout recording overlap')
    if not protocol['onePerKindPerBlock'] or protocol['blockFrames'] != 900:
        raise ValueError('Scientific resampling requires at most one episode per kind/90-second block')
    metadata = json.loads(args.metadata.read_text())
    if sha(args.metadata) != protocol['metadataSha256'] or metadata.get('licenseId') != protocol['license']:
        raise ValueError('Source metadata/license does not match the protocol')
    exposure = json.loads(args.exposure.read_text())
    if sha(args.exposure) != protocol['exposureLedgerSha256']:
        raise ValueError('Exposure ledger does not match the frozen protocol')
    if any(k[0] == protocol['location'] and k[1] == protocol['recordingOrigin'] for k in exposure['identities']):
        raise ValueError('Proposed holdout already appears in exposure ledger')
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    try:
        (root / 'protocol.json').write_bytes(args.protocol.read_bytes())
        (root / 'prior-exposure.json').write_bytes(args.exposure.read_bytes())
        (root / 'metadata.json').write_bytes(args.metadata.read_bytes())
        shutil.copy2(args.jar, root / 'engine.jar')
        source = root / 'sources'; source.mkdir()
        paths = [Path(__file__), HERE / 'micro_empirical_data_audit.py', HERE / 'micro_empirical_pilot.py',
                 HERE / 'validate_micro_field.py', HERE / 'validate_micro_field_v2.py',
                 HERE / 'audit_micro_field_inputs.py', WORKSPACE / 'tools/ngsim-validation/metrics.py']
        for path in paths:
            shutil.copy2(path, source / path.name)
        write(root / 'pre-acquisition-seal.json', dict(created_utc=datetime.now(timezone.utc).isoformat(),
            protocol_sha256=sha(root / 'protocol.json'), engine_sha256=sha(root / 'engine.jar'),
            exposure_sha256=sha(root / 'prior-exposure.json'),
            source_sha256={str(p): sha(p) for p in paths},
            acquisition_started=False, recording_not_in_supplied_exposure_ledger=True,
            exposure_scope='Retained workspace sources and declared development acquisitions; external exposure cannot be verified'))
        # Everything governing eligibility, physics, metrics and gates is sealed before
        # the first individual holdout trajectory is requested.
        rows = acquire(protocol, root)
        episodes, rejected = select(rows, {'recording_origin': protocol['recordingOrigin']}, protocol)
        for e in episodes:
            e.update(id=e['id'].replace('dev-', 'reserved-', 1), cohort='peachtree-heldout', primary=True)
            e['source_episode_id'] = e['id']
            measurement_prefixes(e, rows)
        write(root / 'episodes.json', episodes); write(root / 'rejections.json', rejected)
        write(root / 'selection-seal.json', dict(created_utc=datetime.now(timezone.utc).isoformat(),
            episodes_sha256=sha(root / 'episodes.json'), rejections_sha256=sha(root / 'rejections.json')))
        if not episodes:
            raise ValueError('No eligible episodes; retain an inconclusive data gate')
        metrics_path = source / 'metrics.py'
        spec = importlib.util.spec_from_file_location('sealed_field_metrics', metrics_path)
        metrics = importlib.util.module_from_spec(spec); spec.loader.exec_module(metrics)
        followers, cases, series = [], [], []
        for variant in protocol['variants']:
            for seed in variant['seeds']:
                tag = f"{variant['id']}-{seed}"; folder = root / tag; folder.mkdir()
                inputs = [dict(id=e['id'], followers=e['followers'], leader=e['leader'],
                    speedLimitKmh=protocol['physics']['speedLimitKmh'], sigma=variant['sigma'], randomSeed=seed,
                    maxAcceleration=protocol['physics']['maxAccelerationMps2'],
                    maxDeceleration=protocol['physics']['maxDecelerationMps2'],
                    minGap=protocol['physics']['minGapM'], reactionTime=protocol['physics']['reactionSeconds']) for e in episodes]
                write(folder / 'input.json', dict(episodes=inputs))
                command = ['java', '-XX:ActiveProcessorCount=2', '-Xmx1g', '-cp', str(root / 'engine.jar'),
                    'org.interscity.htc.model.hybrid.validation.MicroFieldReplay', str(folder / 'input.json')]
                write(folder / 'command.json', command + [str(folder / 'predictions.json')])
                for name in ['predictions.json', 'repeat.json'] if seed == 42 else ['predictions.json']:
                    with (folder / (name + '.log')).open('w') as log:
                        subprocess.run(command + [str(folder / name)], stdout=log, stderr=subprocess.STDOUT,
                                       timeout=120, check=True)
                predictions = json.loads((folder / 'predictions.json').read_text())
                if seed == 42 and predictions != json.loads((folder / 'repeat.json').read_text()):
                    raise ValueError('Fixed-seed repeat failure')
                f, c, s = metrics.evaluate(episodes, predictions, 'HTC', variant['id'], seed)
                followers.extend(f); cases.extend(c); series.extend(s)
        for model in ['Constant velocity', 'Causal rigid spacing']:
            predictions = metrics.baselines(episodes, model) if model == 'Constant velocity' else causal_rigid_samples(episodes)
            f, c, s = metrics.evaluate(episodes, predictions, model, 'baseline', 0)
            followers.extend(f); cases.extend(c); series.extend(s)
        outcomes = assess(cases, followers, protocol)
        result = dict(classification=protocol['classification'], episodes=len(episodes),
            counts=dict(Counter(e['kind'] for e in episodes)), rejected=len(rejected),
            acquired_rows=len(rows), acquired_vehicles=len({r['vehicle_id'] for r in rows}),
            fixed_seed_repeat_checks_passed=2, summary=metrics.summaries(cases), outcomes=outcomes,
            cases=cases, followers=followers, general_empirical_adequacy_established=False,
            absolute_application_tolerances_assessed=False, actor_runtime_or_corridor_validated=False)
        write(root / 'results.json', result); write(root / 'series.json', series)
        write(root / 'status.json', dict(state='complete', interpretation='See separate data/skill gates; completion is not scientific acceptance'))
        print(json.dumps({k: v for k, v in result.items() if k not in ['cases', 'followers']}, indent=2))
    except Exception as error:
        write(root / 'status.json', dict(state='failed', error_type=type(error).__name__, error=str(error)))
        raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['protocol', 'exposure', 'metadata', 'jar', 'output']:
        parser.add_argument('--' + name, type=Path, required=True)
    run(parser.parse_args())
