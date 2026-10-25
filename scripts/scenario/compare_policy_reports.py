#!/usr/bin/env python3
"""Compare committed physical report multisets from separate policy runs."""
import argparse
from collections import Counter
from itertools import islice
import json
from pathlib import Path


def reports(directory):
    rows = Counter()
    for path in sorted(directory.rglob("*.jsonl")):
        for line in path.read_text().splitlines():
            if not line.strip():
                continue
            row = json.loads(line)
            row.pop("real_time", None)
            row.pop("simulation_id", None)
            if row.get("event_type") == "sumo_summary_step":
                row["data"].pop("duration", None)
            rows[json.dumps(row, sort_keys=True)] += 1
    if not rows:
        raise ValueError(f"No reports found under {directory}")
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--conservative", required=True, type=Path)
    parser.add_argument("--optimistic", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    conservative = reports(args.conservative)
    optimistic = reports(args.optimistic)
    left, right = conservative - optimistic, optimistic - conservative
    result = {
        "passed": conservative == optimistic,
        "conservativeRows": conservative.total(),
        "optimisticRows": optimistic.total(),
        "differentConservativeRows": left.total(),
        "differentOptimisticRows": right.total(),
        "conservativeExamples": [json.loads(row) for row in islice(left.elements(), 10)],
        "optimisticExamples": [json.loads(row) for row in islice(right.elements(), 10)],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({key: value for key, value in result.items() if not key.endswith("Examples")}, indent=2))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
