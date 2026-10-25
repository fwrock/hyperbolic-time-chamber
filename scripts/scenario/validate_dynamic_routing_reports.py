#!/usr/bin/env python3
"""Validate accounted-routing output against independently specified paths and distances."""

from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reports", type=Path, required=True)
    parser.add_argument("--expected", type=Path, required=True)
    args = parser.parse_args()
    expected = json.loads(args.expected.read_text())
    rows = []
    for path in args.reports.rglob("*.jsonl"):
        rows.extend(json.loads(line) for line in path.read_text().splitlines() if line.strip())

    routes = defaultdict(list)
    for row in rows:
        if row.get("event_type") == "route_planned":
            data = row["data"]
            routes[str(data["car_id"])].append((row["tick"], [int(x) for x in data["route_links"].split(",")]))
    actual_routes = {car: [path for _, path in sorted(values)] for car, values in routes.items()}
    route_ok = actual_routes == expected["expectedRoutes"]

    person_id = str(expected["personId"])
    summaries = [row["data"] for row in rows if row.get("event_type") == "person_schedule_complete"
                 and str(row["data"].get("person_id")) == person_id]
    summary = summaries[0] if len(summaries) == 1 else {}
    trips_ok = summary.get("total_trips") == expected["expectedPersonTrips"]
    distance = float(summary.get("total_distance", -1.0))
    distance_ok = abs(distance - expected["expectedPersonDistance"]) < 1e-6
    bad_lines = [row for row in rows if "exception" in str(row).lower() or "null-state" in str(row).lower()]
    journeys = [row for row in rows if row.get("event_type") == "journey_completed"]
    expected_counts = Counter({car: len(paths) for car, paths in expected["expectedRoutes"].items()})
    journey_counts = Counter(str(row["data"].get("car_id")) for row in journeys)
    journeys_ok = (journey_counts == expected_counts and
                   all(row["data"].get("completion_reason") in {"reached_destination", "onFinish_called"} and
                       row["data"].get("reached_destination") is True and
                       row["data"].get("final_node") == row["data"].get("destination") for row in journeys))
    sumo_counts = Counter(str(row["data"].get("vehicle_id")) for row in rows
                          if row.get("event_type") == "sumo_tripinfo")
    sumo_ok = sumo_counts == expected_counts
    entry_type, exit_type = (("enter_micro_link", "leave_micro_link") if expected["mode"] == "MICRO"
                             else ("enter_link", "leave_link"))
    expected_traversals = Counter((car, link) for car, paths in expected["expectedRoutes"].items()
                                  for path in paths for link in path)
    entries = Counter((str(row["data"].get("car_id")), row["data"].get("link_id"))
                      for row in rows if row.get("event_type") == entry_type)
    exits = Counter((str(row["data"].get("car_id")), row["data"].get("link_id"))
                    for row in rows if row.get("event_type") == exit_type)
    traversal_ok = entries == exits == expected_traversals
    micro_count = sum(row.get("event_type") == "micro_update" for row in rows)
    micro_ok = expected["mode"] != "MICRO" or micro_count > 0
    result = {"routePass": route_ok, "tripsPass": trips_ok, "distancePass": distance_ok,
              "journeysPass": journeys_ok, "vehicleJourneys": len(journeys), "sumoPass": sumo_ok,
              "microPass": micro_ok, "microUpdates": micro_count, "traversalPass": traversal_ok,
              "linkEntries": entries.total(), "linkExits": exits.total(),
              "badReportRows": len(bad_lines), "actualRoutes": actual_routes,
              "dailySummary": summary, "strictPass": route_ok and trips_ok and distance_ok and
              journeys_ok and sumo_ok and micro_ok and traversal_ok and not bad_lines}
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if result["strictPass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
