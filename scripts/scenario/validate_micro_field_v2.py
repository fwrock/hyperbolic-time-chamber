#!/usr/bin/env python3
"""Fresh, context-restricted field following and observed queue-release validation."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path
import subprocess
from urllib.parse import urlencode

from audit_micro_field_inputs import load_snapshot, queue_observation_window
from validate_micro_field import fetch, quality, evaluate, assess, sha, write

PROTOCOL = Path(__file__).with_name("micro_field_protocol_v2.json")


def identity(row):
    return int(row["global_time"]) - 100 * int(row["frame_id"]), int(row["vehicle_id"])


def exposures(v1):
    _, selections = load_snapshot(v1)
    exposed = set()
    for stage, selection in selections.items():
        for source in selection["sources"]:
            rows = json.loads((v1 / stage / source["file"]).read_text())
            exposed.update(identity(r) for r in rows)
    return exposed


def following_context(episode, rows, protocol):
    start = episode["startMilliseconds"]
    end = start + 11000
    relevant = [r for r in rows if start <= int(r["global_time"]) <= end]
    sections = {r["section_id"] for r in relevant}
    if len(sections) != 1 or "0" in sections or any(r["int_id"] != "0" for r in relevant):
        raise ValueError("following crosses a section or recorded intersection")
    gap = episode["leader"][0]["position"] - episode["leader"][0]["length"] - episode["followers"][0]["position"]
    if gap > protocol["followingContext"]["maximumInitialBumperGapM"]:
        raise ValueError("initial leader outside bounded following horizon")
    b = protocol["physics"]["maxDecelerationMps2"]
    tau = protocol["physics"]["reactionSeconds"]
    leader_speed = episode["leader"][0]["velocity"]
    safe = max(0, -tau * b + math.sqrt((tau * b) ** 2 + leader_speed ** 2 + 2 * b * max(0, gap - 2)))
    if safe > protocol["followingContext"]["maximumInitialSafeSpeedFractionOfCap"] * protocol["physics"]["speedLimitKmh"] / 3.6:
        raise ValueError("initial following is not leader dominated")
    return dict(section=next(iter(sections)), initialBumperGapM=gap, initialSafeVelocityMps=safe)


def queue_release(rows, ids, initial_start, protocol):
    records = {(int(r["vehicle_id"]), int(r["global_time"])): r for r in rows}
    rejected = []
    for index in range(11, round(protocol["queueRelease"]["maximumSearchSeconds"] * 10) + 1):
        release = initial_start + index * 100
        before, now, later = (records.get((ids[0], t)) for t in (release - 100, release, release + 1000))
        if before is None or now is None or later is None:
            continue
        if float(before["v_vel"]) * .3048 > .2 or float(now["v_vel"]) * .3048 <= .2:
            continue
        if (float(later["local_y"]) - float(now["local_y"])) * .3048 < .3:
            continue
        start = release - 1100
        try:
            window = queue_observation_window(dict(ids=ids), rows, start, protocol)
            p = dict(protocol, queuePredictionSeconds=window["observationEndSeconds"])
            episode = quality(rows, ids, start, "queue", p)
        except (ValueError, KeyError) as error:
            rejected.append(dict(releaseMilliseconds=release, reason=str(error)))
            continue
        episode.update(releaseMilliseconds=release, queueObservationWindow=window,
                       releaseRejections=rejected, initialCandidateMilliseconds=initial_start)
        return episode
    raise ValueError("no qualifying observed queue release: " + json.dumps(rejected))


def select(root, protocol, exposed, cache):
    selected, rejected, sources = [], [], []
    used = set(exposed)
    counts = Counter()
    encountered = set()
    target = dict(following=protocol["targets"]["reservedFollowing"], queue=protocol["targets"]["reservedQueue"])
    session = protocol["expectedSessionOriginMilliseconds"]
    for block, offset in enumerate(protocol["reservedOffsetsSeconds"]):
        if all(counts[k] == target[k] for k in target):
            break
        start = protocol["samplingFrameOriginMilliseconds"] + offset * 1000
        url = protocol["source"] + "?" + urlencode({"$where": f"location='lankershim' and global_time={start}",
                                                     "$order": "vehicle_id", "$limit": 500})
        snapshot, source = fetch(url, root / f"block-{block}.json", protocol, cache)
        sources.append(source)
        assert len(snapshot) < 500, "truncated v2 candidate snapshot"
        encountered.update(identity(r) for r in snapshot)
        assert all(identity(r)[0] == session for r in snapshot), "unexpected recording session"
        by_id = {int(r["vehicle_id"]): r for r in snapshot}
        for kind in ("following", "queue"):
            if counts[kind] == target[kind]:
                continue
            inspected = 0
            for subject in sorted(by_id):
                r = by_id[subject]
                if int(r.get("v_class", 0)) != 2 or int(r.get("direction", 0)) != 2 or int(r.get("movement", 0)) != 1:
                    continue
                speed = float(r["v_vel"]) * .3048
                if (kind == "following" and speed < 1) or (kind == "queue" and speed > .2):
                    continue
                ids = [subject]
                for _ in range(1 if kind == "following" else 2):
                    parent = int(by_id[ids[0]].get("preceding", 0))
                    if parent not in by_id:
                        break
                    ids.insert(0, parent)
                if len(ids) != (2 if kind == "following" else 3) or len(set(ids)) != len(ids):
                    continue
                if any((session, i) in used for i in ids):
                    continue
                if kind == "queue" and any(float(by_id[i]["v_vel"]) * .3048 > .2 for i in ids):
                    continue
                inspected += 1
                if inspected > protocol["maximumCandidatesPerKindAndBlock"]:
                    break
                name = f"v2-{kind}-{block}-{subject}"
                duration = 11 if kind == "following" else protocol["queueRelease"]["maximumFetchSeconds"]
                where = f"location='lankershim' and vehicle_id in ({','.join(map(str, ids))}) and global_time>={start} and global_time<={start + duration * 1000}"
                url = protocol["source"] + "?" + urlencode({"$where": where, "$order": "global_time,vehicle_id", "$limit": 2000})
                rows, source = fetch(url, root / (name + ".json"), protocol, cache)
                sources.append(source)
                encountered.update(identity(r) for r in rows)
                assert all(identity(r)[0] == session for r in rows), "mixed recording sessions in v2 query"
                try:
                    if len(rows) >= 2000:
                        raise ValueError("truncated v2 trajectories")
                    if kind == "following":
                        e = quality(rows, ids, start, kind, protocol)
                        e["context"] = following_context(e, rows, protocol)
                    else:
                        e = queue_release(rows, ids, start, protocol)
                except (ValueError, KeyError) as error:
                    rejected.append(dict(id=name, ids=ids, reason=str(error), source=source))
                    continue
                e.update(id=name, block=block, source=source, sessionOriginMilliseconds=session,
                         speedLimitKmh=protocol["physics"]["speedLimitKmh"])
                selected.append(e)
                used.update((session, i) for i in ids)
                counts[kind] += 1
                break
    return dict(selected=selected, rejected=rejected, sources=sources, counts=dict(counts), targets=target,
                targetsMet=all(counts[k] == target[k] for k in target),
                exposedSessionVehicleKeys=sorted(exposed), observedSessionVehicleKeys=sorted(encountered))


def prediction_metrics(episode, samples, cap):
    result = evaluate(episode, samples)
    for follower in result["followers"]:
        data = sorted((s for s in samples if s["vehicle"] == follower["vehicle"]), key=lambda s: s["tick"])
        follower["constrainedSteps"] = sum(b["velocity"] + 1e-9 < min(cap / 3.6, a["velocity"] + .26)
                                           for a, b in zip(data, data[1:]))
    result["speedLimitKmh"] = cap
    return result


def run(args):
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    protocol_hash = sha(PROTOCOL)
    assert protocol_hash == "a65c7c6357278ccc83652f95c5067a9c4e6164b5e0572268dc9fe3d1d9bf5325", "frozen v2 protocol changed"
    protocol = json.loads(PROTOCOL.read_text())
    write(root / "protocol.json", protocol)
    snapshot = args.snapshot
    jar_hash = sha(args.jar)
    write(root / "executable.json", dict(jar=str(args.jar.resolve()), jarSha256=jar_hash,
        harnessSha256=sha(Path(__file__)), adapterSha256=sha(Path("src/main/scala/model/hybrid/validation/MicroFieldReplay.scala")),
        supportModules={str(f): sha(f) for f in (Path("scripts/scenario/validate_micro_field.py"),
            Path("scripts/scenario/audit_micro_field_inputs.py"), Path("src/main/scala/model/hybrid/micro/strategy/DefaultMicroSimulationStrategy.scala"))}))
    if snapshot:
        selection = json.loads((snapshot / "selection.json").read_text())
        seal = json.loads((snapshot / "selection-seal.json").read_text())
        assert seal["selectionSha256"] == sha(snapshot / "selection.json") and selection["protocolSha256"] == protocol_hash
        for source in [selection["metadataSource"], *selection["sources"]]:
            assert Path(source["file"]).name == source["file"]
            raw = (snapshot / source["file"]).read_bytes()
            assert hashlib.sha256(raw).hexdigest() == source["sha256"]
            (root / source["file"]).write_bytes(raw)
        for episode in selection["selected"]:
            rows = json.loads((root / episode["source"]["file"]).read_text())
            if episode["kind"] == "following":
                recomputed = quality(rows, episode["ids"], episode["startMilliseconds"], "following", protocol)
                recomputed["context"] = following_context(recomputed, rows, protocol)
            else:
                recomputed = queue_release(rows, episode["ids"], episode["initialCandidateMilliseconds"], protocol)
            assert all(recomputed[k] == v for k, v in episode.items() if k in recomputed), "v2 replay eligibility changed"
    else:
        assert args.v1_snapshot is not None, "v2 needs the released v1 snapshot"
        exposed = exposures(args.v1_snapshot)
        write(root / "v1-exposure.json", sorted(exposed))
        metadata, source = fetch(protocol["metadata"], root / "metadata.json", protocol, args.download_cache)
        assert metadata["licenseId"] == protocol["license"]
        selection = select(root, protocol, exposed, args.download_cache)
        selection.update(protocolSha256=protocol_hash, metadataSource=source, licenseId=metadata["licenseId"],
                         attribution=metadata["attribution"], stage="reserved")
    write(root / "selection.json", selection)
    write(root / "selection-seal.json", dict(selectionSha256=sha(root / "selection.json"), jarSha256=jar_hash))
    cases, profiles, unsupported = [], {}, []
    primary = protocol["physics"]["speedLimitKmh"]
    for e in selection["selected"]:
        assert all(v["velocity"] <= primary / 3.6 for v in e["followers"]), "primary profile outside its domain"
        for cap in protocol["speedSensitivityKmh"]:
            if any(v["velocity"] > cap / 3.6 for v in e["followers"]):
                unsupported.append(dict(episode=e["id"], capKmh=cap, reason="initial speed above sensitivity domain"))
                continue
            name = e["id"] if cap == primary else e["id"] + "-cap-" + str(cap)
            cases.append(dict(id=name, followers=e["followers"], leader=e["leader"], speedLimitKmh=cap))
            profiles[name] = e, cap
    write(root / "input.json", dict(episodes=cases))
    command = ["java", "-XX:ActiveProcessorCount=4", "-cp", str(args.jar.resolve()),
        "org.interscity.htc.model.hybrid.validation.MicroFieldReplay", str(root / "input.json"), str(root / "predictions.json")]
    write(root / "command.json", command)
    with (root / "runner.log").open("w") as log:
        subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=120, check=True)
    assert sha(args.jar) == jar_hash, "executable changed"
    samples = json.loads((root / "predictions.json").read_text())
    grouped = defaultdict(list)
    for s in samples:
        assert s["episode"] in profiles, "unexpected field replay episode"
        grouped[s["episode"]].append(s)
    results, sensitivities = [], []
    for name, (e, cap) in profiles.items():
        metrics = prediction_metrics(dict(e, id=name), grouped[name], cap)
        (results if cap == primary else sensitivities).append(metrics)
    assessment = assess(results, selection)
    record = dict(protocolSha256=protocol_hash, jarSha256=jar_hash, measurementValid=True,
        selectionSha256=sha(root / "selection.json"), predictionsSha256=sha(root / "predictions.json"),
        dataGate="eligible" if selection["targetsMet"] else "inconclusive-insufficient-eligible-episodes",
        scientificGate=("accepted" if assessment["passed"] else "rejected") if selection["targetsMet"] else "not-established",
        assessment=assessment, results=results, sensitivities=sensitivities, unsupportedSensitivities=unsupported,
        productionPhysicsChanged=False, empiricalNetworkValidityEstablished=False, claimBoundary=protocol["claimBoundary"])
    write(root / "assessment.json", record)
    print(json.dumps(dict(dataGate=record["dataGate"], scientificGate=record["scientificGate"],
        counts=selection["counts"], rejections=len(selection["rejected"]), checks=assessment["checks"]), indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--v1-snapshot", type=Path)
    parser.add_argument("--snapshot", type=Path)
    parser.add_argument("--download-cache", type=Path)
    args = parser.parse_args()
    existed = args.output.exists()
    try:
        run(args)
    except Exception as error:
        if not existed and args.output.exists():
            write(args.output / "failure.json", dict(measurementValid=False, errorType=type(error).__name__, message=str(error)))
        raise


if __name__ == "__main__":
    main()
