#!/usr/bin/env python3
"""Run the frozen final protocol end to end: derive parameters on the development recording, run every scenario on the
evaluation recording, check runtime invariance, aggregate with bootstrap intervals and write final-results.json plus a manifest.

Nothing is tuned here. The protocol file and the engine jar are hashed into the manifest. Every result is reported, favourable or not.
"""
import argparse
import hashlib
import json
import platform
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from aggregate_corridor_results import aggregate, paired  # noqa: E402
from analyze_ngsim_actor_approach import canonical, events  # noqa: E402


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


class Runner:
    def __init__(self, root, jar, work, base_port):
        self.root, self.jar, self.work, self.port, self.log = root, jar, work, base_port, []

    def sh(self, args, cwd=None, check=True):
        started = time.time()
        proc = subprocess.run([str(a) for a in args], capture_output=True, text=True, cwd=cwd or self.root)
        self.log.append(dict(command=[str(a) for a in args], returncode=proc.returncode, seconds=round(time.time()-started, 1)))
        if check and proc.returncode != 0:
            raise RuntimeError(f'command failed: {args}\n{proc.stdout[-800:]}\n{proc.stderr[-800:]}')
        return proc

    def py(self, script, *args, **kw):
        return self.sh(['python3', HERE/script, *args], **kw)

    def run_engine(self, simulation, out, nodes, conf):
        self.port += 20
        self.py('run_cluster_validation.py', '--jar', self.jar, '--simulation', simulation, '--output', out, '--base-port', self.port,
                '--nodes', nodes, '--timeout', 1800, '--extra-config', conf, check=False)
        result = json.loads((Path(out)/'result.json').read_text())
        return dict(engine_completed=bool(result['engineCompleted']) and not result['runtimeFailed'] and not any(result['engineErrors']))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--protocol', type=Path, default=HERE/'final_protocol_v1.json')
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--workspace', type=Path, required=True, help='platform root (contains experiments/)')
    p.add_argument('--work', type=Path, required=True, help='new output directory')
    p.add_argument('--base-port', type=int, default=40000)
    a = p.parse_args()
    if a.work.exists(): raise FileExistsError(a.work)
    a.work.mkdir(parents=True)
    protocol = json.loads(a.protocol.read_text())
    root = a.workspace.resolve()
    run = Runner(root, a.jar.resolve(), a.work.resolve(), a.base_port)
    dev, ev = root/protocol['recordings']['development']['pages'], root/protocol['recordings']['evaluation']['pages']
    sig, conf = root/protocol['signals_dir'], root/protocol['reporting_conf']
    ev_suffix = protocol['recordings']['evaluation']['signal_suffix']
    derived = {}
    # 1. parameters derived on the development recording only
    run.py('measure_ngsim_departures.py', 'observed', '--pages', dev, '--output', run.work/'derived-departures.json')
    dep = json.loads((run.work/'derived-departures.json').read_text())
    derived['max_acceleration'] = dict(value=round(dep['m3_equivalent_accel_mps2']['median'], 2), episodes=dep['episodes'], m3=dep['m3_equivalent_accel_mps2'])
    for name, direction in [('desired_speed_nb', '2'), ('desired_speed_sb', '4')]:
        run.py('estimate_ngsim_free_speeds.py', '--pages', dev, '--direction', direction, '--output', run.work/f'derived-{name}.json')
        pop = json.loads((run.work/f'derived-{name}.json').read_text())
        rule = protocol['derived_on_development'][name]['valid_if']
        valid = pop['n'] >= rule['min_n'] and rule['median_between'][0] <= pop['median'] <= rule['median_between'][1]
        derived[name] = dict(valid=valid, n=pop['n'], median=pop['median'], p10=pop['p10'], p90=pop['p90'], path=str(run.work/f'derived-{name}.json') if valid else None)
    run.py('estimate_ngsim_left_gap.py', '--pages', dev, '--signal-sb', sig/f"Peachtree_10th_SB_{protocol['recordings']['development']['signal_suffix']}.txt",
           '--output', run.work/'derived-left-gap.json')
    gap = json.loads((run.work/'derived-left-gap.json').read_text())
    rule = protocol['derived_on_development']['left_critical_gap']
    derived['left_critical_gap'] = dict(value=gap['critical_gap_s'] if gap['permissive_left_turns'] >= rule['min_n'] else rule['fallback_s'],
        permissive_left_turns=gap['permissive_left_turns'], identifiable=gap['permissive_left_turns'] >= rule['min_n'], p15=gap['p15_gap_s'])
    results = dict(derived=derived, scenarios={})
    accel = derived['max_acceleration']['value']
    # 2. scenarios on the evaluation recording
    for sc in protocol['scenarios']:
        outcome = dict(runs=[], invariance=None)
        base_args = ['--raw', ev, '--signals', sig, '--suffix', ev_suffix, '--max-acceleration', accel, *sc['args']]
        if sc['kind'] == 'corridor':
            pop_key = [u for u in sc['uses'] if u.startswith('desired_speed')][0]
            population = derived[pop_key]['path']
            analyses = []
            for seed in sc['seeds']:
                tag = f"{sc['name']}-s{seed}" if seed is not None else sc['name']
                extra = ['--speed-population', population, '--speed-seed', seed] if (population and seed is not None) else []
                run.py('ngsim_corridor_scenario.py', *base_args, *extra, '--output', run.work/f'{tag}-in')
                status = run.run_engine(run.work/f'{tag}-in/simulation.json', run.work/f'{tag}-run', 1, conf)
                outcome['runs'].append(dict(seed=seed, **status))
                if status['engine_completed']:
                    run.py('analyze_corridor_scenario.py', '--scenario', run.work/f'{tag}-in', '--run', run.work/f'{tag}-run', '--output', run.work/f'{tag}-analysis.json')
                    analyses.append(run.work/f'{tag}-analysis.json')
            if analyses:
                outcome['aggregate'] = aggregate(analyses, protocol['bootstrap']['resamples'], protocol['bootstrap']['seed'])
                outcome['categories'] = json.loads((run.work/f"{sc['name']}-s{sc['seeds'][0]}-in/scenario_metadata.json" if sc['seeds'][0] is not None else run.work/f"{sc['name']}-in/scenario_metadata.json").read_text())['categories']
            first = sc['seeds'][0]
            tag0 = f"{sc['name']}-s{first}" if first is not None else sc['name']
            extra0 = ['--speed-population', population, '--speed-seed', first] if (population and first is not None) else []
        else:
            tag0 = sc['name']; extra0 = ['--critical-gap', derived['left_critical_gap']['value']]
            run.py('ngsim_left_turn_slice.py', *base_args, *extra0, '--output', run.work/f'{tag0}-in')
            status = run.run_engine(run.work/f'{tag0}-in/simulation.json', run.work/f'{tag0}-run', 1, conf)
            outcome['runs'].append(dict(seed=None, **status))
            if status['engine_completed']:
                run.py('analyze_left_turn_slice.py', '--scenario', run.work/f'{tag0}-in', '--run', run.work/f'{tag0}-run', '--signals', sig, '--suffix', ev_suffix, '--output', run.work/f'{tag0}-analysis.json')
                outcome['left_turn'] = {k: v for k, v in json.loads((run.work/f'{tag0}-analysis.json').read_text()).items() if k != 'per_vehicle'}
            for ctrl in sc.get('controls', []):
                ct = f"{sc['name']}-{ctrl['name']}"
                run.py('ngsim_left_turn_slice.py', *base_args, *extra0, *ctrl['args'], '--output', run.work/f'{ct}-in')
                cs = run.run_engine(run.work/f'{ct}-in/simulation.json', run.work/f'{ct}-run', 1, conf)
                if cs['engine_completed']:
                    run.py('analyze_left_turn_slice.py', '--scenario', run.work/f'{ct}-in', '--run', run.work/f'{ct}-run', '--signals', sig, '--suffix', ev_suffix, '--output', run.work/f'{ct}-analysis.json')
                    outcome.setdefault('controls', {})[ctrl['name']] = {k: v for k, v in json.loads((run.work/f'{ct}-analysis.json').read_text()).items() if k != 'per_vehicle'}
        # 3. runtime invariance on the first seed
        if sc.get('invariance_variants') and outcome['runs'] and outcome['runs'][0]['engine_completed']:
            reference = canonical(events(run.work/f'{tag0}-run'))
            inv = []
            for v in protocol['runtimes']['invariance']:
                vt = f"{tag0}-{v['policy']}-n{v['nodes']}"
                if sc['kind'] == 'corridor': run.py('ngsim_corridor_scenario.py', *base_args, *extra0, '--policy', v['policy'], '--output', run.work/f'{vt}-in')
                else: run.py('ngsim_left_turn_slice.py', *base_args, *extra0, '--policy', v['policy'], '--output', run.work/f'{vt}-in')
                st = run.run_engine(run.work/f'{vt}-in/simulation.json', run.work/f'{vt}-run', v['nodes'], conf)
                equal = st['engine_completed'] and canonical(events(run.work/f'{vt}-run')) == reference
                inv.append(dict(policy=v['policy'], nodes=v['nodes'], engine_completed=st['engine_completed'], committed_events_equal_to_baseline=equal))
            outcome['invariance'] = dict(committed_events=len(reference), variants=inv)
        results['scenarios'][sc['name']] = outcome
    manifest = dict(protocol_sha256=sha(a.protocol), jar_sha256=sha(a.jar), python=platform.python_version(),
        git_head=run.sh(['git', 'rev-parse', 'HEAD'], cwd=root/'hyperbolic-time-chamber').stdout.strip(),
        git_scripts_dirty=run.sh(['git', 'status', '--short', '--', 'scripts/scenario'], cwd=root/'hyperbolic-time-chamber').stdout.strip().splitlines(),
        recordings=dict(development=str(dev), evaluation=str(ev)), commands=len(run.log))
    (a.work/'final-results.json').write_text(json.dumps(dict(protocol=protocol, manifest=manifest, **results), indent=2)+'\n')
    (a.work/'commands.json').write_text(json.dumps(run.log, indent=1)+'\n')
    print(json.dumps(dict(derived=derived, runs={k: [r for r in v['runs']] for k, v in results['scenarios'].items()}), indent=1))


if __name__ == '__main__':
    main()
