#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any
import xml.etree.ElementTree as ET


def safe_float(value: Any) -> float | None:
    try:
        if value is None:
            return None
        return float(value)
    except (TypeError, ValueError):
        return None


def safe_int(value: Any) -> int | None:
    try:
        if value is None:
            return None
        return int(value)
    except (TypeError, ValueError):
        return None


def mean_or_none(values: list[float]) -> float | None:
    return statistics.mean(values) if values else None


def median_or_none(values: list[float]) -> float | None:
    return statistics.median(values) if values else None


def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    k = (len(ordered) - 1) * p
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return ordered[int(k)]
    return ordered[f] * (c - k) + ordered[c] * (k - f)


def fmt(value: Any, decimals: int = 3) -> str:
    if value is None:
        return "NA"
    if isinstance(value, float):
        if math.isnan(value) or math.isinf(value):
            return "NA"
        return f"{value:.{decimals}f}"
    return str(value)


def normalize_trip_id(raw: str | None) -> str | None:
    if not raw:
        return None
    token = raw
    if "trip_" in token:
        suffix = token.split("trip_")[-1]
        digits = "".join(ch for ch in suffix if ch.isdigit())
        if digits:
            return f"trip_{digits}"
    digits = "".join(ch for ch in token if ch.isdigit())
    if digits:
        return f"trip_{digits}"
    return None


def pearson_corr(a: list[float], b: list[float]) -> float | None:
    if len(a) != len(b) or len(a) < 2:
        return None
    ma = statistics.mean(a)
    mb = statistics.mean(b)
    num = sum((x - ma) * (y - mb) for x, y in zip(a, b))
    den_a = math.sqrt(sum((x - ma) ** 2 for x in a))
    den_b = math.sqrt(sum((y - mb) ** 2 for y in b))
    if den_a == 0 or den_b == 0:
        return None
    return num / (den_a * den_b)


def mae(a: list[float], b: list[float]) -> float | None:
    if len(a) != len(b) or not a:
        return None
    return sum(abs(x - y) for x, y in zip(a, b)) / len(a)


def mape(a: list[float], b: list[float]) -> float | None:
    if len(a) != len(b) or not a:
        return None
    rows = [(x, y) for x, y in zip(a, b) if x != 0]
    if not rows:
        return None
    return 100.0 * sum(abs((x - y) / x) for x, y in rows) / len(rows)


def resolve_vehicle_id(data: dict[str, Any], rec: dict[str, Any]) -> str | None:
    for key in ("vehicle_id", "car_id", "bus_id", "bicycle_id", "motorcycle_id", "person_id"):
        value = data.get(key)
        if isinstance(value, str) and value:
            return value
    entity_id = rec.get("entityId")
    if isinstance(entity_id, str) and entity_id:
        return entity_id
    return None


@dataclass
class SumoTrip:
    trip_id: str
    depart: float | None
    arrival: float | None
    duration: float | None
    route_length: float | None
    waiting_time: float | None
    waiting_count: int | None
    stop_time: float | None
    time_loss: float | None
    depart_delay: float | None
    reroute_no: int | None


@dataclass
class HtcTrip:
    trip_id: str
    depart_tick: float | None
    arrival_tick: float | None
    duration: float | None
    distance: float | None
    avg_speed: float | None
    completed: bool
    completion_reason: str | None


class SumoParser:
    def __init__(self, scenario_dir: Path) -> None:
        self.tripinfo_path = scenario_dir / "sumo" / "tripinfo.xml"
        self.summary_path = scenario_dir / "sumo" / "summary.xml"

    def parse_tripinfo(self) -> tuple[dict[str, SumoTrip], set[str]]:
        if not self.tripinfo_path.exists():
            raise FileNotFoundError(f"Arquivo não encontrado: {self.tripinfo_path}")

        trips: dict[str, SumoTrip] = {}
        raw_fields: set[str] = set()

        for _, elem in ET.iterparse(self.tripinfo_path, events=("end",)):
            if elem.tag != "tripinfo":
                continue

            attrs = elem.attrib
            raw_fields.update(attrs.keys())
            trip_id = attrs.get("id")
            if not trip_id:
                elem.clear()
                continue

            trips[trip_id] = SumoTrip(
                trip_id=trip_id,
                depart=safe_float(attrs.get("depart")),
                arrival=safe_float(attrs.get("arrival")),
                duration=safe_float(attrs.get("duration")),
                route_length=safe_float(attrs.get("routeLength")),
                waiting_time=safe_float(attrs.get("waitingTime")),
                waiting_count=safe_int(attrs.get("waitingCount")),
                stop_time=safe_float(attrs.get("stopTime")),
                time_loss=safe_float(attrs.get("timeLoss")),
                depart_delay=safe_float(attrs.get("departDelay")),
                reroute_no=safe_int(attrs.get("rerouteNo")),
            )
            elem.clear()

        return trips, raw_fields

    def parse_summary(self) -> dict[str, float | int | None]:
        if not self.summary_path.exists():
            return {}

        peak_running = 0.0
        peak_waiting = 0.0
        peak_collisions = 0.0
        peak_teleports = 0.0
        last_arrived = None
        last_ended = None
        sim_end_time = None

        for _, elem in ET.iterparse(self.summary_path, events=("end",)):
            if elem.tag != "step":
                continue
            attrs = elem.attrib
            running = safe_float(attrs.get("running")) or 0.0
            waiting = safe_float(attrs.get("waiting")) or 0.0
            collisions = safe_float(attrs.get("collisions")) or 0.0
            teleports = safe_float(attrs.get("teleports")) or 0.0
            time = safe_float(attrs.get("time"))

            peak_running = max(peak_running, running)
            peak_waiting = max(peak_waiting, waiting)
            peak_collisions = max(peak_collisions, collisions)
            peak_teleports = max(peak_teleports, teleports)
            last_arrived = safe_float(attrs.get("arrived"))
            last_ended = safe_float(attrs.get("ended"))
            sim_end_time = time
            elem.clear()

        return {
            "peak_running": peak_running,
            "peak_waiting": peak_waiting,
            "peak_collisions": peak_collisions,
            "peak_teleports": peak_teleports,
            "last_arrived": last_arrived,
            "last_ended": last_ended,
            "sim_end_time": sim_end_time,
        }


