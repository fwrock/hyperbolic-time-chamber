#!/usr/bin/env python3
"""Frozen, conditional NGSIM following/discharge assessment using active HTC Link physics."""
import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import random
import re
import statistics
import subprocess
import time
from urllib.error import URLError
from urllib.parse import urlencode, urlparse, parse_qs
from urllib.request import urlopen

PROTOCOL = Path(__file__).with_name("micro_field_protocol_v1.json")
PROTOCOL_SHA256 = "e79261f236740ef2e84599a4098b73c9185da7d83fe458c8b6f24750e059407d"
FT = 0.3048


def write(path, value):
    path.write_text(json.dumps(value, indent=2, allow_nan=False) + "\n")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def slope(positions):
    x = [i * .1 for i in range(len(positions))]
    xm, ym = statistics.mean(x), statistics.mean(positions)
    return sum((a - xm) * (b - ym) for a, b in zip(x, positions)) / sum((a - xm) ** 2 for a in x)


def crossing(positions, station):
    for i, (a, b) in enumerate(zip(positions, positions[1:])):
        if a < station <= b:
            return (i + (station - a) / (b - a)) * .1
    return None


def quality(rows, ids, start, kind, protocol):
    duration = protocol["queuePredictionSeconds" if kind == "queue" else "followingPredictionSeconds"]
    count = round((protocol["initializationSeconds"] + duration) * 10) + 1
    records = {}
    duplicates = 0
    for row in rows:
        key = int(row["vehicle_id"]), int(row["global_time"])
        if key in records:
            if row != records[key]:
                raise ValueError("conflicting duplicate subject/time")
            duplicates += 1
        records[key] = row
    trajectories, speeds, lengths, initials, lane_keys, derivative_errors = [], [], [], [], [], []
    q = protocol["quality"]
    for index, identity in enumerate(ids):
        keys = [(identity, start + i * 100) for i in range(count)]
        if not all(k in records for k in keys):
            raise ValueError("missing contiguous subject observations")
        data = [records[k] for k in keys]
        if len({r["lane_id"] for r in data}) != 1:
            raise ValueError("lane change")
        if any(int(r["v_class"]) != 2 or int(r["direction"]) != 2 or int(r["movement"]) != 1 for r in data):
            raise ValueError("class, direction or movement change")
        if index > 0 and any(int(r["preceding"]) != ids[index - 1] for r in data):
            raise ValueError("immediate leader identity change")
        if any(int(b["frame_id"]) != int(a["frame_id"]) + 1 for a, b in zip(data, data[1:])):
            raise ValueError("frame identity discontinuity")
        y = [float(r["local_y"]) * FT for r in data]
        v = [float(r["v_vel"]) * FT for r in data]
        lens = [float(r["v_length"]) * FT for r in data]
        if not all(math.isfinite(x) for x in y + v + lens) or min(v) < 0:
            raise ValueError("nonfinite or negative kinematics")
        if any(b - a < -q["maximumReverseStepM"] for a, b in zip(y, y[1:])):
            raise ValueError("reversed longitudinal motion")
        length = statistics.median(lens[:11])
        if not q["minimumVehicleLengthM"] <= length <= q["maximumVehicleLengthM"]:
            raise ValueError("unsupported observed vehicle length")
        initial = slope(y[:11])
        if initial < -q["maximumAbsolutePrefixStopSpeedMps"] or initial > q["maximumPrefixSpeedMps"]:
            raise ValueError("initial speed outside component scope")
        if kind == "queue" and abs(initial) > q["maximumAbsolutePrefixStopSpeedMps"]:
            raise ValueError("queue not stopped through initialization")
        trajectories.append(y)
        speeds.append([max(0, slope(y[i - 10:i + 1])) for i in range(10, count)])
        derivative_errors.append(statistics.mean(abs((b - a) * 10 - measured)
            for a, b, measured in zip(y, y[1:], v[1:])))
        lengths.append(length)
        initials.append(max(0, initial))
        lane_keys.append((data[0]["lane_id"], data[0]["direction"]))
    if len(set(lane_keys)) != 1:
        raise ValueError("chain is not in the same lane/direction")
    for index in range(1, len(ids)):
        gaps = [a - lengths[index - 1] - b for a, b in zip(trajectories[index - 1], trajectories[index])]
        if min(gaps) < q["minimumObservedBumperGapM"]:
            raise ValueError("observed bumper overlap")
        if gaps[10] < q["minimumInitialBumperGapM"]:
            raise ValueError("initial gap outside supported scope")
    station = trajectories[0][10] + 5
    observed = [y[10:] for y in trajectories]
    observed_crossings = [crossing(y, station) for y in observed]
    if kind == "queue" and any(x is None for x in observed_crossings):
        raise ValueError("no complete observed queue discharge in fixed window")
    offset = 100 - min(y[10] for y in trajectories)
    return dict(ids=ids, kind=kind, startMilliseconds=start, station=station + offset,
        leader=[dict(tick=i, position=y + offset, velocity=speeds[0][i], length=lengths[0])
                for i, y in enumerate(observed[0])],
        followers=[dict(id=identity, position=observed[i][0] + offset, velocity=initials[i], length=lengths[i])
                   for i, identity in enumerate(ids) if i > 0],
        observations=[[y + offset for y in values] for values in observed],
        observedSpeeds=speeds, observedCrossings=observed_crossings,
        quality=dict(exactDuplicatesRemoved=duplicates, framesPerVehicle=count,
                     annotatedSpeedDerivativeMaeMps=derivative_errors, lane=lane_keys[0][0]))


