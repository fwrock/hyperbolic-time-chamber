#!/usr/bin/env python3
"""
Compare SUMO and HTC simulation outputs.

Inputs:
- SUMO tripinfo XML
- HTC events JSONL (single file or directory containing *_events.jsonl)

Outputs:
- JSON metrics
- Markdown report
- PNG charts (optional, if matplotlib is available)
"""

import argparse
import collections
import json
import math
import statistics
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Tuple

try:
    import matplotlib.pyplot as plt
    MATPLOTLIB_AVAILABLE = True
except Exception:
    MATPLOTLIB_AVAILABLE = False


@dataclass
class TripRecord:
    trip_id: str
    depart: float
    arrival: float
    duration: float
    route_length: float
    avg_speed: float


def collect_htc_event_files(path: Path, newer_than: Path | None = None) -> List[Path]:
    if path.is_file():
        files = [path]
    else:
        files = sorted(path.glob("**/*_events.jsonl"))
        if not files:
            files = sorted(path.glob("**/*.jsonl"))
        if not files:
            raise FileNotFoundError(f"No JSONL event files found in {path}")

    if newer_than and newer_than.exists():
        marker_time = newer_than.stat().st_mtime
        files = [f for f in files if f.stat().st_mtime >= marker_time]

    if not files:
        raise FileNotFoundError("No HTC event files matched the provided filter")

    return files


def parse_sumo_tripinfo(path: Path) -> Dict[str, TripRecord]:
    tree = ET.parse(path)
    root = tree.getroot()
    trips: Dict[str, TripRecord] = {}

    for trip in root.findall("tripinfo"):
        trip_id = trip.attrib.get("id", "")
        depart = float(trip.attrib.get("depart", 0.0))
        arrival = float(trip.attrib.get("arrival", depart))
        duration = float(trip.attrib.get("duration", max(arrival - depart, 0.0)))
        route_length = float(trip.attrib.get("routeLength", 0.0))
        avg_speed = (route_length / duration) if duration > 0 else 0.0
        trips[trip_id] = TripRecord(
            trip_id=trip_id,
            depart=depart,
            arrival=arrival,
            duration=duration,
            route_length=route_length,
            avg_speed=avg_speed,
        )
    return trips


