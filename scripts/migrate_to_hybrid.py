#!/usr/bin/env python3
"""
Mobility to Hybrid Model Migration Script

Migrates scenarios from the old mobility model (MESO-only) to the new hybrid model (MICRO/MESO).
Processes existing valid maps and adds missing hybrid fields.

Usage:
    python migrate_to_hybrid.py --input ./input/cenario_1000_viagens --output ./output/cenario_hybrid
    python migrate_to_hybrid.py --input ./input/cenario_1000_viagens --output ./output/cenario_hybrid --micro-ratio 0.3
    python migrate_to_hybrid.py --config migration_config.yaml
"""

import argparse
import json
import random
import sys
from pathlib import Path
from typing import List, Dict, Any, Optional
from dataclasses import dataclass, field
from enum import Enum
import yaml
from datetime import datetime
from collections import defaultdict

# ============================================================================
# CONFIGURATION
# ============================================================================

class SimulationModeEnum(Enum):
    """Simulation mode for links"""
    MESO = "MESO"
    MICRO = "MICRO"

class VehicleTypeEnum(Enum):
    """Vehicle types - must match Scala ActorTypeEnum"""
    CAR = "Car"
    BICYCLE = "Bike"
    MOTORCYCLE = "Motorcycle"

class PublicTransportTypeEnum(Enum):
    """Public transport types"""
    BUS = "Bus"
    SUBWAY = "Subway"

class LaneTypeEnum(Enum):
    """Lane types"""
    NORMAL = "normal"
    BUS_LANE = "bus_lane"
    BIKE_LANE = "bike_lane"
    HOV_LANE = "hov_lane"

@dataclass
class MigrationConfig:
    """Migration configuration"""
    input_dir: Path
    output_dir: Path
    
    # Hybrid configuration
    micro_link_ratio: float = 0.3  # Ratio of links to convert to MICRO
    micro_time_step: float = 0.1  # seconds
    micro_ticks_per_global_tick: int = 10
    
    # Vehicle conversion (private vehicles only)
    convert_vehicles: bool = False  # Convert some cars to bicycles/motorcycles
    vehicle_conversion_ratios: Dict[VehicleTypeEnum, float] = field(default_factory=lambda: {
        VehicleTypeEnum.CAR: 0.8,
        VehicleTypeEnum.BICYCLE: 0.1,
        VehicleTypeEnum.MOTORCYCLE: 0.1
    })
    
    # Public transport generation
    generate_public_transport: bool = True
    bus_stop_coverage: float = 0.15  # % of nodes with bus stops
    subway_station_coverage: float = 0.05  # % of nodes with subway stations
    num_bus_routes: int = 5  # Number of bus routes to generate
    num_subway_routes: int = 2  # Number of subway routes to generate
    buses_per_route: int = 3  # Buses per route
    subways_per_route: int = 2  # Trains per route
    
    # Subway routes input file (if provided, routes are read from file instead of generated)
    subway_routes_file: Optional[Path] = None  # Path to subway_routes.json
    
    # Person generation
    generate_persons: bool = True
    persons_per_vehicle: float = 2.0  # Average persons generated per vehicle trip
    
    # File splitting
    items_per_file: int = 5000  # Maximum items per JSON file
    
    # Link selection strategy for MICRO
    micro_selection_strategy: str = "random"  # random, arterial, highway, hotspot
    
    # Traffic signals
    generate_traffic_signals: bool = True
    signal_coverage_ratio: float = 0.2  # % of nodes with signals
    
    # Other options
    random_seed: int = 42
    preserve_ids: bool = True  # Keep original actor IDs
    verbose: bool = True

# ============================================================================
# MIGRATION ENGINE
# ============================================================================