def validate_cached_response(value, url):
    if "/api/views/" in url:
        if value.get("id") != "8ect-6jqj":
            raise ValueError("cached metadata dataset identity mismatch")
        return
    where = parse_qs(urlparse(url).query)["$where"][0]
    exact = re.search(r"global_time=(\d+)", where)
    lower, upper = re.search(r"global_time>=(\d+)", where), re.search(r"global_time<=(\d+)", where)
    identities = re.search(r"vehicle_id in \(([^)]+)\)", where)
    ids = set(map(int, identities.group(1).split(","))) if identities else None
    if not isinstance(value, list):
        raise ValueError("cached trajectory response is not an array")
    for row in value:
        tick = int(row["global_time"])
        if row.get("location") != "lankershim" or (exact and tick != int(exact.group(1))) or \
                (lower and tick < int(lower.group(1))) or (upper and tick > int(upper.group(1))) or \
                (ids is not None and int(row["vehicle_id"]) not in ids):
            raise ValueError("cached response does not match frozen query")


def fetch(url, path, protocol, cache=None):
    limit = protocol["quality"]["maximumBytesPerQuery"]
    cached = cache / path.name if cache else None
    if cached is not None and cached.is_file():
        raw = cached.read_bytes()
        value = json.loads(raw)
        sidecar = cached.with_suffix(".request.json")
        if sidecar.exists():
            saved = json.loads(sidecar.read_text())
            if saved.get("url") != url or saved.get("sha256") != sha(cached):
                raise ValueError("cached response hash/query identity mismatch")
        validate_cached_response(value, url)
        origin = dict(recoveredFrom=str(cached), provenance="cached frozen query; raw contents revalidated")
    else:
        for attempt in range(3):
            try:
                with urlopen(url, timeout=45) as response:
                    raw = response.read(limit + 1)
                break
            except (TimeoutError, ConnectionError, URLError) as error:
                if attempt == 2:
                    write(path.with_suffix(".request.json"), dict(url=url, attempts=3, error=str(error)))
                    raise
                time.sleep(attempt + 1)
        origin = dict(provenance="https-fetch", attempts=attempt + 1)
    path.write_bytes(raw)
    if len(raw) > limit:
        raise ValueError("bounded response exceeds byte limit")
    source = dict(url=url, file=path.name, sha256=sha(path), bytes=len(raw), **origin)
    write(path.with_suffix(".request.json"), source)
    return json.loads(raw), source


