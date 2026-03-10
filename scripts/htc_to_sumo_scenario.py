#!/usr/bin/env python3
"""
HTC Hybrid Scenario -> SUMO Scenario Converter

Converts an existing HTC scenario folder into a SUMO-compatible scenario.

Main outputs:
- nodes.nod.xml
- edges.edg.xml
- trips.trips.xml
- network.net.xml (if netconvert is available)
- routes.rou.xml (if duarouter is available)
- run.sumocfg

Usage:
  python scripts/htc_to_sumo_scenario.py \
      --htc-scenario /home/dean/hyperbolic-time-chamber/simulations/input/htc_scenario \
      --sumo-output scripts/output/sumo_scenario
"""

import argparse
import json
import math
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional
import xml.etree.ElementTree as ET
from xml.dom import minidom


@dataclass
class Node:
    node_id: str
    lat: float
    lon: float
    x: float
    y: float


@dataclass
class Edge:
    edge_id: str
    from_node: str
    to_node: str
    priority: int
    num_lanes: int
    speed_mps: float
    length_m: float


@dataclass
class Trip:
    trip_id: str
    depart: int
    from_edge: str
    to_edge: str
    vtype: str


def pretty_write_xml(root: ET.Element, output_path: Path) -> None:
    xml_bytes = ET.tostring(root, encoding="utf-8")
    pretty = minidom.parseString(xml_bytes).toprettyxml(indent="  ")
    output_path.write_text(pretty, encoding="utf-8")


def normalize_id(raw_id: str) -> str:
    if ";" in raw_id:
        return raw_id.split(";", 1)[1]
    return raw_id


def choose_data_file(data_dir: Path, prefix: str) -> Path:
    candidates = sorted(data_dir.glob(f"{prefix}_*.json"))
    if candidates:
        return candidates[0]
    single = data_dir / f"{prefix}.json"
    if single.exists():
        return single
    raise FileNotFoundError(f"No file found for prefix '{prefix}' in {data_dir}")


def choose_data_files(data_dir: Path, prefix: str) -> List[Path]:
    files = sorted(data_dir.glob(f"{prefix}_*.json"))
    if files:
        return files
    single = data_dir / f"{prefix}.json"
    if single.exists():
        return [single]
    return []


def load_json_array(path: Path) -> List[Dict]:
    content = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(content, list):
        return content
    raise ValueError(f"Expected JSON array in {path}")


def latlon_to_xy(nodes_raw: List[Dict]) -> Dict[str, Node]:
    points = []
    for n in nodes_raw:
        c = n["data"]["content"]
        points.append((normalize_id(n["id"]), float(c["latitude"]), float(c["longitude"])))

    min_lat = min(p[1] for p in points)
    min_lon = min(p[2] for p in points)
    ref_lat = sum(p[1] for p in points) / len(points)

    mapped: Dict[str, Node] = {}
    for node_id, lat, lon in points:
        y = (lat - min_lat) * 111000.0
        x = (lon - min_lon) * 111000.0 * math.cos(math.radians(ref_lat))
        mapped[node_id] = Node(node_id=node_id, lat=lat, lon=lon, x=x, y=y)
    return mapped


def build_edges(links_raw: List[Dict]) -> Dict[str, Edge]:
    edges: Dict[str, Edge] = {}
    for link in links_raw:
        content = link["data"]["content"]
        edge_id = normalize_id(link["id"])
        from_node = normalize_id(content["from"])
        to_node = normalize_id(content["to"])
        lanes = int(content.get("lanes", 1))
        speed_kmh = float(content.get("speedLimit", 50.0))
        length = float(content.get("length", 100.0))
        speed_mps = max(1.0, speed_kmh / 3.6)
        priority = 2
        edges[edge_id] = Edge(
            edge_id=edge_id,
            from_node=from_node,
            to_node=to_node,
            priority=priority,
            num_lanes=max(1, lanes),
            speed_mps=speed_mps,
            length_m=max(1.0, length),
        )
    return edges


def map_vehicle_type_to_sumo(actor_type: str) -> str:
    mapping = {
        "Car": "passenger",
        "Bike": "bicycle",
        "Motorcycle": "motorcycle",
        "Bus": "bus",
        "Subway": "rail",
    }
    return mapping.get(actor_type, "passenger")