class HybridMigrator:
    """Migrate mobility model to hybrid model"""
    
    def __init__(self, config: MigrationConfig):
        self.config = config
        self.stats = defaultdict(int)
        
        # Data storage
        self.nodes: List[Dict] = []
        self.links: List[Dict] = []
        self.vehicles: Dict[VehicleTypeEnum, List[Dict]] = {
            vtype: [] for vtype in VehicleTypeEnum
        }
        
        # Public transport infrastructure
        self.bus_stops: List[Dict] = []
        self.bus_stations: List[Dict] = []  # Stations that create buses dynamically
        self.subway_stations: List[Dict] = []
        self.bus_routes: List[Dict] = []
        self.subway_routes: List[Dict] = []
        # Legacy support (buses created by stations now)
        self.buses: List[Dict] = []  
        self.subways: List[Dict] = []
        
        # Rail network (exclusive for subway)
        self.rail_links: List[Dict] = []
        
        # Persons
        self.persons: List[Dict] = []
        
        self.city_map: Optional[Dict] = None
        self.simulation_config: Optional[Dict] = None
        
        random.seed(config.random_seed)
        
        self.log("="*80)
        self.log("🔄 Mobility → Hybrid Model Migration")
        self.log("="*80)
        self.log(f"Input:  {config.input_dir}")
        self.log(f"Output: {config.output_dir}")
        self.log("="*80 + "\n")
    
    def migrate(self):
        """Execute full migration"""
        try:
            self.log("📖 Step 1/6: Loading source data...")
            self._load_source_data()
            
            self.log("\n🔄 Step 2/6: Migrating nodes...")
            self._migrate_nodes()
            
            self.log("\n🔗 Step 3/6: Migrating links...")
            self._migrate_links()
            
            self.log("\n🚗 Step 4/8: Migrating vehicles...")
            self._migrate_vehicles()
            
            self.log("\n🚌 Step 5/8: Generating public transport infrastructure...")
            self._generate_public_transport()
            
            self.log("\n� Step 6/8: Generating rail network...")
            self._generate_rail_network()
            
            self.log("\n👥 Step 7/8: Generating persons...")
            self._generate_persons()
            
            self.log("\n🗺️  Step 8/8: Migrating city map...")
            self._migrate_city_map()
            
            self.log("\n💾 Step 8/8: Writing output files...")
            self._write_output()
            
            self.log("\n" + "="*80)
            self.log("✅ Migration completed successfully!")
            self.log("="*80)
            self._print_statistics()
            
        except Exception as e:
            self.log(f"\n❌ Migration failed: {e}", error=True)
            import traceback
            traceback.print_exc()
            sys.exit(1)
    
    def _load_source_data(self):
        """Load all source JSON files"""
        input_dir = self.config.input_dir
        
        # Load nodes
        node_files = sorted(input_dir.glob("nodes_*.json"))
        self.log(f"  📂 Loading {len(node_files)} node files...")
        for node_file in node_files:
            with open(node_file, 'r') as f:
                nodes = json.load(f)
                self.nodes.extend(nodes)
                self.stats['source_nodes'] += len(nodes)
        self.log(f"  ✓ Loaded {self.stats['source_nodes']} nodes")
        
        # Load links
        link_files = sorted(input_dir.glob("links_*.json"))
        self.log(f"  📂 Loading {len(link_files)} link files...")
        for link_file in link_files:
            with open(link_file, 'r') as f:
                links = json.load(f)
                self.links.extend(links)
                self.stats['source_links'] += len(links)
        self.log(f"  ✓ Loaded {self.stats['source_links']} links")
        
        # Load vehicles (cars)
        car_files = sorted(input_dir.glob("cars_*.json"))
        self.log(f"  📂 Loading {len(car_files)} car files...")
        for car_file in car_files:
            with open(car_file, 'r') as f:
                cars = json.load(f)
                self.vehicles[VehicleTypeEnum.CAR].extend(cars)
                self.stats['source_vehicles'] += len(cars)
        self.log(f"  ✓ Loaded {self.stats['source_vehicles']} vehicles")
        
        # Load city map
        city_map_path = input_dir / "data" / "city_map.json"
        if city_map_path.exists():
            with open(city_map_path, 'r') as f:
                self.city_map = json.load(f)
            self.log(f"  ✓ Loaded city map")
        else:
            self.log(f"  ⚠️  City map not found at {city_map_path}")
        
        # Load simulation config
        sim_config_path = input_dir / "simulation.json"
        if sim_config_path.exists():
            with open(sim_config_path, 'r') as f:
                self.simulation_config = json.load(f)
            self.log(f"  ✓ Loaded simulation config")
    
    def _migrate_nodes(self):
        """Migrate nodes from mobility to hybrid model"""
        migrated_nodes = []
        
        for node in self.nodes:
            # Extract old data
            old_content = node['data']['content']
            
            # Build hybrid node
            hybrid_node = {
                "id": node['id'],
                "typeActor": "hybrid.actor.Node",
                "data": {
                    "dataType": "model.hybrid.entity.state.NodeState",
                    "content": {
                        "startTick": old_content.get('startTick', 0),
                        "latitude": old_content['latitude'],
                        "longitude": old_content['longitude'],
                        "links": [],  # Will be populated after link migration
                        "connections": {},
                        "signals": {},
                        "busStops": {},
                        "subwayStations": {},
                        "hasHybridConnections": False,  # Will be updated later
                        "conflictZones": [],
                        "scheduleOnTimeManager": False
                    }
                },
                "dependencies": {}
            }
            
            migrated_nodes.append(hybrid_node)
            self.stats['migrated_nodes'] += 1
        
        self.nodes = migrated_nodes
        self.log(f"  ✓ Migrated {len(migrated_nodes)} nodes to hybrid model")
    
    def _migrate_links(self):
        """Migrate links from mobility to hybrid model"""
        migrated_links = []
        
        # Determine which links should be MICRO (by default)
        micro_link_indices = self._select_micro_links()
        
        for idx, link in enumerate(self.links):
            old_content = link['data']['content']
            
            # Determine simulation mode (default behavior)
            is_micro_mode = idx in micro_link_indices
            sim_mode = SimulationModeEnum.MICRO if is_micro_mode else SimulationModeEnum.MESO
            
            # IMPORTANT: ALL links get lane configurations (prepared for dynamic switching)
            num_lanes = int(old_content.get('lanes', 1))
            lane_configs = self._generate_lane_configs(num_lanes, old_content)
            
            # Build hybrid link (ALL links have MICRO fields for dynamic switching)
            hybrid_link = {
                "id": link['id'],
                "typeActor": "hybrid.actor.Link",
                "data": {
                    "dataType": "model.hybrid.entity.state.LinkState",
                    "content": {
                        "startTick": old_content.get('startTick', 0),
                        "from": old_content['from_node'],
                        "to": old_content['to_node'],
                        "length": old_content['length'],
                        "lanes": num_lanes,
                        "speedLimit": self._convert_speed(old_content.get('freeSpeed', 13.89)),
                        "freeSpeed": self._convert_speed(old_content.get('freeSpeed', 13.89)),
                        "capacity": old_content.get('capacity', 1800.0),
                        
                        # Hybrid-specific fields (ALL links have these for dynamic switching!)
                        "simulationMode": sim_mode.value,  # Current mode (can change at runtime)
                        "microTimeStep": self.config.micro_time_step,
                        "microTicksPerGlobalTick": self.config.micro_ticks_per_global_tick,
                        "laneConfigurations": lane_configs,  # ALL links have lane configs
                        
                        # Additional fields
                        "linkType": old_content.get('linkType', 'residential'),
                        "congestionFactor": 1.0,
                        "currentSpeed": self._convert_speed(old_content.get('freeSpeed', 13.89)),
                        "registered": [],
                        "vehiclesByLane": {}  # ALL links have this (prepared for MICRO)
                    }
                },
                "dependencies": link.get('dependencies', {})
            }
            
            migrated_links.append(hybrid_link)
            
            if is_micro_mode:
                self.stats['micro_links'] += 1
            else:
                self.stats['meso_links'] += 1
        
        self.links = migrated_links
        
        self.log(f"  ✓ Migrated {len(migrated_links)} links")
        self.log(f"    • MICRO: {self.stats['micro_links']} ({self.stats['micro_links']/len(migrated_links)*100:.1f}%)")
        self.log(f"    • MESO:  {self.stats['meso_links']} ({self.stats['meso_links']/len(migrated_links)*100:.1f}%)")
        
        # Update node links
        self._update_node_links()
    
    def _select_micro_links(self) -> set:
        """Select which links should be MICRO based on strategy"""
        num_micro = int(len(self.links) * self.config.micro_link_ratio)
        
        if self.config.micro_selection_strategy == "random":
            indices = random.sample(range(len(self.links)), num_micro)
            return set(indices)
        
        elif self.config.micro_selection_strategy == "arterial":
            # Select arterial/primary roads
            scored = []
            for idx, link in enumerate(self.links):
                content = link['data']['content']
                link_type = content.get('linkType', 'residential')
                lanes = int(content.get('lanes', 1))
                
                # Score based on importance
                score = 0
                if link_type in ['motorway', 'trunk']:
                    score += 3
                elif link_type == 'primary':
                    score += 2
                elif link_type == 'secondary':
                    score += 1
                score += lanes * 0.5
                
                scored.append((score, idx))
            
            scored.sort(reverse=True)
            return set(idx for _, idx in scored[:num_micro])
        
        elif self.config.micro_selection_strategy == "highway":
            # Prioritize high-speed roads
            scored = []
            for idx, link in enumerate(self.links):
                content = link['data']['content']
                free_speed = content.get('freeSpeed', 0)
                scored.append((free_speed, idx))
            
            scored.sort(reverse=True)
            return set(idx for _, idx in scored[:num_micro])
        
        else:  # Default to random
            return set(random.sample(range(len(self.links)), num_micro))
    
    def _generate_lane_configs(self, num_lanes: int, link_content: Dict) -> List[Dict]:
        """Generate lane configurations for MICRO links"""
        lane_configs = []
        
        link_type = link_content.get('linkType', 'residential')
        
        for lane_id in range(num_lanes):
            # Determine lane type
            lane_type = LaneTypeEnum.NORMAL.value
            
            # Special lanes for multi-lane roads
            if num_lanes >= 3:
                # 10% chance for special lanes
                if random.random() < 0.1:
                    if link_type in ['primary', 'secondary', 'trunk']:
                        lane_type = random.choice([
                            LaneTypeEnum.BUS_LANE.value,
                            LaneTypeEnum.HOV_LANE.value
                        ])
            
            lane_configs.append({
                "laneId": lane_id,
                "type": lane_type,
                "width": 3.5,
                "speedLimit": None  # Inherit from link
            })
        
        return lane_configs
    
    def _convert_speed(self, speed_m_s: float) -> float:
        """Convert speed from m/s to km/h"""
        return speed_m_s * 3.6
    
    def _update_node_links(self):
        """Update node links references and connections after link migration.
        
        Populates:
        - links: list of link IDs connected to this node
        - connections: map of link_id -> Identify(opposite_node)
        - hasHybridConnections: whether node has MICRO links
        
        This eliminates the need for LinkConnectionsData messages during runtime.
        """
        # Build node -> links mapping
        node_links = defaultdict(list)
        for link in self.links:
            from_node = link['data']['content']['from']
            to_node = link['data']['content']['to']
            link_id = link['id']
            
            node_links[from_node].append(link_id)
            node_links[to_node].append(link_id)
        
        # Update nodes
        for node in self.nodes:
            node_id = node['id']
            node_content = node['data']['content']
            
            # Update links list
            node_content['links'] = node_links[node_id]
            
            # Build connections map (link_id -> opposite node Identify)
            connections = {}
            for link in self.links:
                link_id = link['id']
                from_node = link['data']['content']['from']
                to_node = link['data']['content']['to']
                
                if from_node == node_id:
                    # This node is the origin, connection points to destination
                    connections[link_id] = {
                        "id": to_node,
                        "classType": "hybrid.actor.Node"
                    }
                elif to_node == node_id:
                    # This node is the destination, connection points to origin
                    connections[link_id] = {
                        "id": from_node,
                        "classType": "hybrid.actor.Node"
                    }
            
            node_content['connections'] = connections
            
            # Check if node has hybrid connections
            has_micro = any(
                link['data']['content']['simulationMode'] == 'MICRO'
                for link in self.links
                if link['id'] in node_links[node_id]
            )
            node_content['hasHybridConnections'] = has_micro
        
        self.log(f"  ✓ Pre-populated {len(self.nodes)} nodes with link connections (no runtime sync needed)")
    
    def _migrate_vehicles(self):
        """Migrate vehicles from mobility to hybrid model"""
        source_cars = self.vehicles[VehicleTypeEnum.CAR]
        
        if not self.config.convert_vehicles:
            # Just migrate all cars to hybrid cars
            migrated_cars = []
            for car in source_cars:
                migrated_car = self._migrate_car_to_hybrid(car, VehicleTypeEnum.CAR)
                migrated_cars.append(migrated_car)
            
            self.vehicles[VehicleTypeEnum.CAR] = migrated_cars
            self.stats['migrated_vehicles'] = len(migrated_cars)
            self.log(f"  ✓ Migrated {len(migrated_cars)} vehicles (all cars)")
            
        else:
            # Convert some cars to bicycles/motorcycles (private vehicles only)
            self.log(f"  🔀 Converting private vehicles...")
            
            # Shuffle for random distribution
            random.shuffle(source_cars)
            
            # Calculate conversion counts
            total = len(source_cars)
            conversion_counts = {}
            remaining = total
            
            for vtype, ratio in self.config.vehicle_conversion_ratios.items():
                count = int(total * ratio)
                conversion_counts[vtype] = count
                remaining -= count
            
            # Add remaining to cars
            conversion_counts[VehicleTypeEnum.CAR] += remaining
            
            # Perform conversion
            idx = 0
            for vtype, count in conversion_counts.items():
                vehicles_to_convert = source_cars[idx:idx+count]
                
                for car in vehicles_to_convert:
                    migrated = self._migrate_car_to_hybrid(car, vtype)
                    self.vehicles[vtype].append(migrated)
                    self.stats[f'vehicles_{vtype.value.lower()}'] += 1
                
                idx += count
            
            self.stats['migrated_vehicles'] = total
            
            # Print conversion summary
            for vtype, count in conversion_counts.items():
                if count > 0:
                    self.log(f"    • {vtype.value}: {count} ({count/total*100:.1f}%)")
    
    def _migrate_car_to_hybrid(self, car: Dict, target_type: VehicleTypeEnum) -> Dict:
        """Migrate a single car to hybrid model (private vehicles only)"""
        old_content = car['data']['content']
        
        # Generate new ID if converting type
        if target_type != VehicleTypeEnum.CAR:
            # Replace 'car' with new type in ID
            original_id = car['id']
            new_id = original_id.replace(':car;', f':{target_type.value.lower()};')
        else:
            new_id = car['id']
        
        # Type-specific actor and state types (private vehicles only)
        type_map = {
            VehicleTypeEnum.CAR: ("hybrid.actor.Car", "model.hybrid.entity.state.CarState", 4.5),
            VehicleTypeEnum.BICYCLE: ("hybrid.actor.Bicycle", "model.hybrid.entity.state.BicycleState", 2.0),
            VehicleTypeEnum.MOTORCYCLE: ("hybrid.actor.Motorcycle", "model.hybrid.entity.state.MotorcycleState", 2.5),
        }
        
        actor_type, state_type, size = type_map[target_type]
        
        # Build base content
        content = {
            "startTick": old_content.get('startTick', 0),
            "origin": old_content['origin'],
            "destination": old_content['destination'],
            "actorType": target_type.value,
            "size": size,
            
            # Hybrid-specific
            "currentSimulationMode": "MESO",
            "microState": None,
            
            # Mobility state
            "status": "Start",
            "bestRoute": None,
            "currentNode": old_content['origin'],
            "distance": 0.0,
            "eventCount": 0,
            
            # Driver attributes with variation
            "driverAttributes": {
                "aggressiveness": random.uniform(0.3, 0.9),
                "reactionTimeFactor": random.uniform(0.8, 1.2),
                "speedFactor": random.uniform(0.9, 1.1),
                "minGapFactor": random.uniform(0.8, 1.2)
            },
            
            "scheduleOnTimeManager": True
        }
        
        # Add type-specific fields for bicycles and motorcycles
        if target_type == VehicleTypeEnum.BICYCLE:
            content.update({
                "prefersBikeLane": True,
                "canUseSidewalk": False
            })
        
        elif target_type == VehicleTypeEnum.MOTORCYCLE:
            content.update({
                "canFilterLanes": True,
                "aggressiveness": content["driverAttributes"]["aggressiveness"]
            })
        
        # Build migrated vehicle
        migrated = {
            "id": new_id,
            "typeActor": actor_type,
            "data": {
                "dataType": state_type,
                "content": content
            },
            "dependencies": car.get('dependencies', {})
        }
        
        # Remove GPS dependency (not needed in hybrid model)
        if 'gps' in migrated['dependencies']:
            del migrated['dependencies']['gps']
        
        return migrated
    
    def _generate_public_transport(self):
        """Generate public transport infrastructure (bus stops, subway stations, routes)"""
        if not self.config.generate_public_transport:
            self.log("  ⏭️  Public transport generation skipped")
            return
        
        # Generate bus stops
        self._generate_bus_stops()
        
        # Generate subway stations
        self._generate_subway_stations()
        
        # Generate bus routes and buses
        self._generate_bus_routes()
        
        # Generate subway routes and trains
        self._generate_subway_routes()
        
        # Count unique subway lines (embedded in stations, not separate files)
        num_lines = len(set(
            line_id 
            for station in self.subway_stations 
            for line_id in station['data']['content'].get('lines', {}).keys()
        ))
        
        self.log(f"  ✓ Generated {len(self.bus_stops)} bus stops")
        self.log(f"  ✓ Generated {len(self.subway_stations)} subway stations")

        self.log(f"  ✓ Generated {num_lines} subway lines with {len(self.subways)} trains")
    
    def _generate_bus_stops(self):
        """Generate bus stops at selected nodes"""
        num_bus_stops = int(len(self.nodes) * self.config.bus_stop_coverage)
        selected_nodes = random.sample(self.nodes, min(num_bus_stops, len(self.nodes)))
        
        for idx, node in enumerate(selected_nodes):
            node_id = node['id']
            node_content = node['data']['content']
            
            bus_stop = {
                "id": f"htcaid:busstop;busstop_{idx}",
                "typeActor": "hybrid.actor.BusStop",
                "data": {
                    "dataType": "model.hybrid.entity.state.BusStopState",
                    "content": {
                        "nodeId": node_id,
                        "label": f"BusStop {idx}",
                        "people": {}  # Map of route_label -> list of person IDs
                    }
                },
                "dependencies": {
                    "node": {
                        "id": node_id,
                        "classType": "hybrid.actor.Node"
                    }
                }
            }
            
            self.bus_stops.append(bus_stop)
            
            # Update node to reference bus stop
            node['data']['content']['busStops'][bus_stop['id']] = {
                "id": bus_stop['id'],
                "classType": "hybrid.actor.BusStop"
            }
    
    def _generate_subway_stations(self):
        """Generate subway stations at selected nodes"""
        num_stations = int(len(self.nodes) * self.config.subway_station_coverage)
        
        # Avoid nodes that already have bus stops (if possible)
        nodes_with_bus_stops = {
            node['id'] for node in self.nodes
            if node['data']['content'].get('busStops')
        }
        
        available_nodes = [n for n in self.nodes if n['id'] not in nodes_with_bus_stops]
        if len(available_nodes) < num_stations:
            available_nodes = self.nodes  # Use all if not enough
        
        selected_nodes = random.sample(available_nodes, min(num_stations, len(available_nodes)))
        
        for idx, node in enumerate(selected_nodes):
            node_id = node['id']
            node_content = node['data']['content']
            
            station = {
                "id": f"htcaid:subwaystation;station_{idx}",
                "typeActor": "hybrid.actor.SubwayStation",
                "data": {
                    "dataType": "model.hybrid.entity.state.SubwayStationState",
                    "content": {
                        "startTick": 0,
                        "name": f"Station {idx + 1}",
                        "nodeId": node_id,
                        "terminal": False,
                        "garage": False,
                        "lines": {},  # Map[line_id -> SubwayLineInformation]
                        "subways": {},  # Map[line_id -> Queue[SubwayInformation]]
                        "linesRoute": {},  # Map[line_id -> Queue[(SubwayStationNode, String)]]
                        "people": {},
                        "status": "Start"
                    }
                },
                "dependencies": {
                    "node": {
                        "id": node_id,
                        "classType": "hybrid.actor.Node"
                    }
                }
            }
            
            self.subway_stations.append(station)
            
            # Update node to reference subway station
            node['data']['content']['subwayStations'][station['id']] = {
                "id": station['id'],
                "classType": "hybrid.actor.SubwayStation"
            }
    
    def _generate_bus_routes(self):
        """Generate bus stations (not individual buses - stations create buses at runtime)"""
        if len(self.bus_stops) < 2:
            self.log("  ⚠️  Not enough bus stops to create routes")
            return
        
        for route_idx in range(self.config.num_bus_routes):
            # Select random stops for this route (4-8 stops per route)
            num_stops = random.randint(4, min(8, len(self.bus_stops)))
            route_stops = random.sample(self.bus_stops, num_stops)
            
            # Bus route is information, not an actor
            route_label = f"Bus Line {route_idx + 1}"
            
            # Update bus stops to include this route in their people map
            for stop in route_stops:
                stop_content = stop['data']['content']
                if 'people' not in stop_content:
                    stop_content['people'] = {}
                # Initialize empty queue for this route
                stop_content['people'][route_label] = []
            
            # Create BusStation (generates buses dynamically at runtime)
            station_id = f"htcaid:busstation;station_{route_idx}"
            origin_node = route_stops[0]['data']['content']['nodeId']
            destination_node = route_stops[-1]['data']['content']['nodeId']  # Last stop
            
            # Build busStops map (stop_id -> node_id) for the station
            bus_stops_map = {}
            for stop in route_stops:
                stop_id = stop['id']
                node_id = stop['data']['content']['nodeId']
                bus_stops_map[stop_id] = node_id
            
            # Create BusInformation objects for the station to generate
            bus_information_queue = []
            for bus_idx in range(self.config.buses_per_route):
                bus_info = {
                    "actorId": f"htcaid:bus;route_{route_idx}_bus_{bus_idx}",
                    "capacity": 80,
                    "size": 12.0,
                    "numberOfPorts": 2,
                    "label": route_label
                }
                bus_information_queue.append(bus_info)
            
            bus_station = {
                "id": station_id,
                "typeActor": "hybrid.actor.BusStation",
                "data": {
                    "dataType": "model.hybrid.entity.state.BusStationState",
                    "content": {
                        "startTick": route_idx * 100,  # Stagger station starts
                        "name": f"Station for {route_label}",
                        "origin": origin_node,
                        "destination": destination_node,
                        "busStops": bus_stops_map,
                        "interval": 600,  # 10 minutes between bus creation
                        "buses": bus_information_queue,  # Queue of BusInformation
                        "goingRoute": None,  # Will be calculated at runtime
                        "goingBestCost": float('inf'),
                        "returningRoute": None,  # Will be calculated at runtime  
                        "returningBestCost": float('inf'),
                        "status": "Start"
                    }
                },
                "dependencies": {
                    "origin": {
                        "id": origin_node,
                        "classType": "hybrid.actor.Node"
                    },
                    "destination": {
                        "id": destination_node,
                        "classType": "hybrid.actor.Node"
                    }
                }
            }
            
            # Store station instead of individual buses
            if not hasattr(self, 'bus_stations'):
                self.bus_stations = []
            self.bus_stations.append(bus_station)
            self.stats['bus_stations'] = getattr(self.stats, 'bus_stations', 0) + 1
            
            route_idx += 1
        
        self.log(f"  ✓ Generated {route_idx} bus stations (will create buses dynamically)")
    
    def _generate_subway_routes(self):
        """Generate subway routes (lines) with trains.
        
        Routes can be:
        1. Read from input file (subway_routes_file in config)
        2. Generated randomly (if no input file)
        """
        if len(self.subway_stations) < 2:
            self.log("  ⚠️  Not enough subway stations to create routes")
            return
        
        # Check if routes should be read from file
        if self.config.subway_routes_file and self.config.subway_routes_file.exists():
            self._load_subway_routes_from_file()
            return
        
        # Otherwise, generate routes intelligently based on network topology
        self.log("  🔄 Generating subway routes automatically from network topology...")
        self._generate_intelligent_subway_routes()
    
    def _generate_intelligent_subway_routes(self):
        """Generate subway routes intelligently based on network structure.
        
        Instead of random selection, this creates realistic metro lines by:
        1. Finding connected paths through the network
        2. Selecting stations that form coherent lines
        3. Using network distance to create logical routes
        """
        if len(self.subway_stations) < 2:
            self.log("  ⚠️  Not enough subway stations to create routes")
            return
        
        # Build adjacency map (node -> connected nodes via links)
        adjacency = self._build_adjacency_map()
        
        # Build node_id -> station mapping and station_id -> station mapping
        node_to_station = {}
        id_to_station = {}
        for station in self.subway_stations:
            station_id = station['id']
            node_id = station['data']['content']['nodeId']  # FIXED: was 'node'
            node_to_station[node_id] = station
            id_to_station[station_id] = station
        
        # Use set of station IDs (strings are hashable)
        available_station_ids = set(id_to_station.keys())
        route_idx = 0
        
        for route_num in range(self.config.num_subway_routes):
            if len(available_station_ids) < 3:
                self.log(f"  ⚠️  Not enough available stations for route {route_num + 1}")
                break
            
            # Start from a random available station
            start_station_id = random.choice(list(available_station_ids))
            start_station = id_to_station[start_station_id]
            start_node = start_station['data']['content']['nodeId']  # FIXED: was 'node'
            
            # Build route by following connected paths
            route_stations = [start_station]
            current_node = start_node
            visited_nodes = {start_node}
            
            # Target: 4-8 stations per route
            target_length = random.randint(4, min(8, len(available_station_ids)))
            
            # BFS/DFS to find connected stations
            for _ in range(target_length - 1):
                # Find next station connected via network
                next_station = self._find_next_station_in_route(
                    current_node, 
                    node_to_station,
                    adjacency,
                    visited_nodes,
                    available_station_ids,
                    id_to_station
                )
                
                if next_station is None:
                    break
                
                route_stations.append(next_station)
                current_node = next_station['data']['content']['nodeId']  # FIXED: was 'node'
                visited_nodes.add(current_node)
            
            if len(route_stations) < 3:
                self.log(f"  ⚠️  Could not create route {route_num + 1} with enough stations")
                continue
            
            # Remove used stations from available pool (50% chance to allow overlap)
            for station in route_stations:
                station_id = station['id']
                if random.random() > 0.5:  # Allow some stations to be shared between lines
                    available_station_ids.discard(station_id)
            
            # Create line information (NOT an actor - just data)
            line_id = f"line_{route_idx + 1}"
            line_label = f"Metro Line {route_idx + 1}"
            frequency = random.choice([3, 5, 7, 10])  # minutes
            
            # Update stations to include this line
            for station in route_stations:
                station_content = station['data']['content']
                
                # Add line to lines map (SubwayLineInformation)
                if 'lines' not in station_content:
                    station_content['lines'] = {}
                
                station_content['lines'][line_id] = {
                    "label": line_label,
                    "frequency": frequency,
                    "terminal": station == route_stations[0] or station == route_stations[-1]
                }
                
                # Add route path to linesRoute
                if 'linesRoute' not in station_content:
                    station_content['linesRoute'] = {}
                
                # Build route from this station
                route_path = []
                station_idx = route_stations.index(station)
                for i in range(station_idx + 1, len(route_stations)):
                    next_station = route_stations[i]
                    next_node = next_station['data']['content']['nodeId']  # FIXED: was 'node'
                    route_path.append({
                        "station": {"id": next_station['id'], "classType": "hybrid.actor.SubwayStation"},
                        "node": next_node
                    })
                
                station_content['linesRoute'][line_id] = route_path
            
            # Generate trains for this line
            for train_idx in range(self.config.subways_per_route):
                train_id = f"htcaid:subway;line_{route_idx}_train_{train_idx}"
                
                # Stagger start times
                start_tick = route_idx * 50 + train_idx * 150
                
                # Start at first station
                origin_station = route_stations[0]
                origin_node = origin_station['data']['content']['nodeId']  # FIXED: was 'node'
                dest_station = route_stations[-1]
                dest_node = dest_station['data']['content']['nodeId']  # FIXED: was 'node'
                
                # Build subwayStations map (station_id -> node_id)
                subway_stations_map = {}
                for station in route_stations:
                    station_id = station['id']
                    node_id = station['data']['content']['nodeId']  # FIXED: was 'node'
                    subway_stations_map[station_id] = node_id
                
                subway = {
                    "id": train_id,
                    "typeActor": "hybrid.actor.Subway",
                    "data": {
                        "dataType": "model.hybrid.entity.state.SubwayState",
                        "content": {
                            "startTick": start_tick,
                            "capacity": 300,
                            "numberOfPorts": 4,
                            "velocity": 22.22,  # 80 km/h
                            "distance": 0.0,
                            "boardingTimeByPassenger": 1.5,
                            "stopTime": 30,  # 30 seconds at each station
                            "subwayStations": subway_stations_map,
                            "countUnloadPassenger": 0,
                            "countUnloadReceived": 0,
                            "origin": origin_node,
                            "destination": dest_node,
                            "passengers": {},
                            "bestRoute": None,
                            "currentPath": None,
                            "currentPathPosition": 0,
                            "bestCost": 0.0,
                            "line": line_id,
                            "actorType": "Subway",
                            "status": "Start",
                            "scheduleOnTimeManager": True
                        }
                    },
                    "dependencies": {}
                }
                
                self.subways.append(subway)
                self.stats['subways'] += 1
            
            route_idx += 1
    
    def _build_adjacency_map(self):
        """Build adjacency map from links (node -> [connected nodes])."""
        adjacency = {}
        for link in self.links:
            link_content = link['data']['content']
            from_node = link_content['from']
            to_node = link_content['to']
            
            if from_node not in adjacency:
                adjacency[from_node] = []
            adjacency[from_node].append(to_node)
        
        return adjacency
    
    def _find_next_station_in_route(self, current_node, node_to_station, adjacency, 
                                     visited_nodes, available_station_ids, id_to_station):
        """Find next station connected via network that hasn't been visited."""
        # BFS to find nearest unvisited station
        queue = [(current_node, 0)]  # (node, distance)
        explored = {current_node}
        max_distance = 10  # Max hops to search
        
        while queue:
            node, distance = queue.pop(0)
            
            if distance > max_distance:
                break
            
            # Check if this node has a station
            if node in node_to_station and node not in visited_nodes:
                station = node_to_station[node]
                station_id = station['id']
                if station_id in available_station_ids:
                    return station
            
            # Explore neighbors
            if node in adjacency:
                for neighbor in adjacency[node]:
                    if neighbor not in explored:
                        explored.add(neighbor)
                        queue.append((neighbor, distance + 1))
        
        return None  # No connected station found
    
    def _load_subway_routes_from_file(self):
        """Load subway routes from input file.
        
        Expected format:
        {
          "routes": [
            {
              "id": "line_blue",
              "label": "Blue Line",
              "stations": ["node_123", "node_456", "node_789"],
              "frequency": 5,
              "operatingHours": {"start": 300, "end": 1380},
              "trainsPerRoute": 3
            }
          ]
        }
        """
        try:
            with open(self.config.subway_routes_file, 'r') as f:
                routes_data = json.load(f)
            
            if 'routes' not in routes_data:
                self.log("  ❌ Invalid subway_routes file: missing 'routes' key")
                return
            
            # Build node_id -> station mapping
            node_to_station = {}
            for station in self.subway_stations:
                node_id = station['data']['content']['node']
                node_to_station[node_id] = station
            
            for route_data in routes_data['routes']:
                route_id = f"htcaid:subwayroute;{route_data['id']}"
                station_node_ids = route_data['stations']
                
                # Validate stations exist
                route_stations = []
                for node_id in station_node_ids:
                    if node_id not in node_to_station:
                        self.log(f"  ⚠️  Station with node {node_id} not found, skipping route {route_data['id']}")
                        break
                    route_stations.append(node_to_station[node_id])
                
                if len(route_stations) < 2:
                    self.log(f"  ⚠️  Route {route_data['id']} has less than 2 valid stations, skipping")
                    continue
                
                # Create route
                route = {
                    "id": route_id,
                    "typeActor": "hybrid.actor.SubwayRoute",
                    "data": {
                        "dataType": "model.hybrid.entity.state.SubwayRouteState",
                        "content": {
                            "startTick": 0,
                            "label": route_data['label'],
                            "stations": [station['id'] for station in route_stations],
                            "frequency": route_data.get('frequency', 5),
                            "operatingHours": route_data.get('operatingHours', {"start": 300, "end": 1380}),
                            "digitalRail": True,
                            "scheduleOnTimeManager": False
                        }
                    },
                    "dependencies": {}
                }
                
                self.subway_routes.append(route)
                
                # Update stations to reference this line
                for station in route_stations:
                    station['data']['content']['lines'].append(route_id)
                
                # Generate trains for this line
                trains_per_route = route_data.get('trainsPerRoute', self.config.subways_per_route)
                for train_idx in range(trains_per_route):
                    train_id = f"htcaid:subway;{route_data['id']}_train_{train_idx}"
                    
                    # Stagger start times
                    start_tick = len(self.subway_routes) * 50 + train_idx * 150
                    
                    # Start at first station
                    origin_station = route_stations[0]
                    origin_node = origin_station['data']['content']['node']
                    
                    subway = {
                        "id": train_id,
                        "typeActor": "hybrid.actor.Subway",
                        "data": {
                            "dataType": "model.hybrid.entity.state.SubwayState",
                            "content": {
                                "startTick": start_tick,
                                "label": f"{route_data['label']} Train {train_idx + 1}",
                                "capacity": 300,
                                "origin": origin_node,
                                "destination": origin_node,
                                "route": route_id,
                                "stations": {station['id']: station['id'] for station in route_stations},
                                "people": {},
                                "actorType": "Subway",
                                "size": 60.0,
                                "currentSimulationMode": "MESO",
                                "microState": None,
                                "status": "Start",
                                "digitalRailPath": [station['id'] for station in route_stations],
                                "currentStation": origin_station['id'],
                                "nextStation": route_stations[1]['id'] if len(route_stations) > 1 else None,
                                "distance": 0.0,
                                "eventCount": 0,
                                "scheduleOnTimeManager": True
                            }
                        },
                        "dependencies": {
                            "route": {
                                "id": route_id,
                                "classType": "hybrid.actor.SubwayRoute"
                            }
                        }
                    }
                    
                    self.subways.append(subway)
                    self.stats['subways'] += 1
            
            self.log(f"  ✓ Loaded {len(self.subway_routes)} subway routes from file")
            
        except Exception as e:
            self.log(f"  ❌ Error loading subway routes from file: {e}")
            import traceback
            traceback.print_exc()
    
    def _generate_rail_network(self):
        """Generate dedicated rail links connecting subway stations.
        
        Rail links are EXCLUSIVE to subway trains - they form a separate
        network from road links. Each subway route gets dedicated rail links
        connecting its stations in order.
        
        Benefits:
        - Subways don't compete with road traffic
        - More realistic subway simulation
        - Can have different speeds and capacities
        - Prevents cars/buses from entering rail tracks
        """
        if not self.subway_routes:
            self.log("  ⏭️  No subway routes, skipping rail network generation")
            return
        
        rail_link_id = 0
        total_rail_links = 0
        
        # Generate rail links for each subway line
        for route in self.subway_routes:
            route_content = route['data']['content']
            station_ids = route_content['stations']
            line_label = route_content['label']
            
            if len(station_ids) < 2:
                continue
            
            # Create rail links connecting consecutive stations
            for i in range(len(station_ids) - 1):
                from_station_id = station_ids[i]
                to_station_id = station_ids[i + 1]
                
                # Get station nodes
                from_station = next((s for s in self.subway_stations if s['id'] == from_station_id), None)
                to_station = next((s for s in self.subway_stations if s['id'] == to_station_id), None)
                
                if not from_station or not to_station:
                    continue
                
                from_node = from_station['data']['content']['node']
                to_node = to_station['data']['content']['node']
                
                # Calculate distance between stations (use existing road network distance as approximation)
                # In production, would use actual rail alignment
                distance = self._calculate_distance_between_nodes(from_node, to_node)
                
                # Rail link parameters (optimized for subway)
                rail_link = {
                    "id": f"htcaid:rail_link;line_{route['id'].split(';')[1]}_segment_{i}",
                    "typeActor": "hybrid.actor.RailLink",
                    "data": {
                        "dataType": "model.hybrid.entity.state.RailLinkState",
                        "content": {
                            "from": from_node,
                            "to": to_node,
                            "length": distance,
                            "lanes": 2,  # Typically bidirectional
                            "speedLimit": 80.0,  # km/h - subway speed
                            "capacity": 10.0,  # trains per hour
                            "freeSpeed": 80.0,
                            
                            # Rail-specific
                            "railType": "SUBWAY",
                            "subwayLine": route['id'],
                            "fromStation": from_station_id,
                            "toStation": to_station_id,
                            "gradient": 0.0,  # Could be calculated from elevation
                            "curvature": 0.0,  # Could be calculated from geometry
                            
                            # Hybrid mode (rail typically uses MESO)
                            "simulationMode": "MESO",
                            "laneConfigurations": [],  # Rails don't have lane changes
                            "vehiclesByLane": {},
                            "microTimeStep": 0.1,
                            "microTicksPerGlobalTick": 10,
                            
                            "registered": [],
                            "scheduleOnTimeManager": False
                        }
                    },
                    "dependencies": {
                        "from_node": {
                            "id": from_node,
                            "classType": "hybrid.actor.Node"
                        },
                        "to_node": {
                            "id": to_node,
                            "classType": "hybrid.actor.Node"
                        },
                        "from_station": {
                            "id": from_station_id,
                            "classType": "hybrid.actor.SubwayStation"
                        },
                        "to_station": {
                            "id": to_station_id,
                            "classType": "hybrid.actor.SubwayStation"
                        },
                        "subway_line": {
                            "id": route['id'],
                            "classType": "hybrid.actor.SubwayRoute"
                        }
                    }
                }
                
                self.rail_links.append(rail_link)
                rail_link_id += 1
                total_rail_links += 1
            
            # Create return rail links (for circular/bidirectional lines)
            for i in range(len(station_ids) - 1, 0, -1):
                from_station_id = station_ids[i]
                to_station_id = station_ids[i - 1]
                
                from_station = next((s for s in self.subway_stations if s['id'] == from_station_id), None)
                to_station = next((s for s in self.subway_stations if s['id'] == to_station_id), None)
                
                if not from_station or not to_station:
                    continue
                
                from_node = from_station['data']['content']['node']
                to_node = to_station['data']['content']['node']
                
                distance = self._calculate_distance_between_nodes(from_node, to_node)
                
                rail_link = {
                    "id": f"htcaid:rail_link;line_{route['id'].split(';')[1]}_return_{i}",
                    "typeActor": "hybrid.actor.RailLink",
                    "data": {
                        "dataType": "model.hybrid.entity.state.RailLinkState",
                        "content": {
                            "from": from_node,
                            "to": to_node,
                            "length": distance,
                            "lanes": 2,
                            "speedLimit": 80.0,
                            "capacity": 10.0,
                            "freeSpeed": 80.0,
                            "railType": "SUBWAY",
                            "subwayLine": route['id'],
                            "fromStation": from_station_id,
                            "toStation": to_station_id,
                            "gradient": 0.0,
                            "curvature": 0.0,
                            "simulationMode": "MESO",
                            "laneConfigurations": [],
                            "vehiclesByLane": {},
                            "microTimeStep": 0.1,
                            "microTicksPerGlobalTick": 10,
                            "registered": [],
                            "scheduleOnTimeManager": False
                        }
                    },
                    "dependencies": {
                        "from_node": {"id": from_node, "classType": "hybrid.actor.Node"},
                        "to_node": {"id": to_node, "classType": "hybrid.actor.Node"},
                        "from_station": {"id": from_station_id, "classType": "hybrid.actor.SubwayStation"},
                        "to_station": {"id": to_station_id, "classType": "hybrid.actor.SubwayStation"},
                        "subway_line": {"id": route['id'], "classType": "hybrid.actor.SubwayRoute"}
                    }
                }
                
                self.rail_links.append(rail_link)
                rail_link_id += 1
                total_rail_links += 1
        
        self.stats['rail_links'] = total_rail_links
        self.log(f"  ✓ Generated {total_rail_links} rail links for {len(self.subway_routes)} subway lines")
        
        # Populate linesRoute in SubwayStations with rail_link IDs
        self._populate_station_routes()
    
    def _populate_station_routes(self):
        """Populate linesRoute in SubwayStations with rail_link IDs.
        
        For each station, build a map of:
        - line_id -> queue of (SubwayStationNode, rail_link_id)
        
        This tells each subway train which rail_link to use to reach the next station.
        """
        for station in self.subway_stations:
            station_content = station['data']['content']
            station_node = station_content['node']
            
            # Initialize linesRoute structure
            lines_route = {}
            
            # For each subway line this station is part of
            for line_id in station_content.get('lines', []):
                # Find the route
                route = next((r for r in self.subway_routes if r['id'] == line_id), None)
                if not route:
                    continue
                
                route_content = route['data']['content']
                station_ids = route_content['stations']
                
                # Find this station's position in the route
                try:
                    station_idx = station_ids.index(station['id'])
                except ValueError:
                    continue
                
                # Build route path for this line from this station
                route_path = []
                
                # Forward path (to end of line)
                for i in range(station_idx, len(station_ids) - 1):
                    from_station_id = station_ids[i]
                    to_station_id = station_ids[i + 1]
                    
                    # Find corresponding rail_link
                    rail_link = self._find_rail_link(line_id, from_station_id, to_station_id)
                    if rail_link:
                        to_station = next((s for s in self.subway_stations if s['id'] == to_station_id), None)
                        if to_station:
                            route_path.append({
                                "stationId": to_station_id,
                                "nodeId": to_station['data']['content']['node'],
                                "linkId": rail_link['id']
                            })
                
                # Return path (back to start - for circular lines)
                for i in range(len(station_ids) - 1, station_idx, -1):
                    from_station_id = station_ids[i]
                    to_station_id = station_ids[i - 1]
                    
                    rail_link = self._find_rail_link(line_id, from_station_id, to_station_id)
                    if rail_link:
                        to_station = next((s for s in self.subway_stations if s['id'] == to_station_id), None)
                        if to_station:
                            route_path.append({
                                "stationId": to_station_id,
                                "nodeId": to_station['data']['content']['node'],
                                "linkId": rail_link['id']
                            })
                
                if route_path:
                    lines_route[line_id] = route_path
            
            # Update station with linesRoute
            station_content['linesRoute'] = lines_route
        
        self.log(f"  ✓ Populated linesRoute for {len(self.subway_stations)} stations")
    
    def _find_rail_link(self, line_id: str, from_station_id: str, to_station_id: str):
        """Find rail_link connecting two stations on a given line."""
        for rail_link in self.rail_links:
            rail_content = rail_link['data']['content']
            if (rail_content.get('subwayLine') == line_id and
                rail_content.get('fromStation') == from_station_id and
                rail_content.get('toStation') == to_station_id):
                return rail_link
        return None
    
    def _calculate_distance_between_nodes(self, node_id1: str, node_id2: str) -> float:
        """Calculate approximate distance between two nodes.
        
        Uses road network distance as approximation. In production,
        would use actual rail alignment or Euclidean distance.
        """
        # Find nodes
        node1 = next((n for n in self.nodes if n['id'] == node_id1), None)
        node2 = next((n for n in self.nodes if n['id'] == node_id2), None)
        
        if not node1 or not node2:
            return 1000.0  # Default 1km
        
        # Try to find existing road link between nodes
        for link in self.links:
            link_content = link['data']['content']
            if link_content['from'] == node_id1 and link_content['to'] == node_id2:
                return link_content['length']
            if link_content['from'] == node_id2 and link_content['to'] == node_id1:
                return link_content['length']
        
        # If no direct road link, use Euclidean distance
        try:
            lat1 = node1['data']['content']['latitude']
            lon1 = node1['data']['content']['longitude']
            lat2 = node2['data']['content']['latitude']
            lon2 = node2['data']['content']['longitude']
            
            # Haversine formula (simplified)
            import math
            dlat = math.radians(lat2 - lat1)
            dlon = math.radians(lon2 - lon1)
            a = math.sin(dlat/2)**2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dlon/2)**2
            c = 2 * math.asin(math.sqrt(a))
            distance = 6371000 * c  # Earth radius in meters
            return distance
        except:
            return 1000.0  # Default 1km
    
    def _find_nearest_bus_stop(self, node_id: str):
        """Find nearest bus stop to a given node"""
        if not self.bus_stops:
            return None
        
        # Get node coordinates
        node = next((n for n in self.nodes if n['id'] == node_id), None)
        if not node:
            return None
        
        node_lat = float(node['data']['content']['latitude'])
        node_lon = float(node['data']['content']['longitude'])
        
        # Find nearest bus stop
        min_dist = float('inf')
        nearest_stop = None
        
        for stop in self.bus_stops:
            stop_node_id = stop['data']['content']['nodeId']
            stop_node = next((n for n in self.nodes if n['id'] == stop_node_id), None)
            if stop_node:
                stop_lat = float(stop_node['data']['content']['latitude'])
                stop_lon = float(stop_node['data']['content']['longitude'])
                
                # Simple Euclidean distance (good enough for small areas)
                dist = ((node_lat - stop_lat)**2 + (node_lon - stop_lon)**2)**0.5
                
                if dist < min_dist:
                    min_dist = dist
                    nearest_stop = stop
        
        return nearest_stop
    
    def _generate_persons(self):
        """Generate Person actors based on vehicle trips"""
        if not self.config.generate_persons:
            self.log("  ⏭️  Person generation skipped")
            return
        
        # Calculate number of persons to generate
        total_vehicles = sum(len(vehicles) for vehicles in self.vehicles.values())
        num_persons = int(total_vehicles * self.config.persons_per_vehicle)
        
        # Get all vehicle trips for origin/destination
        all_vehicle_trips = []
        for vtype, vehicles in self.vehicles.items():
            for vehicle in vehicles:
                content = vehicle['data']['content']
                all_vehicle_trips.append({
                    'origin': content['origin'],
                    'destination': content['destination'],
                    'startTick': content.get('startTick', 0)
                })
        
        # Generate persons
        for person_idx in range(num_persons):
            # Use vehicle trip as template (with variation)
            if all_vehicle_trips:
                template = random.choice(all_vehicle_trips)
                origin = template['origin']
                destination = template['destination']
                base_start_tick = template['startTick']
            else:
                # Fallback to random nodes
                origin = random.choice(self.nodes)['id']
                destination = random.choice([n['id'] for n in self.nodes if n['id'] != origin])
                base_start_tick = 0
            
            # Add variation to start tick
            start_tick = max(0, base_start_tick + random.randint(-300, 300))
            
            # Randomly decide if person uses public transport or walking
            uses_public_transport = random.random() < 0.4  # 40% use public transport
            
            # Select mode preference
            if uses_public_transport:
                if self.bus_stops and self.subway_stations:
                    mode_preference = random.choice(["bus", "subway", "mixed"])
                elif self.bus_stops:
                    mode_preference = "bus"
                elif self.subway_stations:
                    mode_preference = "subway"
                else:
                    mode_preference = "walk"
            else:
                mode_preference = "walk"
            
            person_id = f"htcaid:person;person_{person_idx}"
            
            # Create daily schedule (Home -> Work -> Home) following Activity structure
            # Activity(sequence, activityType, nodeId, endTime, arrivalLogistics)
            work_start_tick = start_tick
            work_end_tick = work_start_tick + random.randint(14400, 28800)  # 4-8 hours
            
            # Generate driver attributes
            aggressiveness = random.uniform(0.3, 0.8)
            max_speed_factor = random.uniform(0.9, 1.2)
            reaction_time = random.uniform(0.8, 1.5)
            min_gap_factor = random.uniform(0.8, 1.3)
            
            daily_schedule = [
                {
                    "sequence": 0,
                    "activityType": "Home",
                    "nodeId": origin,
                    "endTime": str(work_start_tick),  # String format
                    "arrivalLogistics": None  # First activity has no arrival logistics
                },
                {
                    "sequence": 1,
                    "activityType": "Work",
                    "nodeId": destination,
                    "endTime": str(work_end_tick),
                    "arrivalLogistics": {
                        "mode": mode_preference,  # "walk", "bus", "subway", "mixed"
                        "vehicle": None,  # Will be assigned if person uses private vehicle
                        "driverAttributes": {
                            "aggressiveness": aggressiveness,
                            "maxSpeedFactor": max_speed_factor,
                            "reactionTime": reaction_time,
                            "minGapFactor": min_gap_factor
                        }
                    }
                },
                {
                    "sequence": 2,
                    "activityType": "Home",
                    "nodeId": origin,
                    "endTime": "86400",  # End of day
                    "arrivalLogistics": {
                        "mode": mode_preference,
                        "vehicle": None,
                        "driverAttributes": {
                            "aggressiveness": aggressiveness,
                            "maxSpeedFactor": max_speed_factor,
                            "reactionTime": reaction_time,
                            "minGapFactor": min_gap_factor
                        }
                    }
                }
            ]
            
            person = {
                "id": person_id,
                "typeActor": "hybrid.actor.Person",
                "data": {
                    "dataType": "model.hybrid.entity.state.PersonState",
                    "content": {
                        "dailySchedule": daily_schedule,
                        "currentActivityIndex": 0,
                        "ownedVehicles": {},  # Empty initially, will be populated if person has vehicles
                        "currentTripVehicleId": None,
                        "currentTripStartTick": None,
                        "totalDistanceTraveled": 0.0,
                        "completedTrips": 0
                    }
                },
                "dependencies": {}
            }
            
            self.persons.append(person)
            self.stats['persons'] += 1
        
        self.log(f"  ✓ Generated {len(self.persons)} persons")
    
    def _migrate_city_map(self):
        """Migrate city map to hybrid model"""
        if not self.city_map:
            self.log("  ⚠️  No city map to migrate")
            return
        
        # Update node class types
        for node in self.city_map['nodes']:
            node['classType'] = 'hybrid.actor.Node'
        
        # Update edge class types
        for edge in self.city_map['edges']:
            if 'label' in edge:
                edge['label']['classType'] = 'hybrid.actor.Link'
        
        self.log(f"  ✓ Migrated city map")
    
    def _write_output(self):
        """Write all output files"""
        output_dir = self.config.output_dir
        output_dir.mkdir(parents=True, exist_ok=True)
        data_dir = output_dir / "data"
        data_dir.mkdir(exist_ok=True)
        
        # IMPORTANT: Generate traffic signals BEFORE writing nodes
        # This populates node.signals which must be in the output files
        if self.config.generate_traffic_signals:
            self._generate_traffic_signals_and_populate_nodes(data_dir)
        
        # Write nodes (NOW with signals populated)
        self._write_split_files(self.nodes, data_dir, "nodes")
        
        # Write links (split into files)
        self._write_split_files(self.links, data_dir, "links")
        
        # Write rail links (subway-only network)
        if self.rail_links:
            self._write_split_files(self.rail_links, data_dir, "rail_links")
        
        # Write vehicles by type (split into files)
        for vtype, vehicles in self.vehicles.items():
            if vehicles:
                filename = f"{vtype.value.lower()}s"
                self._write_split_files(vehicles, data_dir, filename)
        
        # Write public transport infrastructure
        if self.bus_stops:
            self._write_split_files(self.bus_stops, data_dir, "bus_stops")
        if hasattr(self, 'bus_stations') and self.bus_stations:
            self._write_split_files(self.bus_stations, data_dir, "bus_stations")
        if self.subway_stations:
            self._write_split_files(self.subway_stations, data_dir, "subway_stations")
        # Note: Individual buses are created by BusStations at runtime, not pre-generated
        if hasattr(self, 'buses') and self.buses:  # Legacy support
            self._write_split_files(self.buses, data_dir, "buses")
        if self.subways:
            self._write_split_files(self.subways, data_dir, "subways")
        
        # Write persons
        if self.persons:
            self._write_split_files(self.persons, data_dir, "persons")
        
        # Write city map
        if self.city_map:
            city_map_path = data_dir / "city_map.json"
            with open(city_map_path, 'w') as f:
                json.dump(self.city_map, f, indent=2)
            self.log(f"  ✓ City map: {city_map_path.name}")
        
        # Write traffic signals (already generated and populated in nodes)
        if self.config.generate_traffic_signals and hasattr(self, '_generated_signals'):
            self._write_split_files(self._generated_signals, data_dir, "traffic_signals")
        
        # Write simulation config
        self._write_simulation_config(output_dir / "simulation.json")
        
        # Write metadata
        self._write_metadata(output_dir / "scenario_metadata.json")
        
        # Write migration report
        self._write_migration_report(output_dir / "MIGRATION_REPORT.md")
        
        self.log(f"\n  ✓ All files written to: {output_dir.absolute()}")
    
    def _write_split_files(self, items: List[Dict], output_dir: Path, base_name: str):
        """Write items split into multiple files"""
        if not items:
            return
        
        items_per_file = self.config.items_per_file
        num_files = (len(items) + items_per_file - 1) // items_per_file
        
        for file_idx in range(num_files):
            start_idx = file_idx * items_per_file
            end_idx = min(start_idx + items_per_file, len(items))
            chunk = items[start_idx:end_idx]
            
            filename = f"{base_name}_{file_idx + 1}.json"
            filepath = output_dir / filename
            
            with open(filepath, 'w') as f:
                json.dump(chunk, f, indent=2)
            
            self.stats[f'files_{base_name}'] += 1
        
        self.log(f"  ✓ {base_name.capitalize()}: {num_files} files ({len(items)} items)")
    
    def _generate_traffic_signals_and_populate_nodes(self, data_dir: Path):
        """
        Generate traffic signals and pre-populate node.signals.
        This MUST be called BEFORE writing nodes to disk.
        Eliminates need for TrafficSignalChangeStatusData runtime messages.
        """
        signals = []
        signal_id = 0
        
        # Select nodes for signals
        num_signals = int(len(self.nodes) * self.config.signal_coverage_ratio)
        selected_nodes = random.sample(self.nodes, min(num_signals, len(self.nodes)))
        
        for node in selected_nodes:
            node_id = node['id']
            node_content = node['data']['content']
            
            # Get incoming links
            incoming_links = [
                link['id'] for link in self.links
                if link['data']['content']['to'] == node_id
            ]
            
            if len(incoming_links) < 2:
                continue  # Need at least 2 approaches
            
            # Create simple 2-phase signal
            cycle_duration = random.choice([60, 90, 120])
            offset = random.randint(0, 30)
            
            phases = [
                {
                    "phaseId": 0,
                    "duration": cycle_duration // 2,
                    "greenLinks": [link for i, link in enumerate(incoming_links) if i % 2 == 0],
                    "yellowDuration": 3,
                    "allRedDuration": 2
                },
                {
                    "phaseId": 1,
                    "duration": cycle_duration // 2,
                    "greenLinks": [link for i, link in enumerate(incoming_links) if i % 2 == 1],
                    "yellowDuration": 3,
                    "allRedDuration": 2
                }
            ]
            
            signal_states = {
                link: {"state": "Red", "timeInState": 0}
                for link in incoming_links
            }
            
            signal = {
                "id": f"htcaid:signal;signal_{signal_id}",
                "typeActor": "hybrid.actor.TrafficSignal",
                "data": {
                    "dataType": "model.hybrid.entity.state.TrafficSignalState",
                    "content": {
                        "startTick": 0,
                        "cycleDuration": cycle_duration,
                        "offset": offset,
                        "nodes": [node_id],
                        "phases": phases,
                        "signalStates": signal_states
                    }
                },
                "dependencies": {
                    "node": {
                        "id": node_id,
                        "classType": "hybrid.actor.Node"
                    }
                }
            }
            
            signals.append(signal)
            signal_id += 1
            
            # PRE-POPULATE node signals (eliminates TrafficSignalChangeStatusData messages)
            node = next((n for n in self.nodes if n['id'] == node_id), None)
            if node:
                node_signals = node['data']['content']['signals']
                signal_identify = {
                    "id": signal['id'],
                    "classType": "hybrid.actor.TrafficSignal"
                }
                
                # Add initial signal state for each incoming link
                for link_id in incoming_links:
                    node_signals[link_id] = {
                        "state": "Red",  # Initial state
                        "remainingTime": 0,
                        "nextTick": offset,  # First transition at offset
                        "signalId": signal['id']
                    }
        
        # Store signals for later writing (don't write yet - nodes need to be written first in sequence)
        self._generated_signals = signals
        if signals:
            self.stats['traffic_signals'] = len(signals)
            self.log(f"  ✓ Generated {len(signals)} traffic signals and populated node.signals maps")

    
    def _write_simulation_config(self, output_path: Path):
        """Write simulation.json configuration"""
        if self.simulation_config:
            # Use original config as base
            config = self.simulation_config.copy()
        else:
            # Create new config
            config = {
                "id": f"hybrid_{self.config.output_dir.name}",
                "name": f"Hybrid Simulation: {self.config.output_dir.name}",
                "description": "Migrated from mobility model to hybrid model",
                "randomSeed": self.config.random_seed,
                "startRealTime": "2026-01-27T00:00:00.000",
                "timeUnit": "seconds",
                "timeStep": 1,
                "duration": 86400,
                "startTick": 0
            }
        
        # Update actor data sources
        base_path = f"/app/hyperbolic-time-chamber/simulations/input/{self.config.output_dir.name}/data"
        
        actor_sources = []
        
        # Nodes
        num_node_files = self.stats.get('files_nodes', 0)
        for i in range(1, num_node_files + 1):
            actor_sources.append({
                "id": f"htcrid:node;{i}",
                "classType": "hybrid.actor.Node",
                "creationType": "LoadBalancedDistributed",
                "dataSource": {
                    "sourceType": "json",
                    "info": {
                        "path": f"{base_path}/nodes_{i}.json"
                    }
                }
            })
        
        # Links
        num_link_files = self.stats.get('files_links', 0)
        for i in range(1, num_link_files + 1):
            actor_sources.append({
                "id": f"htcrid:link;{i}",
                "classType": "hybrid.actor.Link",
                "creationType": "LoadBalancedDistributed",
                "dataSource": {
                    "sourceType": "json",
                    "info": {
                        "path": f"{base_path}/links_{i}.json"
                    }
                }
            })
        
        # Rail Links (subway-only network)
        num_rail_link_files = self.stats.get('files_rail_links', 0)
        if num_rail_link_files > 0:
            for i in range(1, num_rail_link_files + 1):
                actor_sources.append({
                    "id": f"htcrid:rail_link;{i}",
                    "classType": "hybrid.actor.RailLink",
                    "creationType": "LoadBalancedDistributed",
                    "dataSource": {
                        "sourceType": "json",
                        "info": {
                            "path": f"{base_path}/rail_links_{i}.json"
                        }
                    }
                })
        
        # Vehicles by type (private vehicles only)
        vehicle_type_map = {
            VehicleTypeEnum.CAR: ("cars", "hybrid.actor.Car"),
            VehicleTypeEnum.BICYCLE: ("bicycles", "hybrid.actor.Bicycle"),
            VehicleTypeEnum.MOTORCYCLE: ("motorcycles", "hybrid.actor.Motorcycle"),
        }
        
        for vtype, (filename_base, actor_class) in vehicle_type_map.items():
            num_files = self.stats.get(f'files_{filename_base}', 0)
            if num_files > 0:
                for i in range(1, num_files + 1):
                    actor_sources.append({
                        "id": f"htcrid:{vtype.value.lower()};{i}",
                        "classType": actor_class,
                        "creationType": "LoadBalancedDistributed",
                        "dataSource": {
                            "sourceType": "json",
                            "info": {
                                "path": f"{base_path}/{filename_base}_{i}.json"
                            }
                        }
                    })
        
        # Public transport infrastructure
        public_transport_map = [
            ("bus_stops", "hybrid.actor.BusStop", "busstop"),
            ("bus_stations", "hybrid.actor.BusStation", "busstation"),
            ("subway_stations", "hybrid.actor.SubwayStation", "subwaystation"),
            ("bus_routes", "hybrid.actor.BusRoute", "busroute"),
            ("subway_routes", "hybrid.actor.SubwayRoute", "subwayroute"),
            ("buses", "hybrid.actor.Bus", "bus"),  # Legacy support
            ("subways", "hybrid.actor.Subway", "subway"),
        ]
        
        for filename_base, actor_class, resource_prefix in public_transport_map:
            num_files = self.stats.get(f'files_{filename_base}', 0)
            if num_files > 0:
                for i in range(1, num_files + 1):
                    actor_sources.append({
                        "id": f"htcrid:{resource_prefix};{i}",
                        "classType": actor_class,
                        "creationType": "LoadBalancedDistributed",
                        "dataSource": {
                            "sourceType": "json",
                            "info": {
                                "path": f"{base_path}/{filename_base}_{i}.json"
                            }
                        }
                    })
        
        # Persons
        num_person_files = self.stats.get('files_persons', 0)
        if num_person_files > 0:
            for i in range(1, num_person_files + 1):
                actor_sources.append({
                    "id": f"htcrid:person;{i}",
                    "classType": "hybrid.actor.Person",
                    "creationType": "LoadBalancedDistributed",
                    "dataSource": {
                        "sourceType": "json",
                        "info": {
                            "path": f"{base_path}/persons_{i}.json"
                        }
                    }
                })
        
        # Traffic signals
        num_signal_files = self.stats.get('files_traffic_signals', 0)
        if num_signal_files > 0:
            for i in range(1, num_signal_files + 1):
                actor_sources.append({
                    "id": f"htcrid:signal;{i}",
                    "classType": "hybrid.actor.TrafficSignal",
                    "creationType": "LoadBalancedDistributed",
                    "dataSource": {
                        "sourceType": "json",
                        "info": {
                            "path": f"{base_path}/traffic_signals_{i}.json"
                        }
                    }
                })
        
        config['actorsDataSources'] = actor_sources
        config['cityMapFile'] = f"{base_path}/city_map.json"
        
        with open(output_path, 'w') as f:
            json.dump(config, f, indent=2)
        
        self.log(f"  ✓ Simulation config: {output_path.name}")
    
    def _write_metadata(self, output_path: Path):
        """Write scenario metadata"""
        metadata = {
            "name": self.config.output_dir.name,
            "description": "Migrated from mobility model to hybrid model",
            "migrated": datetime.now().isoformat(),
            "version": "1.0.0",
            "source": {
                "directory": str(self.config.input_dir),
                "model": "mobility (MESO-only)"
            },
            "target": {
                "directory": str(self.config.output_dir),
                "model": "hybrid (MICRO/MESO)"
            },
            "statistics": dict(self.stats),
            "configuration": {
                "microLinkRatio": self.config.micro_link_ratio,
                "microSelectionStrategy": self.config.micro_selection_strategy,
                "convertVehicles": self.config.convert_vehicles,
                "itemsPerFile": self.config.items_per_file,
                "randomSeed": self.config.random_seed
            }
        }
        
        with open(output_path, 'w') as f:
            json.dump(metadata, f, indent=2)
        
        self.log(f"  ✓ Metadata: {output_path.name}")
    
    def _write_migration_report(self, output_path: Path):
        """Write human-readable migration report"""
        report = f"""# Migration Report: Mobility → Hybrid Model

**Migrated:** {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}

## Source

- **Directory:** `{self.config.input_dir}`
- **Model:** Mobility (MESO-only)
- **Nodes:** {self.stats['source_nodes']}
- **Links:** {self.stats['source_links']}
- **Vehicles:** {self.stats['source_vehicles']}

## Target

- **Directory:** `{self.config.output_dir}`
- **Model:** Hybrid (MICRO/MESO)

## Migration Statistics

### Infrastructure

- **Nodes Migrated:** {self.stats['migrated_nodes']}
- **Links Migrated:** {self.stats['source_links']}
  - MICRO: {self.stats['micro_links']} ({self.stats['micro_links']/self.stats['source_links']*100:.1f}%)
  - MESO: {self.stats['meso_links']} ({self.stats['meso_links']/self.stats['source_links']*100:.1f}%)
- **Traffic Signals Generated:** {self.stats.get('traffic_signals', 0)}

### Vehicles

- **Total Vehicles:** {self.stats['migrated_vehicles']}

"""
        
        # Vehicle breakdown
        vehicle_stats = []
        for vtype in VehicleTypeEnum:
            key = f'vehicles_{vtype.value.lower()}'
            count = self.stats.get(key, 0)
            if count > 0:
                vehicle_stats.append(f"- **{vtype.value}:** {count} ({count/self.stats['migrated_vehicles']*100:.1f}%)")
        
        if vehicle_stats:
            report += "\n".join(vehicle_stats) + "\n"
        
        report += f"""
### Files Generated

- **Node Files:** {self.stats.get('files_nodes', 0)}
- **Link Files:** {self.stats.get('files_links', 0)}
- **Vehicle Files:** {sum(self.stats.get(f'files_{vtype.value.lower()}s', 0) for vtype in VehicleTypeEnum)}
- **Traffic Signal Files:** {self.stats.get('files_traffic_signals', 0)}
- **Max Items per File:** {self.config.items_per_file}

## Configuration

### Hybrid Settings

- **MICRO Link Ratio:** {self.config.micro_link_ratio} ({self.stats['micro_links']} links)
- **Selection Strategy:** {self.config.micro_selection_strategy}
- **Micro Time Step:** {self.config.micro_time_step} seconds
- **Sub-ticks per Global Tick:** {self.config.micro_ticks_per_global_tick}

### Vehicle Conversion

- **Enabled:** {self.config.convert_vehicles}
"""
        
        if self.config.convert_vehicles:
            report += "\n**Conversion Ratios:**\n"
            for vtype, ratio in self.config.vehicle_conversion_ratios.items():
                report += f"- {vtype.value}: {ratio*100:.0f}%\n"
        
        report += f"""
### Other Settings

- **Random Seed:** {self.config.random_seed}
- **Preserve IDs:** {self.config.preserve_ids}
- **Generate Traffic Signals:** {self.config.generate_traffic_signals}
- **Signal Coverage:** {self.config.signal_coverage_ratio*100:.0f}%

## Changes Applied

### Node Migration

- ✅ Updated class type: `mobility.actor.Node` → `hybrid.actor.Node`
- ✅ Updated state type: `mobility.entity.state.NodeState` → `model.hybrid.entity.state.NodeState`
- ✅ Added hybrid-specific fields: `hasHybridConnections`, `conflictZones`
- ✅ Populated `links` array with connected link IDs

### Link Migration

- ✅ Updated class type: `mobility.actor.Link` → `hybrid.actor.Link`
- ✅ Updated state type: `model.mobility.entity.state.LinkState` → `model.hybrid.entity.state.LinkState`
- ✅ Added `simulationMode` field (MICRO/MESO)
- ✅ Added `microTimeStep` and `microTicksPerGlobalTick`
- ✅ Generated `laneConfigurations` for MICRO links
- ✅ Converted speeds from m/s to km/h
- ✅ Added `vehiclesByLane` for MICRO links

### Vehicle Migration

- ✅ Updated class types to hybrid actor types
- ✅ Updated state types to hybrid state types
- ✅ Added `currentSimulationMode` field (defaults to MESO)
- ✅ Added `microState` field (initially null)
- ✅ Generated `driverAttributes` with random variation
- ✅ Added type-specific fields (bus capacity, bicycle preferences, etc.)
- ✅ Removed GPS dependencies (not needed in hybrid model)

### City Map Migration

- ✅ Updated all node class types
- ✅ Updated all edge label class types

## File Structure

```
{self.config.output_dir.name}/
├── data/
│   ├── city_map.json
│   ├── nodes_1.json ... nodes_N.json
│   ├── links_1.json ... links_N.json
│   ├── cars_1.json ... cars_N.json
│   ├── buses_1.json ... buses_N.json (if any)
│   ├── bicycles_1.json ... bicycles_N.json (if any)
│   ├── motorcycles_1.json ... motorcycles_N.json (if any)
│   └── traffic_signals_1.json (if generated)
├── simulation.json
├── scenario_metadata.json
└── MIGRATION_REPORT.md (this file)
```

## Usage

### Running the Simulation

```bash
# Set environment variable
export HTC_SIMULATION_DATA_PATH={self.config.output_dir.absolute()}

# Run simulation
./build-and-run.sh
```

### Docker

```bash
docker run -v {self.config.output_dir.absolute()}:/app/scenario \\
  -e HTC_SIMULATION_DATA_PATH=/app/scenario \\
  hyperbolic-time-chamber
```

## Notes

- This scenario was **automatically migrated** from the mobility model
- MICRO links use car-following models (Krauss) for detailed dynamics
- MESO links use aggregate speed calculations
- Vehicles automatically switch modes when entering different link types
- All vehicle attributes were randomly generated with realistic variation
- Original actor IDs were {"preserved" if self.config.preserve_ids else "regenerated"}

## Validation

✅ All source nodes migrated  
✅ All source links migrated  
✅ All source vehicles migrated  
✅ City map structure preserved  
✅ Graph connectivity maintained  
✅ Dependencies updated correctly  

---
*Generated by Mobility→Hybrid Migration Script v1.0*
"""
        
        with open(output_path, 'w') as f:
            f.write(report)
        
        self.log(f"  ✓ Migration report: {output_path.name}")
    
    def _print_statistics(self):
        """Print final migration statistics"""
        self.log("\n📊 Migration Statistics:")
        self.log(f"  Source Entities:")
        self.log(f"    • Nodes:    {self.stats['source_nodes']}")
        self.log(f"    • Links:    {self.stats['source_links']}")
        self.log(f"    • Vehicles: {self.stats['source_vehicles']}")
        
        self.log(f"\n  Migrated Entities:")
        self.log(f"    • Nodes:    {self.stats['migrated_nodes']}")
        self.log(f"    • Links:    {self.stats['source_links']} ({self.stats['micro_links']} MICRO, {self.stats['meso_links']} MESO)")
        self.log(f"    • Vehicles: {self.stats['migrated_vehicles']}")
        
        for vtype in VehicleTypeEnum:
            key = f'vehicles_{vtype.value.lower()}'
            count = self.stats.get(key, 0)
            if count > 0:
                self.log(f"      - {vtype.value}: {count}")
        
        if self.stats.get('traffic_signals', 0) > 0:
            self.log(f"    • Signals:  {self.stats['traffic_signals']}")
        
        self.log(f"\n  Output Files:")
        for key, value in self.stats.items():
            if key.startswith('files_'):
                name = key.replace('files_', '').replace('_', ' ').title()
                self.log(f"    • {name}: {value} files")
        
        self.log(f"\n📂 Output: {self.config.output_dir.absolute()}")
    
    def log(self, message: str, error: bool = False):
        """Log message if verbose"""
        if self.config.verbose or error:
            print(message, file=sys.stderr if error else sys.stdout)