def select(root, stage, protocol, exclude_ids, cache=None):
    selected, rejected, sources = [], [], []
    used = set(exclude_ids)
    observed_ids = set()
    counts = Counter()
    offsets = protocol["developmentOffsetsSeconds" if stage == "development" else "reservedOffsetsSeconds"]
    targets = {kind: protocol["targets"][stage + kind.title()] for kind in ("following", "queue")}
    for block, offset in enumerate(offsets):
        if all(counts[k] >= targets[k] for k in targets):
            break
        start = protocol["samplingFrameOriginMilliseconds"] + offset * 1000
        query = dict(**{"$where": f"location='lankershim' and global_time={start}", "$limit": 500,
                        "$order": "vehicle_id"})
        candidates, source = fetch(protocol["source"] + "?" + urlencode(query), root / f"block-{block}.json", protocol, cache)
        sources.append(source)
        if len(candidates) >= 500:
            raise ValueError("truncated candidate snapshot")
        by_id = {int(r["vehicle_id"]): r for r in candidates}
        observed_ids.update(by_id)
        for kind in ("following", "queue"):
            if counts[kind] >= targets[kind]:
                continue
            inspected = 0
            for subject in sorted(by_id):
                row = by_id[subject]
                if int(row.get("v_class", 0)) != 2 or int(row.get("direction", 0)) != 2 or \
                        int(row.get("movement", 0)) != 1 or not int(row.get("preceding", 0)):
                    continue
                speed = float(row["v_vel"]) * FT
                if (kind == "following" and speed < 1) or (kind == "queue" and speed > .2):
                    continue
                ids = [subject]
                for _ in range(1 if kind == "following" else protocol["queueFollowers"]):
                    parent = int(by_id.get(ids[0], {}).get("preceding", 0))
                    if parent not in by_id:
                        break
                    ids.insert(0, parent)
                if len(ids) != (2 if kind == "following" else 3) or len(set(ids)) != len(ids):
                    continue
                if any(i in used for i in ids):
                    continue
                if kind == "queue" and any(float(by_id[i]["v_vel"]) * FT > .2 for i in ids):
                    continue
                inspected += 1
                if inspected > protocol["maximumCandidatesPerKindAndBlock"]:
                    break
                name = f"{stage}-{kind}-{block}-{subject}"
                duration = protocol["queuePredictionSeconds" if kind == "queue" else "followingPredictionSeconds"]
                where = f"location='lankershim' and vehicle_id in ({','.join(map(str, ids))}) and global_time>={start} and global_time<={start + (1 + duration) * 1000}"
                url = protocol["source"] + "?" + urlencode({"$where": where, "$order": "global_time,vehicle_id", "$limit": 2000})
                rows, source = fetch(url, root / (name + ".json"), protocol, cache)
                sources.append(source)
                try:
                    if len(rows) >= protocol["quality"]["maximumRowsPerQuery"]:
                        raise ValueError("truncated subject observations")
                    episode = quality(rows, ids, start, kind, protocol)
                except (ValueError, KeyError) as error:
                    rejected.append(dict(id=name, ids=ids, reason=str(error), source=source))
                    continue
                episode.update(id=name, block=block, source=source)
                selected.append(episode)
                used.update(ids)
                counts[kind] += 1
                break
    return dict(selected=selected, rejected=rejected, sources=sources, counts=dict(counts),
                observedVehicleIds=sorted(observed_ids), targets=targets,
                targetsMet=all(counts[k] == targets[k] for k in targets))


def rmse(a, b):
    if len(a) != len(b) or not a:
        raise ValueError("incomplete paired measurement")
    return math.sqrt(statistics.mean((x - y) ** 2 for x, y in zip(a, b)))


def evaluate(episode, samples):
    by_id = {identity: {} for identity in episode["ids"][1:]}
    for s in samples:
        if s["vehicle"] not in by_id or s["tick"] in by_id[s["vehicle"]]:
            raise ValueError("duplicate or unexpected replay sample")
        if not all(math.isfinite(s[k]) for k in ("position", "velocity", "acceleration")):
            raise ValueError("nonfinite replay output")
        by_id[s["vehicle"]][s["tick"]] = s
    count = len(episode["leader"])
    predicted = [episode["observations"][0]]
    baseline_cv = [episode["observations"][0]]
    baseline_rigid = [episode["observations"][0]]
    errors, gaps = [], []
    for i, identity in enumerate(episode["ids"][1:], 1):
        if sorted(by_id[identity]) != list(range(count)):
            raise ValueError("missing replay prediction samples")
        data = [by_id[identity][tick] for tick in range(count)]
        observed = episode["observations"][i]
        positions = [s["position"] for s in data]
        initial = episode["followers"][i - 1]
        cv = [initial["position"] + initial["velocity"] * tick * .1 for tick in range(count)]
        rigid = [initial["position"] + p - episode["leader"][0]["position"] for p in episode["observations"][0]]
        length = episode["leader"][0]["length"] if i == 1 else episode["followers"][i - 2]["length"]
        gaps.extend(a - length - b for a, b in zip(predicted[-1], positions))
        errors.append(dict(vehicle=identity, positionRmseM=rmse(positions[1:], observed[1:]),
            speedRmseMps=rmse([s["velocity"] for s in data[1:]], episode["observedSpeeds"][i][1:]),
            constantVelocityRmseM=rmse(cv[1:], observed[1:]), rigidSpacingRmseM=rmse(rigid[1:], observed[1:]),
            initialBumperGapM=predicted[-1][0] - length - positions[0],
            constrainedSteps=sum(b["velocity"] + 1e-9 < min(50 / 3.6, a["velocity"] + .26)
                                 for a, b in zip(data, data[1:]))))
        predicted.append(positions)
        baseline_cv.append(cv)
        baseline_rigid.append(rigid)
    result = dict(id=episode["id"], kind=episode["kind"], block=episode["block"],
        source=episode["source"], quality=episode["quality"], followers=errors,
        meanPositionRmseM=statistics.mean(e["positionRmseM"] for e in errors),
        meanConstantVelocityRmseM=statistics.mean(e["constantVelocityRmseM"] for e in errors),
        meanRigidSpacingRmseM=statistics.mean(e["rigidSpacingRmseM"] for e in errors),
        minimumPredictedGapM=min(gaps), sampleCoverage=1)
    if episode["kind"] == "queue":
        station = episode["station"]
        actual = [crossing(p, station) for p in predicted]
        rigid = [crossing(p, station) for p in baseline_rigid]
        observed = episode["observedCrossings"]
        covered = all(t is not None for t in actual)
        result.update(observedCrossingsSeconds=observed, predictedCrossingsSeconds=actual,
                      rigidCrossingsSeconds=rigid, crossingCoverage=sum(t is not None for t in actual) / len(actual))
        def headway_mae(times):
            if any(t is None for t in times):
                return None
            return statistics.mean(abs((b - a) - (y - x)) for a, b, x, y in
                                   zip(times, times[1:], observed, observed[1:]))
        result.update(headwayMaeSeconds=headway_mae(actual), rigidHeadwayMaeSeconds=headway_mae(rigid))
    return result


