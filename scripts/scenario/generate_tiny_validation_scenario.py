#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
from pathlib import Path


DEFAULT_OUTPUT_ROOT = Path(
    "/home/dean/PhD/scenarios/eqasim/sao-paulo/output/repair-fixed-v6"
)


def default_output_dir(plan_mode: str) -> Path:
    return DEFAULT_OUTPUT_ROOT / f"sp_tiny_validation_all_entities_{plan_mode.replace('-', '_')}"


def write_json(path: Path, payload: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n")


# `core.entity.actor.ActorSimulation.id` (and every entity-id-carrying field across the state
# classes this generator emits -- Node/Link/RailLink/BusStop/BusStation/Bus/SubwayStation/Subway/
# Person/Car ids, `from`/`to`, `dependencies[...].id`, `ownedVehicles[...].id`, city_map.json
# node/edge ids, transit_map.json `actorId`/`nodeId`) is `Long` since commit 755a3c7 ("fix:
# complete String->Long entity id migration across the actor layer", 2026-08-13). This script used
# to mint entity ids as human-readable strings like `"htcaid:node;mini_bus_terminal"` directly as
# the JSON `"id"`, which Jackson can no longer deserialize into a `Long` -- every EAGER and
# PROGRESSIVE load source failed with `Cannot deserialize value of type 'long' from String "..."`.
# `eid()` mints a stable, incrementing `Long` the first time a human-readable label is seen and
# returns the same `Long` for that label on every later call, so the same node referenced from
# `links_0.json`'s `from`/`to` and from `nodes_0.json`'s `id` still resolves to one shared id.
# Route/line labels (`bus_line`/`subway_line` below) are NOT entity ids -- per
# docs/ENTITY_ID_MIGRATION.md they stay plain strings (`NodeState.busStops`/`subwayStations`,
# `RailLinkState.subwayLine`, `TransitRoute.lineId`, `BusInformation.label`) and must never be
# passed through `eid()`.
class IdRegistry:
    def __init__(self, start: int = 1) -> None:
        self._next = start
        self._map: dict[str, int] = {}

    def get(self, key: str) -> int:
        if key not in self._map:
            self._map[key] = self._next
            self._next += 1
        return self._map[key]


_ID_REGISTRY = IdRegistry()


def eid(key: str) -> int:
    return _ID_REGISTRY.get(key)


def node_actor(node_id: int, latitude: float, longitude: float, links: list[int]) -> dict:
    return {
        "id": node_id,
        "typeActor": "hybrid.actor.Node",
        "data": {
            "dataType": "model.hybrid.entity.state.NodeState",
            "content": {
                "startTick": 0,
                "scheduleOnTimeManager": False,
                "latitude": latitude,
                "longitude": longitude,
                "links": links,
                "connections": {},
                "signals": {},
                "busStops": {},
                "subwayStations": {},
            },
        },
        "dependencies": {},
    }


def link_actor(
    link_id: str,
    from_node: str,
    to_node: str,
    length: float,
    speed_limit: float,
) -> dict:
    return {
        "id": link_id,
        "typeActor": "hybrid.actor.Link",
        "data": {
            "dataType": "model.hybrid.entity.state.LinkState",
            "content": {
                "startTick": 0,
                "scheduleOnTimeManager": False,
                "from": from_node,
                "to": to_node,
                "length": length,
                "lanes": 1,
                "speedLimit": speed_limit,
                "capacity": 600.0,
                "freeSpeed": speed_limit,
                "currentSpeed": speed_limit,
                "congestionFactor": 1.0,
                "registered": [],
                "simulationMode": "MESO",
                "microTimeStep": 0.1,
                "microTicksPerGlobalTick": 10,
                "vehiclesByLane": {},
                "laneConfigurations": [
                    {"laneId": 0, "type": "normal", "width": 3.5, "speedLimit": None}
                ],
            },
        },
        "dependencies": {
            "from_node": {"id": from_node, "classType": "hybrid.actor.Node"},
            "to_node": {"id": to_node, "classType": "hybrid.actor.Node"},
        },
    }


def rail_link_actor(
    rail_link_id: str,
    from_node: str,
    to_node: str,
    from_station: str,
    to_station: str,
    line_id: str,
    length: float,
) -> dict:
    return {
        "id": rail_link_id,
        "typeActor": "hybrid.actor.RailLink",
        "data": {
            "dataType": "model.hybrid.entity.state.RailLinkState",
            "content": {
                "startTick": 0,
                "from": from_node,
                "to": to_node,
                "length": length,
                "lanes": 1,
                "speedLimit": 80.0,
                "capacity": 60.0,
                "freeSpeed": 80.0,
                "railType": "SUBWAY",
                "subwayLine": line_id,
                "fromStation": from_station,
                "toStation": to_station,
                "gradient": 0.0,
                "curvature": 0.0,
                "simulationMode": "MESO",
                "registered": [],
                "scheduleOnTimeManager": False,
            },
        },
        "dependencies": {
            "from_node": {"id": from_node, "classType": "hybrid.actor.Node"},
            "to_node": {"id": to_node, "classType": "hybrid.actor.Node"},
        },
    }


def bus_stop_actor(stop_id: str, node_id: str, label: str) -> dict:
    return {
        "id": stop_id,
        "typeActor": "hybrid.actor.BusStop",
        "data": {
            "dataType": "model.hybrid.entity.state.BusStopState",
            "content": {
                "nodeId": node_id,
                "label": label,
                "people": {},
                "scheduleOnTimeManager": False,
            },
        },
        "dependencies": {
            "node": {"id": node_id, "classType": "hybrid.actor.Node"},
        },
    }


def bus_station_actor(
    station_id: str,
    line_label: str,
    origin_node: str,
    destination_node: str,
    stop_a_id: str,
    stop_b_id: str,
    bus_id: str,
) -> dict:
    return {
        "id": station_id,
        "typeActor": "hybrid.actor.BusStation",
        "data": {
            "dataType": "model.hybrid.entity.state.BusStationState",
            "content": {
                "startTick": 0,
                "name": f"{line_label} (tiny validation)",
                "origin": origin_node,
                "destination": destination_node,
                "busStops": {
                    stop_a_id: origin_node,
                    stop_b_id: destination_node,
                },
                "interval": 3600,
                "buses": [
                    {
                        "actorId": bus_id,
                        "label": line_label,
                        "capacity": 40,
                        "numberOfPorts": 2,
                        "size": 12.0,
                        "speedFactor": 1.0,
                    }
                ],
                "goingRoute": {},
                "goingBestCost": 1000000000000.0,
                "returningRoute": {},
                "returningBestCost": 1000000000000.0,
                "status": "Start",
            },
        },
        "dependencies": {
            origin_node: {"id": origin_node, "classType": "hybrid.actor.Node"},
            destination_node: {"id": destination_node, "classType": "hybrid.actor.Node"},
        },
    }


def subway_station_actor(
    station_id: str,
    node_id: str,
    name: str,
    line_id: str,
    next_station_id: str | None,
    next_node_id: str | None,
    rail_link_id: str | None,
    subway_actor_id: str | None,
) -> dict:
    is_terminal = subway_actor_id is not None
    lines = {line_id: {"interval": 600, "nextTick": 0}} if is_terminal else {}
    subways = (
        {
            line_id: [
                {
                    "line": line_id,
                    "actorId": subway_actor_id,
                    "capacity": 400,
                    "numberOfPorts": 4,
                    "velocity": 22.22,
                    "stopTime": 20,
                    "speedFactor": 1.0,
                }
            ]
        }
        if is_terminal
        else {}
    )
    lines_route = (
        {
            line_id: [
                {
                    "stationNode": {
                        "stationId": next_station_id,
                        "nodeId": next_node_id,
                    },
                    "railLinkId": rail_link_id,
                }
            ]
        }
        if is_terminal
        else {}
    )
    return {
        "id": station_id,
        "typeActor": "hybrid.actor.SubwayStation",
        "data": {
            "dataType": "model.hybrid.entity.state.SubwayStationState",
            "content": {
                "startTick": 0,
                "name": name,
                "nodeId": node_id,
                "terminal": is_terminal,
                "garage": is_terminal,
                "lines": lines,
                "subways": subways,
                "linesRoute": lines_route,
                "people": {},
                "status": "Start",
            },
        },
        "dependencies": {
            "node": {"id": node_id, "classType": "hybrid.actor.Node"},
        },
    }


# `PersonState.originalPlan` (List[PlanElement]) is what `Person.internStateStrings` seeds
# `cursor` from on scenario load -- NOT a `dailySchedule`/`currentActivityIndex`/
# `enableDynamicModeChoice`/`origin`/`destination`/`currentTripVehicleId` shape (a pre-redesign
# `PersonState`/legacy `ArrivalLogistics` schema the current engine no longer has fields for --
# `FAIL_ON_UNKNOWN_PROPERTIES` is off, so Jackson silently drops all of it and the person ends up
# with an empty plan that "completes" instantly with 0 real trips). See
# `model.hybrid.entity.state.PersonState`/`plan.PlanElement`/`plan.PendingDecision` for the
# authoritative schema this mirrors, and `scripts/scenario/generate_hybrid_scenario.py`'s
# `person_actor`/`build_original_plan` for the reference fix this ports (found+fixed there
# 2026-07-23, never back-ported here until now).
MODE_CHOICE_WEIGHTS = {
    "includedModes": ["bus", "subway", "walk", "car", "bicycle"],
    "betaMode": 1.0,
    "betaAccess": 0.001,
    "betaEgress": 0.001,
    "betaPrivateVehicle": 0.00005,
    "modePrefSubway": 2.0,
    "modePrefBus": 1.0,
    "modePrefWalk": 0.0,
    "modePrefCar": 3.0,
    "modePrefBicycle": 1.5,
    "modePrefMotorcycle": 2.0,
    "maxAccessDistanceM": 1500.0,
    "maxWalkDistanceM": 2000.0,
    "betaTravelTime": 0.01,
    "walkingSpeedMs": 1.4,
    "avgBusSpeedMs": 8.33,
    "avgSubwaySpeedMs": 12.5,
    "avgBicycleSpeedMs": 5.56,
    "avgMotorcycleSpeedMs": 11.11,
    "avgCarSpeedMs": 13.89,
}

ALL_CONCRETE_MODES = ["Walk", "Car", "Bicycle", "Motorcycle", "Bus", "Subway"]


def _activity(activity_type: str, node_id: int, end_tick: int) -> dict:
    return {
        "kind": "Activity",
        "activityType": activity_type,
        "nodeId": node_id,
        "endTime": {"kind": "AtTick", "tick": end_tick},
    }


def _pending_mode_decision(strategy_id: str = "travel-time") -> dict:
    return {
        "kind": "PendingDecision",
        "decision": {
            "allowedModes": ALL_CONCRETE_MODES,
            "strategyId": strategy_id,
            "weightsOverride": None,
        },
    }


def _transit_leg(line: str, boarding_stop_id: int, boarding_node_id: int, alighting_stop_id: int, alighting_node_id: int) -> dict:
    return {
        "kind": "TransitLeg",
        "mode": "Bus",
        "line": line,
        "boardingStop": {
            "actorId": boarding_stop_id,
            "actorClassType": "hybrid.actor.BusStop",
            "nodeId": boarding_node_id,
        },
        "alightingStop": {
            "actorId": alighting_stop_id,
            "actorClassType": "hybrid.actor.BusStop",
            "nodeId": alighting_node_id,
        },
    }


def build_original_plan(plan_mode: str, ids: dict[str, int | str]) -> list[dict]:
    """`home -> work -> home`, 2 trips total (`completedTrips` reaches 2, never 3 -- the last
    Activity's `endTime` is exactly `endTick`, so the person never actually departs it before the
    simulation force-terminates; see `person_schedule_complete` caveat in
    `generate_hybrid_scenario.py`'s docstring, same reasoning applies here).

    - `"auto"`: every leg is an unresolved `PendingDecision` (dynamic mode choice, resolved at run
      time by `ModeDecisionEngineRegistry`'s `"travel-time"` engine from `ownedVehicles`/
      `modeChoiceWeights`).
    - `"static-bus"`: every leg is a pre-resolved `TransitLeg` (fixed mode, bus) -- exercises the
      already-resolved-`AtomicLeg` path instead of `PendingResolutionRequired`, distinct coverage
      from `"auto"`. Both trip nodes coincide with the bus stop's own node here, so no access/
      egress `WalkLeg` is needed around the `TransitLeg`.
    """
    if plan_mode == "auto":
        return [
            _activity("home", ids["bus_node_a"], 300),
            _pending_mode_decision(),
            _activity("work", ids["bus_node_b"], 1800),
            _pending_mode_decision(),
            _activity("home", ids["bus_node_a"], 86400),
        ]

    return [
        _activity("home", ids["bus_node_a"], 300),
        _transit_leg(ids["bus_line"], ids["bus_stop_a"], ids["bus_node_a"], ids["bus_stop_b"], ids["bus_node_b"]),
        _activity("work", ids["bus_node_b"], 1800),
        _transit_leg(ids["bus_line"], ids["bus_stop_b"], ids["bus_node_b"], ids["bus_stop_a"], ids["bus_node_a"]),
        _activity("home", ids["bus_node_a"], 86400),
    ]


def person_actor(person_id: int, plan_mode: str, ids: dict[str, int | str]) -> dict:
    original_plan = build_original_plan(plan_mode, ids)
    return {
        "id": person_id,
        "typeActor": "hybrid.actor.Person",
        "data": {
            "dataType": "model.hybrid.entity.state.PersonState",
            "content": {
                "startTick": 0,
                "scheduleOnTimeManager": True,
                "originalPlan": original_plan,
                "ownedVehicles": {},
                "vehicleCurrentNode": {},
                "totalDistanceTraveled": 0.0,
                "completedTrips": 0,
                "ptWaitTimeoutTicks": 86400,
                "modeChoiceWeights": MODE_CHOICE_WEIGHTS,
            },
        },
        "dependencies": {},
    }


def car_actor(car_id: str, node_id: str) -> dict:
    return {
        "id": car_id,
        "typeActor": "hybrid.actor.Car",
        "data": {
            "dataType": "model.hybrid.entity.state.CarState",
            "content": {
                "startTick": 0,
                "scheduleOnTimeManager": False,
                "origin": node_id,
                "destination": node_id,
                "actorType": "Car",
                "size": 4.5,
                "currentSimulationMode": "MESO",
                "microState": None,
                "status": "Parked",
                "bestRoute": None,
                "currentNode": node_id,
                "distance": 0.0,
            },
        },
        "dependencies": {
            "from_node": {"id": node_id, "classType": "hybrid.actor.Node"},
            "to_node": {"id": node_id, "classType": "hybrid.actor.Node"},
        },
    }


def city_map_payload(nodes: list[dict], bus_ab: dict, bus_ba: dict, rail_ab: dict, rail_ba: dict) -> dict:
    return {
        "nodes": [
            {
                "id": node["id"],
                "classType": "hybrid.actor.Node",
                # `NodeGraph.resourceId` is `String` (unlike `id`, which is `Long`) -- see
                # docs/ENTITY_ID_MIGRATION.md.
                "resourceId": str(node["id"]),
                "latitude": node["data"]["content"]["latitude"],
                "longitude": node["data"]["content"]["longitude"],
            }
            for node in nodes
        ],
        "edges": [
            {
                "source_id": bus_ab["data"]["content"]["from"],
                "target_id": bus_ab["data"]["content"]["to"],
                "weight": bus_ab["data"]["content"]["length"],
                "label": {
                    "id": bus_ab["id"],
                    "resourceId": str(bus_ab["id"]),
                    "classType": "hybrid.actor.Link",
                    "length": bus_ab["data"]["content"]["length"],
                },
            },
            {
                "source_id": bus_ba["data"]["content"]["from"],
                "target_id": bus_ba["data"]["content"]["to"],
                "weight": bus_ba["data"]["content"]["length"],
                "label": {
                    "id": bus_ba["id"],
                    "resourceId": str(bus_ba["id"]),
                    "classType": "hybrid.actor.Link",
                    "length": bus_ba["data"]["content"]["length"],
                },
            },
            {
                "source_id": rail_ab["data"]["content"]["from"],
                "target_id": rail_ab["data"]["content"]["to"],
                "weight": rail_ab["data"]["content"]["length"],
                "label": {
                    "id": rail_ab["id"],
                    "resourceId": str(rail_ab["id"]),
                    "classType": "hybrid.actor.RailLink",
                    "length": rail_ab["data"]["content"]["length"],
                },
            },
            {
                "source_id": rail_ba["data"]["content"]["from"],
                "target_id": rail_ba["data"]["content"]["to"],
                "weight": rail_ba["data"]["content"]["length"],
                "label": {
                    "id": rail_ba["id"],
                    "resourceId": str(rail_ba["id"]),
                    "classType": "hybrid.actor.RailLink",
                    "length": rail_ba["data"]["content"]["length"],
                },
            },
        ],
        "directed": True,
    }


def transit_map_payload(
    bus_stop_a_id: str,
    bus_stop_b_id: str,
    subway_station_a_id: str,
    subway_station_b_id: str,
    bus_line_id: str,
    subway_line_id: str,
    node_meta: dict[str, tuple[float, float]],
    bus_node_a: str,
    bus_node_b: str,
    subway_node_a: str,
    subway_node_b: str,
) -> list[dict]:
    return [
        {
            "id": "htcaid:stop;bus_mini_terminal",
            "actorId": bus_stop_a_id,
            "actorClassType": "hybrid.actor.BusStop",
            "nodeId": bus_node_a,
            "latitude": node_meta[bus_node_a][0],
            "longitude": node_meta[bus_node_a][1],
            "stopType": "bus",
            "lines": [bus_line_id],
        },
        {
            "id": "htcaid:stop;bus_mini_mid",
            "actorId": bus_stop_b_id,
            "actorClassType": "hybrid.actor.BusStop",
            "nodeId": bus_node_b,
            "latitude": node_meta[bus_node_b][0],
            "longitude": node_meta[bus_node_b][1],
            "stopType": "bus",
            "lines": [bus_line_id],
        },
        {
            "id": "htcaid:stop;subway_mini_terminal",
            "actorId": subway_station_a_id,
            "actorClassType": "hybrid.actor.SubwayStation",
            "nodeId": subway_node_a,
            "latitude": node_meta[subway_node_a][0],
            "longitude": node_meta[subway_node_a][1],
            "stopType": "subway",
            "lines": [subway_line_id],
        },
        {
            "id": "htcaid:stop;subway_mini_dest",
            "actorId": subway_station_b_id,
            "actorClassType": "hybrid.actor.SubwayStation",
            "nodeId": subway_node_b,
            "latitude": node_meta[subway_node_b][0],
            "longitude": node_meta[subway_node_b][1],
            "stopType": "subway",
            "lines": [subway_line_id],
        },
    ]


def transit_routes_payload(bus_line_id: str, subway_line_id: str) -> list[dict]:
    return [
        {
            "lineId": bus_line_id,
            "stopType": "bus",
            "headwaySeconds": 3600,
            "stops": [
                {"stopId": "htcaid:stop;bus_mini_terminal", "travelTimeFromPrevSeconds": 0},
                {"stopId": "htcaid:stop;bus_mini_mid", "travelTimeFromPrevSeconds": 60},
            ],
        },
        {
            "lineId": subway_line_id,
            "stopType": "subway",
            "headwaySeconds": 600,
            "stops": [
                {"stopId": "htcaid:stop;subway_mini_terminal", "travelTimeFromPrevSeconds": 0},
                {"stopId": "htcaid:stop;subway_mini_dest", "travelTimeFromPrevSeconds": 120},
            ],
        },
    ]


def simulation_payload(
    output_dir: Path,
    data_path_prefix: str,
    name: str = "São Paulo EQAsim – tiny validation all entities",
    description: str = (
        "Tiny self-contained validation scenario with bus, subway, person, car, "
        "nodes, links, bus stops, bus station, and rail link."
    ),
) -> dict:
    def source_entry(file_id: str, class_type: str, loading: str, lifecycle: str) -> dict:
        return {
            "id": file_id,
            "classType": class_type,
            "creationType": "LoadBalancedDistributed",
            "loadingStrategy": loading,
            "entityLifecycle": lifecycle,
            "dataSource": {
                "sourceType": "json",
                "info": {"path": f"{data_path_prefix}/data/{file_id}.json"},
            },
        }

    return {
        "id": output_dir.name,
        "name": name,
        "description": description,
        "startTick": 0,
        "endTick": 86400,
        "startRealTime": "2025-01-01T00:00:00.000",
        "timeUnit": "seconds",
        "timeStep": 1.0,
        "duration": 86400,
        "tickDuration": 1.0,
        "randomSeed": 42,
        "actorsDataSources": [
            source_entry("nodes_0", "hybrid.actor.Node", "EAGER", "STATIC"),
            source_entry("links_0", "hybrid.actor.Link", "EAGER", "STATIC"),
            source_entry("persons_0", "hybrid.actor.Person", "PROGRESSIVE", "DYNAMIC"),
            source_entry("cars_0", "hybrid.actor.Car", "PROGRESSIVE", "DYNAMIC"),
            source_entry("bus_stops_0", "hybrid.actor.BusStop", "EAGER", "STATIC"),
            source_entry("bus_stations_0", "hybrid.actor.BusStation", "EAGER", "STATIC"),
            source_entry("subway_stations_0", "hybrid.actor.SubwayStation", "EAGER", "STATIC"),
            source_entry("rail_links_0", "hybrid.actor.RailLink", "EAGER", "STATIC"),
        ],
        "cityMapFile": f"{data_path_prefix}/data/city_map.json",
        "warmupTargets": ["org.interscity.htc.model.hybrid.util.CityMapUtil#warmUp"],
        "postLoadRegistrationClasses": [
            "org.interscity.htc.model.hybrid.actor.BusStop",
            "org.interscity.htc.model.hybrid.actor.BusStation",
            "org.interscity.htc.model.hybrid.actor.SubwayStation",
        ],
        "transitMapFile": f"{data_path_prefix}/data/transit_map.json",
        "transitRoutesFile": f"{data_path_prefix}/data/transit_routes.json",
    }


def build_scenario(output_dir: Path, data_path_prefix: str, plan_mode: str) -> None:
    # Every value here is a `Long` entity id minted by `eid()` from a human-readable label, except
    # `bus_line`/`subway_line` -- route/line labels, kept as plain strings per
    # docs/ENTITY_ID_MIGRATION.md (`NodeState.busStops`/`subwayStations` keys,
    # `RailLinkState.subwayLine`, `TransitRoute.lineId`, `BusInformation.label`, `TransitLeg.line`).
    ids: dict[str, int | str] = {
        "bus_node_a": eid("htcaid:node;mini_bus_terminal"),
        "bus_node_b": eid("htcaid:node;mini_bus_mid"),
        "person_node": eid("htcaid:node;mini_person_home"),
        "subway_node_a": eid("htcaid:node;mini_subway_terminal"),
        "subway_node_b": eid("htcaid:node;mini_subway_dest"),
        "bus_link_ab": eid("htcaid:link;mini_bus_ab"),
        "bus_link_ba": eid("htcaid:link;mini_bus_ba"),
        "rail_link_ab": eid("htcaid:rail_link;mini_subway_ab"),
        "rail_link_ba": eid("htcaid:rail_link;mini_subway_ba"),
        "bus_stop_a": eid("htcaid:busstop;mini_terminal"),
        "bus_stop_b": eid("htcaid:busstop;mini_mid"),
        "bus_station": eid("htcaid:busstation;mini_loop"),
        "bus_actor": eid("htcaid:bus;mini_loop_bus_0"),
        "person": eid("htcaid:person;tiny_0"),
        "car": eid("htcaid:car;tiny_0"),
        "subway_station_a": eid("htcaid:subwaystation;mini_terminal"),
        "subway_station_b": eid("htcaid:subwaystation;mini_dest"),
        "subway_actor": eid("htcaid:subway;mini_line_train_0"),
        "bus_line": "mini_bus_loop",
        "subway_line": "mini_subway_line",
    }

    node_meta = {
        ids["bus_node_a"]: (-23.4315944, -46.7929983),
        ids["bus_node_b"]: (-23.4357649, -46.7867581),
        ids["person_node"]: (-23.5540220, -46.6711080),
        ids["subway_node_a"]: (-23.6588121, -46.7678791),
        ids["subway_node_b"]: (-23.6485000, -46.7630000),
    }

    nodes = [
        node_actor(ids["bus_node_a"], *node_meta[ids["bus_node_a"]], [ids["bus_link_ab"]]),
        node_actor(ids["bus_node_b"], *node_meta[ids["bus_node_b"]], [ids["bus_link_ba"]]),
        node_actor(ids["person_node"], *node_meta[ids["person_node"]], []),
        node_actor(ids["subway_node_a"], *node_meta[ids["subway_node_a"]], [ids["rail_link_ab"]]),
        node_actor(ids["subway_node_b"], *node_meta[ids["subway_node_b"]], [ids["rail_link_ba"]]),
    ]

    bus_link_ab = link_actor(ids["bus_link_ab"], ids["bus_node_a"], ids["bus_node_b"], 500.0, 40.0)
    bus_link_ba = link_actor(ids["bus_link_ba"], ids["bus_node_b"], ids["bus_node_a"], 500.0, 40.0)
    rail_link_ab = rail_link_actor(
        ids["rail_link_ab"],
        ids["subway_node_a"],
        ids["subway_node_b"],
        ids["subway_station_a"],
        ids["subway_station_b"],
        ids["subway_line"],
        1200.0,
    )
    rail_link_ba = rail_link_actor(
        ids["rail_link_ba"],
        ids["subway_node_b"],
        ids["subway_node_a"],
        ids["subway_station_b"],
        ids["subway_station_a"],
        ids["subway_line"],
        1200.0,
    )

    data_dir = output_dir / "data"
    write_json(data_dir / "nodes_0.json", nodes)
    write_json(data_dir / "links_0.json", [bus_link_ab, bus_link_ba])
    write_json(
        data_dir / "bus_stops_0.json",
        [
            bus_stop_actor(ids["bus_stop_a"], ids["bus_node_a"], "Mini Terminal"),
            bus_stop_actor(ids["bus_stop_b"], ids["bus_node_b"], "Mini Mid"),
        ],
    )
    write_json(
        data_dir / "bus_stations_0.json",
        [
            bus_station_actor(
                ids["bus_station"],
                ids["bus_line"],
                ids["bus_node_a"],
                ids["bus_node_b"],
                ids["bus_stop_a"],
                ids["bus_stop_b"],
                ids["bus_actor"],
            )
        ],
    )
    write_json(data_dir / "persons_0.json", [person_actor(ids["person"], plan_mode, ids)])
    write_json(data_dir / "cars_0.json", [car_actor(ids["car"], ids["person_node"])])
    station_terminal = subway_station_actor(
        ids["subway_station_a"],
        ids["subway_node_a"],
        "Mini Terminal",
        ids["subway_line"],
        ids["subway_station_b"],
        ids["subway_node_b"],
        ids["rail_link_ab"],
        ids["subway_actor"],
    )
    # Add return leg: dest → terminal via rail_link_ba
    station_terminal["data"]["content"]["linesRoute"][ids["subway_line"]].append({
        "stationNode": {
            "stationId": ids["subway_station_a"],
            "nodeId": ids["subway_node_a"],
        },
        "railLinkId": ids["rail_link_ba"],
    })
    write_json(
        data_dir / "subway_stations_0.json",
        [
            station_terminal,
            subway_station_actor(
                ids["subway_station_b"],
                ids["subway_node_b"],
                "Mini Destination",
                ids["subway_line"],
                None,
                None,
                None,
                None,
            ),
        ],
    )
    write_json(data_dir / "rail_links_0.json", [rail_link_ab, rail_link_ba])
    write_json(data_dir / "city_map.json", city_map_payload(nodes, bus_link_ab, bus_link_ba, rail_link_ab, rail_link_ba))
    write_json(
        data_dir / "transit_map.json",
        transit_map_payload(
            ids["bus_stop_a"],
            ids["bus_stop_b"],
            ids["subway_station_a"],
            ids["subway_station_b"],
            ids["bus_line"],
            ids["subway_line"],
            node_meta,
            ids["bus_node_a"],
            ids["bus_node_b"],
            ids["subway_node_a"],
            ids["subway_node_b"],
        ),
    )
    write_json(data_dir / "transit_routes.json", transit_routes_payload(ids["bus_line"], ids["subway_line"]))
    write_json(output_dir / "simulation.json", simulation_payload(output_dir, data_path_prefix))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate a tiny all-entities validation scenario.")
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=None,
        help="Directory where the scenario will be written.",
    )
    parser.add_argument(
        "--person-plan-mode",
        choices=["auto", "static-bus"],
        default="auto",
        help="How the person trip plan should be generated.",
    )
    parser.add_argument(
        "--data-path-prefix",
        default=None,
        help="Prefix written inside simulation.json for data file paths. Defaults to --output-dir.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    output_dir = (args.output_dir or default_output_dir(args.person_plan_mode)).resolve()
    data_path_prefix = args.data_path_prefix or str(output_dir)
    build_scenario(output_dir, data_path_prefix, args.person_plan_mode)
    print(f"Tiny validation scenario ({args.person_plan_mode}) written to {output_dir}")


if __name__ == "__main__":
    main()