class HtcParser:
    def __init__(self, htc_files: list[Path], tick_seconds: float) -> None:
        self.htc_files = htc_files
        self.tick_seconds = tick_seconds

    def parse(self) -> tuple[dict[str, HtcTrip], dict[str, Any]]:
        if not self.htc_files:
            raise FileNotFoundError("Nenhum arquivo HTC (.jsonl) encontrado.")

        vehicles: dict[str, dict[str, Any]] = defaultdict(
            lambda: {
                "first_tick": None,
                "last_tick": None,
                "journey_started_tick": None,
                "journey_completed_tick": None,
                "completion_reason": None,
                "distance_max": None,
                "distance_from_completion": None,
                "segment_time_sum": 0.0,
            }
        )
        sumo_tripinfo_trips: dict[str, HtcTrip] = {}

        event_counter = Counter()
        top_level_fields: set[str] = set()
        data_fields: set[str] = set()
        journey_started_trip_ids: set[str] = set()
        journey_completed_trip_ids: set[str] = set()
        journey_completed_valid_trip_ids: set[str] = set()
        journey_completed_missing_fields: Counter[str] = Counter()
        journey_completed_event_count = 0
        journey_completed_valid_event_count = 0
        negative_vehicles_remaining = 0
        bad_lines = 0
        total_lines = 0
        summary_ticks: dict[int, dict[str, float]] = defaultdict(
            lambda: {
                "loaded": 0.0,
                "inserted": 0.0,
                "running": 0.0,
                "waiting": 0.0,
                "ended": 0.0,
                "arrived": 0.0,
                "collisions": 0.0,
                "teleports": 0.0,
                "halting": 0.0,
                "stopped": 0.0,
                "discarded": 0.0,
                "meanSpeed_wsum": 0.0,
                "meanSpeedRelative_wsum": 0.0,
                "meanWaitingTime_wsum": 0.0,
                "meanTravelTime_wsum": 0.0,
                "weight": 0.0,
            }
        )

        for file_path in self.htc_files:
            with file_path.open("r", encoding="utf-8") as handle:
                for line in handle:
                    total_lines += 1
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        rec = json.loads(line)
                    except json.JSONDecodeError:
                        bad_lines += 1
                        continue

                    top_level_fields.update(rec.keys())
                    data = rec.get("data") or {}
                    if isinstance(data, str):
                        try:
                            data = json.loads(data)
                        except json.JSONDecodeError:
                            data = {}
                    if isinstance(data, dict):
                        data_fields.update(data.keys())
                    else:
                        data = {}

                    event_type_semantic = data.get("event_type") or rec.get("event_type")
                    event_type_top = rec.get("event_type")
                    if isinstance(event_type_semantic, str):
                        event_counter[event_type_semantic] += 1
                    if isinstance(event_type_top, str) and event_type_top != event_type_semantic:
                        event_counter[event_type_top] += 1

                    tick = safe_float(rec.get("tick"))
                    if tick is None:
                        tick = safe_float(data.get("tick"))

                    vehicle_id = resolve_vehicle_id(data, rec)
                    if isinstance(vehicle_id, str):
                        v = vehicles[vehicle_id]
                        if tick is not None:
                            if v["first_tick"] is None or tick < v["first_tick"]:
                                v["first_tick"] = tick
                            if v["last_tick"] is None or tick > v["last_tick"]:
                                v["last_tick"] = tick

                    if event_type_semantic == "sumo_tripinfo":
                        if isinstance(vehicle_id, str):
                            trip_id = normalize_trip_id(vehicle_id) or vehicle_id
                            depart_tick = safe_float(data.get("depart"))
                            arrival_tick = safe_float(data.get("arrival"))
                            duration_raw = safe_float(data.get("duration"))
                            route_length = safe_float(data.get("routeLength"))
                            if route_length is None:
                                route_length = safe_float(data.get("total_distance"))

                            duration = duration_raw * self.tick_seconds if duration_raw is not None else None
                            avg_speed = None
                            if route_length is not None and duration is not None and duration > 0:
                                avg_speed = route_length / duration

                            sumo_tripinfo_trips[trip_id] = HtcTrip(
                                trip_id=trip_id,
                                depart_tick=depart_tick,
                                arrival_tick=arrival_tick,
                                duration=duration,
                                distance=route_length,
                                avg_speed=avg_speed,
                                completed=bool(data.get("reached_destination", True)),
                                completion_reason=(data.get("completion_reason") if isinstance(data.get("completion_reason"), str) else None),
                            )
                        continue

                    if event_type_semantic == "journey_started":
                        if isinstance(vehicle_id, str):
                            v = vehicles[vehicle_id]
                            if tick is not None:
                                v["journey_started_tick"] = tick
                            trip_id = normalize_trip_id(vehicle_id)
                            if trip_id is not None:
                                journey_started_trip_ids.add(trip_id)
                    elif event_type_semantic == "journey_completed":
                        journey_completed_event_count += 1
                        missing_fields: list[str] = []
                        completion_distance = safe_float(data.get("total_distance"))
                        if not isinstance(vehicle_id, str):
                            missing_fields.append("vehicle_id")
                        if tick is None:
                            missing_fields.append("tick")
                        if completion_distance is None:
                            missing_fields.append("total_distance")

                        if missing_fields:
                            for field in missing_fields:
                                journey_completed_missing_fields[field] += 1
                        else:
                            journey_completed_valid_event_count += 1

                        if isinstance(vehicle_id, str):
                            v = vehicles[vehicle_id]
                            if tick is not None:
                                v["journey_completed_tick"] = tick
                            v["completion_reason"] = data.get("completion_reason")
                            v["distance_from_completion"] = completion_distance
                            trip_id = normalize_trip_id(vehicle_id)
                            if trip_id is not None:
                                journey_completed_trip_ids.add(trip_id)
                                if not missing_fields:
                                    journey_completed_valid_trip_ids.add(trip_id)
                    elif event_type_semantic == "leave_micro_link":
                        if isinstance(vehicle_id, str):
                            v = vehicles[vehicle_id]
                            dist = safe_float(data.get("total_distance"))
                            if dist is not None and (v["distance_max"] is None or dist > v["distance_max"]):
                                v["distance_max"] = dist
                            seg = safe_float(data.get("travel_time_seconds"))
                            if seg is not None:
                                v["segment_time_sum"] += seg
                    elif event_type_semantic in ("link_vehicle_left", "vehicle_left_link"):
                        vr = safe_int(data.get("vehicles_remaining"))
                        if vr is not None and vr < 0:
                            negative_vehicles_remaining += 1
                    elif event_type_semantic == "sumo_summary_step":
                        t = safe_int(data.get("time"))
                        if t is None:
                            t = safe_int(rec.get("tick"))
                        if t is None:
                            continue

                        bucket = summary_ticks[t]
                        for key in [
                            "loaded",
                            "inserted",
                            "running",
                            "waiting",
                            "ended",
                            "arrived",
                            "collisions",
                            "teleports",
                            "halting",
                            "stopped",
                            "discarded",
                        ]:
                            bucket[key] += safe_float(data.get(key)) or 0.0

                        weight = safe_float(data.get("running")) or 0.0
                        if weight <= 0:
                            weight = 1.0
                        bucket["weight"] += weight
                        bucket["meanSpeed_wsum"] += (safe_float(data.get("meanSpeed")) or 0.0) * weight
                        bucket["meanSpeedRelative_wsum"] += (
                            (safe_float(data.get("meanSpeedRelative")) or 0.0) * weight
                        )
                        bucket["meanWaitingTime_wsum"] += (
                            (safe_float(data.get("meanWaitingTime")) or 0.0) * weight
                        )
                        bucket["meanTravelTime_wsum"] += (
                            (safe_float(data.get("meanTravelTime")) or 0.0) * weight
                        )

        summary_agg: dict[str, float | None] = {}
        if summary_ticks:
            ordered_ticks = sorted(summary_ticks)
            peak_running = max(summary_ticks[t]["running"] for t in ordered_ticks)
            peak_waiting = max(summary_ticks[t]["waiting"] for t in ordered_ticks)
            peak_collisions = max(summary_ticks[t]["collisions"] for t in ordered_ticks)
            peak_teleports = max(summary_ticks[t]["teleports"] for t in ordered_ticks)
            last_tick = ordered_ticks[-1]
            last_arrived = summary_ticks[last_tick]["arrived"]
            last_ended = summary_ticks[last_tick]["ended"]

            speed_values: list[float] = []
            speed_rel_values: list[float] = []
            waiting_values: list[float] = []
            travel_values: list[float] = []
            for t in ordered_ticks:
                weight = summary_ticks[t]["weight"]
                if weight <= 0:
                    continue
                speed_values.append(summary_ticks[t]["meanSpeed_wsum"] / weight)
                speed_rel_values.append(summary_ticks[t]["meanSpeedRelative_wsum"] / weight)
                waiting_values.append(summary_ticks[t]["meanWaitingTime_wsum"] / weight)
                travel_values.append(summary_ticks[t]["meanTravelTime_wsum"] / weight)

            summary_agg = {
                "peak_running": peak_running,
                "peak_waiting": peak_waiting,
                "peak_collisions": peak_collisions,
                "peak_teleports": peak_teleports,
                "last_arrived": last_arrived,
                "last_ended": last_ended,
                "sim_end_time": float(last_tick),
                "mean_speed_step_avg": mean_or_none(speed_values),
                "mean_speed_relative_step_avg": mean_or_none(speed_rel_values),
                "mean_waiting_time_step_avg": mean_or_none(waiting_values),
                "mean_travel_time_step_avg": mean_or_none(travel_values),
                "summary_tick_count": float(len(ordered_ticks)),
            }

        trips: dict[str, HtcTrip] = {}

        # Fallback path (journey_* / leave_micro_link)
        for raw_id, v in vehicles.items():
            trip_id = normalize_trip_id(raw_id)
            if not trip_id:
                continue

            depart_tick = v["journey_started_tick"]
            if depart_tick is None:
                depart_tick = v["first_tick"]

            arrival_tick = v["journey_completed_tick"]
            if arrival_tick is None:
                arrival_tick = v["last_tick"]

            duration = None
            if depart_tick is not None and arrival_tick is not None and arrival_tick >= depart_tick:
                duration = (arrival_tick - depart_tick) * self.tick_seconds

            distance = v["distance_from_completion"]
            if distance is None:
                distance = v["distance_max"]

            avg_speed = None
            if distance is not None and duration and duration > 0:
                avg_speed = distance / duration

            trips[trip_id] = HtcTrip(
                trip_id=trip_id,
                depart_tick=depart_tick,
                arrival_tick=arrival_tick,
                duration=duration,
                distance=distance,
                avg_speed=avg_speed,
                completed=v["journey_completed_tick"] is not None,
                completion_reason=(v["completion_reason"] if isinstance(v["completion_reason"], str) else None),
            )

        # Primary source path (sumo_tripinfo) overrides fallback
        trips.update(sumo_tripinfo_trips)

        meta = {
            "event_counter": event_counter,
            "top_level_fields": top_level_fields,
            "data_fields": data_fields,
            "journey_started_trip_count": len(journey_started_trip_ids),
            "journey_completed_trip_count": len(journey_completed_trip_ids),
            "journey_completed_valid_trip_count": len(journey_completed_valid_trip_ids),
            "journey_completed_event_count": journey_completed_event_count,
            "journey_completed_valid_event_count": journey_completed_valid_event_count,
            "journey_completed_invalid_event_count": max(0, journey_completed_event_count - journey_completed_valid_event_count),
            "journey_completed_missing_fields": dict(journey_completed_missing_fields),
            "negative_vehicles_remaining": negative_vehicles_remaining,
            "bad_lines": bad_lines,
            "total_lines": total_lines,
            "files_read": [str(p) for p in self.htc_files],
            "summary_agg": summary_agg,
            "sumo_tripinfo_count": len(sumo_tripinfo_trips),
        }
        return trips, meta


