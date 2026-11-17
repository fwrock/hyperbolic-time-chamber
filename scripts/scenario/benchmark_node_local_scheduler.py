#!/usr/bin/env python3
"""Paired two-JVM scheduler timing and physical remote-envelope comparison.

Use a generated person-owned Car scenario; the existing mobility validator gates
every result. Runs are sequential with alternating order and equal CPU/heap limits.
"""
import argparse
from collections import Counter, defaultdict
from contextlib import redirect_stdout
import hashlib
import io
import json
import os
from pathlib import Path
import statistics
from types import SimpleNamespace

from run_cluster_validation import run
from validate_meso_reports import validate


def report_rows(directory):
    for path in directory.rglob('*.jsonl'):
        for line in path.read_text().splitlines():
            if line.strip():
                yield json.loads(line)


def extract_measurements(rows, expected_nodes=2):
    """Use final per-JVM snapshots, not a possibly stale last Prometheus scrape."""
    traffic, processes, seen = defaultdict(lambda: dict(messages=0, bytes=0)), set(), set()
    loop, synchronization = [], []
    placements = Counter()
    for row in rows:
        data = row['data']
        if row['event_type'] == 'execution_timing_summary':
            if data['operation'] == 'simulation_loop':
                if data['active_count'] or data['failed_count'] or data['count'] != 1:
                    raise ValueError('simulation stopwatch did not settle successfully')
                loop.append(data['total_seconds'])
            if data['operation'] == 'time_manager_synchronization':
                synchronization.append(data['total_seconds'])
        elif row['event_type'] == 'remote_transport_summary':
            key = data['process_id'], data['direction'], data['category']
            if key in seen:
                raise ValueError('duplicate final transport snapshot')
            seen.add(key)
            processes.add(data['process_id'])
            if data['direction'] == 'sent':
                traffic[data['category']]['messages'] += data['messages']
                traffic[data['category']]['bytes'] += data['serialized_bytes']
        elif row['event_type'] == 'actor_placement':
            placements[(row['node'], data['actor_type'])] += 1
    if len(processes) != expected_nodes or len(loop) != 1 or not traffic:
        raise ValueError('missing final node transport snapshots or GTM stopwatch')
    return dict(simulationSeconds=loop[0], synchronizationSeconds=sum(synchronization),
                remoteMessages=sum(v['messages'] for v in traffic.values()),
                remoteBytes=sum(v['bytes'] for v in traffic.values()),
                schedulingRemoteMessages=traffic.get('scheduling', {}).get('messages', 0),
                schedulingRemoteBytes=traffic.get('scheduling', {}).get('bytes', 0),
                trafficByCategory=dict(traffic),
                placementCounts=[dict(node=node, actorType=kind, actors=count)
                                 for (node, kind), count in sorted(placements.items())])


def write_override(path, strategy, workers):
    path.write_text(f'''htc.time-manager.scheduling-strategy="{strategy}"
htc.time-manager.conservative-transport="direct"
htc.time-manager.total-instances={2 * workers}
htc.time-manager.max-instances-per-node={workers}
htc.time-manager.node-local.nodes=2
htc.time-manager.node-local.workers-per-node={workers}
htc.load-balance-manager.enabled=false
htc.node.label-strategy="rank"
htc.report-manager.json.batch-size=1000
pekko.remote.artery.advanced.instruments=["org.interscity.htc.core.metrics.RemoteTrafficInstrument"]
''')


def one_run(args, name, strategy, port, warmup):
    directory = args.output / name
    launch = SimpleNamespace(jar=args.jar, simulation=args.simulation, output=directory,
        base_port=port, nodes=2, timeout=args.timeout, delay_ms=0, kill_node=None,
        kill_after=None, extra_config=args.output / (strategy + '.conf'), measure=True,
        cpu_ids=args.cpu_ids, metrics_interval=0)
    with redirect_stdout(io.StringIO()):
        code = run(launch)
    if code:
        raise RuntimeError(f'engine/measurement failure: {directory}/result.json')
    outcome = validate(SimpleNamespace(simulation=args.simulation, reports=directory / 'reports',
        log=directory / 'runtime.log', mode=args.mode, free_flow=False))
    (directory / 'outcome.json').write_text(json.dumps(outcome, indent=2) + '\n')
    receipt = json.loads((directory / 'result.json').read_text())
    measurement = extract_measurements(report_rows(directory / 'reports'))
    measurement.update(name=name, strategy=strategy, warmup=warmup,
        completionSeconds=receipt['measurement']['completionSeconds'],
        cpuSeconds=receipt['measurement']['cpuSeconds'],
        peakRssBytes=receipt['measurement']['peakAggregateRssBytes'],
        hostLoad1Min=receipt['measurement']['hostLoad1Min'],
        hostLoad1Max=receipt['measurement']['hostLoad1Max'],
        jarSha256=receipt['jarSha256'], outcome=outcome)
    (directory / 'comparison-measurements.json').write_text(json.dumps(measurement, indent=2) + '\n')
    print(json.dumps({k: measurement[k] for k in ('name', 'simulationSeconds',
        'completionSeconds', 'remoteMessages', 'remoteBytes', 'schedulingRemoteMessages')}), flush=True)
    return measurement


