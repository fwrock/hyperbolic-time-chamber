#!/usr/bin/env python3

"""General-purpose hybrid-model scenario generator for smoke-testing HTC end-to-end.

Builds a small, fully controlled NxM grid of nodes/links (never city-scale), with a
configurable MESO/MICRO link mix and a configurable population of persons, cars, bus
lines and subway lines. Output has the exact JSON shape produced by
`generate_tiny_validation_scenario.py` (same actor schema, same simulation.json
wiring), so it loads through the same LoadDataManager / ScenarioPreflightValidator
path with no engine changes.

Connectivity is by construction, not by a separate validator: the grid is bidirectionally
connected between orthogonal neighbors, so every node can reach every other node over the
Link graph, which is what person dailySchedule origin/destination routing relies on.
"""

from __future__ import annotations

import argparse
import random
from pathlib import Path

from generate_tiny_validation_scenario import (
    bus_station_actor,
    bus_stop_actor,
    car_actor,
    link_actor,
    node_actor,
    rail_link_actor,
    simulation_payload,
    subway_station_actor,
    write_json,
)

GRID_EDGE_LENGTH_M = 200.0
GRID_SPEED_LIMIT_KMH = 40.0
GRID_LAT_STEP = 0.003
GRID_LON_STEP = 0.003
BASE_LAT = -23.55
BASE_LON = -46.63

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


class IdRegistry:
    """Maps a readable `htcaid:...` key to a stable, unique, non-zero Long entity id.

    Entity ids are `Long` end-to-end in the model (see `docs/ENTITY_ID_MIGRATION.md`); this
    generator keeps the human-readable `htcaid:...` strings internally (useful for debugging
    the script itself and for guaranteeing per-kind uniqueness) and only maps them to sequential
    Longs at the point each id is minted, before any actor JSON is built. `0` is reserved as the
    "unset" sentinel elsewhere in the model, so the counter starts at 1.
    """

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


def grid_node_id(row: int, col: int) -> int:
    return eid(f"htcaid:node;grid_r{row}_c{col}")


def build_grid_nodes(rows: int, cols: int) -> tuple[list[dict], dict[int, tuple[float, float]]]:
    node_meta: dict[int, tuple[float, float]] = {}
    for row in range(rows):
        for col in range(cols):
            node_meta[grid_node_id(row, col)] = (
                BASE_LAT + row * GRID_LAT_STEP,
                BASE_LON + col * GRID_LON_STEP,
            )
    # links populated once link ids are known; nodes are finalized in build_grid_links.
    return [], node_meta


def build_grid_links(rows: int, cols: int, rng: random.Random, micro_pct: float, micro_count: int | None) -> tuple[list[dict], dict[int, list[int]]]:
    """Bidirectional grid: every node connects to its right and down neighbor both ways.

    A grid with edges in both directions is connected by construction — no separate
    reachability check is needed for person origin/destination routing.
    """
    edges: list[tuple[int, int]] = []
    for row in range(rows):
        for col in range(cols):
            here = grid_node_id(row, col)
            if col + 1 < cols:
                right = grid_node_id(row, col + 1)
                edges.append((here, right))
                edges.append((right, here))
            if row + 1 < rows:
                down = grid_node_id(row + 1, col)
                edges.append((here, down))
                edges.append((down, here))

    total = len(edges)
    if micro_count is not None:
        micro_edge_count = min(max(micro_count, 0), total)
    else:
        micro_edge_count = round(total * max(0.0, min(micro_pct, 100.0)) / 100.0)
    micro_indices = set(rng.sample(range(total), micro_edge_count)) if total else set()

    links: list[dict] = []
    outgoing: dict[int, list[int]] = {}
    for index, (from_node, to_node) in enumerate(edges):
        link_id = eid(f"htcaid:link;grid_{index}")
        link = link_actor(link_id, from_node, to_node, GRID_EDGE_LENGTH_M, GRID_SPEED_LIMIT_KMH)
        if index in micro_indices:
            link["data"]["content"]["simulationMode"] = "MICRO"
        links.append(link)
        outgoing.setdefault(from_node, []).append(link_id)

    return links, outgoing