def aggregate_sumo(trips: dict[str, SumoTrip], summary: dict[str, Any]) -> dict[str, Any]:
    durations = [t.duration for t in trips.values() if t.duration is not None]
    distances = [t.route_length for t in trips.values() if t.route_length is not None]
    speeds = [t.route_length / t.duration for t in trips.values() if t.route_length and t.duration and t.duration > 0]
    waiting = [t.waiting_time for t in trips.values() if t.waiting_time is not None]
    time_loss = [t.time_loss for t in trips.values() if t.time_loss is not None]

    return {
        "trip_count": len(trips),
        "duration_mean_s": mean_or_none(durations),
        "duration_median_s": median_or_none(durations),
        "duration_p90_s": percentile(durations, 0.90),
        "route_length_mean_m": mean_or_none(distances),
        "route_length_median_m": median_or_none(distances),
        "speed_mean_m_s": mean_or_none(speeds),
        "speed_p90_m_s": percentile(speeds, 0.90),
        "waiting_time_mean_s": mean_or_none(waiting),
        "time_loss_mean_s": mean_or_none(time_loss),
        "peak_running": summary.get("peak_running"),
        "peak_waiting": summary.get("peak_waiting"),
        "peak_collisions": summary.get("peak_collisions"),
        "peak_teleports": summary.get("peak_teleports"),
        "sim_end_time": summary.get("sim_end_time"),
    }