def interval(values):
    if not values:
        return None
    rng = random.Random(20261026)
    means = sorted(statistics.mean(rng.choices(values, k=len(values))) for _ in range(10000))
    return dict(mean=statistics.mean(values), lower95=means[249], upper95=means[9749],
                episodes=len(values), interpretation="Exploratory conditional episode bootstrap; temporal dependence remains.")


def assess(results, selection):
    following = [r for r in results if r["kind"] == "following"]
    queues = [r for r in results if r["kind"] == "queue"]
    skill_cv = interval([r["meanPositionRmseM"] - r["meanConstantVelocityRmseM"] for r in following])
    skill_rigid = interval([r["meanPositionRmseM"] - r["meanRigidSpacingRmseM"] for r in following])
    queue_skill = interval([r["headwayMaeSeconds"] - r["rigidHeadwayMaeSeconds"] for r in queues
                            if r["headwayMaeSeconds"] is not None and r["rigidHeadwayMaeSeconds"] is not None])
    checks = dict(selectionCoverage=selection["targetsMet"], predictionCoverage=bool(results),
        nonoverlappingPredictedBumpers=bool(results) and min(r["minimumPredictedGapM"] for r in results) >= -1e-9,
        queueCrossingCoverage=bool(queues) and all(r["crossingCoverage"] == 1 for r in queues),
        followingBeatsConstantVelocity=skill_cv is not None and skill_cv["upper95"] < 0,
        followingBeatsRigidSpacing=skill_rigid is not None and skill_rigid["upper95"] < 0,
        queueHeadwaysBeatRigidSpacing=queue_skill is not None and len(queues) == queue_skill["episodes"] and queue_skill["upper95"] < 0)
    return dict(checks=checks, passed=all(checks.values()), followingVsConstantVelocity=skill_cv,
                followingVsRigidSpacing=skill_rigid, queueHeadwaysVsRigidSpacing=queue_skill)