def build_bus_lines(node_ids: list[int], count: int, rng: random.Random) -> tuple[list[dict], list[dict], list[dict]]:
    """Each line gets a BusStation with 2 stops on distinct grid nodes plus 1 bus."""
    bus_stops: list[dict] = []
    bus_stations: list[dict] = []
    lines: list[dict] = []
    for i in range(count):
        origin, destination = rng.sample(node_ids, 2)
        # `line_id` is a route/line label (TransitRoute.lineId, BusInformation.label), not an
        # entity id -- kept as a plain String per docs/ENTITY_ID_MIGRATION.md, never run through
        # `eid()`.
        line_id = f"mini_bus_line_{i}"
        stop_a_id = eid(f"htcaid:busstop;line{i}_a")
        stop_b_id = eid(f"htcaid:busstop;line{i}_b")
        station_id = eid(f"htcaid:busstation;line{i}")
        bus_actor_id = eid(f"htcaid:bus;line{i}_bus_0")
        bus_stops.append(bus_stop_actor(stop_a_id, origin, f"Line {i} Terminal A"))
        bus_stops.append(bus_stop_actor(stop_b_id, destination, f"Line {i} Terminal B"))
        bus_stations.append(
            bus_station_actor(station_id, line_id, origin, destination, stop_a_id, stop_b_id, bus_actor_id)
        )
        lines.append(
            {
                "line_id": line_id,
                "stop_a_id": stop_a_id,
                "stop_b_id": stop_b_id,
                "node_a": origin,
                "node_b": destination,
            }
        )
    return bus_stops, bus_stations, lines


def build_subway_lines(node_ids: list[int], count: int, rng: random.Random) -> tuple[list[dict], list[dict], list[dict]]:
    """Each line gets 2 SubwayStation actors (terminal + destination) and a pair of RailLinks."""
    subway_stations: list[dict] = []
    rail_links: list[dict] = []
    lines: list[dict] = []
    for i in range(count):
        node_a, node_b = rng.sample(node_ids, 2)
        # Route/line label (SubwayLineInformation key, RailLinkState.subwayLine), not an entity
        # id -- stays a plain String, never run through `eid()`.
        line_id = f"mini_subway_line_{i}"
        station_a_id = eid(f"htcaid:subwaystation;line{i}_a")
        station_b_id = eid(f"htcaid:subwaystation;line{i}_b")
        rail_ab_id = eid(f"htcaid:rail_link;line{i}_ab")
        rail_ba_id = eid(f"htcaid:rail_link;line{i}_ba")
        subway_actor_id = eid(f"htcaid:subway;line{i}_train_0")

        rail_ab = rail_link_actor(rail_ab_id, node_a, node_b, station_a_id, station_b_id, line_id, 1000.0)
        rail_ba = rail_link_actor(rail_ba_id, node_b, node_a, station_b_id, station_a_id, line_id, 1000.0)
        rail_links.append(rail_ab)
        rail_links.append(rail_ba)

        terminal = subway_station_actor(
            station_a_id, node_a, f"Line {i} Terminal", line_id, station_b_id, node_b, rail_ab_id, subway_actor_id
        )
        terminal["data"]["content"]["linesRoute"][line_id].append(
            {"stationNode": {"stationId": station_a_id, "nodeId": node_a}, "railLinkId": rail_ba_id}
        )
        destination = subway_station_actor(station_b_id, node_b, f"Line {i} Destination", line_id, None, None, None, None)
        subway_stations.append(terminal)
        subway_stations.append(destination)
        lines.append(
            {
                "line_id": line_id,
                "station_a_id": station_a_id,
                "station_b_id": station_b_id,
                "node_a": node_a,
                "node_b": node_b,
            }
        )
    return subway_stations, rail_links, lines


ALL_CONCRETE_MODES = ["Walk", "Car", "Bicycle", "Motorcycle", "Bus", "Subway"]

