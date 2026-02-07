#!/usr/bin/env python3
"""
Scenario Statistics Reporter

Generates detailed statistics and visualizations from a generated scenario.
"""

import json
import sys
from pathlib import Path
from collections import defaultdict
from typing import Dict, List

def analyze_scenario(scenario_path: Path):
    """Analyze and report scenario statistics"""
    
    print(f"\n{'='*80}")
    print(f"📊 Scenario Statistics: {scenario_path.name}")
    print(f"{'='*80}\n")
    
    # Load metadata
    metadata_path = scenario_path / "scenario_metadata.json"
    if metadata_path.exists():
        with open(metadata_path, 'r') as f:
            metadata = json.load(f)
        
        print("📋 Scenario Information")
        print(f"  • Name: {metadata.get('name')}")
        print(f"  • Description: {metadata.get('description')}")
        print(f"  • Generated: {metadata.get('generated')}")
        print()
    
    # Load data files
    city_map = load_json(scenario_path / "data" / "city_map.json")
    nodes = load_json(scenario_path / "data" / "nodes.json")
    links = load_json(scenario_path / "data" / "links.json")
    vehicles = load_json(scenario_path / "data" / "vehicles.json")
    signals = load_json(scenario_path / "data" / "traffic_signals.json")
    
    # Network analysis
    print("🗺️  Network Topology")
    print(f"  • Nodes: {len(nodes)}")
    print(f"  • Links: {len(links)}")
    print(f"  • Signalized intersections: {len(signals) if signals else 0}")
    
    # Link analysis
    print("\n🔗 Link Analysis")
    link_stats = analyze_links(links)
    print(f"  • MICRO links: {link_stats['micro_count']} ({link_stats['micro_pct']:.1f}%)")
    print(f"  • MESO links: {link_stats['meso_count']} ({link_stats['meso_pct']:.1f}%)")
    print(f"  • Average length: {link_stats['avg_length']:.1f} m")
    print(f"  • Length range: {link_stats['min_length']:.1f} - {link_stats['max_length']:.1f} m")
    print(f"  • Average lanes: {link_stats['avg_lanes']:.1f}")
    print(f"  • Speed limits: {link_stats['min_speed']:.0f} - {link_stats['max_speed']:.0f} km/h")
    
    # Lane type distribution (for MICRO links)
    if link_stats['lane_types']:
        print("\n  Lane Types (MICRO links):")
        for lane_type, count in sorted(link_stats['lane_types'].items()):
            print(f"    - {lane_type}: {count}")
    
    # Link type distribution
    if link_stats['link_types']:
        print("\n  Road Types:")
        for link_type, count in sorted(link_stats['link_types'].items(), key=lambda x: -x[1]):
            print(f"    - {link_type}: {count}")
    
    # Vehicle analysis
    print("\n🚗 Vehicle Analysis")
    vehicle_stats = analyze_vehicles(vehicles)
    print(f"  • Total vehicles: {len(vehicles)}")
    
    print("\n  By Type:")
    for vtype, count in sorted(vehicle_stats['by_type'].items()):
        pct = count / len(vehicles) * 100
        print(f"    - {vtype}: {count} ({pct:.1f}%)")
    
    print(f"\n  Temporal Distribution:")
    print(f"    - Start tick range: {vehicle_stats['min_start']} - {vehicle_stats['max_start']}")
    print(f"    - Average start tick: {vehicle_stats['avg_start']:.0f}")
    
    # OD matrix summary
    print(f"\n  Origin-Destination:")
    print(f"    - Unique origins: {len(vehicle_stats['origins'])}")
    print(f"    - Unique destinations: {len(vehicle_stats['destinations'])}")
    print(f"    - Unique OD pairs: {len(vehicle_stats['od_pairs'])}")
    
    # Node analysis
    print("\n🔵 Node Analysis")
    node_stats = analyze_nodes(nodes, links)
    print(f"  • Average degree: {node_stats['avg_degree']:.1f}")
    print(f"  • Max degree: {node_stats['max_degree']}")
    
    if node_stats['high_degree_nodes']:
        print(f"\n  High-degree nodes (intersections):")
        for node_id, degree in node_stats['high_degree_nodes'][:5]:
            print(f"    - {node_id}: {degree} connections")
    
    # Geographic extent
    print("\n🌍 Geographic Extent")
    geo_stats = analyze_geography(nodes)
    print(f"  • Latitude range: {geo_stats['min_lat']:.6f} to {geo_stats['max_lat']:.6f}")
    print(f"  • Longitude range: {geo_stats['min_lon']:.6f} to {geo_stats['max_lon']:.6f}")
    print(f"  • Approximate area: {geo_stats['approx_area']:.2f} km²")
    
    # Simulation parameters
    if metadata:
        print("\n⏱️  Simulation Configuration")
        config = metadata.get('configuration', {})
        print(f"  • Start tick: {config.get('startTick', 0)}")
        print(f"  • End tick: {config.get('endTick', 0)}")
        print(f"  • Tick duration: {config.get('tickDuration', 1.0)} seconds")
        duration_min = config.get('endTick', 0) * config.get('tickDuration', 1.0) / 60
        print(f"  • Total duration: {duration_min:.1f} minutes")
        print(f"  • Random seed: {config.get('randomSeed', 'N/A')}")
    
    print(f"\n{'='*80}\n")

