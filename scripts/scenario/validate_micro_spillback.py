#!/usr/bin/env python3
"""Generate and inspect the frozen bounded MICRO spillback fixtures."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path

import generate_tiny_validation_scenario as tiny
from generate_hybrid_scenario import build_scenario, city_map_payload
from validate_sumo_micro import read_htc, write_json

PROTOCOL = Path(__file__).with_name("micro_spillback_protocol_v1.json")
PROTOCOL_SHA256 = "43dba1baf951e7cbd0f58f2f9a21fcc9e2e0151b4f519ca8921bef2b62685b6e"


def generate(root, case_id, legacy=False):
    assert hashlib.sha256(PROTOCOL.read_bytes()).hexdigest() == PROTOCOL_SHA256, "frozen protocol changed"
    protocol = json.loads(PROTOCOL.read_text())
    cases = [protocol["developmentCase"], *protocol["reservedCases"]]
    case = next(c for c in cases if c["id"] == case_id)
    p = dict(protocol["defaultParameters"], **case)
    tiny._ID_REGISTRY = tiny.IdRegistry()
    build_scenario(root, str(root), 1, 4, 0, p["vehicles"], 0, 0, 0, 0,
                   0.0, None, 2, "travel-time", p["seed"])

    def read(name):
        return json.loads((root / "data" / name).read_text())

    quantum = p["runtimeTickSeconds"]

    def ticks(seconds):
        assert abs(seconds / quantum - round(seconds / quantum)) < 1e-7
        return round(seconds / quantum)

    nodes, links, cars = (read(name + "_0.json") for name in ("nodes", "links", "cars"))
    forward = sorted((r for r in links if r["data"]["content"]["from"] < r["data"]["content"]["to"]),
                     key=lambda r: r["data"]["content"]["from"])
    for link in links:
        s = link["data"]["content"]
        s.update(length=p["roadLengths"][min(s["from"], s["to"]) - 1],
                 capacity=100, speedLimit=p["speedLimitKmh"], freeSpeed=p["speedLimitKmh"])
    forward[1]["data"]["content"]["capacity"] = p["bottleneckCapacity"]
    route = forward.copy()
    signal_id = max(r["id"] for r in nodes + links + cars) + 1
    phase_id = signal_id + 1
    nodes[2]["data"]["content"].update(
        connections={str(forward[2]["id"]): dict(id=phase_id)},
        approachConnections={str(forward[1]["id"]): dict(id=phase_id)})
    if "connectorLengthM" in p:
        connector_id = signal_id + 2
        length = p["connectorLengthM"]
        connector = tiny.link_actor(connector_id, 2, 2, length, p["speedLimitKmh"])
        connector["data"]["content"].update(capacity=p["connectorCapacity"], junctionGeometry=dict(
            nodeId=2, incomingLinkId=forward[0]["id"], outgoingLinkId=forward[1]["id"],
            lengthMeters=length, speedLimitMps=p["speedLimitKmh"] / 3.6,
            shape=[dict(x=0, y=0), dict(x=length, y=0)]))
        links.append(connector)
        route.insert(1, connector)
        nodes[1]["data"]["content"]["links"].append(connector_id)
        nodes[1]["data"]["content"]["junctionMovements"] = [dict(
            incomingLinkId=forward[0]["id"], outgoingLinkId=forward[1]["id"], connectorLinkId=connector_id)]
        forward[0]["data"]["content"]["downstreamConnectors"] = {str(forward[1]["id"]): connector_id}
    for link in links:
        s = link["data"]["content"]
        s.update(simulationMode="MICRO", lanes=1, microTimeStep=p["physicsStepSeconds"],
                 microTicksPerGlobalTick=1, carryMicroRemainder=True, downstreamLookahead=True,
                 stopLineGapM=p["stopLineGapM"])
        if not legacy:
            s["downstreamSpillback"] = True
    for i, car in enumerate(cars):
        car["data"]["content"].update(startTick=ticks(i * p["departureHeadwaySeconds"]),
            scheduleOnTimeManager=True, origin=1, destination=4, currentNode=1, status="RouteWaiting",
            precomputedRoute=[dict(linkId=r["id"], nodeId=r["data"]["content"]["to"]) for r in forward])
        car["dependencies"] = {"from_node": dict(id=1, classType="hybrid.actor.Node"),
                               "to_node": dict(id=4, classType="hybrid.actor.Node")}
    signals = [dict(id=signal_id, typeActor="hybrid.actor.TrafficSignal", data=dict(
        dataType="model.hybrid.entity.state.TrafficSignalState", content=dict(startTick=0,
            cycleDuration=ticks(p["signalCycleSeconds"]), offset=0, nodes=[3], phases=[dict(
                origin=phase_id, greenStart=ticks(p["greenStartSeconds"]),
                greenDuration=ticks(p["greenDurationSeconds"]), state="Red")],
            # The actor publishes transitions only; force publication of the actual Red at tick zero.
            signalStates={str(phase_id): dict(state="Green", remainingTime=ticks(p["greenStartSeconds"]),
                                              nextTick=ticks(p["greenStartSeconds"]))})),
        dependencies={"3": dict(id=3, classType="hybrid.actor.Node")})]
    for name, data in (("nodes", nodes), ("links", links), ("cars", cars), ("traffic_signals", signals)):
        write_json(root / "data" / (name + "_0.json"), data)
    write_json(root / "data/city_map.json", city_map_payload(nodes, links, []))
    config = json.loads((root / "simulation.json").read_text())
    supported = {"hybrid.actor." + name for name in ("Node", "Link", "Car")}
    config["actorsDataSources"] = [s for s in config["actorsDataSources"] if s["classType"] in supported]
    config["actorsDataSources"].append(dict(id="signals", classType="hybrid.actor.TrafficSignal",
        creationType="LoadBalancedDistributed", loadingStrategy="EAGER", entityLifecycle="STATIC",
        dataSource=dict(sourceType="json", info=dict(path=str(root / "data/traffic_signals_0.json")))))
    config.update(duration=ticks(p["horizonSeconds"]), endTick=ticks(p["horizonSeconds"]),
                  tickDuration=quantum, randomSeed=p["seed"], id="micro-spillback-" + case_id,
                  postLoadRegistrationClasses=[])
    for policy in ("discrete-event", "time-warp"):
        write_json(root / (policy + ".json"), dict(config, timeManagerType=policy))
    (root / "reporting.conf").write_text("htc.report-manager.kafka.micro-update-report-every=1\n")
    canonical = dict(case=case_id, parameters=p, protocolSha256=PROTOCOL_SHA256,
        legacy=legacy, vehicles=[str(c["id"]) for c in cars],
        links=[str(r["id"]) for r in route],
        lengths=[r["data"]["content"]["length"] for r in route],
        capacities={str(r["id"]): r["data"]["content"]["capacity"] for r in route},
        expectedCompleted=case.get("expectedCompletedJourneys", len(cars)))
    canonical["inputSha256"] = {str(f.relative_to(root)): hashlib.sha256(f.read_bytes()).hexdigest()
                                for f in sorted(root.rglob("*.json"))}
    write_json(root / "canonical.json", canonical)
    return canonical


def inspect(rows, canonical):
    p = canonical["parameters"]
    vehicles = set(canonical["vehicles"])
    expected_links = canonical["links"]
    lengths = dict(zip(expected_links, canonical["lengths"]))
    offsets = dict(zip(expected_links, [sum(canonical["lengths"][:i]) for i in range(len(expected_links))]))
    by_label = defaultdict(list)
    for row in rows:
        by_label[row["event_type"]].append(row["data"])
    entries = defaultdict(list)
    leaves = defaultdict(list)
    samples = {}
    for label in ("enter_micro_link", "micro_update", "leave_micro_link", "micro_exit_wait"):
        for d in by_label[label]:
            vehicle, tick, link = str(d["car_id"]), int(d["tick"]), str(d["link_id"])
            if vehicle not in vehicles:
                raise ValueError("unexpected vehicle " + vehicle)
            if label == "enter_micro_link":
                entries[vehicle].append(d)
                position, speed, priority = d.get("initial_position", 0), d["initial_velocity"], 0
            elif label == "leave_micro_link":
                leaves[vehicle].append(d)
                position, speed, priority = d["final_position"], d["final_velocity"], 1
            else:
                position, speed, priority = d["position"], d["velocity"], 2
            sample = dict(link=link, position=position, speed=speed, distance=offsets[link] + position,
                          priority=priority)
            previous = samples.get((vehicle, tick))
            if previous is None or (sample["distance"], offsets[link], priority) > (
                    previous["distance"], offsets[previous["link"]], previous["priority"]):
                samples[vehicle, tick] = sample
    for records in (entries, leaves):
        for data in records.values():
            data.sort(key=lambda d: (d["tick"], offsets[str(d["link_id"])]))
    trips = by_label["sumo_tripinfo"]
    completed = [d for d in trips if not d.get("horizon_truncated", False)]
    censored = [d for d in trips if d.get("horizon_truncated", False)]
    tol = 1e-6
    checks = dict(
        oneTripPerVehicle=Counter(str(d["vehicle_id"]) for d in trips) == Counter(vehicles),
        expectedCompletions=len(completed) == canonical["expectedCompleted"],
        exactCompletedDistances=all(math.isclose(d["routeLength"], sum(lengths.values()), abs_tol=tol)
                                    and d["arrival"] >= d["depart"] for d in completed),
        censoredDistances=all(d["arrival"] == -1 and math.isclose(d["routeLength"], sum(
            x["distance_traveled"] for x in leaves[str(d["vehicle_id"])]), abs_tol=tol) for d in censored),
        uniqueOrderedLinkEntries=all([str(d["link_id"]) for d in entries[v]] ==
                                    expected_links[:len(entries[v])] for v in vehicles),
        uniqueOrderedLinkLeaves=all([str(d["link_id"]) for d in leaves[v]] ==
                                   expected_links[:len(leaves[v])] for v in vehicles),
        completeTraversal=all(len(entries[str(d["vehicle_id"])]) == len(expected_links) ==
                              len(leaves[str(d["vehicle_id"])]) for d in completed),
        positionsInBounds=all(-tol <= s["position"] <= lengths[s["link"]] + tol for s in samples.values()),
        speedsInBounds=all(math.isfinite(s["speed"]) and 0 <= s["speed"] <= p["speedLimitKmh"] / 3.6 + tol
                           for s in samples.values()))
    checks["signalInitialRed"] = any(d["tick"] == 0 and d["phase_state"] == "Red"
                                      for d in by_label["signal_phase_change"])
    checks["signalPreventsEarlyExit"] = all(d["tick"] * p["runtimeTickSeconds"] >=
        p["greenStartSeconds"] - tol for v in vehicles for d in leaves[v]
        if str(d["link_id"]) == expected_links[-2])
    waits = by_label["micro_exit_wait"]
    checks["retainedBlockedFootprint"] = len({str(d["car_id"]) for d in waits}) >= 1
    gaps, stopped_followers = [], set()
    cohorts = defaultdict(list)
    for (vehicle, tick), sample in samples.items():
        cohorts[tick].append((vehicle, sample))
    wait_keys = {(str(d["car_id"]), int(d["tick"])) for d in waits}
    for tick, cohort in cohorts.items():
        ordered = sorted(cohort, key=lambda item: (-item[1]["distance"], item[0]))
        for (leader, a), (follower, b) in zip(ordered, ordered[1:]):
            gap = a["distance"] - p["vehicleLengthM"] - b["distance"]
            gaps.append(gap)
            if (leader, tick) in wait_keys and b["speed"] < .1 and b["link"] == a["link"]:
                stopped_followers.add(follower)
    checks["physicalSpacing"] = bool(gaps) and min(gaps) >= p["minGapM"] - tol
    checks["upstreamStoppedFollower"] = bool(stopped_followers)
    # Each vehicle must remain sampled throughout a blocked handoff; a missing trace cannot pass spacing.
    missing = []
    delayed = []
    for vehicle in sorted(vehicles):
        ent = {str(d["link_id"]): d for d in entries[vehicle]}
        for i, leave in enumerate(leaves[vehicle]):
            if i + 1 == len(expected_links):
                continue
            nxt = ent.get(expected_links[i + 1])
            end = int(nxt["tick"]) if nxt else round(p["horizonSeconds"] / p["runtimeTickSeconds"]) + 1
            start = int(leave["tick"])
            if end > start:
                delayed.append((vehicle, start, end, nxt))
                missing.extend((vehicle, tick) for tick in range(start + 1, end) if (vehicle, tick) not in wait_keys)
    checks["continuousBlockedTrace"] = bool(delayed) and not missing
    checks["stoppedDelayedEntry"] = all(d["initial_velocity"] == 0 and d.get("initial_position", 0) == 0
                                         for _, _, _, d in delayed if d is not None)
    checks["unblockedHandoffPreservesMomentum"] = all(math.isclose(
        entry["initial_velocity"], min(leave["final_velocity"], p["speedLimitKmh"] / 3.6), abs_tol=tol)
        for v in vehicles for leave, entry in zip(leaves[v], entries[v][1:])
        if leave["tick"] == entry["tick"])
    checks["freeOutletHasNoDwell"] = all(leave["tick"] == entry["tick"]
        for v in vehicles for leave, entry in zip(leaves[v], entries[v][1:])
        if canonical["capacities"][str(entry["link_id"])] > len(vehicles))
    missing_active = []
    trip_by_id = {str(d["vehicle_id"]): d for d in trips}
    for v in sorted(vehicles):
        if not entries[v]:
            missing_active.append((v, "no entry"))
            continue
        trip = trip_by_id.get(v)
        if trip is None:
            missing_active.append((v, "no trip"))
            continue
        end = round((p["horizonSeconds"] if trip.get("horizon_truncated", False) else trip["arrival"])
                    / p["runtimeTickSeconds"])
        missing_active.extend((v, tick) for tick in range(int(entries[v][0]["tick"]), end + 1)
                              if (v, tick) not in samples)
    checks["completePhysicalTrace"] = not missing_active
    occupancy_ok = True
    max_occupancy = Counter()
    horizon = round(p["horizonSeconds"] / p["runtimeTickSeconds"])
    for tick in range(horizon + 1):
        counts = Counter()
        for vehicle in vehicles:
            for entry in entries[vehicle]:
                link = str(entry["link_id"])
                idx = expected_links.index(link)
                # A retained exit owns its old lane until the next Link accepts the same vehicle.
                nxt = next((d for d in entries[vehicle] if str(d["link_id"]) ==
                            (expected_links[idx + 1] if idx + 1 < len(expected_links) else "")), None)
                finish = next((d["tick"] for d in leaves[vehicle] if str(d["link_id"]) == link), horizon + 1)
                release = nxt["tick"] if nxt else (horizon + 1 if idx + 1 < len(expected_links) else finish)
                if entry["tick"] <= tick < release:
                    counts[link] += 1
        max_occupancy |= counts
        occupancy_ok &= all(count <= canonical["capacities"][link] for link, count in counts.items())
    checks["finiteCapacity"] = occupancy_ok
    return dict(checks=checks, passed=all(checks.values()), completed=len(completed), censored=len(censored),
                blockedVehicles=len({str(d["car_id"]) for d in waits}), waitSamples=len(waits),
                stoppedFollowers=sorted(stopped_followers), minGapM=min(gaps) if gaps else None,
                missingWaitSamples=len(missing), maxOccupancy=dict(max_occupancy),
                missingActiveSamples=len(missing_active),
                delayedHandoffs=[dict(vehicle=v, exitTick=start, entryTick=end if d else None)
                                 for v, start, end, d in delayed], samples=len(samples))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    gen = sub.add_parser("generate")
    gen.add_argument("--output", type=Path, required=True)
    gen.add_argument("--case", required=True)
    gen.add_argument("--legacy", action="store_true")
    validate = sub.add_parser("inspect")
    validate.add_argument("--scenario", type=Path, required=True)
    validate.add_argument("--run", type=Path, required=True)
    validate.add_argument("--output", type=Path, required=True)
    validate.add_argument("--measure-only", action="store_true")
    args = parser.parse_args()
    if args.action == "generate":
        assert not args.output.exists(), "use a fresh scenario directory"
        result = generate(args.output.resolve(), args.case, args.legacy)
    else:
        canonical = json.loads((args.scenario / "canonical.json").read_text())
        assert canonical["protocolSha256"] == hashlib.sha256(PROTOCOL.read_bytes()).hexdigest() == PROTOCOL_SHA256
        assert all(hashlib.sha256((args.scenario / name).read_bytes()).hexdigest() == digest
                   for name, digest in canonical["inputSha256"].items()), "scenario inputs changed"
        engine = json.loads((args.run / "result.json").read_text())
        assert engine["engineReportedStop"] and engine["engineCompleted"] and not engine["runtimeFailed"] \
            and not any(engine["engineErrors"]) and engine["executableUnchanged"], "engine failure"
        log = (args.run / "runtime.log").read_text()
        assert not any(marker in log for marker in ("[STALL", "[GTM-STALL]", "[RUNTIME-STALL]",
            "Replay did not consume all recorded reads")), "runtime failure"
        result = inspect(read_htc(args.run / "reports"), canonical)
        result.update(protocolSha256=canonical["protocolSha256"], engine=engine,
                      canonicalSha256=hashlib.sha256((args.scenario / "canonical.json").read_bytes()).hexdigest(),
                      rollbacks=log.count("[TW-ROLLBACK]"))
        write_json(args.output, result)
        if not args.measure_only:
            assert result["passed"], result["checks"]
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
