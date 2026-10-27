#!/usr/bin/env python3
"""Exploratory input audit on released v1 observations; no fresh validation cases."""
import argparse
from collections import Counter, defaultdict
import copy
import hashlib
import json
import math
from pathlib import Path
import statistics
import subprocess

from validate_micro_field import quality, crossing, evaluate, write

CAPS_KMH = (40.0, 50.0, 35 * 1.609344, 65.0)
SPEED_SOURCE = "https://ops.fhwa.dot.gov/publications/fhwahop08054/sect7.htm"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_snapshot(root):
    protocol = json.loads((root / "development/protocol.json").read_text())
    selections = {}
    for stage in ("development", "reserved"):
        folder = root / stage
        selection = json.loads((folder / "selection.json").read_text())
        seal = json.loads((folder / "selection-seal.json").read_text())
        assert sha(folder / "selection.json") == seal["selectionSha256"]
        for source in [selection["metadataSource"], *selection["sources"]]:
            assert Path(source["file"]).name == source["file"]
            assert sha(folder / source["file"]) == source["sha256"]
        selections[stage] = selection
    return protocol, selections


def context(episode, rows):
    prefix_end = episode["startMilliseconds"] + 1000
    by_vehicle = defaultdict(list)
    for r in rows:
        by_vehicle[int(r["vehicle_id"])].append(r)
    initial_context = {}
    for identity, data in by_vehicle.items():
        first = next(r for r in data if int(r["global_time"]) == prefix_end)
        initial_context[str(identity)] = dict(section=first["section_id"], intersection=first["int_id"])
    origins = sorted({int(r["global_time"]) - 100 * int(r["frame_id"]) for r in rows})
    sections = sorted({r["section_id"] for r in rows})
    intersections = sorted({r["int_id"] for r in rows})
    same_section = len(sections) == 1 and sections[0] != "0" and intersections == ["0"]
    gaps = []
    stop_streak = longest_stop = 0
    for tick, (leader, follower) in enumerate(zip(episode["observations"][0], episode["observations"][1])):
        gap = leader - episode["leader"][0]["length"] - follower
        gaps.append(gap)
        leader_speed = episode["observedSpeeds"][0][tick]
        follower_speed = episode["observedSpeeds"][1][tick]
        safe = max(0, -4.5 + math.sqrt(4.5 ** 2 + leader_speed ** 2 + 9 * max(0, gap - 2)))
        stop_streak = stop_streak + 1 if follower_speed <= .2 and safe >= follower_speed + 2 else 0
        longest_stop = max(longest_stop, stop_streak)
    return dict(sessionOriginsMilliseconds=origins, initialContext=initial_context,
        sections=sections, intersections=intersections, sameSectionOutsideIntersection=same_section,
        initialBumperGapM=gaps[0], minimumObservedBumperGapM=min(gaps),
        initialFollowerSpeedKmh=episode["followers"][0]["velocity"] * 3.6,
        maximumObservedFollowerSpeedKmh=max(episode["observedSpeeds"][1]) * 3.6,
        independentStopProxySeconds=longest_stop * .1,
        proxyInterpretation="A stopped follower with >=2 m/s safe-speed allowance is a possible missing-input/context flag, not a measured signal state or causal diagnosis.")


def queue_observation_window(candidate, rows, start, protocol):
    ids = candidate["ids"]
    records = {}
    for r in rows:
        key = int(r["vehicle_id"]), int(r["global_time"])
        if key in records and records[key] != r:
            raise ValueError("conflicting duplicate")
        records[key] = r
    common = 0
    while common < 211 and all((identity, start + 100 * common) in records for identity in ids):
        common += 1
    if common < 61:
        raise ValueError("less than five prediction seconds of common observations")
    ys = [[float(records[identity, start + 100 * i]["local_y"]) * .3048
           for i in range(10, common)] for identity in ids]
    station = ys[0][0] + 5
    crossings = [crossing(y, station) for y in ys]
    if any(t is None for t in crossings):
        raise ValueError("not all observed subjects cross before common coverage ends")
    duration = max(5, math.ceil((max(crossings) + 1) * 10) / 10)
    if duration > 20 or duration > (common - 11) / 10 + 1e-9:
        raise ValueError("crossing plus one-second pad exceeds common coverage")
    p = copy.deepcopy(protocol)
    p["queuePredictionSeconds"] = duration
    quality(rows, ids, start, "queue", p)
    return dict(availablePredictionSeconds=(common - 11) / 10, observationEndSeconds=duration,
                crossingsSeconds=crossings)


