#!/usr/bin/env python3
"""Generate a deterministic two-path scenario for accounted routing validation."""

from __future__ import annotations

import argparse
import copy
import json
from pathlib import Path

from generate_hybrid_scenario import build_scenario


def read(path: Path):
    return json.loads(path.read_text())


def write(path: Path, value) -> None:
    path.write_text(json.dumps(value, indent=2) + "\n")


def source(file_id: str, class_type: str, path: Path) -> dict:
    return {
        "id": file_id,
        "classType": class_type,
        "creationType": "LoadBalancedDistributed",
        "loadingStrategy": "EAGER",
        "entityLifecycle": "STATIC",
        "dataSource": {"sourceType": "json", "info": {"path": str(path)}},
    }


def activity(name: str, node: int, tick: int) -> dict:
    return {"kind": "Activity", "activityType": name, "nodeId": node,
            "endTime": {"kind": "AtTick", "tick": tick}}


def decision() -> dict:
    return {"kind": "PendingDecision", "decision": {
        "allowedModes": ["Car"], "strategyId": "travel-time", "weightsOverride": None}}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--mode", choices=("MESO", "MICRO"), default="MESO")
    parser.add_argument("--workers", type=int, choices=(1, 2), default=1)
    args = parser.parse_args()
    root = args.output_dir.resolve()
    build_scenario(root, str(root), 2, 2, 1, 1, 0, 0, 0, 0, 0.0, None, 4,
                   "travel-time", 42)
    data = root / "data"

    links = read(data / "links_0.json")
    city = read(data / "city_map.json")
    by_pair = {(item["data"]["content"]["from"], item["data"]["content"]["to"]): item
               for item in links}
    shortest = [by_pair[(1, 2)]["id"], by_pair[(2, 4)]["id"]]
    alternate = [by_pair[(1, 3)]["id"], by_pair[(3, 4)]["id"]]
    reverse = [by_pair[(4, 2)]["id"], by_pair[(2, 1)]["id"]]

    for item in links:
        content = item["data"]["content"]
        pair = (content["from"], content["to"])
        length = 250.0 if pair in {(1, 3), (3, 1), (3, 4), (4, 3)} else 200.0
        content["length"] = length
        content["capacity"] = 1.0 if pair == (1, 2) else 100.0
        content["currentSpeed"] = content["freeSpeed"]
        content["simulationMode"] = args.mode
    link_by_id = {item["id"]: item for item in links}
    for edge in city["edges"]:
        length = link_by_id[edge["label"]["id"]]["data"]["content"]["length"]
        edge["weight"] = length
        edge["label"]["length"] = length
    write(data / "links_0.json", links)
    write(data / "city_map.json", city)

    persons = read(data / "persons_0.json")
    cars = read(data / "cars_0.json")
    person, owned_car = persons[0], cars[0]
    person_state = person["data"]["content"]
    person_state["originalPlan"] = [
        activity("home", 1, 2 if args.mode == "MICRO" else 10), decision(), activity("work", 4, 100), decision(),
        activity("home", 1, 900), decision(), activity("work", 4, 1700),
    ]
    person_state["vehicleCurrentNode"] = {"car": 1}
    person_state["modeChoiceWeights"]["includedModes"] = ["car"]
    car_state = owned_car["data"]["content"]
    car_state.update({"origin": 1, "destination": 1, "currentNode": 1,
                      "scheduleOnTimeManager": False, "status": "Parked",
                      "bestRoute": None, "precomputedRoute": None})

    background = copy.deepcopy(owned_car)
    background_id = 9000002
    background["id"] = background_id
    background_state = background["data"]["content"]
    background_state.update({
        "startTick": 0, "scheduleOnTimeManager": True, "origin": 1, "destination": 4,
        "currentNode": 1, "status": "Start", "bestRoute": None,
        "precomputedRoute": [
            {"linkId": shortest[0], "nodeId": 2},
            {"linkId": shortest[1], "nodeId": 4},
        ],
    })
    background["dependencies"] = {
        "from_node": {"id": 1, "classType": "hybrid.actor.Node"},
        "to_node": {"id": 4, "classType": "hybrid.actor.Node"},
    }
    dynamic_cars = []
    for car_id, tick in ((9000003, 3 if args.mode == "MICRO" else 20), (9000004, 1700)):
        car = copy.deepcopy(background)
        car["id"] = car_id
        car["data"]["content"].update({"startTick": tick, "precomputedRoute": None})
        dynamic_cars.append(car)
    write(data / "persons_0.json", persons)
    write(data / "cars_0.json", [owned_car, background, *dynamic_cars])

    worker_id = 9000001
    routing_path = data / "routing_services_0.json"
    worker_ids = [worker_id + index * 10 for index in range(args.workers)]
    write(routing_path, [{
        "id": current_worker_id,
        "typeActor": "hybrid.actor.RoutingService",
        "data": {"dataType": "model.hybrid.entity.state.RoutingServiceState", "content": {
            "startTick": 0, "scheduleOnTimeManager": False, "costs": {}}},
        "dependencies": {},
    } for current_worker_id in worker_ids])

    simulation = read(root / "simulation.json")
    simulation.update({"duration": 2600, "endTick": 2600,
                       "description": "Independent two-path accounted-routing validation."})
    simulation["actorsDataSources"].insert(0, source(
        "routing_services_0", "hybrid.actor.RoutingService", routing_path))
    for policy, filename in (("discrete-event", "discrete-event.json"),
                             ("time-warp", "time-warp.json")):
        configured = copy.deepcopy(simulation)
        configured["id"] = f"accounted-routing-{policy}"
        configured["timeManagerType"] = policy
        write(root / filename, configured)
    (root / "simulation.json").unlink()
    write(root / "expected.json", {
        "workerId": worker_id,
        "workerIds": worker_ids,
        "mode": args.mode,
        "seed": 42,
        "backgroundCarId": background_id,
        "personCarId": owned_car["id"],
        "personId": person["id"],
        "expectedRoutes": {
            str(background_id): [shortest],
            str(owned_car["id"]): [alternate, reverse, shortest],
            "9000003": [alternate],
            "9000004": [shortest],
        },
        "expectedPersonTrips": 3,
        "expectedPersonDistance": 1300.0,
        "expectedVehicleJourneys": 6,
    })
    (root / "routing.conf").write_text(
        'htc.routing.cache-strategy="accounted"\n'
        f'htc.routing.accounted.worker-ids={json.dumps(worker_ids)}\n'
        'htc.routing.link-cost.publish-interval=0\n'
        'htc.report-manager.kafka.micro-update-report-every=1\n')
    print(root)


if __name__ == "__main__":
    main()
