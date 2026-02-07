#!/usr/bin/env python3
"""
Scenario Validator

Validates generated scenario files for correctness and consistency.
"""

import json
import sys
from pathlib import Path
from typing import Dict, List, Set, Tuple

class ScenarioValidator:
    def __init__(self, scenario_path: Path):
        self.scenario_path = scenario_path
        self.data_path = scenario_path / "data"
        self.errors = []
        self.warnings = []
        
    def validate(self) -> bool:
        """Run all validation checks"""
        print(f"\n{'='*80}")
        print(f"🔍 Validating Scenario: {self.scenario_path.name}")
        print(f"{'='*80}\n")
        
        # Check directory structure
        self._check_structure()
        
        # Load all files
        city_map = self._load_json("data/city_map.json")
        nodes = self._load_json("data/nodes.json")
        links = self._load_json("data/links.json")
        vehicles = self._load_json("data/vehicles.json")
        signals = self._load_json("data/traffic_signals.json")
        metadata = self._load_json("scenario_metadata.json")
        
        if not all([city_map, nodes, links, vehicles]):
            return False
        
        # Validate consistency
        self._validate_city_map(city_map, nodes, links)
        self._validate_nodes(nodes, links)
        self._validate_links(links, nodes)
        self._validate_vehicles(vehicles, nodes)
        if signals:
            self._validate_signals(signals, nodes, links)
        
        # Print results
        self._print_results()
        
        return len(self.errors) == 0
    
    def _check_structure(self):
        """Check directory structure"""
        required_files = [
            "data/city_map.json",
            "data/nodes.json",
            "data/links.json",
            "data/vehicles.json",
            "simulation.json",
            "scenario_metadata.json"
        ]
        
        for file_path in required_files:
            full_path = self.scenario_path / file_path
            if not full_path.exists():
                self.errors.append(f"Missing required file: {file_path}")
        
        if self.errors:
            return
        
        print("✅ Directory structure OK")
    
    def _load_json(self, relative_path: str) -> dict:
        """Load and parse JSON file"""
        full_path = self.scenario_path / relative_path
        
        try:
            with open(full_path, 'r') as f:
                data = json.load(f)
            print(f"✅ Loaded: {relative_path}")
            return data
        except FileNotFoundError:
            self.errors.append(f"File not found: {relative_path}")
            return None
        except json.JSONDecodeError as e:
            self.errors.append(f"Invalid JSON in {relative_path}: {e}")
            return None
    
    def _validate_city_map(self, city_map: dict, nodes: list, links: list):
        """Validate city map graph structure"""
        print("\n🗺️  Validating city map...")
        
        # Check vertices match nodes
        map_vertices = set(city_map.get("vertices", {}).keys())
        node_ids = {self._extract_id(node["id"]) for node in nodes}
        
        if map_vertices != node_ids:
            missing_in_map = node_ids - map_vertices
            missing_in_nodes = map_vertices - node_ids
            
            if missing_in_map:
                self.errors.append(f"Nodes missing in city map: {missing_in_map}")
            if missing_in_nodes:
                self.warnings.append(f"Extra vertices in city map: {missing_in_nodes}")
        
        # Check edges match links
        map_edges = {(e["sourceId"], e["targetId"]) for e in city_map.get("edges", [])}
        link_edges = {(self._extract_id(link["data"]["content"]["from"]), 
                       self._extract_id(link["data"]["content"]["to"])) 
                      for link in links}
        
        if map_edges != link_edges:
            self.warnings.append(f"City map edges ({len(map_edges)}) don't match links ({len(link_edges)})")
        
        print(f"  • Vertices: {len(map_vertices)}")
        print(f"  • Edges: {len(map_edges)}")
    
    def _validate_nodes(self, nodes: list, links: list):
        """Validate node actors"""
        print("\n🔵 Validating nodes...")
        
        node_ids = {node["id"] for node in nodes}
        
        for node in nodes:
            node_id = node["id"]
            content = node["data"]["content"]
            
            # Check required fields
            required = ["latitude", "longitude", "links"]
            for field in required:
                if field not in content:
                    self.errors.append(f"Node {node_id} missing field: {field}")
            
            # Check latitude/longitude validity
            lat = content.get("latitude", 0)
            lon = content.get("longitude", 0)
            
            if not (-90 <= lat <= 90):
                self.errors.append(f"Node {node_id} invalid latitude: {lat}")
            if not (-180 <= lon <= 180):
                self.errors.append(f"Node {node_id} invalid longitude: {lon}")
        
        print(f"  • Total nodes: {len(nodes)}")
    
    def _validate_links(self, links: list, nodes: list):
        """Validate link actors"""
        print("\n🔗 Validating links...")
        
        node_ids = {node["id"] for node in nodes}
        micro_count = 0
        meso_count = 0
        
        for link in links:
            link_id = link["id"]
            content = link["data"]["content"]
            
            # Check required fields
            required = ["from", "to", "length", "lanes", "speedLimit", "simulationMode"]
            for field in required:
                if field not in content:
                    self.errors.append(f"Link {link_id} missing field: {field}")
            
            # Check from/to nodes exist
            from_node = content.get("from")
            to_node = content.get("to")
            
            if from_node not in node_ids:
                self.errors.append(f"Link {link_id} from_node not found: {from_node}")
            if to_node not in node_ids:
                self.errors.append(f"Link {link_id} to_node not found: {to_node}")
            
            # Check simulation mode
            sim_mode = content.get("simulationMode")
            if sim_mode not in ["MESO", "MICRO"]:
                self.errors.append(f"Link {link_id} invalid simulationMode: {sim_mode}")
            elif sim_mode == "MICRO":
                micro_count += 1
                # Check lane configurations
                if not content.get("laneConfigurations"):
                    self.warnings.append(f"MICRO link {link_id} has no lane configurations")
            else:
                meso_count += 1
            
            # Check physical parameters
            if content.get("length", 0) <= 0:
                self.errors.append(f"Link {link_id} invalid length: {content.get('length')}")
            if content.get("lanes", 0) <= 0:
                self.errors.append(f"Link {link_id} invalid lanes: {content.get('lanes')}")
            if content.get("speedLimit", 0) <= 0:
                self.warnings.append(f"Link {link_id} invalid speedLimit: {content.get('speedLimit')}")
        
        print(f"  • Total links: {len(links)}")
        print(f"  • MICRO links: {micro_count} ({micro_count/len(links)*100:.1f}%)")
        print(f"  • MESO links: {meso_count} ({meso_count/len(links)*100:.1f}%)")
    
    def _validate_vehicles(self, vehicles: list, nodes: list):
        """Validate vehicle actors"""
        print("\n🚗 Validating vehicles...")
        
        node_ids = {node["id"] for node in nodes}
        vehicle_types = {}
        
        for vehicle in vehicles:
            vehicle_id = vehicle["id"]
            content = vehicle["data"]["content"]
            
            # Check required fields
            required = ["startTick", "origin", "destination", "actorType"]
            for field in required:
                if field not in content:
                    self.errors.append(f"Vehicle {vehicle_id} missing field: {field}")
            
            # Check origin/destination exist
            origin = content.get("origin")
            destination = content.get("destination")
            
            if origin not in node_ids:
                self.errors.append(f"Vehicle {vehicle_id} origin not found: {origin}")
            if destination not in node_ids:
                self.errors.append(f"Vehicle {vehicle_id} destination not found: {destination}")
            if origin == destination:
                self.warnings.append(f"Vehicle {vehicle_id} origin == destination")
            
            # Count by type
            vtype = content.get("actorType", "UNKNOWN")
            vehicle_types[vtype] = vehicle_types.get(vtype, 0) + 1
        
        print(f"  • Total vehicles: {len(vehicles)}")
        for vtype, count in sorted(vehicle_types.items()):
            print(f"    - {vtype}: {count}")
    
    def _validate_signals(self, signals: list, nodes: list, links: list):
        """Validate traffic signal actors"""
        print("\n🚦 Validating traffic signals...")
        
        node_ids = {node["id"] for node in nodes}
        link_ids = {link["id"] for link in links}
        
        for signal in signals:
            signal_id = signal["id"]
            content = signal["data"]["content"]
            
            # Check required fields
            required = ["cycleDuration", "phases", "nodes"]
            for field in required:
                if field not in content:
                    self.errors.append(f"Signal {signal_id} missing field: {field}")
            
            # Check nodes exist
            for node_id in content.get("nodes", []):
                if node_id not in node_ids:
                    self.errors.append(f"Signal {signal_id} node not found: {node_id}")
        
        print(f"  • Total signals: {len(signals)}")
    
    def _extract_id(self, full_id: str) -> str:
        """Extract ID from htcaid format"""
        if ";" in full_id:
            return full_id.split(";")[1]
        return full_id
    
    def _print_results(self):
        """Print validation results"""
        print(f"\n{'='*80}")
        print("📊 Validation Results")
        print(f"{'='*80}\n")
        
        if self.errors:
            print(f"❌ ERRORS: {len(self.errors)}")
            for i, error in enumerate(self.errors, 1):
                print(f"  {i}. {error}")
            print()
        
        if self.warnings:
            print(f"⚠️  WARNINGS: {len(self.warnings)}")
            for i, warning in enumerate(self.warnings, 1):
                print(f"  {i}. {warning}")
            print()
        
        if not self.errors and not self.warnings:
            print("✅ No issues found!")
        elif not self.errors:
            print("✅ Validation passed (with warnings)")
        else:
            print("❌ Validation failed")
        
        print()

def main():
    if len(sys.argv) < 2:
        print("Usage: python validate_scenario.py <scenario_directory>")
        sys.exit(1)
    
    scenario_path = Path(sys.argv[1])
    
    if not scenario_path.exists():
        print(f"Error: Directory not found: {scenario_path}")
        sys.exit(1)
    
    validator = ScenarioValidator(scenario_path)
    success = validator.validate()
    
    sys.exit(0 if success else 1)

if __name__ == "__main__":
    main()