def run(args):
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    protocol, selections = load_snapshot(args.snapshot)
    episodes = []
    source_episodes = []
    contexts = {}
    for stage, selection in selections.items():
        for e in selection["selected"]:
            rows = json.loads((args.snapshot / stage / e["source"]["file"]).read_text())
            contexts[e["id"]] = context(e, rows)
            source_episodes.append((stage, e))
            for cap in CAPS_KMH:
                identity = e["id"] if cap == 50 else e["id"] + "-cap-" + str(cap)
                episodes.append(dict(id=identity, followers=e["followers"], leader=e["leader"], speedLimitKmh=cap))
    write(root / "input.json", dict(episodes=episodes))
    command = ["java", "-XX:ActiveProcessorCount=4", "-cp", str(args.jar.resolve()),
        "org.interscity.htc.model.hybrid.validation.MicroFieldReplay", str(root / "input.json"), str(root / "predictions.json")]
    write(root / "command.json", command)
    with (root / "runner.log").open("w") as log:
        subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=120, check=True)
    samples = json.loads((root / "predictions.json").read_text())
    grouped = defaultdict(list)
    for sample in samples:
        grouped[sample["episode"]].append(sample)
    original = []
    for stage in ("development", "reserved"):
        original.extend(json.loads((args.snapshot / stage / "predictions.json").read_text()))
    baseline = [s for s in samples if s["episode"] in contexts]
    assert baseline == original, "50 km/h legacy replay changed"
    results = []
    for stage, episode in source_episodes:
        for cap in CAPS_KMH:
            e = dict(episode, id=episode["id"] if cap == 50 else episode["id"] + "-cap-" + str(cap))
            result = evaluate(e, grouped[e["id"]])
            result.update(originalId=episode["id"], releasedStage=stage, capKmh=cap, context=contexts[episode["id"]])
            results.append(result)
    windows = []
    for stage, selection in selections.items():
        candidates = [dict(e, originallySelected=True) for e in selection["selected"] if e["kind"] == "queue"]
        candidates += [dict(e, originallySelected=False) for e in selection["rejected"] if "-queue-" in e["id"]]
        for candidate in candidates:
            block = int(candidate["id"].split("-")[2])
            offsets = protocol["developmentOffsetsSeconds" if stage == "development" else "reservedOffsetsSeconds"]
            start = protocol["samplingFrameOriginMilliseconds"] + offsets[block] * 1000
            rows = json.loads((args.snapshot / stage / candidate["source"]["file"]).read_text())
            record = dict(id=candidate["id"], originallySelected=candidate["originallySelected"],
                          originalRejection=candidate.get("reason"))
            try:
                record.update(eligibleEventWindow=True, **queue_observation_window(candidate, rows, start, protocol))
            except (ValueError, KeyError) as error:
                record.update(eligibleEventWindow=False, reason=str(error))
            windows.append(record)
    summary = []
    for stage in ("development", "reserved"):
        for kind in ("following", "queue"):
            for cap in CAPS_KMH:
                group = [r for r in results if r["releasedStage"] == stage and r["kind"] == kind and r["capKmh"] == cap]
                summary.append(dict(releasedStage=stage, kind=kind, capKmh=cap, episodes=len(group),
                    meanPositionRmseM=statistics.mean(r["meanPositionRmseM"] for r in group),
                    meanHeadwayMaeSeconds=statistics.mean(r["headwayMaeSeconds"] for r in group) if kind == "queue" else None))
    record = dict(status="exploratory-development-audit-of-released-v1-data", productionPhysicsChanged=False,
        postedLimit=dict(mph=35, kmh=35 * 1.609344, source=SPEED_SOURCE), capsKmh=CAPS_KMH,
        legacy50KmhPredictionsExact=True, jarSha256=sha(args.jar), sourceSha256=sha(Path(__file__)),
        contexts=contexts, capSensitivity=results, summary=summary, queueWindowAudit=windows,
        queueWindowCounts=dict(originallySelected=sum(w["originallySelected"] for w in windows),
            recoveredReleasedCandidates=sum(not w["originallySelected"] and w["eligibleEventWindow"] for w in windows),
            rejections=dict(Counter(w.get("reason") for w in windows if not w["eligibleEventWindow"]))),
        interpretation="Exploratory input sensitivity and observational coverage audit. No v1 result is relabeled as a fresh validation success; a new reserved cohort is required.")
    write(root / "audit.json", record)
    print(json.dumps(dict(summary=summary, queueWindowCounts=record["queueWindowCounts"]), indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", type=Path, required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    run(parser.parse_args())


if __name__ == "__main__":
    main()
