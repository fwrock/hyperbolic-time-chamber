#!/usr/bin/env python3
"""External Linux process measurements and parsing for bounded comparison studies."""
import json
import math
import os
from pathlib import Path
import re
import statistics
import threading
import time
import urllib.request


def imbalance(values):
    if not values or any(not math.isfinite(v) or v < 0 for v in values):
        raise ValueError('loads must be finite and nonnegative')
    mean = statistics.mean(values)
    return None if mean == 0 else dict(cv=statistics.pstdev(values)/mean, maxOverMean=max(values)/mean)


def process_sample(pid):
    try:
        fields = Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()
        return dict(pid=pid, cpuSeconds=(int(fields[11])+int(fields[12]))/os.sysconf('SC_CLK_TCK'),
                    rssBytes=int(fields[21])*os.sysconf('SC_PAGE_SIZE'), state=fields[0])
    except (FileNotFoundError, ProcessLookupError):
        return None


class ProcessMonitor:
    def __init__(self, output, started=None, interval=.25, metrics_ports=()):
        self.output = Path(output)
        self.started = started if started is not None else time.monotonic()
        self.interval, self.ports = interval, tuple(metrics_ports)
        self.pids, self.samples, self.prometheus = [], [], []
        self.stop_event = threading.Event()
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def add(self, pid):
        self.pids.append(pid)

    def sample(self):
        nodes = [process_sample(pid) for pid in tuple(self.pids)]
        return dict(seconds=time.monotonic()-self.started, nodes=nodes,
                    hostLoad1=os.getloadavg()[0],
                    hostCpu=Path('/proc/stat').read_text().splitlines()[0])

    def _run(self):
        while not self.stop_event.is_set():
            self.samples.append(self.sample())
            for port in self.ports:
                at = time.monotonic()-self.started
                try:
                    with urllib.request.urlopen(f'http://127.0.0.1:{port}/metrics', timeout=.04) as response:
                        body = response.read(2_000_000).decode()
                    self.prometheus.append(dict(seconds=at, port=port, text=body))
                except (OSError, TimeoutError):
                    pass
            self.stop_event.wait(max(0, self.interval-(time.monotonic()-self.started-self.samples[-1]['seconds'])))

    def finish(self):
        self.stop_event.set()
        self.thread.join(2)
        self.samples.append(self.sample())
        self.output.mkdir(parents=True, exist_ok=True)
        (self.output/'process-samples.json').write_text(json.dumps(self.samples))
        (self.output/'prometheus-samples.json').write_text(json.dumps(self.prometheus))
        return summarize_samples(self.samples)


def summarize_samples(samples):
    peaks, cpu = [], {}
    for sample in samples:
        nodes = [n for n in sample['nodes'] if n]
        peaks.append(sum(n['rssBytes'] for n in nodes))
        for n in nodes:
            cpu[n['pid']] = max(cpu.get(n['pid'], 0), n['cpuSeconds'])
    valid = [s for s in samples if any(s['nodes'])]
    return dict(sampleCount=len(samples), validSamples=len(valid), cpuSeconds=sum(cpu.values()),
                cpuSecondsByPid=cpu, peakAggregateRssBytes=max(peaks, default=0),
                hostLoad1Min=min((s['hostLoad1'] for s in samples), default=None),
                hostLoad1Max=max((s['hostLoad1'] for s in samples), default=None),
                cpuMeasurement='last live /proc counters before harness teardown',
                memoryMeasurement='maximum simultaneous sampled RSS sum; not sum of per-process peaks')


def parse_runtime(log):
    loads, pauses, moves, waves = [], [], [], []
    for line in log.splitlines():
        load = re.search(r'\[LOAD-BALANCE-METRICS\].*round=(\d+).*node_loads=\[(.*)\]', line)
        stamp = re.search(r'(\d{4}-\d\d-\d\d[ T]\d\d:\d\d:\d\d[.,]\d+)', line)
        at = stamp[1] if stamp else None
        if load:
            values = [float(v) for v in re.findall(r'=([0-9.]+)(?:,|$)', load[2])]
            if values:
                loads.append(dict(timestamp=at, round=int(load[1]), values=values, imbalance=imbalance(values)))
        duration = re.search(r"Migration '([^']+)' placement and restore verified: ([0-9.]+)ms", line)
        if duration:
            moves.append(dict(timestamp=at, shard=duration[1], seconds=float(duration[2])/1000))
        wave = re.search(r"Migration wave '([^']+)': (\d+) dynamic plan\(s\), (\d+) entities", line)
        if wave:
            waves.append(dict(timestamp=at, batch=wave[1], shards=int(wave[2]), entities=int(wave[3])))
        timing = re.search(r'\[MIGRATION-TIMING\] request=(\S+) phase=resumed totalNanos=(\d+) freezeNanos=(\d+) relocationNanos=(\d+) resumeNanos=(\d+)', line)
        if timing:
            pauses.append(dict(request=timing[1], timestamp=at, **{k:int(v)/1e9 for k,v in zip(
                ('totalSeconds','freezeSeconds','relocationSeconds','resumeSeconds'), timing.groups()[1:])}))
    phases = [dict(phase=name,seconds=float(value)) for name,value in re.findall(r"\[Phase\] '([^']+)' completed in ([0-9.]+) seconds", log)]
    return dict(loads=loads, pauses=pauses, verifiedMoves=moves, waves=waves,
                declinedRequests=len(re.findall(r'migration.*declin|declin.*migration',log,re.I)),
                rollbacks=log.count('[TW-ROLLBACK]'), phases=phases,
                transferSummaries=re.findall(r'^.*(?:transfers=|crossOwner|cross-owner|\[ACCOUNTED-COMPLETE\]|\[CONSERVATIVE-COMPLETE\]).*$',log,re.M))