# Built-in `model.hybrid.decision.ModeDecisionEngine.id`s registered in
# `ModeDecisionEngineRegistry`. `PendingDecision.decision.strategyId` must match one of these or
# `ScenarioPreflightValidator`/`ScenarioLoadValidator` reject the scenario at load time.
#
#   "travel-time"          — TravelTimeEngine/TravelTimeModeChoiceStrategy. Evaluates ALL modes
#                             (walk/car/bicycle/motorcycle/bus/subway) with a cheap approximation,
#                             then — as of 2026-07-23 — validates a *winning* bus/subway candidate
#                             with one real RAPTOR search before committing to it (replacing the
#                             guessed leg with RAPTOR's real, possibly multi-leg itinerary, or
#                             re-picking with PT masked out if RAPTOR finds no real path at all).
#                             Deterministic: same origin/destination/weights/owned vehicles always
#                             produces the exact same choice.
#   "travel-time-logit"    — TravelTimeLogitEngine. Identical candidate scoring and RAPTOR
#                             validation to "travel-time", but PICKS by sampling a multinomial
#                             logit distribution over the candidates' scores instead of always
#                             taking the argmax (random utility theory — McFadden 1974: real
#                             populations split probabilistically between close-scoring options,
#                             not 100/0). Still fully reproducible under a fixed `randomSeed` (draws
#                             from `RandomSeedManager`'s seeded RNG) — it just no longer degenerates
#                             to "the same person always makes the identical choice". Use this
#                             (not "travel-time") whenever mode-*share* realism across a population
#                             matters, not just single-trip correctness.
#   "raptor"                — RaptorMultiModalEngine/RaptorRouter. Real round-based, frequency-aware
#                             transit routing — transit modes only (bus/subway; never walk-only,
#                             never private vehicles). Feasibility is guaranteed by construction
#                             (a result only exists if a real path through the network does), and it
#                             returns a *full* itinerary: access walk (origin -> first boarding
#                             stop) + one TransitLeg per leg + a transfer WalkLeg between any two
#                             legs whose nodes differ + egress walk (last alighting stop ->
#                             destination). Requires `TransitRouteUtil.isAvailable` (i.e.
#                             `transit_routes.json` present — this generator always writes one). No
#                             fallback to another mode when it finds nothing — the trip is aborted
#                             and the person resumes at the next Activity with zero real travel
#                             (see `htc-scenario-qa`'s 2026-07-23 finding on this).
#   "nearest-stop-utility"  — walk + bus/subway only, no private vehicles, no RAPTOR-grade routing.
#
# Every leg in this generator is left as an unresolved `PendingDecision` — mode choice is always
# DYNAMIC (resolved by the engine at run time from `ownedVehicles`/`modeChoiceWeights`), never a
# precomputed/"fixed" `AtomicLeg` (`WalkLeg`/`PrivateVehicleLeg`/`TransitLeg` placed directly in
# `originalPlan`, skipping `ModeDecisionEngineRegistry` entirely). Fixed-leg scenarios exercise a
# different code path (`onLegAdvanced` never sees a `PendingResolutionRequired`) and are not
# covered by this generator as of 2026-07-23 — call this out explicitly if asked "is mode choice
# fixed or dynamic" rather than assuming dynamic coverage implies fixed-leg coverage too.
MODE_DECISION_STRATEGY_ID = "travel-time"


def _activity(activity_type: str, node_id: int, end_tick: int) -> dict:
    return {
        "kind": "Activity",
        "activityType": activity_type,
        "nodeId": node_id,
        "endTime": {"kind": "AtTick", "tick": end_tick},
    }


def _pending_mode_decision(strategy_id: str = MODE_DECISION_STRATEGY_ID) -> dict:
    return {
        "kind": "PendingDecision",
        "decision": {
            "allowedModes": ALL_CONCRETE_MODES,
            "strategyId": strategy_id,
            "weightsOverride": None,
        },
    }


