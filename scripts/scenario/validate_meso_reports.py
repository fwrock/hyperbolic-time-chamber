#!/usr/bin/env python3
"""Validate bounded person-owned Car MESO runs against their actual input plans."""

import argparse
from collections import Counter
import heapq
import json
import math
from pathlib import Path
import re


def load_entities(config, class_type, directory):
    entities = []
    for source in config["actorsDataSources"]:
        if source["classType"] == class_type:
            path = Path(source["dataSource"]["info"]["path"])
            if not path.is_absolute():
                path = directory / path
            entities.extend(json.loads(path.read_text()))
    return entities


def shortest_distance(links, origin, destination):
    queue = [(0.0, origin)]
    visited = set()
    while queue:
        distance, node = heapq.heappop(queue)
        if node == destination:
            return distance
        if node in visited:
            continue
        visited.add(node)
        for link in links.values():
            if link["from"] == node:
                heapq.heappush(queue, (distance + link["length"], link["to"]))
    raise AssertionError(f"unreachable destination: {origin} -> {destination}")


def validate(args):
    config = json.loads(args.simulation.read_text())
    persons = load_entities(config, "hybrid.actor.Person", args.simulation.parent)
    links = {str(entity["id"]): entity["data"]["content"] for entity in
             load_entities(config, "hybrid.actor.Link", args.simulation.parent)}
    assert persons and links, "scenario must exercise persons and links"
    assert all(link["simulationMode"] == "MESO" for link in links.values())
    rows = [json.loads(line) for path in args.reports.rglob("*.jsonl")
            for line in path.read_text().splitlines() if line.strip()]
    events = {}
    for row in rows:
        events.setdefault(row["event_type"], []).append(row["data"])
    assert not events.get("enter_micro_link") and not events.get("leave_micro_link"), "unexpected MICRO traversal"
    entries = events.get("enter_link", [])
    exits = events.get("leave_link", [])
    assert entries and all(row["mode"] == "MESO" and "car_id" in row for row in entries), "Car MESO scope required"
    assert Counter((str(r["car_id"]), str(r["link_id"])) for r in entries) == Counter(
        (str(r["car_id"]), str(r["link_id"])) for r in exits), "unmatched MESO entries/exits"
    expected_trips = expected_traversals = 0
    trip_ticks = {}
    trip_distances = {}
    for entity in persons:
        person_id = str(entity["id"])
        person = entity["data"]["content"]
        car_id = str(person["ownedVehicles"]["car"]["id"])
        activities = [s for s in person["originalPlan"] if s["kind"] == "Activity"]
        trips = sorted((r for r in events.get("person_trip_completed", []) if str(r["person_id"]) == person_id),
                       key=lambda r: r["completed_trips"])
        starts = sorted((r for r in events.get("person_trip_started", []) if str(r["person_id"]) == person_id),
                        key=lambda r: r["tick"])
        assert len(trips) == len(starts) == len(activities) - 1, f"person {person_id}: trip count"
        expected_trips += len(trips)
        total_distance = 0.0
        for number, (trip, start, origin, destination) in enumerate(zip(trips, starts, activities, activities[1:]), 1):
            expected_distance = shortest_distance(links, origin["nodeId"], destination["nodeId"])
            assert trip["completed_trips"] == number and str(trip["vehicle_id"]) == car_id
            assert trip["completion_reason"] == "reached_destination", trip
            assert math.isclose(trip["distance_traveled"], expected_distance, abs_tol=1e-6), trip
            total_distance += expected_distance
            journeys = [r for r in events.get("journey_completed", []) if
                        str(r["car_id"]) == car_id and r["tick"] == trip["tick"]]
            sumo = [r for r in events.get("sumo_tripinfo", []) if
                    str(r["vehicle_id"]) == car_id and r["tick"] == trip["tick"]]
            assert len(journeys) == len(sumo) == 1, "missing or duplicate journey/SUMO completion"
            assert math.isclose(journeys[0]["total_distance"], total_distance, abs_tol=1e-6), journeys
            assert math.isclose(sumo[0]["routeLength"], expected_distance, abs_tol=1e-6), (
                "SUMO per-trip distance", sumo[0]["routeLength"], expected_distance)
            assert sumo[0]["arrival"] == trip["tick"] and not sumo[0]["vaporized"]
            entered = sorted((r for r in entries if str(r["car_id"]) == car_id and
                              start["tick"] <= r["tick"] <= trip["tick"]), key=lambda r: r["tick"])
            left = sorted((r for r in exits if str(r["car_id"]) == car_id and
                           start["tick"] <= r["tick"] <= trip["tick"]), key=lambda r: r["tick"])
            assert entered and len(entered) == len(left), f"person {person_id}: incomplete trip traversal"
            expected_traversals += len(entered)
            node, distance, next_entry = origin["nodeId"], 0.0, start["tick"] + 1
            for entry, exit_event in zip(entered, left):
                assert entry["link_id"] == exit_event["link_id"], (entry, exit_event)
                link = links[str(entry["link_id"])]
                assert link["from"] == node, "route is not connected"
                node = link["to"]
                distance += link["length"]
                speed = 1.0 if entry["cars_in_link"] >= link["capacity"] else (
                    link["freeSpeed"] * (1.0 - entry["cars_in_link"] / link["capacity"]))
                travel = link["length"] / (speed / 3.6)
                assert math.isclose(entry["travel_time"], travel, rel_tol=1e-9), entry
                minimum_exit = entry["tick"] + math.ceil(travel)
                assert entry["tick"] >= next_entry, "next link started before previous leave completed"
                assert exit_event["tick"] >= minimum_exit, "MESO travel time was skipped"
                if args.free_flow:
                    assert entry["tick"] == next_entry, ("unexpected handoff delay", entry, next_entry)
                    assert exit_event["tick"] == minimum_exit, ("unexpected exit delay", exit_event, minimum_exit)
                next_entry = exit_event["tick"] + 1
            assert node == destination["nodeId"] and math.isclose(distance, expected_distance, abs_tol=1e-6)
            assert trip["tick"] == left[-1]["tick"], "trip completed before/after its final leave reply"
            assert trip["travel_time"] == trip["tick"] - start["tick"]
        actual_activities = sorted((r for r in events.get("person_activity_start", []) if str(r["person_id"]) == person_id),
                                   key=lambda r: r["activity_sequence"])
        assert [r["node_id"] for r in actual_activities] == [a["nodeId"] for a in activities]
        summaries = [r for r in events.get("person_schedule_complete", []) if str(r["person_id"]) == person_id]
        assert len(summaries) == 1 and summaries[0]["total_trips"] == len(trips)
        assert math.isclose(summaries[0]["total_distance"], total_distance, abs_tol=1e-6), summaries
        trip_ticks[person_id] = [r["tick"] for r in trips]
        trip_distances[person_id] = [r["distance_traveled"] for r in trips]
    assert len(entries) == len(exits) == expected_traversals, "unattributed MESO traversal"
    assert len(events.get("person_trip_completed", [])) == expected_trips
    assert len(events.get("person_schedule_complete", [])) == len(persons)
    assert len(events.get("journey_completed", [])) == expected_trips
    assert len(events.get("sumo_tripinfo", [])) == expected_trips
    log = args.log.read_text(errors="replace")
    for marker in ("[ERROR]", "[RUNTIME-FAILED]", "[SIMULATION-FAILED]", "[STALL", "[GTM-STALL]",
                   "[RUNTIME-STALL]", "null state", "Replay did not consume all recorded reads"):
        assert marker not in log, f"runtime log contains {marker}"
    stop = "Received StopSimulationEvent. Flushing/stopping reporters"
    assert stop in log, "engine did not finish before launcher teardown"
    if config["timeManagerType"] == "time-warp":
        rounds = [(int(a), int(b)) for a, b in re.findall(r"\[ACCOUNTED-GVT\] round=(\d+) frontier=(-?\d+)", log)]
        assert rounds and all(a[0] < b[0] and a[1] <= b[1] for a, b in zip(rounds, rounds[1:]))
        completed = "[ACCOUNTED-COMPLETE] frontier=9223372036854775807 status=completed"
        assert completed in log and log.index(completed) < log.index(stop)
    return {"passed": True, "policy": config["timeManagerType"], "seed": config["randomSeed"],
            "persons": len(persons), "expectedTrips": expected_trips, "completedTrips": expected_trips,
            "dailySummaries": len(persons), "mesoEntries": len(entries), "mesoExits": len(exits),
            "tripCompletionTicks": trip_ticks, "tripDistances": trip_distances,
            "rollbacks": log.count("[TW-ROLLBACK]"), "freeFlowTimingChecked": args.free_flow,
            "engineStopped": True, "errors": 0, "nullStateDiscards": 0, "stalls": 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--simulation", type=Path, required=True)
    parser.add_argument("--reports", type=Path, required=True)
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--free-flow", action="store_true", help="require exact travel and handoff ticks on an unsignaled route")
    args = parser.parse_args()
    try:
        result = validate(args)
    except (AssertionError, KeyError, ValueError, OSError) as error:
        result = {"passed": False, "error": str(error)}
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