def aggregate_htc(trips: dict[str, HtcTrip], meta: dict[str, Any]) -> dict[str, Any]:
    durations = [t.duration for t in trips.values() if t.duration is not None]
    distances = [t.distance for t in trips.values() if t.distance is not None]
    speeds = [t.avg_speed for t in trips.values() if t.avg_speed is not None]

    event_counter: Counter = meta["event_counter"]
    summary_agg = meta.get("summary_agg", {})

    return {
        "trip_count": len(trips),
        "completed_trip_count": sum(1 for t in trips.values() if t.completed),
        "duration_mean_s": mean_or_none(durations),
        "duration_median_s": median_or_none(durations),
        "duration_p90_s": percentile(durations, 0.90),
        "route_length_mean_m": mean_or_none(distances),
        "route_length_median_m": median_or_none(distances),
        "speed_mean_m_s": mean_or_none(speeds),
        "speed_p90_m_s": percentile(speeds, 0.90),
        "journey_started_events": event_counter.get("journey_started", 0),
        "journey_completed_events": event_counter.get("journey_completed", 0),
        "journey_started_trip_count": meta.get("journey_started_trip_count", 0),
        "journey_completed_trip_count": meta.get("journey_completed_trip_count", 0),
        "journey_completed_valid_trip_count": meta.get("journey_completed_valid_trip_count", 0),
        "journey_completed_event_count": meta.get("journey_completed_event_count", 0),
        "journey_completed_valid_event_count": meta.get("journey_completed_valid_event_count", 0),
        "journey_completed_invalid_event_count": meta.get("journey_completed_invalid_event_count", 0),
        "sumo_tripinfo_events": event_counter.get("sumo_tripinfo", 0),
        "leave_micro_link_events": event_counter.get("leave_micro_link", 0),
        "link_vehicle_entered_events": event_counter.get("link_vehicle_entered", 0) + event_counter.get("vehicle_entered_link", 0),
        "link_vehicle_left_events": event_counter.get("link_vehicle_left", 0) + event_counter.get("vehicle_left_link", 0),
        "negative_vehicles_remaining": meta.get("negative_vehicles_remaining", 0),
        "bad_json_lines": meta.get("bad_lines", 0),
        "peak_running": summary_agg.get("peak_running"),
        "peak_waiting": summary_agg.get("peak_waiting"),
        "peak_collisions": summary_agg.get("peak_collisions"),
        "peak_teleports": summary_agg.get("peak_teleports"),
        "last_arrived": summary_agg.get("last_arrived"),
        "last_ended": summary_agg.get("last_ended"),
        "sim_end_time": summary_agg.get("sim_end_time"),
        "mean_speed_step_avg": summary_agg.get("mean_speed_step_avg"),
        "mean_speed_relative_step_avg": summary_agg.get("mean_speed_relative_step_avg"),
        "mean_waiting_time_step_avg": summary_agg.get("mean_waiting_time_step_avg"),
        "mean_travel_time_step_avg": summary_agg.get("mean_travel_time_step_avg"),
        "summary_tick_count": summary_agg.get("summary_tick_count"),
    }


