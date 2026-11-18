#!/usr/bin/env python3
"""Run a development-only conditional MICRO pilot from an audited recording prefix."""
import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess

from micro_empirical_data_audit import sha, summarize, validate_protocol, validate_rows, write
from validate_micro_field import quality
from validate_micro_field_v2 import following_context, queue_release

HERE = Path(__file__).resolve().parent
WORKSPACE = HERE.parents[2]


def causal_rigid_samples(episodes):
    """Use only the head state available at the start of each forecast interval."""
    samples = []
    for episode in episodes:
        initial_head = episode['leader'][0]['position']
        for follower in episode['followers']:
            for tick in range(len(episode['leader'])):
                head = episode['leader'][max(0, tick - 1)]
                displacement = head['position'] - initial_head + head['velocity'] * .1 if tick else 0
                samples.append(dict(episode=episode['id'], vehicle=follower['id'], tick=tick,
                    position=follower['position'] + displacement,
                    velocity=follower['velocity'] if tick == 0 else head['velocity'], acceleration=0.0))
    return samples


def load_audit(root):
    if json.loads((root / 'status.json').read_text())['state'] != 'complete':
        raise ValueError('Development input audit did not complete')
    protocol = json.loads((root / 'protocol.json').read_text())
    validate_protocol(protocol)
    provenance = json.loads((root / 'provenance.json').read_text())
    if sha(root / 'protocol.json') != provenance['protocol_sha256']:
        raise ValueError('Audit protocol changed')
    rows = []
    for receipt in json.loads((root / 'requests.json').read_text()):
        name = receipt['file']
        if Path(name).name != name or sha(root / name) != receipt['sha256']:
            raise ValueError('Invalid development source receipt')
        page = json.loads((root / name).read_text())
        validate_rows(page, protocol)
        rows.extend(page)
    if summarize(rows, protocol) != json.loads((root / 'audit.json').read_text()):
        raise ValueError('Development audit summary changed')
    return rows, protocol


def select(rows, audit, protocol):
    chosen, rejected, used, filled = [], [], set(), set()
    origin = audit.get('recording_origin', audit.get('development_recording_origin'))
    by_vehicle = defaultdict(list)
    for row in rows:
        by_vehicle[int(row['vehicle_id'])].append(row)
    for frame in protocol['candidateFrames']:
        block = (frame - 1) // protocol.get('blockFrames', 900)
        snapshot = {int(r['vehicle_id']): r for r in rows if int(r['frame_id']) == frame}
        for kind, size in [('following', 2), ('queue', 3)]:
            if protocol.get('onePerKindPerBlock') and (kind, block) in filled:
                continue
            attempts = 0
            for subject in sorted(snapshot):
                ids = [subject]
                for _ in range(size - 1):
                    parent = int(snapshot[ids[0]].get('preceding', 0))
                    if parent not in snapshot:
                        break
                    ids.insert(0, parent)
                if len(ids) != size or len(set(ids)) != size or any(i in used for i in ids):
                    continue
                if kind == 'following' and float(snapshot[subject]['v_vel']) * .3048 < 1:
                    continue
                if kind == 'queue' and any(float(snapshot[i]['v_vel']) * .3048 > .2 for i in ids):
                    continue
                attempts += 1
                if attempts > protocol['maximumCandidatesPerKindAndBlock']:
                    break
                name = f'dev-{kind}-frame{frame}-vehicle{subject}'
                start = origin + 100 * frame
                subject_rows = [row for identity in ids for row in by_vehicle[identity]]
                try:
                    if kind == 'following':
                        episode = quality(subject_rows, ids, start, kind, protocol)
                        episode['context'] = following_context(episode, subject_rows, protocol)
                    else:
                        episode = queue_release(subject_rows, ids, start, protocol)
                except (ValueError, KeyError) as error:
                    rejected.append(dict(id=name, ids=ids, reason=str(error)))
                    continue
                episode.update(id=name, source_episode_id=name, cohort='peachtree-development-prefix',
                               primary=False, source_ids=list(ids), recording_origin_ms=origin, block=block)
                chosen.append(episode)
                used.update(ids)
                filled.add((kind, block))
                break
    return chosen, rejected


def measurement_prefixes(episode, rows):
    records = {(int(r['vehicle_id']), int(r['global_time'])): r for r in rows}
    start = episode['startMilliseconds']
    offset = episode['observations'][0][0] - float(records[episode['ids'][0], start + 1000]['local_y']) * .3048
    episode['prefix_positions'] = [
        [float(records[identity, start + 100 * i]['local_y']) * .3048 + offset for i in range(11)]
        for identity in episode['ids']]
    episode['annotated_speeds'] = [
        [float(records[identity, start + 1000 + 100 * i]['v_vel']) * .3048 for i in range(len(episode['leader']))]
        for identity in episode['ids']]


