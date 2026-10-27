#!/usr/bin/env python3
"""Generate a bounded fixed-seed car cohort for conservative migration acceptance."""
import argparse
import json
from pathlib import Path

from generate_hybrid_scenario import build_scenario


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--cohort-size", type=int, default=30)
    parser.add_argument("--late-cohort", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.cohort_size <= 50:
        parser.error("cohort size must be between 1 and 50")
    root = args.output_dir.resolve()
    if root.exists():
        parser.error("use a fresh output directory")
    count = 2 * args.cohort_size
    build_scenario(root, str(root), 4, 4, count, count, 0, 0, 0, 0,
                   0.0, None, 3, "travel-time", 413)

    def read(name):
        return json.loads((root / name).read_text())

    def write(name, value):
        (root / name).write_text(json.dumps(value, indent=2) + "\n")

    if args.late_cohort:
        for name in ("persons_0", "cars_0"):
            actors = read(f"data/{name}.json")
            for index, actor in enumerate(actors):
                state = actor["data"]["content"]
                late = index >= args.cohort_size
                state["startTick"] = 10050 if late else 0
                if name == "persons_0":
                    activities = [part for part in state["originalPlan"] if part["kind"] == "Activity"]
                    row, column = divmod(activities[0]["nodeId"] - 1, 4)
                    activities[1]["nodeId"] = 16 if row + column < 3 else 1
                    activities[0]["endTime"]["tick"] = 10300 if late else 100
                    activities[1]["endTime"]["tick"] = 40000
            write(f"data/{name}.json", actors)
        roads = read("data/links_0.json")
        for road in roads:
            road["data"]["content"]["length"] = 40000.0
        write("data/links_0.json", roads)
        graph = read("data/city_map.json")
        for edge in graph["edges"]:
            edge["weight"] = edge["label"]["length"] = 40000.0
        write("data/city_map.json", graph)
    configuration = read("simulation.json")
    configuration["timeManagerType"] = "discrete-event"
    configuration["description"] = (__doc__ + " Seed 413. " +
        ("Cohorts at ticks 0/10050; long roads retain active journeys across input enrollment."
         if args.late_cohort else "Both cohorts enroll at tick zero."))
    write("simulation.json", configuration)
    common = '''htc.load-balance-manager.enabled=true
htc.load-balance-manager.strategy="geo-affinity"
htc.load-balance-manager.world-bounds { min-x=-46.6301, max-x=-46.6209, min-y=-23.5501, max-y=-23.5409 }
htc.load-balance-manager.quadtree.initial-depth=2
htc.load-balance-manager.quadtree.max-actors-per-shard=100
htc.load-balance-manager.quadtree.min-actors-per-shard=0
htc.load-balance-manager.prediction.enabled=true
htc.load-balance-manager.kdtree.load-threshold=0.01
htc.load-balance-manager.metrics.interval-seconds=0.25
pekko.cluster.sharding.rebalance-interval=100 ms
'''
    for mode, interval in (("reference", 3600), ("migration", 0.25)):
        (root / f"{mode}.conf").write_text(common +
            f"htc.load-balance-manager.rebalance-interval-seconds={interval}\n")
    print(root)


if __name__ == "__main__":
    main()