def evaluate_journey_completed_coverage(
    htc_agg: dict[str, Any],
    sumo_trip_count: int,
    min_coverage_pct: float,
) -> dict[str, Any]:
    started_trip_count = safe_int(htc_agg.get("journey_started_trip_count")) or 0
    expected_trip_count = started_trip_count if started_trip_count > 0 else max(0, sumo_trip_count)
    if expected_trip_count <= 0:
        expected_trip_count = safe_int(htc_agg.get("trip_count")) or 0

    valid_completed_trip_count = safe_int(htc_agg.get("journey_completed_valid_trip_count")) or 0
    invalid_completed_event_count = safe_int(htc_agg.get("journey_completed_invalid_event_count")) or 0

    coverage_pct = None
    below_threshold = False
    if expected_trip_count > 0:
        coverage_pct = 100.0 * valid_completed_trip_count / expected_trip_count
        below_threshold = coverage_pct < min_coverage_pct

    return {
        "expected_trip_count": expected_trip_count,
        "valid_completed_trip_count": valid_completed_trip_count,
        "invalid_completed_event_count": invalid_completed_event_count,
        "coverage_pct": coverage_pct,
        "below_threshold": below_threshold,
    }


def compare_scalar_metrics(sumo_agg: dict[str, Any], htc_agg: dict[str, Any]) -> list[dict[str, Any]]:
    comparable = [
        "trip_count",
        "duration_mean_s",
        "duration_median_s",
        "duration_p90_s",
        "route_length_mean_m",
        "route_length_median_m",
        "speed_mean_m_s",
        "speed_p90_m_s",
        "peak_running",
        "peak_waiting",
        "peak_collisions",
        "peak_teleports",
        "sim_end_time",
    ]

    rows: list[dict[str, Any]] = []
    for metric in comparable:
        s = sumo_agg.get(metric)
        h = htc_agg.get(metric)
        diff = None
        pct = None
        if isinstance(s, (int, float)) and isinstance(h, (int, float)):
            diff = h - s
            if s != 0:
                pct = 100.0 * diff / s
        rows.append(
            {
                "metric": metric,
                "sumo": s,
                "htc": h,
                "abs_diff": diff,
                "pct_diff": pct,
            }
        )
    return rows