def run(args):
    rows, audit = load_audit(args.audit)
    protocol = json.loads(args.protocol.read_text())
    if protocol.get('classification') != 'Development pilot only; no empirical acceptance decision':
        raise ValueError('This runner cannot evaluate a final validation protocol')
    if protocol['location'] != audit['location'] or protocol['recordingOrigin'] != audit['development_recording_origin']:
        raise ValueError('Pilot protocol does not match the audited development partition')
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    try:
        (root / 'protocol.json').write_bytes(args.protocol.read_bytes())
        shutil.copy2(args.jar, root / 'engine.jar')
        metrics_path = WORKSPACE / 'tools/ngsim-validation/metrics.py'
        write(root / 'provenance.json', dict(created_utc=datetime.now(timezone.utc).isoformat(),
            protocol_sha256=sha(root / 'protocol.json'), engine_sha256=sha(root / 'engine.jar'),
            audit_protocol_sha256=sha(args.audit / 'protocol.json'),
            sources={str(p): sha(p) for p in [Path(__file__), HERE / 'validate_micro_field.py',
                HERE / 'validate_micro_field_v2.py', HERE / 'micro_empirical_data_audit.py', metrics_path]}))
        episodes, rejected = select(rows, audit, protocol)
        for episode in episodes:
            measurement_prefixes(episode, rows)
        write(root / 'episodes.json', episodes)
        write(root / 'rejections.json', rejected)
        write(root / 'selection-seal.json', dict(episodes_sha256=sha(root / 'episodes.json'),
            rejections_sha256=sha(root / 'rejections.json'), protocol_sha256=sha(root / 'protocol.json')))
        if not episodes:
            raise ValueError('No eligible development episodes; retain data failure without changing selection')
        physics = protocol['physics']
        inputs = [dict(id=e['id'], followers=e['followers'], leader=e['leader'],
                       speedLimitKmh=physics['speedLimitKmh'], maxAcceleration=physics['maxAccelerationMps2'],
                       maxDeceleration=physics['maxDecelerationMps2'], minGap=physics['minGapM'],
                       reactionTime=physics['reactionSeconds'], sigma=0, randomSeed=42) for e in episodes]
        write(root / 'input.json', dict(episodes=inputs))
        command = ['java', '-XX:ActiveProcessorCount=2', '-Xmx1g', '-cp', str(root / 'engine.jar'),
                   'org.interscity.htc.model.hybrid.validation.MicroFieldReplay', str(root / 'input.json')]
        write(root / 'command.json', command + [str(root / 'predictions.json')])
        for filename in ['predictions.json', 'repeat-predictions.json']:
            with (root / (filename + '.log')).open('w') as log:
                subprocess.run(command + [str(root / filename)], stdout=log, stderr=subprocess.STDOUT,
                               timeout=120, check=True)
        predictions = json.loads((root / 'predictions.json').read_text())
        if predictions != json.loads((root / 'repeat-predictions.json').read_text()):
            raise ValueError('Exact fixed-seed repeat failed')
        spec = importlib.util.spec_from_file_location('shared_ngsim_measurement', metrics_path)
        metrics = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(metrics)
        followers, cases, series = metrics.evaluate(episodes, predictions, 'HTC', 'deterministic', 42)
        for model in ['Constant velocity', 'Rigid spacing']:
            f, c, s = metrics.evaluate(episodes, metrics.baselines(episodes, model), model, 'baseline', 0)
            followers.extend(f); cases.extend(c); series.extend(s)
        if protocol.get('includeCausalRigidSpacing', False):
            f, c, s = metrics.evaluate(episodes, causal_rigid_samples(episodes), 'Causal rigid spacing', 'baseline', 0)
            followers.extend(f); cases.extend(c); series.extend(s)
        result = dict(classification=protocol['classification'], episodes=len(episodes),
                      counts=dict(Counter(e['kind'] for e in episodes)), rejected=len(rejected),
                      fixed_seed_repeat_passed=True, summary=metrics.summaries(cases),
                      cases=cases, followers=followers, reserved_recording_accessed=False,
                      empirical_acceptance_established=False)
        write(root / 'results.json', result)
        write(root / 'series.json', series)
        write(root / 'status.json', dict(state='complete', development_only=True))
        print(json.dumps({k: v for k, v in result.items() if k not in ['cases', 'followers']}, indent=2))
    except Exception as error:
        write(root / 'status.json', dict(state='failed', error_type=type(error).__name__, error=str(error)))
        raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--audit', type=Path, required=True)
    parser.add_argument('--protocol', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    run(parser.parse_args())