def build_node_edge_indexes(edges: Dict[str, Edge]) -> tuple[Dict[str, List[str]], Dict[str, List[str]]]:
    outgoing: Dict[str, List[str]] = {}
    incoming: Dict[str, List[str]] = {}
    for edge_id, edge in edges.items():
        outgoing.setdefault(edge.from_node, []).append(edge_id)
        incoming.setdefault(edge.to_node, []).append(edge_id)
    return outgoing, incoming


def build_trips(vehicles_raw: List[Dict], edges: Dict[str, Edge]) -> List[Trip]:
    outgoing, incoming = build_node_edge_indexes(edges)
    trips: List[Trip] = []

    for v in vehicles_raw:
        content = v["data"]["content"]
        origin_node = normalize_id(content["origin"])
        destination_node = normalize_id(content["destination"])
        if origin_node == destination_node:
            continue

        from_edge_candidates = outgoing.get(origin_node, [])
        to_edge_candidates = incoming.get(destination_node, [])
        if not from_edge_candidates or not to_edge_candidates:
            continue

        from_edge = from_edge_candidates[0]
        to_edge = to_edge_candidates[0]
        if from_edge == to_edge:
            continue

        trips.append(
            Trip(
                trip_id=normalize_id(v["id"]),
                depart=int(content.get("startTick", 0)),
                from_edge=from_edge,
                to_edge=to_edge,
                vtype=map_vehicle_type_to_sumo(content.get("actorType", "Car")),
            )
        )

    return trips


def write_nodes_xml(nodes: Dict[str, Node], path: Path) -> None:
    root = ET.Element("nodes")
    for node in nodes.values():
        ET.SubElement(
            root,
            "node",
            id=node.node_id,
            x=f"{node.x:.3f}",
            y=f"{node.y:.3f}",
            type="priority",
        )
    pretty_write_xml(root, path)


def write_edges_xml(edges: Dict[str, Edge], path: Path) -> None:
    root = ET.Element("edges")
    for edge in edges.values():
        ET.SubElement(
            root,
            "edge",
            id=edge.edge_id,
            **{
                "from": edge.from_node,
                "to": edge.to_node,
                "priority": str(edge.priority),
                "numLanes": str(edge.num_lanes),
                "speed": f"{edge.speed_mps:.3f}",
                "length": f"{edge.length_m:.3f}",
            },
        )
    pretty_write_xml(root, path)


def write_trips_xml(trips: List[Trip], path: Path) -> None:
    root = ET.Element("routes")

    ET.SubElement(root, "vType", id="passenger", accel="2.6", decel="4.5", sigma="0.5", length="4.5", maxSpeed="19.44")
    ET.SubElement(root, "vType", id="bicycle", accel="1.0", decel="3.0", sigma="0.5", length="2.0", maxSpeed="7.0")
    ET.SubElement(root, "vType", id="motorcycle", accel="3.5", decel="5.0", sigma="0.5", length="2.5", maxSpeed="22.22")
    ET.SubElement(root, "vType", id="bus", accel="1.2", decel="3.5", sigma="0.5", length="12.0", maxSpeed="13.89")

    for trip in trips:
        ET.SubElement(
            root,
            "trip",
            id=trip.trip_id,
            depart=str(trip.depart),
            **{"from": trip.from_edge, "to": trip.to_edge},
            type=trip.vtype,
        )
    pretty_write_xml(root, path)


def write_sumocfg(path: Path, end_time: int) -> None:
    root = ET.Element("configuration")
    inp = ET.SubElement(root, "input")
    ET.SubElement(inp, "net-file", value="network.net.xml")
    ET.SubElement(inp, "route-files", value="routes.rou.xml")

    time = ET.SubElement(root, "time")
    ET.SubElement(time, "begin", value="0")
    ET.SubElement(time, "end", value=str(end_time))

    out = ET.SubElement(root, "output")
    ET.SubElement(out, "tripinfo-output", value="tripinfo.xml")
    ET.SubElement(out, "summary-output", value="summary.xml")
    ET.SubElement(out, "fcd-output", value="fcd.xml")

    report = ET.SubElement(root, "report")
    ET.SubElement(report, "verbose", value="true")
    ET.SubElement(report, "duration-log.statistics", value="true")
    pretty_write_xml(root, path)


def run_cmd(cmd: List[str], cwd: Path) -> None:
    subprocess.run(cmd, cwd=str(cwd), check=True)


def resolve_binary(custom: Optional[str], fallback: str) -> Optional[str]:
    if custom:
        return custom
    return shutil.which(fallback)