def run(args):
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    assert sha(PROTOCOL) == PROTOCOL_SHA256, "frozen protocol changed"
    protocol = json.loads(PROTOCOL.read_text())
    write(root / "protocol.json", protocol)
    frozen_jar = sha(args.jar)
    write(root / "executable.json", dict(jar=str(args.jar.resolve()), sha256=frozen_jar,
        adapterSourceSha256=sha(Path("src/main/scala/model/hybrid/validation/MicroFieldReplay.scala")),
        harnessSourceSha256=sha(Path(__file__)), physicsSourceSha256={str(f): sha(f) for f in (
            Path("src/main/scala/model/hybrid/micro/strategy/DefaultMicroSimulationStrategy.scala"),
            Path("src/main/scala/model/hybrid/micro/model/KraussModel.scala"),
            Path("src/main/scala/model/hybrid/entity/state/model/VehicleInLane.scala"))}))
    snapshot = getattr(args, "snapshot", None)
    if snapshot:
        selection = json.loads((snapshot / "selection.json").read_text())
        seal = json.loads((snapshot / "selection-seal.json").read_text())
        assert seal["selectionSha256"] == sha(snapshot / "selection.json"), "field manifest seal mismatch"
        assert selection["stage"] == args.stage and selection["protocolSha256"] == PROTOCOL_SHA256
        for item in [selection["metadataSource"], *selection["sources"]]:
            name = item["file"]
            assert Path(name).name == name, "snapshot file escapes its directory"
            raw = (snapshot / name).read_bytes()
            assert hashlib.sha256(raw).hexdigest() == item["sha256"], "raw field snapshot hash mismatch"
            (root / name).write_bytes(raw)
        metadata = json.loads((root / "metadata.json").read_text())
        source = selection["metadataSource"]
        for episode in selection["selected"]:
            recomputed = quality(json.loads((root / episode["source"]["file"]).read_text()),
                episode["ids"], episode["startMilliseconds"], episode["kind"], protocol)
            assert all(recomputed[k] == v for k, v in episode.items() if k in recomputed), "replayed field eligibility changed"
    else:
        metadata, source = fetch(protocol["metadata"], root / "metadata.json", protocol,
                                 getattr(args, "download_cache", None))
    assert metadata["licenseId"] == protocol["license"]
    columns = {c.get("fieldName"): c for c in metadata["columns"]}
    assert "front center" in columns["local_y"]["description"] and "feet" in columns["local_y"]["description"]
    excluded = []
    if args.stage == "reserved" and not snapshot:
        assert args.development_manifest is not None, "reserved selection requires the development manifest"
        development = json.loads(args.development_manifest.read_text())
        excluded = development["observedVehicleIds"]
    if not snapshot:
        selection = select(root, args.stage, protocol, excluded, getattr(args, "download_cache", None))
        selection.update(stage=args.stage, protocolSha256=PROTOCOL_SHA256, metadataSource=source,
                         licenseId=metadata["licenseId"], attribution=metadata["attribution"], excludedVehicleIds=excluded)
    write(root / "selection.json", selection)
    # Seal identity and eligibility before producing any model prediction.
    write(root / "selection-seal.json", dict(selectionSha256=sha(root / "selection.json"), jarSha256=frozen_jar))
    replay = dict(episodes=[{k: e[k] for k in ("id", "followers", "leader")} for e in selection["selected"]])
    write(root / "input.json", replay)
    command = ["java", "-XX:ActiveProcessorCount=4", "-cp", str(args.jar.resolve()),
               "org.interscity.htc.model.hybrid.validation.MicroFieldReplay", str(root / "input.json"), str(root / "predictions.json")]
    write(root / "command.json", command)
    with (root / "runner.log").open("w") as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=120)
    assert result.returncode == 0 and sha(args.jar) == frozen_jar, "replay engine failure"
    predictions = json.loads((root / "predictions.json").read_text())
    if any(s["episode"] not in {e["id"] for e in selection["selected"]} for s in predictions):
        raise ValueError("unexpected replay episode")
    results = [evaluate(e, [s for s in predictions if s["episode"] == e["id"]]) for e in selection["selected"]]
    assessment = assess(results, selection)
    record = dict(stage=args.stage, protocolSha256=PROTOCOL_SHA256, jarSha256=frozen_jar,
        selectionSha256=sha(root / "selection.json"), inputSha256=sha(root / "input.json"),
        predictionsSha256=sha(root / "predictions.json"), measurementValid=True,
        dataGate="eligible" if selection["targetsMet"] else "inconclusive-insufficient-eligible-episodes",
        scientificGate=("accepted" if assessment["passed"] else "rejected") if selection["targetsMet"] else "not-established",
        empiricalNetworkValidityEstablished=False, assessment=assessment, results=results,
        licenseId=metadata["licenseId"], attribution=metadata["attribution"],
        limitations=protocol["claimBoundary"])
    write(root / "assessment.json", record)
    print(json.dumps(dict(stage=args.stage, dataGate=record["dataGate"], scientificGate=record["scientificGate"],
                         selected=selection["counts"], rejected=len(selection["rejected"]), checks=assessment["checks"]), indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--stage", choices=("development", "reserved"), required=True)
    parser.add_argument("--development-manifest", type=Path)
    parser.add_argument("--snapshot", type=Path, help="Verify and replay a retained field selection without network access")
    parser.add_argument("--download-cache", type=Path, help="Recover raw responses from an interrupted identical-query run")
    args = parser.parse_args()
    existed = args.output.exists()
    try:
        run(args)
    except Exception as error:
        if not existed and args.output.exists():
            write(args.output / "failure.json", dict(measurementValid=False, stage=args.stage,
                errorType=type(error).__name__, message=str(error), protocolSha256=PROTOCOL_SHA256))
        raise


if __name__ == "__main__":
    main()