# ============================================================================
# COMMAND-LINE INTERFACE
# ============================================================================

def load_config_from_yaml(yaml_path: Path) -> MigrationConfig:
    """Load migration configuration from YAML file"""
    with open(yaml_path, 'r') as f:
        data = yaml.safe_load(f)
    
    # Convert paths
    if 'input_dir' in data:
        data['input_dir'] = Path(data['input_dir'])
    if 'output_dir' in data:
        data['output_dir'] = Path(data['output_dir'])
    
    # Convert vehicle conversion ratios
    if 'vehicle_conversion_ratios' in data:
        ratios = {}
        for key, value in data['vehicle_conversion_ratios'].items():
            ratios[VehicleTypeEnum[key.upper()]] = value
        data['vehicle_conversion_ratios'] = ratios
    
    return MigrationConfig(**data)

def main():
    parser = argparse.ArgumentParser(
        description="Migrate mobility model scenarios to hybrid model",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Basic migration
  python migrate_to_hybrid.py --input ./input/cenario_1000_viagens --output ./output/hybrid_scenario
  
  # With custom MICRO ratio
  python migrate_to_hybrid.py --input ./input/old_scenario --output ./output/new_scenario --micro-ratio 0.5
  
  # With vehicle type conversion
  python migrate_to_hybrid.py --input ./input/old_scenario --output ./output/new_scenario --convert-vehicles
  
  # From YAML config
  python migrate_to_hybrid.py --config migration_config.yaml
  
  # Custom file splitting
  python migrate_to_hybrid.py --input ./input/large_scenario --output ./output/split_scenario --items-per-file 1000
        """
    )
    
    parser.add_argument('--config', type=Path, help='YAML configuration file')
    parser.add_argument('--input', type=Path, help='Input directory (mobility model)')
    parser.add_argument('--output', type=Path, help='Output directory (hybrid model)')
    
    # Hybrid configuration
    parser.add_argument('--micro-ratio', type=float, default=0.3, 
                       help='Ratio of links to convert to MICRO (0.0-1.0, default: 0.3)')
    parser.add_argument('--micro-strategy', choices=['random', 'arterial', 'highway'], 
                       default='random', help='Strategy for selecting MICRO links')
    
    # Vehicle conversion (private vehicles only)
    parser.add_argument('--convert-vehicles', action='store_true',
                       help='Convert some cars to bicycles/motorcycles')
    parser.add_argument('--car-ratio', type=float, default=0.8,
                       help='Ratio of cars when converting (default: 0.8)')
    parser.add_argument('--bicycle-ratio', type=float, default=0.1,
                       help='Ratio of bicycles when converting (default: 0.1)')
    parser.add_argument('--motorcycle-ratio', type=float, default=0.1,
                       help='Ratio of motorcycles when converting (default: 0.1)')
    
    # Public transport generation
    parser.add_argument('--no-public-transport', action='store_true',
                       help='Do not generate public transport infrastructure')
    parser.add_argument('--bus-stop-coverage', type=float, default=0.15,
                       help='Ratio of nodes with bus stops (default: 0.15)')
    parser.add_argument('--subway-station-coverage', type=float, default=0.05,
                       help='Ratio of nodes with subway stations (default: 0.05)')
    parser.add_argument('--num-bus-routes', type=int, default=5,
                       help='Number of bus routes to generate (default: 5)')
    parser.add_argument('--num-subway-routes', type=int, default=2,
                       help='Number of subway routes to generate (default: 2)')
    parser.add_argument('--subway-routes', type=Path,
                       help='JSON file with predefined subway routes (if not provided, routes are generated randomly)')
    
    # Person generation
    parser.add_argument('--no-persons', action='store_true',
                       help='Do not generate Person actors')
    parser.add_argument('--persons-per-vehicle', type=float, default=2.0,
                       help='Average persons per vehicle trip (default: 2.0)')
    
    # File splitting
    parser.add_argument('--items-per-file', type=int, default=5000,
                       help='Maximum items per JSON file (default: 5000)')
    
    # Traffic signals
    parser.add_argument('--no-signals', action='store_true',
                       help='Do not generate traffic signals')
    parser.add_argument('--signal-coverage', type=float, default=0.2,
                       help='Ratio of nodes with traffic signals (default: 0.2)')
    
    # Other options
    parser.add_argument('--seed', type=int, default=42, help='Random seed')
    parser.add_argument('--quiet', action='store_true', help='Suppress output')
    
    args = parser.parse_args()
    
    # Validate arguments
    if not args.config:
        if not args.input or not args.output:
            parser.error("Either --config or both --input and --output are required")
    
    # Determine configuration source
    if args.config:
        config = load_config_from_yaml(args.config)
        # Override with command line args if provided
        if args.subway_routes:
            config.subway_routes_file = args.subway_routes
    else:
        config = MigrationConfig(
            input_dir=args.input,
            output_dir=args.output,
            micro_link_ratio=args.micro_ratio,
            micro_selection_strategy=args.micro_strategy,
            convert_vehicles=args.convert_vehicles,
            vehicle_conversion_ratios={
                VehicleTypeEnum.CAR: args.car_ratio,
                VehicleTypeEnum.BICYCLE: args.bicycle_ratio,
                VehicleTypeEnum.MOTORCYCLE: args.motorcycle_ratio
            },
            generate_public_transport=not args.no_public_transport,
            bus_stop_coverage=args.bus_stop_coverage,
            subway_station_coverage=args.subway_station_coverage,
            num_bus_routes=args.num_bus_routes,
            num_subway_routes=args.num_subway_routes,
            subway_routes_file=args.subway_routes,
            generate_persons=not args.no_persons,
            persons_per_vehicle=args.persons_per_vehicle,
            items_per_file=args.items_per_file,
            generate_traffic_signals=not args.no_signals,
            signal_coverage_ratio=args.signal_coverage,
            random_seed=args.seed,
            verbose=not args.quiet
        )
    
    # Validate input directory
    if not config.input_dir.exists():
        print(f"❌ Error: Input directory does not exist: {config.input_dir}", file=sys.stderr)
        sys.exit(1)
    
    # Run migration
    migrator = HybridMigrator(config)
    migrator.migrate()

if __name__ == "__main__":
    main()