def main() -> None:
    parser = argparse.ArgumentParser(description="Convert HTC scenario to SUMO scenario")
    parser.add_argument("--htc-scenario", type=Path, required=True, help="HTC scenario folder")
    parser.add_argument("--sumo-output", type=Path, required=True, help="SUMO output folder")
    parser.add_argument("--sumo-netconvert", type=str, default=None, help="Path to netconvert binary")
    parser.add_argument("--sumo-duarouter", type=str, default=None, help="Path to duarouter binary")
    parser.add_argument("--end-time", type=int, default=3600, help="SUMO simulation end time")
    parser.add_argument("--clean", action="store_true", help="Clean output folder before generation")
    args = parser.parse_args()

    data_dir = args.htc_scenario / "data"
    if not data_dir.exists():
        raise FileNotFoundError(f"Missing HTC scenario data folder: {data_dir}")

    if args.clean and args.sumo_output.exists():
        shutil.rmtree(args.sumo_output)
    args.sumo_output.mkdir(parents=True, exist_ok=True)

    node_files = choose_data_files(data_dir, "nodes")
    link_files = choose_data_files(data_dir, "links")
    if not node_files:
        raise FileNotFoundError(f"No node files found in {data_dir}")
    if not link_files:
        raise FileNotFoundError(f"No link files found in {data_dir}")

    vehicle_files = []
    for prefix in ["cars", "buses", "bicycles", "motorcycles"]:
        vehicle_files.extend(sorted(data_dir.glob(f"{prefix}_*.json")))
        single = data_dir / f"{prefix}.json"
        if single.exists():
            vehicle_files.append(single)
    if not vehicle_files:
        vehicle_files = sorted(data_dir.glob("vehicles*.json"))
    if not vehicle_files:
        raise FileNotFoundError(f"No vehicle files found in {data_dir}")

    nodes_raw: List[Dict] = []
    for nf in node_files:
        nodes_raw.extend(load_json_array(nf))

    links_raw: List[Dict] = []
    for lf in link_files:
        links_raw.extend(load_json_array(lf))
    vehicles_raw: List[Dict] = []
    for vf in vehicle_files:
        vehicles_raw.extend(load_json_array(vf))

    nodes = latlon_to_xy(nodes_raw)
    edges = build_edges(links_raw)
    trips = build_trips(vehicles_raw, edges)

    nodes_xml = args.sumo_output / "nodes.nod.xml"
    edges_xml = args.sumo_output / "edges.edg.xml"
    trips_xml = args.sumo_output / "trips.trips.xml"
    net_xml = args.sumo_output / "network.net.xml"
    routes_xml = args.sumo_output / "routes.rou.xml"
    cfg_xml = args.sumo_output / "run.sumocfg"

    write_nodes_xml(nodes, nodes_xml)
    write_edges_xml(edges, edges_xml)
    write_trips_xml(trips, trips_xml)

    netconvert_bin = resolve_binary(args.sumo_netconvert, "netconvert")
    duarouter_bin = resolve_binary(args.sumo_duarouter, "duarouter")

    if not netconvert_bin:
        raise RuntimeError("netconvert not found. Install SUMO or provide --sumo-netconvert")
    if not duarouter_bin:
        raise RuntimeError("duarouter not found. Install SUMO or provide --sumo-duarouter")

    run_cmd(
        [
            netconvert_bin,
            "--node-files",
            str(nodes_xml.name),
            "--edge-files",
            str(edges_xml.name),
            "--output-file",
            str(net_xml.name),
        ],
        args.sumo_output,
    )

    run_cmd(
        [
            duarouter_bin,
            "-n",
            str(net_xml.name),
            "-t",
            str(trips_xml.name),
            "-o",
            str(routes_xml.name),
            "--routing-algorithm",
            "dijkstra",
            "--ignore-errors",
            "true",
        ],
        args.sumo_output,
    )

    write_sumocfg(cfg_xml, end_time=args.end_time)

    print("=" * 80)
    print("✅ SUMO scenario generated")
    print(f"HTC source: {args.htc_scenario}")
    print(f"SUMO output: {args.sumo_output}")
    print(f"Nodes: {len(nodes)} | Edges: {len(edges)} | Trips: {len(trips)}")
    print("Generated files: nodes.nod.xml, edges.edg.xml, network.net.xml, trips.trips.xml, routes.rou.xml, run.sumocfg")
    print("=" * 80)


if __name__ == "__main__":
    main()