def load_json(path: Path) -> any:
    """Load JSON file"""
    try:
        with open(path, 'r') as f:
            return json.load(f)
    except:
        return None

def analyze_links(links: List[dict]) -> dict:
    """Analyze link statistics"""
    micro_count = 0
    meso_count = 0
    lengths = []
    lanes = []
    speeds = []
    lane_types = defaultdict(int)
    link_types = defaultdict(int)
    
    for link in links:
        content = link["data"]["content"]
        
        # Simulation mode
        if content.get("simulationMode") == "MICRO":
            micro_count += 1
            # Count lane types
            for lane_config in content.get("laneConfigurations", []):
                lane_types[lane_config.get("type", "unknown")] += 1
        else:
            meso_count += 1
        
        # Metrics
        lengths.append(content.get("length", 0))
        lanes.append(content.get("lanes", 0))
        speeds.append(content.get("speedLimit", 0))
        link_types[content.get("linkType", "unknown")] += 1
    
    total = len(links)
    
    return {
        'micro_count': micro_count,
        'meso_count': meso_count,
        'micro_pct': micro_count / total * 100 if total > 0 else 0,
        'meso_pct': meso_count / total * 100 if total > 0 else 0,
        'avg_length': sum(lengths) / len(lengths) if lengths else 0,
        'min_length': min(lengths) if lengths else 0,
        'max_length': max(lengths) if lengths else 0,
        'avg_lanes': sum(lanes) / len(lanes) if lanes else 0,
        'min_speed': min(speeds) if speeds else 0,
        'max_speed': max(speeds) if speeds else 0,
        'lane_types': dict(lane_types),
        'link_types': dict(link_types)
    }

def analyze_vehicles(vehicles: List[dict]) -> dict:
    """Analyze vehicle statistics"""
    by_type = defaultdict(int)
    start_ticks = []
    origins = set()
    destinations = set()
    od_pairs = set()
    
    for vehicle in vehicles:
        content = vehicle["data"]["content"]
        
        vtype = content.get("actorType", "UNKNOWN")
        by_type[vtype] += 1
        
        start_ticks.append(content.get("startTick", 0))
        
        origin = content.get("origin", "")
        destination = content.get("destination", "")
        origins.add(origin)
        destinations.add(destination)
        od_pairs.add((origin, destination))
    
    return {
        'by_type': dict(by_type),
        'min_start': min(start_ticks) if start_ticks else 0,
        'max_start': max(start_ticks) if start_ticks else 0,
        'avg_start': sum(start_ticks) / len(start_ticks) if start_ticks else 0,
        'origins': origins,
        'destinations': destinations,
        'od_pairs': od_pairs
    }

def analyze_nodes(nodes: List[dict], links: List[dict]) -> dict:
    """Analyze node statistics"""
    # Calculate degree for each node
    node_degrees = defaultdict(int)
    
    for link in links:
        content = link["data"]["content"]
        from_node = content.get("from", "")
        to_node = content.get("to", "")
        
        node_degrees[from_node] += 1
        node_degrees[to_node] += 1
    
    degrees = list(node_degrees.values())
    avg_degree = sum(degrees) / len(degrees) if degrees else 0
    max_degree = max(degrees) if degrees else 0
    
    # Get high-degree nodes
    high_degree_nodes = sorted(node_degrees.items(), key=lambda x: -x[1])[:5]
    
    return {
        'avg_degree': avg_degree,
        'max_degree': max_degree,
        'high_degree_nodes': high_degree_nodes
    }

def analyze_geography(nodes: List[dict]) -> dict:
    """Analyze geographic extent"""
    lats = []
    lons = []
    
    for node in nodes:
        content = node["data"]["content"]
        lats.append(content.get("latitude", 0))
        lons.append(content.get("longitude", 0))
    
    min_lat = min(lats) if lats else 0
    max_lat = max(lats) if lats else 0
    min_lon = min(lons) if lons else 0
    max_lon = max(lons) if lons else 0
    
    # Approximate area (very rough)
    lat_diff = abs(max_lat - min_lat)
    lon_diff = abs(max_lon - min_lon)
    approx_area = (lat_diff * 111) * (lon_diff * 111)  # km²
    
    return {
        'min_lat': min_lat,
        'max_lat': max_lat,
        'min_lon': min_lon,
        'max_lon': max_lon,
        'approx_area': approx_area
    }

def main():
    if len(sys.argv) < 2:
        print("Usage: python scenario_stats.py <scenario_directory>")
        sys.exit(1)
    
    scenario_path = Path(sys.argv[1])
    
    if not scenario_path.exists():
        print(f"Error: Directory not found: {scenario_path}")
        sys.exit(1)
    
    analyze_scenario(scenario_path)

if __name__ == "__main__":
    main()
