#!/usr/bin/env python3
"""Validate completed car/walk trips in a bounded generated balancing fixture.

Expected endpoints and distances come from input plans and the directed road graph.
Transit fixtures require their own validator. This makes no performance claim.
"""
import argparse
from collections import defaultdict
import hashlib
import heapq
import json
import math
from pathlib import Path
import re


def validate(simulation, run_directory, require_migration=False, require_active_migration=False):
    configuration = json.loads(simulation.read_text())
    graph_file = Path(configuration["cityMapFile"])
    if not graph_file.is_absolute():
        graph_file = simulation.parent / graph_file
    graph = json.loads(graph_file.read_text())
    roads = defaultdict(list)
    for edge in graph["edges"]:
        length = edge["label"]["length"]
        assert math.isfinite(length) and length > 0 and edge["weight"] == length
        roads[edge["source_id"]].append((edge["target_id"], length))

    def distance(origin, destination):
        pending, best = [(0., origin)], {origin: 0.}
        while pending:
            cost, node = heapq.heappop(pending)
            if cost != best[node]:
                continue
            if node == destination:
                return cost
            for target, length in roads[node]:
                updated = cost + length
                if updated < best.get(target, math.inf):
                    best[target] = updated
                    heapq.heappush(pending, (updated, target))
        raise AssertionError("unreachable input plan")

    expected, people, vehicle_distance = {}, {}, defaultdict(float)
    vehicle_legs, sumo_legs = defaultdict(list), defaultdict(list)
    input_files = {simulation, graph_file}
    for source in configuration["actorsDataSources"]:
        assert source["dataSource"]["sourceType"] == "json"
        path = Path(source["dataSource"]["info"]["path"])
        if not path.is_absolute():
            path = simulation.parent / path
        input_files.add(path)
        if source["classType"] != "hybrid.actor.Person":
            continue
        for actor in json.loads(path.read_text()):
            state, person = actor["data"]["content"], str(actor["id"])
            assert person not in people
            activities = [part for part in state["originalPlan"] if part["kind"] == "Activity"]
            assert len(activities) >= 2
            car = state.get("ownedVehicles", {}).get("car")
            assert not state.get("ownedVehicles") or set(state["ownedVehicles"]) == {"car"}
            mode, total = ("car" if car else "walk"), 0.
            for index, (origin, destination) in enumerate(zip(activities, activities[1:]), 1):
                assert origin["nodeId"] != destination["nodeId"], "fixture must exercise real trips"
                length = distance(origin["nodeId"], destination["nodeId"])
                expected[f"{person}:trip:{index}"] = (person, mode, length, origin["endTime"]["tick"])
                if car:
                    vehicle_legs[str(car["id"])].append((origin["nodeId"], destination["nodeId"], length,
                                                       origin["endTime"]["tick"]))
                total += length
            people[person] = (len(activities) - 1, total)
            if car:
                vehicle_distance[str(car["id"])] += total
    assert expected
    rows = [json.loads(line) for path in (run_directory / "reports").rglob("*.jsonl")
            for line in path.read_text().splitlines()]
    trips, schedules = {}, {}
    traversed, journey_distance, journeys = defaultdict(float), defaultdict(float), defaultdict(int)
    for row in rows:
        label, data = row["event_type"], row["data"]
        if label == "person_trip_metrics":
            identity = data["trip_id"]
            assert identity in expected and identity not in trips
            person, mode, length, departure = expected[identity]
            assert str(data["person_id"]) == person and data["mode"] == mode
            assert math.isclose(data["traveled_distance"], length, abs_tol=1e-8)
            assert data["departure_time"] >= departure
            assert data["arrival_time"] > data["departure_time"]
            assert data["arrival_time"] <= configuration["endTick"]
            trips[identity] = data
        elif label == "person_schedule_complete":
            person = str(data["person_id"])
            assert person in people and person not in schedules
            count, length = people[person]
            assert data["total_trips"] == count and math.isclose(data["total_distance"], length, abs_tol=1e-8)
            schedules[person] = data
        elif label == "leave_link":
            traversed[str(data["car_id"])] += data["link_length"]
        elif label == "journey_completed":
            vehicle = str(data["vehicle_id"])
            assert data["reached_destination"] and data["final_node"] == data["destination"]
            assert data["total_distance"] >= journey_distance[vehicle]
            journey_distance[vehicle] = data["total_distance"]
            journeys[vehicle] += 1
        elif label == "sumo_tripinfo":
            assert data["arrival"] >= 0 and not data.get("horizon_truncated", False)
            sumo_legs[str(data["vehicle_id"])].append(data)
    assert trips.keys() == expected.keys() and schedules.keys() == people.keys()
    assert set(journey_distance) == set(vehicle_distance) == set(traversed)
    assert sumo_legs.keys() == vehicle_legs.keys()
    for vehicle, length in vehicle_distance.items():
        assert math.isclose(journey_distance[vehicle], length, abs_tol=1e-8)
        assert math.isclose(traversed[vehicle], length, abs_tol=1e-8)
        assert journeys[vehicle] == len(vehicle_legs[vehicle]) == len(sumo_legs[vehicle])
        for planned, actual in zip(vehicle_legs[vehicle], sorted(sumo_legs[vehicle], key=lambda leg: leg["depart"])):
            origin, destination, distance, departure = planned
            assert (actual["origin"], actual["destination"], actual["final_node"]) == (origin, destination, destination)
            assert math.isclose(actual["routeLength"], distance, abs_tol=1e-8)
            assert actual["arrival"] > actual["depart"] >= departure
    runtime = (run_directory / "runtime.log").read_text()
    assert "[CONSERVATIVE-COMPLETE]" in runtime and "[ERROR]" not in runtime
    assert not any(marker in runtime for marker in ("[RUNTIME-FAILED]", "[SIMULATION-FAILED]", "[CONSERVATIVE-FAILED]"))
    migrations = re.findall(r"Migration '([^']+)' placement and restore verified", runtime)
    if require_migration:
        assert migrations, "no verified physical migration"
        assert "TimeManager paused at tick" in runtime and "Triggering Pekko hand-off" in runtime
    cuts, restores, cut = {}, [], None
    for line in runtime.splitlines():
        paused = re.search(r"TimeManager paused at tick (\d+)", line)
        if paused:
            cut = int(paused[1])
        wave = re.search(r"Migration wave '([^']+)'", line)
        if wave:
            assert cut is not None
            cuts[wave[1]] = cut
        restored = re.search(r"sending MigrationContextEvent to '([^']+)' \(batch=([^\)]+)\)", line)
        if restored:
            restores.append((restored[1], restored[2]))
    entries, exits = defaultdict(list), defaultdict(list)
    for row in rows:
        if row["event_type"] in ("enter_link", "leave_link"):
            data = row["data"]
            table = entries if row["event_type"] == "enter_link" else exits
            table[(str(data["car_id"]), str(data["link_id"]))].append(data["tick"])
    active_restores = []
    for vehicle, batch in restores:
        if vehicle in vehicle_distance and batch in cuts:
            at = cuts[batch]
            if any(car == vehicle and any(entry <= at < exit_tick for entry, exit_tick in
                   zip(sorted(ticks), sorted(exits[(car, link)]))) for (car, link), ticks in entries.items()):
                active_restores.append(dict(vehicle=vehicle, batch=batch, tick=at))
    if require_active_migration:
        assert migrations and active_restores, "no restored Car occupied a road at the migration cut"
    return dict(passed=True, persons=len(people), trips=len(trips), completedVehicleJourneys=sum(journeys.values()),
                vehicleDistance=sum(vehicle_distance.values()), reportRows=len(rows), verifiedMigrations=migrations,
                activeCarRestores=active_restores,
                inputs={str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(input_files)})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--simulation", type=Path, required=True)
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--require-migration", action="store_true")
    parser.add_argument("--require-active-migration", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = validate(args.simulation.resolve(), args.run.resolve(), args.require_migration, args.require_active_migration)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
