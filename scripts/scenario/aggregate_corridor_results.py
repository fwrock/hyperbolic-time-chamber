#!/usr/bin/env python3
"""Aggregate corridor analyses (one per seed) with bootstrap confidence intervals over vehicles.

Per vehicle, values are averaged across seeds (seeds are replicates of the same vehicle); vehicles are then resampled with
replacement (default 2000 resamples, fixed seed). Reports the median stop-bar error, the share within 5 s, the share of
one-cycle-later slips and the median road-time difference (model minus observed), each with a 95% percentile interval.
"""
import argparse
import json
import random
import statistics
from collections import defaultdict
from pathlib import Path


def ci(values, stat, resamples, rng):
    if not values: return None
    point = stat(values)
    boots = sorted(stat([values[rng.randrange(len(values))] for _ in values]) for _ in range(resamples))
    return dict(n=len(values), point=round(point, 3), low=round(boots[int(.025*resamples)], 3), high=round(boots[int(.975*resamples)-1], 3))


def per_vehicle(paths):
    stop = defaultdict(lambda: defaultdict(list))
    for path in paths:
        for name, items in json.loads(Path(path).read_text())['raw']['stop_bar_error_s'].items():
            for vid, e in items: stop[name][vid].append(e)
    return {n: {v: statistics.mean(x) for v, x in d.items()} for n, d in stop.items()}


def paired(paths, baseline, resamples=2000, seed=20261119):
    """Paired difference of per-vehicle absolute stop-bar error (condition minus baseline) over the same vehicles."""
    rng, a, b, out = random.Random(seed), per_vehicle(paths), per_vehicle(baseline), {}
    for name in a:
        diffs = [abs(a[name][v]) - abs(b[name][v]) for v in a[name] if v in b.get(name, {})]
        out[name] = dict(mean_abs_error_difference_s=ci(diffs, statistics.mean, resamples, rng), median_abs_error_difference_s=ci(diffs, statistics.median, resamples, rng))
    return out


def aggregate(paths, resamples=2000, seed=20261119):
    rng = random.Random(seed)
    stop, road = defaultdict(lambda: defaultdict(list)), defaultdict(lambda: defaultdict(list))
    for path in paths:
        raw = json.loads(Path(path).read_text())['raw']
        for name, items in raw['stop_bar_error_s'].items():
            for vid, e in items: stop[name][vid].append(e)
        for name, items in raw['road_time_s_observed_model'].items():
            for vid, obs, mod in items: road[name][vid].append(mod - obs)
    out = {}
    for name in stop:
        v = [statistics.mean(x) for x in stop[name].values()]
        out[name] = dict(vehicles=len(v), seeds=len(paths),
            median_stop_bar_error_s=ci(v, statistics.median, resamples, rng),
            share_within_5s=ci(v, lambda x: sum(abs(y) <= 5 for y in x)/len(x), resamples, rng),
            share_one_cycle_later=ci(v, lambda x: sum(y > 20 for y in x)/len(x), resamples, rng))
    for name in road:
        v = [statistics.mean(x) for x in road[name].values()]
        out.setdefault(name, {})['median_road_time_error_s'] = ci(v, statistics.median, resamples, rng)
    return out


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--analyses', type=Path, nargs='+', required=True)
    p.add_argument('--baseline', type=Path, nargs='+', help='analyses of a baseline condition for a paired comparison')
    p.add_argument('--resamples', type=int, default=2000)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    result = aggregate(a.analyses, a.resamples)
    if a.baseline: result['paired_vs_baseline'] = paired(a.analyses, a.baseline, a.resamples)
    with a.output.open('x') as f:
        json.dump(result, f, indent=2); f.write('\n')
    print(json.dumps(result, indent=1))