def summarize(records):
    """Retain all runs and ranges; small paired pilots do not justify general speed claims."""
    fields = ('simulationSeconds', 'completionSeconds', 'synchronizationSeconds', 'cpuSeconds',
              'peakRssBytes', 'remoteMessages', 'remoteBytes', 'schedulingRemoteMessages',
              'schedulingRemoteBytes')
    result = {}
    for strategy in ('cluster-pool', 'node-local-tick'):
        rows = [r for r in records if r['strategy'] == strategy and not r['warmup']]
        result[strategy] = {field: dict(median=statistics.median(r[field] for r in rows),
            minimum=min(r[field] for r in rows), maximum=max(r[field] for r in rows)) for field in fields}
    reference, candidate = result['cluster-pool'], result['node-local-tick']
    reductions = {field: 100 * (reference[field]['median'] - candidate[field]['median']) /
                  reference[field]['median'] if reference[field]['median'] else None for field in fields}
    return dict(byStrategy=result, reductionPercent=reductions)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--simulation', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--mode', choices=('MESO', 'MICRO'), default='MESO')
    parser.add_argument('--workers-per-node', type=int, default=3)
    parser.add_argument('--repetitions', type=int, default=3)
    parser.add_argument('--warmup-pairs', type=int, default=1)
    parser.add_argument('--base-port', type=int, default=28600)
    parser.add_argument('--timeout', type=float, default=180)
    parser.add_argument('--cpu-ids', required=True, help='Four distinct available logical CPUs')
    args = parser.parse_args()
    if args.repetitions < 1 or args.warmup_pairs < 0 or args.workers_per_node < 1:
        parser.error('repetitions/workers must be positive; warm-up count must be nonnegative')
    args.jar, args.simulation, args.output = args.jar.resolve(), args.simulation.resolve(), args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=False)
    manifest = dict(jar=str(args.jar), simulation=str(args.simulation), mode=args.mode,
        scenarioSha256=hashlib.sha256(args.simulation.read_bytes()).hexdigest(),
        cpuIds=args.cpu_ids, workersPerNode=args.workers_per_node, nodes=2,
        repetitions=args.repetitions, warmupPairs=args.warmup_pairs,
        uname=list(os.uname()), cpuAffinity=sorted(os.sched_getaffinity(0)),
        measurement='outbound Artery envelope counts/serialized bytes; process cumulative through final capture',
        timing='GTM simulation_loop stopwatch and launch-to-engine-stop; teardown excluded',
        scope='single-host loopback two-JVM pilot; not multi-host network throughput')
    (args.output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    for strategy in ('cluster-pool', 'node-local-tick'):
        write_override(args.output / (strategy + '.conf'), strategy, args.workers_per_node)
    records = []
    for pair in range(args.warmup_pairs + args.repetitions):
        warmup = pair < args.warmup_pairs
        order = ('cluster-pool', 'node-local-tick') if pair % 2 == 0 else ('node-local-tick', 'cluster-pool')
        cohort = []
        for index, strategy in enumerate(order):
            name = f'{"warmup" if warmup else "measured"}-{pair:02d}-{strategy}'
            result = one_run(args, name, strategy, args.base_port + pair * 100 + index * 40, warmup)
            records.append(result)
            cohort.append(result)
            (args.output / 'runs.json').write_text(json.dumps(records, indent=2) + '\n')
        left, right = (r['outcome'] for r in cohort)
        for field in ('completedTrips', 'dailySummaries', 'tripDistances'):
            if left[field] != right[field]:
                raise RuntimeError(f'paired mobility mismatch: {field}')
    if len({r['jarSha256'] for r in records}) != 1:
        raise RuntimeError('executable changed between runs')
    summary = summarize(records)
    (args.output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2), flush=True)


if __name__ == '__main__':
    main()