def build_original_plan(
    rng: random.Random,
    node_ids: list[int],
    home_node: int,
    num_activities: int,
    duration: int,
    strategy_id: str = MODE_DECISION_STRATEGY_ID,
) -> list[dict]:
    """Builds `home -> [intermediate activities] -> home`, `num_activities - 1` trips total.

    Trip end-times are compressed near the start of the day (300, 1800, 3300, ...) rather than
    spread realistically across 24h — this is a smoke-test generator, not a demand model, and
    compressed timing means a wall-clock-bounded run actually observes every trip instead of most
    of the day being silent activity-dwell time. The *last* activity's `endTime` is always exactly
    `duration` (matching the 2-activity case this generalizes): the person never actually departs
    it before the simulation force-terminates, so `completedTrips` reaches `num_activities - 1`,
    never `num_activities` — see `person_actor`'s docstring on why `person_schedule_complete` is
    the wrong signal to check for this reason.
    """
    if num_activities < 2:
        raise ValueError("num_activities must be >= 2 (at least one trip: home -> ... -> home)")

    activity_types = ["home"] + [
        rng.choice(["work", "leisure", "shopping", "school"]) for _ in range(num_activities - 2)
    ] + ["home"]
    intermediate_pool = [n for n in node_ids if n != home_node] or node_ids
    intermediate_nodes = [
        rng.choice(intermediate_pool) for _ in range(num_activities - 2)
    ]
    nodes = [home_node] + intermediate_nodes + [home_node]

    end_times = [300 + 1500 * i for i in range(num_activities - 1)] + [duration]

    plan: list[dict] = []
    for i in range(num_activities):
        plan.append(_activity(activity_types[i], nodes[i], end_times[i]))
        if i < num_activities - 1:
            plan.append(_pending_mode_decision(strategy_id))
    return plan


def person_actor(
    person_id: int,
    origin_node: int,
    original_plan: list[dict],
    owned_vehicles: dict[str, int] | None = None,
) -> dict:
    # `PersonState.originalPlan` (List[PlanElement]) is what `Person.internStateStrings` seeds
    # `cursor` from on scenario load — NOT a `dailySchedule`/`currentActivityIndex` pair, which is
    # a pre-redesign shape the current engine silently ignores (Jackson drops unknown properties),
    # leaving the person with an empty plan that completes instantly with 0 trips. Each leg is left
    # as an unresolved `PendingDecision` so the scenario-configured mode-choice engine picks the
    # concrete mode at run time, matching `enableDynamicModeChoice=True` from the legacy shape this
    # replaces. See `model.hybrid.entity.state.PersonState`/`PlanElement`/`PersonStateJsonSpec` for
    # the authoritative schema this mirrors. Callers build `original_plan` via `build_original_plan`.

    # `PersonState.ownedVehicles`/`vehicleCurrentNode` are keyed by the lowercase private-vehicle
    # mode string ("car"/"bicycle"/"motorcycle" — see `TravelTimeModeChoiceStrategy.PrivateModes`),
    # not the `ConcreteMode` enum name. `PrivateVehicleCandidates.available` only offers a vehicle
    # to the decision engine when `vehicleCurrentNode` matches the person's current node, so each
    # owned vehicle must be parked at `origin_node` (the person's first Activity) to ever be
    # selected for the first PendingDecision.
    owned_vehicles = owned_vehicles or {}
    owned_vehicles_content = {
        mode: {"id": vehicle_id, "resourceId": "", "classType": PRIVATE_VEHICLE_CLASS_TYPE[mode]}
        for mode, vehicle_id in owned_vehicles.items()
    }
    vehicle_current_node = {mode: origin_node for mode in owned_vehicles}
    return {
        "id": person_id,
        "typeActor": "hybrid.actor.Person",
        "data": {
            "dataType": "model.hybrid.entity.state.PersonState",
            "content": {
                "startTick": 0,
                "scheduleOnTimeManager": True,
                "originalPlan": original_plan,
                "ownedVehicles": owned_vehicles_content,
                "vehicleCurrentNode": vehicle_current_node,
                "totalDistanceTraveled": 0.0,
                "completedTrips": 0,
                "ptWaitTimeoutTicks": 86400,
                "modeChoiceWeights": MODE_CHOICE_WEIGHTS,
            },
        },
        "dependencies": {},
    }