def parse_htc_events(files: List[Path]) -> Dict[str, TripRecord]:
    starts: Dict[str, float] = {}
    completed: Dict[str, Dict] = {}
    sumo_tripinfo_records: Dict[str, TripRecord] = {}

    def as_float(value, default: float = 0.0) -> float:
        try:
            return float(value)
        except (TypeError, ValueError):
            return default

    def resolve_vehicle_id(data: Dict, event: Dict) -> str | None:
        return (
            data.get("vehicle_id")
            or data.get("car_id")
            or data.get("bus_id")
            or data.get("bicycle_id")
            or data.get("motorcycle_id")
            or event.get("entityId")
        )

    for event_file in files:
        with event_file.open("r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    event = json.loads(line)
                except json.JSONDecodeError:
                    continue

                data = event.get("data", {})
                if isinstance(data, str):
                    try:
                        data = json.loads(data)
                    except json.JSONDecodeError:
                        data = {}

                event_type = str(data.get("event_type", event.get("event_type", "")))
                tick = as_float(event.get("tick", data.get("tick", 0.0)))
                vehicle_id = resolve_vehicle_id(data, event)
                if not vehicle_id:
                    continue

                if event_type == "sumo_tripinfo":
                    depart = as_float(data.get("depart", 0.0))
                    arrival = as_float(data.get("arrival", tick))
                    duration = as_float(data.get("duration", max(arrival - depart, 0.0)))
                    route_length = as_float(data.get("routeLength", data.get("total_distance", 0.0)))
                    avg_speed = (route_length / duration) if duration > 0 else 0.0
                    sumo_tripinfo_records[vehicle_id] = TripRecord(
                        trip_id=vehicle_id,
                        depart=depart,
                        arrival=arrival,
                        duration=max(0.0, duration),
                        route_length=max(0.0, route_length),
                        avg_speed=avg_speed,
                    )
                    continue

                if event_type == "journey_started":
                    starts[vehicle_id] = tick
                elif event_type == "journey_completed":
                    completed[vehicle_id] = {
                        "tick": tick,
                        "distance": as_float(data.get("total_distance", 0.0)),
                        "completed": bool(data.get("reached_destination", True)),
                    }

    if sumo_tripinfo_records:
        return sumo_tripinfo_records

    trips: Dict[str, TripRecord] = {}
    for vehicle_id, end_data in completed.items():
        if not end_data.get("completed", True):
            continue
        depart = starts.get(vehicle_id, 0.0)
        arrival = end_data["tick"]
        duration = max(0.0, arrival - depart)
        route_length = max(0.0, end_data["distance"])
        avg_speed = (route_length / duration) if duration > 0 else 0.0
        trips[vehicle_id] = TripRecord(
            trip_id=vehicle_id,
            depart=depart,
            arrival=arrival,
            duration=duration,
            route_length=route_length,
            avg_speed=avg_speed,
        )

    return trips


def analyze_htc_event_coverage(files: List[Path]) -> Dict:
    expected_event_fields = {
        "sumo_tripinfo": [
            "event_type",
            "vehicle_id",
            "vehicle_type",
            "depart",
            "arrival",
            "duration",
            "routeLength",
            "waitingTime",
            "waitingCount",
            "stopTime",
            "timeLoss",
            "speedFactor",
            "departDelay",
        ],
        "sumo_summary_step": [
            "event_type",
            "time",
            "loaded",
            "inserted",
            "running",
            "waiting",
            "ended",
            "arrived",
            "meanWaitingTime",
            "meanTravelTime",
            "meanSpeed",
            "meanSpeedRelative",
            "halting",
        ],
    }

    event_counts = collections.Counter()
    field_non_null_counts: Dict[str, collections.Counter] = {
        event_type: collections.Counter() for event_type in expected_event_fields
    }

    for event_file in files:
        with event_file.open("r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue

                try:
                    event = json.loads(line)
                except json.JSONDecodeError:
                    continue

                data = event.get("data", {})
                if isinstance(data, str):
                    try:
                        data = json.loads(data)
                    except json.JSONDecodeError:
                        data = {}

                if not isinstance(data, dict):
                    data = {}

                event_type = str(data.get("event_type", event.get("label", "unknown")))
                event_counts[event_type] += 1

                if event_type in expected_event_fields:
                    for field in expected_event_fields[event_type]:
                        value = data.get(field)
                        if value is not None:
                            field_non_null_counts[event_type][field] += 1

    coverage_by_event = {}
    missing_event_types = []
    for event_type, expected_fields in expected_event_fields.items():
        total = int(event_counts.get(event_type, 0))
        if total == 0:
            missing_event_types.append(event_type)

        fields = {}
        missing_fields = []
        for field in expected_fields:
            present_count = int(field_non_null_counts[event_type].get(field, 0))
            coverage_ratio = (present_count / total) if total > 0 else 0.0
            fields[field] = {
                "present_count": present_count,
                "coverage_ratio": coverage_ratio,
            }
            if coverage_ratio < 1.0:
                missing_fields.append(field)

        coverage_by_event[event_type] = {
            "event_count": total,
            "fields": fields,
            "missing_fields": missing_fields,
        }

    return {
        "event_type_inventory": dict(sorted(event_counts.items(), key=lambda x: (-x[1], x[0]))),
        "expected_event_fields": expected_event_fields,
        "coverage_by_event": coverage_by_event,
        "missing_event_types": missing_event_types,
    }


def summarize(records: Dict[str, TripRecord]) -> Dict[str, float]:
    durations = [r.duration for r in records.values()]
    lengths = [r.route_length for r in records.values()]
    speeds = [r.avg_speed for r in records.values()]

    if not records:
        return {
            "n": 0,
            "duration_mean": 0.0,
            "duration_median": 0.0,
            "duration_p90": 0.0,
            "distance_mean": 0.0,
            "speed_mean": 0.0,
        }

    return {
        "n": float(len(records)),
        "duration_mean": statistics.mean(durations),
        "duration_median": statistics.median(durations),
        "duration_p90": percentile(durations, 90),
        "distance_mean": statistics.mean(lengths),
        "speed_mean": statistics.mean(speeds),
    }


def percentile(values: List[float], p: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = (len(ordered) - 1) * (p / 100.0)
    lo = int(math.floor(idx))
    hi = int(math.ceil(idx))
    if lo == hi:
        return ordered[lo]
    return ordered[lo] + (ordered[hi] - ordered[lo]) * (idx - lo)


def relative_diff(a: float, b: float) -> float:
    denom = max(abs(a), 1e-9)
    return abs(a - b) / denom


def compute_similarity(sumo_summary: Dict[str, float], htc_summary: Dict[str, float], paired: List[Tuple[TripRecord, TripRecord]]) -> Dict[str, float]:
    duration_mean_diff = relative_diff(sumo_summary["duration_mean"], htc_summary["duration_mean"])
    duration_p90_diff = relative_diff(sumo_summary["duration_p90"], htc_summary["duration_p90"])
    distance_mean_diff = relative_diff(sumo_summary["distance_mean"], htc_summary["distance_mean"])

    if paired:
        duration_abs_pct = [
            relative_diff(s.duration, h.duration)
            for s, h in paired
            if s.duration > 0
        ]
        per_trip_mape = statistics.mean(duration_abs_pct) if duration_abs_pct else 1.0
    else:
        per_trip_mape = 1.0

    score = max(0.0, 1.0 - (0.35 * duration_mean_diff + 0.25 * duration_p90_diff + 0.2 * distance_mean_diff + 0.2 * per_trip_mape))

    return {
        "duration_mean_rel_diff": duration_mean_diff,
        "duration_p90_rel_diff": duration_p90_diff,
        "distance_mean_rel_diff": distance_mean_diff,
        "per_trip_duration_mape": per_trip_mape,
        "similarity_score_0_1": score,
    }


def paired_records(sumo: Dict[str, TripRecord], htc: Dict[str, TripRecord]) -> List[Tuple[TripRecord, TripRecord]]:
    ids = sorted(set(sumo.keys()) & set(htc.keys()))
    return [(sumo[i], htc[i]) for i in ids]


def write_charts(output_dir: Path, sumo: Dict[str, TripRecord], htc: Dict[str, TripRecord], paired: List[Tuple[TripRecord, TripRecord]]) -> List[str]:
    if not MATPLOTLIB_AVAILABLE:
        return []

    generated = []

    sumo_d = [r.duration for r in sumo.values()]
    htc_d = [r.duration for r in htc.values()]
    if sumo_d and htc_d:
        plt.figure(figsize=(10, 5))
        plt.hist(sumo_d, bins=30, alpha=0.6, label="SUMO")
        plt.hist(htc_d, bins=30, alpha=0.6, label="HTC")
        plt.xlabel("Trip duration")
        plt.ylabel("Frequency")
        plt.title("Trip Duration Distribution")
        plt.legend()
        out = output_dir / "duration_distribution.png"
        plt.tight_layout()
        plt.savefig(out, dpi=150)
        plt.close()
        generated.append(out.name)

    if paired:
        x = [s.duration for s, _ in paired]
        y = [h.duration for _, h in paired]
        plt.figure(figsize=(6, 6))
        plt.scatter(x, y, s=10, alpha=0.6)
        lim = max(max(x), max(y), 1)
        plt.plot([0, lim], [0, lim], "r--", linewidth=1)
        plt.xlabel("SUMO duration")
        plt.ylabel("HTC duration")
        plt.title("Per-trip Duration Agreement")
        out = output_dir / "duration_scatter.png"
        plt.tight_layout()
        plt.savefig(out, dpi=150)
        plt.close()
        generated.append(out.name)

    return generated


def recommend_actions(similarity: Dict[str, float]) -> List[str]:
    recs = []
    if similarity["duration_mean_rel_diff"] > 0.15:
        recs.append("Alinhar parâmetros de car-following no modo MICRO (aceleração, desaceleração, gap mínimo, reactionTime).")
    if similarity["duration_p90_rel_diff"] > 0.2:
        recs.append("Investigar caudas de atraso: tempos de semáforo, bloqueios de interseção e lógica de troca de faixa.")
    if similarity["distance_mean_rel_diff"] > 0.1:
        recs.append("Garantir equivalência de roteamento: mesmo grafo, pesos e algoritmo (Dijkstra por tempo/custo).")
    if similarity["per_trip_duration_mape"] > 0.25:
        recs.append("Comparar trajetórias por veículo (top 20 maiores erros) para identificar divergência de decisão local.")
    if not recs:
        recs.append("Diferenças principais estão sob controle. Próximo passo: validar robustez em múltiplas seeds e escalas de demanda.")
    return recs


def main() -> None:
    parser = argparse.ArgumentParser(description="Compare SUMO and HTC outputs")
    parser.add_argument("--sumo-tripinfo", type=Path, required=True, help="SUMO tripinfo.xml")
    parser.add_argument("--htc-events", type=Path, required=True, help="HTC events JSONL file or folder")
    parser.add_argument("--htc-newer-than", type=Path, default=None, help="Optional marker file; include only events newer than marker mtime")
    parser.add_argument("--output", type=Path, required=True, help="Output JSON metrics path")
    parser.add_argument("--markdown", type=Path, required=True, help="Output Markdown report path")
    args = parser.parse_args()

    output_dir = args.output.parent
    output_dir.mkdir(parents=True, exist_ok=True)

    sumo = parse_sumo_tripinfo(args.sumo_tripinfo)
    htc_files = collect_htc_event_files(args.htc_events, args.htc_newer_than)
    htc = parse_htc_events(htc_files)
    htc_coverage = analyze_htc_event_coverage(htc_files)
    paired = paired_records(sumo, htc)

    sumo_summary = summarize(sumo)
    htc_summary = summarize(htc)
    similarity = compute_similarity(sumo_summary, htc_summary, paired)
    charts = write_charts(output_dir, sumo, htc, paired)
    recommendations = recommend_actions(similarity)

    result = {
        "inputs": {
            "sumo_tripinfo": str(args.sumo_tripinfo),
            "htc_events": str(args.htc_events),
            "htc_event_files": [str(p) for p in htc_files],
        },
        "counts": {
            "sumo_completed_trips": len(sumo),
            "htc_completed_trips": len(htc),
            "paired_trips": len(paired),
            "htc_event_types_detected": len(htc_coverage["event_type_inventory"]),
        },
        "sumo_summary": sumo_summary,
        "htc_summary": htc_summary,
        "similarity": similarity,
        "htc_event_coverage": htc_coverage,
        "recommendations": recommendations,
        "charts": charts,
    }

    args.output.write_text(json.dumps(result, indent=2), encoding="utf-8")

    md = []
    md.append("# SUMO x HTC Comparison Report")
    md.append("")
    md.append("## Coverage")
    md.append(f"- SUMO completed trips: {len(sumo)}")
    md.append(f"- HTC completed trips: {len(htc)}")
    md.append(f"- Paired trips (same id): {len(paired)}")
    md.append(f"- HTC event types detected: {len(htc_coverage['event_type_inventory'])}")
    if htc_coverage["missing_event_types"]:
        md.append(f"- Missing expected event types: {', '.join(htc_coverage['missing_event_types'])}")
    else:
        md.append("- Missing expected event types: none")
    md.append("")
    md.append("## HTC Event Types Inventory")
    for event_type, count in htc_coverage["event_type_inventory"].items():
        md.append(f"- {event_type}: {count}")
    md.append("")
    md.append("## Metric Coverage Gaps")
    for event_type, event_cov in htc_coverage["coverage_by_event"].items():
        missing_fields = event_cov["missing_fields"]
        if event_cov["event_count"] == 0:
            md.append(f"- {event_type}: event not found")
        elif missing_fields:
            md.append(f"- {event_type}: missing/incomplete fields -> {', '.join(missing_fields)}")
        else:
            md.append(f"- {event_type}: complete")
    md.append("")
    md.append("## Similarity")
    md.append(f"- Similarity score [0..1]: **{similarity['similarity_score_0_1']:.4f}**")
    md.append(f"- Mean duration relative diff: {similarity['duration_mean_rel_diff']:.4f}")
    md.append(f"- P90 duration relative diff: {similarity['duration_p90_rel_diff']:.4f}")
    md.append(f"- Mean distance relative diff: {similarity['distance_mean_rel_diff']:.4f}")
    md.append(f"- Per-trip duration MAPE: {similarity['per_trip_duration_mape']:.4f}")
    md.append("")
    md.append("## Recommendations")
    for rec in recommendations:
        md.append(f"- {rec}")
    md.append("")
    if charts:
        md.append("## Generated Charts")
        for chart in charts:
            md.append(f"- {chart}")
        md.append("")

    args.markdown.write_text("\n".join(md), encoding="utf-8")

    print("=" * 80)
    print("✅ Comparison complete")
    print(f"JSON metrics: {args.output}")
    print(f"Markdown report: {args.markdown}")
    if charts:
        print(f"Charts: {', '.join(charts)}")
    else:
        print("Charts not generated (matplotlib unavailable)")
    print(f"Similarity score: {similarity['similarity_score_0_1']:.4f}")
    print("=" * 80)


if __name__ == "__main__":
    main()