def compare_trips(
    sumo_trips: dict[str, SumoTrip], htc_trips: dict[str, HtcTrip]
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    common_ids = sorted(set(sumo_trips) & set(htc_trips))

    details: list[dict[str, Any]] = []
    sumo_duration: list[float] = []
    htc_duration: list[float] = []
    sumo_distance: list[float] = []
    htc_distance: list[float] = []
    sumo_speed: list[float] = []
    htc_speed: list[float] = []

    for tid in common_ids:
        s = sumo_trips[tid]
        h = htc_trips[tid]

        s_speed = None
        if s.route_length and s.duration and s.duration > 0:
            s_speed = s.route_length / s.duration

        if s.duration is not None and h.duration is not None:
            sumo_duration.append(s.duration)
            htc_duration.append(h.duration)
        if s.route_length is not None and h.distance is not None:
            sumo_distance.append(s.route_length)
            htc_distance.append(h.distance)
        if s_speed is not None and h.avg_speed is not None:
            sumo_speed.append(s_speed)
            htc_speed.append(h.avg_speed)

        details.append(
            {
                "trip_id": tid,
                "sumo_duration_s": s.duration,
                "htc_duration_s": h.duration,
                "sumo_route_length_m": s.route_length,
                "htc_route_length_m": h.distance,
                "sumo_avg_speed_m_s": s_speed,
                "htc_avg_speed_m_s": h.avg_speed,
                "htc_completed": h.completed,
                "htc_completion_reason": h.completion_reason,
            }
        )

    similarity = {
        "matched_trip_count": len(common_ids),
        "sumo_only_trip_count": len(set(sumo_trips) - set(htc_trips)),
        "htc_only_trip_count": len(set(htc_trips) - set(sumo_trips)),
        "duration_mae_s": mae(sumo_duration, htc_duration),
        "duration_mape_pct": mape(sumo_duration, htc_duration),
        "duration_corr": pearson_corr(sumo_duration, htc_duration),
        "distance_mae_m": mae(sumo_distance, htc_distance),
        "distance_mape_pct": mape(sumo_distance, htc_distance),
        "distance_corr": pearson_corr(sumo_distance, htc_distance),
        "speed_mae_m_s": mae(sumo_speed, htc_speed),
        "speed_mape_pct": mape(sumo_speed, htc_speed),
        "speed_corr": pearson_corr(sumo_speed, htc_speed),
    }

    return details, similarity


def compute_metric_coverage(sumo_fields: set[str], htc_meta: dict[str, Any]) -> dict[str, list[str]]:
    htc_fields = set(htc_meta.get("data_fields", set()))

    comparable_now = [
        "trip_count",
        "depart/arrival/duration (via sumo_tripinfo, fallback journey_started/journey_completed)",
        "route_length (via routeLength/total_distance)",
        "avg_speed (distância/duração)",
        "peak_running / peak_waiting (via sumo_summary_step)",
        "peak_collisions / peak_teleports (via sumo_summary_step)",
        "sim_end_time (via sumo_summary_step)",
    ]

    sumo_only_important = [
        f
        for f in [
            "waitingTime",
            "waitingCount",
            "stopTime",
            "timeLoss",
            "departDelay",
            "rerouteNo",
            "departLane",
            "arrivalLane",
        ]
        if f in sumo_fields
    ]

    htc_only = sorted(
        f
        for f in [
            "route_cost",
            "route_length",
            "current_congestion",
            "vehicles_in_link",
            "completion_reason",
            "reached_destination",
        ]
        if f in htc_fields
    )

    recommended_additions = [
        "Garantir `sumo_tripinfo` para 100% das viagens (vehicle_id, depart, arrival, duration, routeLength)",
        "Emitir waiting_time acumulado por veículo",
        "Emitir waiting_count e stop_time por veículo",
        "Emitir time_loss por veículo (se houver modelo equivalente)",
        "Emitir depart_delay por veículo",
        "Emitir reroute_count por veículo",
    ]

    return {
        "comparable_now": comparable_now,
        "sumo_only_important": sumo_only_important,
        "htc_only": htc_only,
        "recommended_additions": recommended_additions,
    }


def maybe_generate_plots(output_dir: Path, trip_rows: list[dict[str, Any]]) -> list[str]:
    generated: list[str] = []
    try:
        import matplotlib.pyplot as plt  # type: ignore
    except Exception:
        return generated

    duration_pairs = [
        (r["sumo_duration_s"], r["htc_duration_s"]) for r in trip_rows if r["sumo_duration_s"] and r["htc_duration_s"]
    ]
    distance_pairs = [
        (r["sumo_route_length_m"], r["htc_route_length_m"]) for r in trip_rows if r["sumo_route_length_m"] and r["htc_route_length_m"]
    ]

    def scatter(pairs: list[tuple[float, float]], title: str, xlab: str, ylab: str, filename: str) -> None:
        if not pairs:
            return
        xs = [p[0] for p in pairs]
        ys = [p[1] for p in pairs]
        plt.figure(figsize=(7, 6))
        plt.scatter(xs, ys, alpha=0.5, s=14)
        minv = min(min(xs), min(ys))
        maxv = max(max(xs), max(ys))
        plt.plot([minv, maxv], [minv, maxv], linestyle="--")
        plt.title(title)
        plt.xlabel(xlab)
        plt.ylabel(ylab)
        plt.tight_layout()
        out = output_dir / filename
        plt.savefig(out, dpi=150)
        plt.close()
        generated.append(out.name)

    scatter(duration_pairs, "SUMO vs HTC - Duração por viagem", "SUMO duração (s)", "HTC duração (s)", "duration_scatter.png")
    scatter(distance_pairs, "SUMO vs HTC - Distância por viagem", "SUMO distância (m)", "HTC distância (m)", "distance_scatter.png")

    return generated


def write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    if not rows:
        path.write_text("", encoding="utf-8")
        return
    fieldnames = list(rows[0].keys())
    with path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def build_markdown_report(
    scenario_dir: Path,
    htc_files: list[Path],
    scalar_rows: list[dict[str, Any]],
    similarity: dict[str, Any],
    coverage: dict[str, list[str]],
    sumo_agg: dict[str, Any],
    htc_agg: dict[str, Any],
    meta: dict[str, Any],
    plot_files: list[str],
) -> str:
    lines: list[str] = []
    lines.append(f"# Relatório comparativo HTC vs SUMO - {scenario_dir.name}")
    lines.append("")
    lines.append("## Entrada")
    lines.append(f"- Cenário: `{scenario_dir}`")
    lines.append(f"- Arquivos HTC lidos: {len(htc_files)}")
    lines.append(f"- Linhas HTC processadas: {meta.get('total_lines', 0)}")
    lines.append(f"- Linhas HTC inválidas: {meta.get('bad_lines', 0)}")
    lines.append("")

    lines.append("## Confiabilidade da comparação")
    journey_started = htc_agg.get("journey_started_trip_count", 0) or 0
    journey_completed = htc_agg.get("journey_completed_valid_trip_count", 0) or 0
    invalid_journey_completed = htc_agg.get("journey_completed_invalid_event_count", 0) or 0
    tripinfo_events = htc_agg.get("sumo_tripinfo_events", 0) or 0
    completion_ratio = None
    if journey_started > 0:
        completion_ratio = 100.0 * journey_completed / journey_started
    lines.append(f"- Cobertura de `journey_completed` válido: **{fmt(completion_ratio)}%** ({journey_completed}/{journey_started})")
    lines.append(f"- Eventos `journey_completed` inválidos (campos mínimos ausentes): **{invalid_journey_completed}**")
    lines.append(f"- Cobertura de `sumo_tripinfo`: **{fmt(100.0 * tripinfo_events / max(1, sumo_agg.get('trip_count') or 1))}%** ({tripinfo_events}/{sumo_agg.get('trip_count')})")
    started_vs_sumo = None
    if (sumo_agg.get("trip_count") or 0) > 0:
        started_vs_sumo = 100.0 * journey_started / (sumo_agg.get("trip_count") or 1)
    lines.append(
        f"- Cobertura de `journey_started` vs trips SUMO: **{fmt(started_vs_sumo)}%** ({journey_started}/{sumo_agg.get('trip_count')})"
    )
    lines.append(f"- Ticks com `sumo_summary_step`: **{fmt(htc_agg.get('summary_tick_count'), 0)}**")
    if completion_ratio is not None and completion_ratio < 80 and tripinfo_events <= 0:
        lines.append("- ⚠️ Baixa cobertura de conclusão no HTC: parte das métricas por viagem foi inferida e pode não refletir a viagem completa.")
    if started_vs_sumo is not None and started_vs_sumo < 95 and tripinfo_events <= 0:
        lines.append("- ⚠️ Nem todas as viagens do SUMO possuem `journey_started` no HTC; parte da comparação pode ficar incompleta.")
    if (htc_agg.get("negative_vehicles_remaining", 0) or 0) > 0:
        lines.append("- ⚠️ Foram detectadas inconsistências em `vehicles_remaining < 0`, indicando possíveis problemas de contabilização de eventos.")
    lines.append("")

    lines.append("## Métricas globais")
    lines.append("| Métrica | SUMO | HTC | Diferença absoluta | Diferença % |")
    lines.append("|---|---:|---:|---:|---:|")
    for r in scalar_rows:
        lines.append(
            f"| {r['metric']} | {fmt(r['sumo'])} | {fmt(r['htc'])} | {fmt(r['abs_diff'])} | {fmt(r['pct_diff'])} |"
        )
    lines.append("")

    lines.append("## Similaridade por viagem (IDs em comum)")
    lines.append(f"- Trips em comum: **{similarity['matched_trip_count']}**")
    lines.append(f"- Trips apenas no SUMO: **{similarity['sumo_only_trip_count']}**")
    lines.append(f"- Trips apenas no HTC: **{similarity['htc_only_trip_count']}**")
    lines.append(f"- Duração MAE (s): **{fmt(similarity['duration_mae_s'])}**")
    lines.append(f"- Duração MAPE (%): **{fmt(similarity['duration_mape_pct'])}**")
    lines.append(f"- Duração correlação de Pearson: **{fmt(similarity['duration_corr'])}**")
    lines.append(f"- Distância MAE (m): **{fmt(similarity['distance_mae_m'])}**")
    lines.append(f"- Distância MAPE (%): **{fmt(similarity['distance_mape_pct'])}**")
    lines.append(f"- Distância correlação de Pearson: **{fmt(similarity['distance_corr'])}**")
    lines.append(f"- Velocidade MAE (m/s): **{fmt(similarity['speed_mae_m_s'])}**")
    lines.append(f"- Velocidade MAPE (%): **{fmt(similarity['speed_mape_pct'])}**")
    lines.append(f"- Velocidade correlação de Pearson: **{fmt(similarity['speed_corr'])}**")
    lines.append("")

    lines.append("## Compatibilidade de métricas")
    lines.append("### Já comparáveis")
    for item in coverage["comparable_now"]:
        lines.append(f"- {item}")
    lines.append("")

    lines.append("### Importantes no SUMO e ausentes no HTC")
    if coverage["sumo_only_important"]:
        for item in coverage["sumo_only_important"]:
            lines.append(f"- {item}")
    else:
        lines.append("- Nenhuma")
    lines.append("")

    lines.append("### Métricas específicas do HTC")
    if coverage["htc_only"]:
        for item in coverage["htc_only"]:
            lines.append(f"- {item}")
    else:
        lines.append("- Nenhuma")
    lines.append("")

    lines.append("### Recomendações para próxima simulação HTC")
    for item in coverage["recommended_additions"]:
        lines.append(f"- {item}")
    lines.append("")

    lines.append("## Sanidade dos eventos HTC")
    lines.append(f"- `journey_started`: {htc_agg.get('journey_started_events', 0)}")
    lines.append(f"- `journey_completed`: {htc_agg.get('journey_completed_events', 0)}")
    lines.append(f"- `journey_completed` válidos: {htc_agg.get('journey_completed_valid_event_count', 0)}")
    lines.append(f"- `journey_completed` inválidos: {htc_agg.get('journey_completed_invalid_event_count', 0)}")
    lines.append(f"- `sumo_tripinfo`: {htc_agg.get('sumo_tripinfo_events', 0)}")
    lines.append(f"- `leave_micro_link`: {htc_agg.get('leave_micro_link_events', 0)}")
    lines.append(
        f"- Ocorrências de `vehicles_remaining < 0`: {htc_agg.get('negative_vehicles_remaining', 0)}"
    )
    lines.append("")

    if plot_files:
        lines.append("## Gráficos")
        for p in plot_files:
            lines.append(f"- `{p}`")
        lines.append("")
    else:
        lines.append("## Gráficos")
        lines.append("- Não gerados (biblioteca `matplotlib` não disponível no ambiente).")
        lines.append("")

    lines.append("## Arquivos gerados")
    lines.append("- `global_metrics_comparison.csv`")
    lines.append("- `trip_level_comparison.csv`")
    lines.append("- `report.md`")
    lines.append("")

    return "\n".join(lines)


def resolve_htc_files(scenario_dir: Path, htc_file: str | None) -> list[Path]:
    htc_dir = scenario_dir / "htc"
    if not htc_dir.exists():
        raise FileNotFoundError(f"Diretório HTC não encontrado: {htc_dir}")

    if htc_file:
        file_path = Path(htc_file)
        if not file_path.is_absolute():
            file_path = htc_dir / htc_file
        if not file_path.exists():
            raise FileNotFoundError(f"Arquivo HTC não encontrado: {file_path}")
        return [file_path]

    files = sorted(htc_dir.glob("*.jsonl"))
    if not files:
        raise FileNotFoundError(f"Nenhum arquivo .jsonl encontrado em: {htc_dir}")
    return files


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Compara saídas de simulação HTC vs SUMO para um cenário em output/<cenario>."
    )
    parser.add_argument(
        "--scenario",
        required=True,
        help="Caminho para o diretório do cenário (ex.: output/scenario_1000_trips)",
    )
    parser.add_argument(
        "--htc-file",
        default=None,
        help="Arquivo HTC específico (.jsonl). Se omitido, agrega todos os .jsonl em htc/ (comportamento padrão).",
    )
    parser.add_argument(
        "--tick-seconds",
        type=float,
        default=1.0,
        help="Conversão de tick HTC para segundos (default: 1.0)",
    )
    parser.add_argument(
        "--output-dir",
        default=None,
        help="Diretório de saída do relatório. Default: <scenario>/comparison_report",
    )
    parser.add_argument(
        "--min-journey-completed-coverage",
        type=float,
        default=80.0,
        help="Cobertura mínima (%) de `journey_completed` válido para considerar comparação confiável (default: 80).",
    )
    parser.add_argument(
        "--fail-on-low-journey-completed-coverage",
        action="store_true",
        help="Falha com erro explícito se a cobertura de `journey_completed` válido ficar abaixo do limiar.",
    )
    parser.add_argument(
        "--fail-on-invalid-journey-completed",
        action="store_true",
        help="Falha com erro explícito se existir `journey_completed` sem campos mínimos (vehicle_id/tick/total_distance).",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()

    scenario_dir = Path(args.scenario).resolve()
    if not scenario_dir.exists():
        raise FileNotFoundError(f"Cenário não encontrado: {scenario_dir}")

    output_dir = (
        Path(args.output_dir).resolve()
        if args.output_dir
        else scenario_dir / "comparison_report"
    )
    output_dir.mkdir(parents=True, exist_ok=True)

    htc_files = resolve_htc_files(scenario_dir, args.htc_file)

    sumo_parser = SumoParser(scenario_dir)
    sumo_trips, sumo_fields = sumo_parser.parse_tripinfo()
    sumo_summary = sumo_parser.parse_summary()

    htc_parser = HtcParser(htc_files=htc_files, tick_seconds=args.tick_seconds)
    htc_trips, htc_meta = htc_parser.parse()

    sumo_agg = aggregate_sumo(sumo_trips, sumo_summary)
    htc_agg = aggregate_htc(htc_trips, htc_meta)

    validation = evaluate_journey_completed_coverage(
        htc_agg=htc_agg,
        sumo_trip_count=safe_int(sumo_agg.get("trip_count")) or 0,
        min_coverage_pct=args.min_journey_completed_coverage,
    )
    coverage_pct = validation.get("coverage_pct")
    expected_trip_count = validation.get("expected_trip_count")
    valid_completed_trip_count = validation.get("valid_completed_trip_count")
    invalid_completed_event_count = validation.get("invalid_completed_event_count")

    if coverage_pct is not None and validation.get("below_threshold"):
        print(
            "⚠️ Cobertura baixa de journey_completed válido: "
            f"{coverage_pct:.2f}% ({valid_completed_trip_count}/{expected_trip_count}), "
            f"mínimo esperado={args.min_journey_completed_coverage:.2f}%"
        )

    if invalid_completed_event_count and invalid_completed_event_count > 0:
        missing_fields = htc_meta.get("journey_completed_missing_fields", {})
        print(
            "⚠️ journey_completed com campos mínimos ausentes: "
            f"{invalid_completed_event_count} eventos. "
            f"Campos faltantes acumulados: {missing_fields}"
        )

    should_fail_low = bool(args.fail_on_low_journey_completed_coverage and validation.get("below_threshold"))
    should_fail_invalid = bool(args.fail_on_invalid_journey_completed and invalid_completed_event_count and invalid_completed_event_count > 0)
    if should_fail_low or should_fail_invalid:
        reasons: list[str] = []
        if should_fail_low:
            reasons.append(
                "cobertura de journey_completed válido abaixo do mínimo "
                f"({coverage_pct:.2f}% < {args.min_journey_completed_coverage:.2f}%)"
            )
        if should_fail_invalid:
            reasons.append(
                "existem eventos journey_completed sem campos mínimos (vehicle_id/tick/total_distance)"
            )
        raise SystemExit("ERRO DE VALIDAÇÃO: " + "; ".join(reasons))

    scalar_rows = compare_scalar_metrics(sumo_agg, htc_agg)
    trip_rows, similarity = compare_trips(sumo_trips, htc_trips)
    coverage = compute_metric_coverage(sumo_fields, htc_meta)

    global_csv = output_dir / "global_metrics_comparison.csv"
    trip_csv = output_dir / "trip_level_comparison.csv"
    report_md = output_dir / "report.md"

    write_csv(global_csv, scalar_rows)
    write_csv(trip_csv, trip_rows)

    plot_files = maybe_generate_plots(output_dir, trip_rows)

    report_text = build_markdown_report(
        scenario_dir=scenario_dir,
        htc_files=htc_files,
        scalar_rows=scalar_rows,
        similarity=similarity,
        coverage=coverage,
        sumo_agg=sumo_agg,
        htc_agg=htc_agg,
        meta=htc_meta,
        plot_files=plot_files,
    )
    report_md.write_text(report_text, encoding="utf-8")

    print("Comparação concluída com sucesso.")
    print(f"- Relatório: {report_md}")
    print(f"- Métricas globais: {global_csv}")
    print(f"- Métricas por viagem: {trip_csv}")
    if plot_files:
        print(f"- Gráficos: {', '.join(plot_files)}")


if __name__ == "__main__":
    main()