# mode key (PersonState.ownedVehicles / TravelTimeModeChoiceStrategy.PrivateModes) -> Link/Node
# actor classType referenced by the vehicle's own Identify.
PRIVATE_VEHICLE_CLASS_TYPE = {
    "car": "hybrid.actor.Car",
    "bicycle": "hybrid.actor.Bicycle",
    "motorcycle": "hybrid.actor.Motorcycle",
}


def private_vehicle_actor(vehicle_id: int, node_id: int, mode: str) -> dict:
    """Builds a Car/Bicycle/Motorcycle actor JSON — same `MovableState`-derived shape for all
    three (`origin`/`destination`/`actorType`/`size` are the only fields `CarState`/`BicycleState`/
    `MotorcycleState`'s primary constructors actually declare; `status`/`bestRoute`/`currentNode`
    are included for parity with `generate_tiny_validation_scenario.car_actor` but are inert at
    deserialization — those three names don't match the `movableStatus`/`movableBestRoute`/
    `movableCurrentNode` constructor params `MovableState` actually declares, so Jackson silently
    drops them (`FAIL_ON_UNKNOWN_PROPERTIES=false`) and the vehicle starts at `MovableState`'s own
    defaults regardless. Harmless today because `Person`/`PrivateVehicle` set real status via
    `updateStatus`/`updateBestRoute` on trip start, not from this JSON — but don't extend this
    field list expecting it to configure initial state).
    """
    state_type = {"car": "CarState", "bicycle": "BicycleState", "motorcycle": "MotorcycleState"}[mode]
    actor_type = {"car": "Car", "bicycle": "Bicycle", "motorcycle": "Motorcycle"}[mode]
    size = {"car": 4.5, "bicycle": 1.8, "motorcycle": 2.2}[mode]
    class_type = PRIVATE_VEHICLE_CLASS_TYPE[mode]
    return {
        "id": vehicle_id,
        "typeActor": class_type,
        "data": {
            "dataType": f"model.hybrid.entity.state.{state_type}",
            "content": {
                "startTick": 0,
                "scheduleOnTimeManager": False,
                "origin": node_id,
                "destination": node_id,
                "actorType": actor_type,
                "size": size,
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


def city_map_payload(nodes: list[dict], links: list[dict], rail_links: list[dict]) -> dict:
    def edge(actor: dict, class_type: str) -> dict:
        content = actor["data"]["content"]
        return {
            "source_id": content["from"],
            "target_id": content["to"],
            "weight": content["length"],
            "label": {
                "id": actor["id"],
                # `EdgeGraph.resourceId` is `String` (unlike `id`, which is `Long`) -- see
                # docs/ENTITY_ID_MIGRATION.md.
                "resourceId": str(actor["id"]),
                "classType": class_type,
                "length": content["length"],
            },
        }

    return {
        "nodes": [
            {
                "id": node["id"],
                "classType": "hybrid.actor.Node",
                # `NodeGraph.resourceId` is `String` (unlike `id`, which is `Long`).
                "resourceId": str(node["id"]),
                "latitude": node["data"]["content"]["latitude"],
                "longitude": node["data"]["content"]["longitude"],
            }
            for node in nodes
        ],
        "edges": [edge(link, "hybrid.actor.Link") for link in links]
        + [edge(rail_link, "hybrid.actor.RailLink") for rail_link in rail_links],
        "directed": True,
    }


def transit_map_payload(
    node_meta: dict[int, tuple[float, float]], bus_lines: list[dict], subway_lines: list[dict]
) -> list[dict]:
    entries: list[dict] = []
    for line in bus_lines:
        for stop_key, node_key in (("stop_a_id", "node_a"), ("stop_b_id", "node_b")):
            node_id = line[node_key]
            entries.append(
                {
                    "id": f"htcaid:stop;{line[stop_key]}",
                    "actorId": line[stop_key],
                    "actorClassType": "hybrid.actor.BusStop",
                    "nodeId": node_id,
                    "latitude": node_meta[node_id][0],
                    "longitude": node_meta[node_id][1],
                    "stopType": "bus",
                    "lines": [line["line_id"]],
                }
            )
    for line in subway_lines:
        for station_key, node_key in (("station_a_id", "node_a"), ("station_b_id", "node_b")):
            node_id = line[node_key]
            entries.append(
                {
                    "id": f"htcaid:stop;{line[station_key]}",
                    "actorId": line[station_key],
                    "actorClassType": "hybrid.actor.SubwayStation",
                    "nodeId": node_id,
                    "latitude": node_meta[node_id][0],
                    "longitude": node_meta[node_id][1],
                    "stopType": "subway",
                    "lines": [line["line_id"]],
                }
            )
    return entries


def transit_routes_payload(bus_lines: list[dict], subway_lines: list[dict]) -> list[dict]:
    routes = []
    for line in bus_lines:
        routes.append(
            {
                "lineId": line["line_id"],
                "stopType": "bus",
                "headwaySeconds": 3600,
                "stops": [
                    {"stopId": f"htcaid:stop;{line['stop_a_id']}", "travelTimeFromPrevSeconds": 0},
                    {"stopId": f"htcaid:stop;{line['stop_b_id']}", "travelTimeFromPrevSeconds": 60},
                ],
            }
        )
    for line in subway_lines:
        routes.append(
            {
                "lineId": line["line_id"],
                "stopType": "subway",
                "headwaySeconds": 600,
                "stops": [
                    {"stopId": f"htcaid:stop;{line['station_a_id']}", "travelTimeFromPrevSeconds": 0},
                    {"stopId": f"htcaid:stop;{line['station_b_id']}", "travelTimeFromPrevSeconds": 120},
                ],
            }
        )
    return routes


def build_scenario(
    output_dir: Path,
    data_path_prefix: str,
    rows: int,
    cols: int,
    num_persons: int,
    num_cars: int,
    num_bicycles: int,
    num_motorcycles: int,
    num_bus_lines: int,
    num_subway_lines: int,
    micro_pct: float,
    micro_count: int | None,
    num_activities: int,
    strategy_id: str,
    seed: int,
) -> None:
    rng = random.Random(seed)

    _, node_meta = build_grid_nodes(rows, cols)
    node_ids = list(node_meta.keys())
    links, outgoing_by_node = build_grid_links(rows, cols, rng, micro_pct, micro_count)
    nodes = [
        node_actor(node_id, lat, lon, outgoing_by_node.get(node_id, []))
        for node_id, (lat, lon) in node_meta.items()
    ]

    bus_stops, bus_stations, bus_lines = build_bus_lines(node_ids, num_bus_lines, rng)
    subway_stations, rail_links, subway_lines = build_subway_lines(node_ids, num_subway_lines, rng)

    # Vehicle ownership is assigned in disjoint cohorts across the person population — person i
    # owns exactly one vehicle type (or none), never two, so each mode's dynamic-mode-choice path
    # (car/bicycle/motorcycle/PT/walk) is exercised by a distinct, unambiguous cohort instead of
    # "car" silently winning every PendingDecision by virtue of being the fastest option available
    # to everyone (see `htc-scenario-qa` findings, 2026-07-23: an all-car-owning population never
    # once picked bus, because a person who can drive essentially always out-scores riding transit
    # under `TravelTimeModeChoiceStrategy`). Persons past `num_cars + num_bicycles + num_motorcycles`
    # own no private vehicle at all — forced to choose among walk/bus/subway, which is what actually
    # gets bus picked in practice.
    car_owners = list(range(0, min(num_persons, num_cars)))
    bicycle_owners = list(range(car_owners[-1] + 1 if car_owners else 0, min(num_persons, num_cars + num_bicycles)))
    motorcycle_owners = list(
        range(
            bicycle_owners[-1] + 1 if bicycle_owners else (car_owners[-1] + 1 if car_owners else 0),
            min(num_persons, num_cars + num_bicycles + num_motorcycles),
        )
    )

    persons = []
    origins = []
    for i in range(num_persons):
        origin = rng.choice(node_ids)
        origins.append(origin)
        original_plan = build_original_plan(rng, node_ids, origin, num_activities, 86400, strategy_id)

        owned_vehicles: dict[str, int] = {}
        if i in car_owners:
            owned_vehicles["car"] = eid(f"htcaid:car;grid_{i}")
        elif i in bicycle_owners:
            owned_vehicles["bicycle"] = eid(f"htcaid:bicycle;grid_{i}")
        elif i in motorcycle_owners:
            owned_vehicles["motorcycle"] = eid(f"htcaid:motorcycle;grid_{i}")

        persons.append(person_actor(eid(f"htcaid:person;grid_{i}"), origin, original_plan, owned_vehicles))

    # `eid(...)` is memoized by key, so re-minting `htcaid:car;grid_{i}` here returns the exact
    # same Long already registered in `owned_vehicles` above -- the Car actor and the Person's
    # ownership reference always agree.
    cars = [car_actor(eid(f"htcaid:car;grid_{i}"), origins[i]) for i in car_owners] + [
        car_actor(eid(f"htcaid:car;grid_{len(car_owners) + j}"), rng.choice(node_ids))
        for j in range(num_cars - len(car_owners))
    ]
    bicycles = [
        private_vehicle_actor(eid(f"htcaid:bicycle;grid_{i}"), origins[i], "bicycle") for i in bicycle_owners
    ]
    motorcycles = [
        private_vehicle_actor(eid(f"htcaid:motorcycle;grid_{i}"), origins[i], "motorcycle")
        for i in motorcycle_owners
    ]

    data_dir = output_dir / "data"
    write_json(data_dir / "nodes_0.json", nodes)
    write_json(data_dir / "links_0.json", links)
    write_json(data_dir / "bus_stops_0.json", bus_stops)
    write_json(data_dir / "bus_stations_0.json", bus_stations)
    write_json(data_dir / "persons_0.json", persons)
    write_json(data_dir / "cars_0.json", cars)
    if bicycles:
        write_json(data_dir / "bicycles_0.json", bicycles)
    if motorcycles:
        write_json(data_dir / "motorcycles_0.json", motorcycles)
    write_json(data_dir / "subway_stations_0.json", subway_stations)
    write_json(data_dir / "rail_links_0.json", rail_links)
    write_json(data_dir / "city_map.json", city_map_payload(nodes, links, rail_links))
    write_json(data_dir / "transit_map.json", transit_map_payload(node_meta, bus_lines, subway_lines))
    write_json(data_dir / "transit_routes.json", transit_routes_payload(bus_lines, subway_lines))

    payload = simulation_payload(
        output_dir,
        data_path_prefix,
        name=f"Hybrid grid smoke-test scenario ({rows}x{cols})",
        description=(
            f"Generated {rows}x{cols} grid: {len(links)} links "
            f"({sum(1 for l in links if l['data']['content']['simulationMode'] == 'MICRO')} MICRO), "
            f"{num_persons} persons ({num_activities - 1} trips/day each), {num_cars} car(s), "
            f"{num_bicycles} bicycle(s), {num_motorcycles} motorcycle(s), {num_bus_lines} bus line(s), "
            f"{num_subway_lines} subway line(s), seed={seed}."
        ),
    )
    # `simulation_payload` (shared with `generate_tiny_validation_scenario.py`) doesn't know about
    # bicycles_0/motorcycles_0 — append those sources here rather than adding scenario-specific
    # params to the shared reference-script function.
    if bicycles:
        payload["actorsDataSources"].append(
            {
                "id": "bicycles_0",
                "classType": "hybrid.actor.Bicycle",
                "creationType": "LoadBalancedDistributed",
                "loadingStrategy": "PROGRESSIVE",
                "entityLifecycle": "DYNAMIC",
                "dataSource": {"sourceType": "json", "info": {"path": f"{data_path_prefix}/data/bicycles_0.json"}},
            }
        )
    if motorcycles:
        payload["actorsDataSources"].append(
            {
                "id": "motorcycles_0",
                "classType": "hybrid.actor.Motorcycle",
                "creationType": "LoadBalancedDistributed",
                "loadingStrategy": "PROGRESSIVE",
                "entityLifecycle": "DYNAMIC",
                "dataSource": {"sourceType": "json", "info": {"path": f"{data_path_prefix}/data/motorcycles_0.json"}},
            }
        )
    write_json(output_dir / "simulation.json", payload)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate a small, fully controlled hybrid-model grid scenario for end-to-end smoke testing."
    )
    parser.add_argument("--output-dir", type=Path, required=True, help="Directory where the scenario will be written.")
    parser.add_argument(
        "--data-path-prefix", default=None, help="Prefix written inside simulation.json for data file paths. Defaults to --output-dir."
    )
    parser.add_argument("--rows", type=int, default=3, help="Grid rows (N).")
    parser.add_argument("--cols", type=int, default=3, help="Grid columns (M).")
    parser.add_argument("--num-persons", type=int, default=20, help="Number of Person actors.")
    parser.add_argument("--num-cars", type=int, default=10, help="Number of Car actors. First min(num-persons, num-cars) are person-owned.")
    parser.add_argument(
        "--num-bicycles", type=int, default=0,
        help="Number of Bicycle actors, owned by the person cohort right after car owners."
    )
    parser.add_argument(
        "--num-motorcycles", type=int, default=0,
        help="Number of Motorcycle actors, owned by the person cohort right after bicycle owners."
    )
    parser.add_argument("--num-bus-lines", type=int, default=1, help="Number of bus lines (each = 1 BusStation + 2 BusStops + 1 bus).")
    parser.add_argument("--num-subway-lines", type=int, default=1, help="Number of subway lines (each = 2 SubwayStations + 2 RailLinks).")
    micro_group = parser.add_mutually_exclusive_group()
    micro_group.add_argument("--micro-pct", type=float, default=20.0, help="Percentage of grid links running in MICRO mode (0-100).")
    micro_group.add_argument("--micro-count", type=int, default=None, help="Explicit count of grid links running in MICRO mode, overrides --micro-pct.")
    parser.add_argument(
        "--num-activities", type=int, default=3,
        help="Activities per person's daily plan (default 3 = home->work->home, 2 trips). "
        "N activities = N-1 trips/PendingDecisions; must be >= 2."
    )
    parser.add_argument(
        "--strategy-id", default=MODE_DECISION_STRATEGY_ID,
        choices=["travel-time", "travel-time-logit", "raptor", "nearest-stop-utility"],
        help="ModeDecisionEngine id for every PendingDecision. 'travel-time' (default) evaluates "
        "all modes, deterministically picking the highest-scoring one every time (same inputs -> "
        "same choice, always). 'travel-time-logit' evaluates the exact same candidates but samples "
        "a multinomial logit distribution over their scores instead (random utility theory) — same "
        "seed still reruns identically, but two candidates close in score split probabilistically "
        "rather than one always winning outright; use it to test mode-share realism, not just "
        "single-trip correctness. Both validate a winning PT candidate against a real RAPTOR search "
        "before committing to it. 'raptor' does real multi-leg/transfer-aware transit routing but "
        "only ever considers bus/subway (never walk-only, never private vehicles) — use it "
        "specifically to test PT itinerary/transfer "
        "correctness, not as a general-purpose default."
    )
    parser.add_argument("--seed", type=int, default=42, help="Seed for reproducible link-mode assignment and agent placement.")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.rows < 2 or args.cols < 2:
        raise SystemExit("--rows and --cols must each be at least 2 to form a connected grid.")
    if args.num_bus_lines and len(list(range(args.rows * args.cols))) < 2:
        raise SystemExit("grid must have at least 2 nodes to place a bus line.")
    if args.num_activities < 2:
        raise SystemExit("--num-activities must be >= 2 (at least one trip: home -> ... -> home).")

    output_dir = args.output_dir.resolve()
    data_path_prefix = args.data_path_prefix or str(output_dir)
    build_scenario(
        output_dir,
        data_path_prefix,
        args.rows,
        args.cols,
        args.num_persons,
        args.num_cars,
        args.num_bicycles,
        args.num_motorcycles,
        args.num_bus_lines,
        args.num_subway_lines,
        args.micro_pct,
        args.micro_count,
        args.num_activities,
        args.strategy_id,
        args.seed,
    )
    print(f"Hybrid grid scenario ({args.rows}x{args.cols}) written to {output_dir}")


if __name__ == "__main__":
    main()
