#!/usr/bin/env python3
"""
Hybrid Scenario Generator for Hyperbolic Time Chamber

This script generates complete simulation scenarios with configurable hybrid (MICRO/MESO) links,
nodes, vehicles, and all required configuration files.

Usage:
    python generate_hybrid_scenario.py --config scenario_config.yaml --output ./output_dir
    python generate_hybrid_scenario.py --interactive
"""

import argparse
import json
import random
import math
import sys
from pathlib import Path
from typing import List, Dict, Any, Optional, Tuple
from dataclasses import dataclass, field, asdict
from enum import Enum
import yaml
from datetime import datetime

# ============================================================================
# CONFIGURATION DATACLASSES
# ============================================================================

class SimulationModeEnum(Enum):
    """Simulation mode for links"""
    MESO = "MESO"
    MICRO = "MICRO"

class VehicleTypeEnum(Enum):
    """Vehicle types supported"""
    CAR = "CAR"
    BUS = "BUS"
    BICYCLE = "BICYCLE"
    MOTORCYCLE = "MOTORCYCLE"

class LaneTypeEnum(Enum):
    """Lane types"""
    NORMAL = "normal"
    BUS_LANE = "bus_lane"
    BIKE_LANE = "bike_lane"
    HOV_LANE = "hov_lane"

@dataclass
class Point:
    """Geographic point"""
    latitude: float
    longitude: float
    
    def distance_to(self, other: 'Point') -> float:
        """Calculate Euclidean distance to another point (in meters approximation)"""
        # Simplified distance calculation
        lat_diff = (self.latitude - other.latitude) * 111000  # ~111km per degree
        lon_diff = (self.longitude - other.longitude) * 111000 * math.cos(math.radians(self.latitude))
        return math.sqrt(lat_diff**2 + lon_diff**2)

@dataclass
class LaneConfig:
    """Lane configuration"""
    laneId: int
    type: str = "normal"
    width: float = 3.5  # meters
    speedLimit: Optional[float] = None  # km/h, None means inherit from link

@dataclass
class NodeConfig:
    """Node (intersection) configuration"""
    id: str
    latitude: float
    longitude: float
    has_signal: bool = False
    signal_cycle: int = 90  # seconds
    signal_offset: int = 0  # seconds

@dataclass
class LinkConfig:
    """Link (road segment) configuration"""
    id: str
    from_node: str
    to_node: str
    length: float  # meters
    lanes: int
    speed_limit: float  # km/h
    free_speed: float  # km/h
    capacity: float  # vehicles/hour
    simulation_mode: SimulationModeEnum = SimulationModeEnum.MESO
    lane_configurations: List[LaneConfig] = field(default_factory=list)
    link_type: str = "residential"
    micro_time_step: float = 0.1  # seconds
    micro_ticks_per_global_tick: int = 10

@dataclass
class DriverAttributes:
    """Driver behavior attributes"""
    aggressiveness: float = 0.5  # [0-1]
    reactionTimeFactor: float = 1.0  # multiplier
    speedFactor: float = 1.0  # multiplier
    minGapFactor: float = 1.0  # multiplier

@dataclass
class VehicleConfig:
    """Vehicle configuration"""
    id: str
    type: VehicleTypeEnum
    start_tick: int
    origin: str
    destination: str
    driver_attributes: Optional[DriverAttributes] = None

@dataclass
class ScenarioConfig:
    """Complete scenario configuration"""
    name: str
    description: str
    output_dir: Path
    start_tick: int = 0
    end_tick: int = 3600
    tick_duration: float = 1.0
    
    # Network parameters
    network_type: str = "grid"  # grid, random, real
    num_nodes: int = 9
    grid_size: float = 500.0  # meters between nodes in grid
    base_latitude: float = -23.5505
    base_longitude: float = -46.6333
    
    # Link parameters
    default_lanes: int = 2
    default_speed_limit: float = 50.0  # km/h
    default_capacity: float = 1800.0  # vehicles/hour/lane
    micro_link_ratio: float = 0.3  # ratio of links that are MICRO
    
    # Vehicle parameters
    num_vehicles: int = 100
    vehicle_distribution: Dict[VehicleTypeEnum, float] = field(default_factory=lambda: {
        VehicleTypeEnum.CAR: 0.7,
        VehicleTypeEnum.BUS: 0.1,
        VehicleTypeEnum.BICYCLE: 0.1,
        VehicleTypeEnum.MOTORCYCLE: 0.1
    })
    
    # Simulation parameters
    random_seed: int = 42
    verbose: bool = True

# ============================================================================
# SCENARIO GENERATOR
# ============================================================================

class HybridScenarioGenerator:
    """Generate complete hybrid simulation scenarios"""
    
    def __init__(self, config: ScenarioConfig):
        self.config = config
        self.nodes: List[NodeConfig] = []
        self.links: List[LinkConfig] = []
        self.vehicles: List[VehicleConfig] = []
        
        random.seed(config.random_seed)
        
        if config.verbose:
            print(f"\n{'='*80}")
            print(f"🚀 Hybrid Scenario Generator")
            print(f"{'='*80}")
            print(f"Scenario: {config.name}")
            print(f"Output: {config.output_dir}")
            print(f"{'='*80}\n")
    
    def generate(self):
        """Generate complete scenario"""
        self.log("📍 Step 1/5: Generating network topology...")
        self._generate_network()
        
        self.log("🔗 Step 2/5: Generating links...")
        self._generate_links()
        
        self.log("🚗 Step 3/5: Generating vehicles...")
        self._generate_vehicles()
        
        self.log("💾 Step 4/5: Writing output files...")
        self._write_output()
        
        self.log("📊 Step 5/5: Generating reports...")
        self._generate_reports()
        
        self.log("\n✅ Scenario generation complete!")
        self.log(f"📂 Output directory: {self.config.output_dir.absolute()}\n")
    
    def _generate_network(self):
        """Generate network nodes based on network type"""
        if self.config.network_type == "grid":
            self._generate_grid_network()
        elif self.config.network_type == "random":
            self._generate_random_network()
        else:
            raise ValueError(f"Unknown network type: {self.config.network_type}")
        
        self.log(f"  ✓ Generated {len(self.nodes)} nodes")
    
    def _generate_grid_network(self):
        """Generate a regular grid network"""
        grid_dim = int(math.sqrt(self.config.num_nodes))
        actual_nodes = grid_dim * grid_dim
        
        for i in range(grid_dim):
            for j in range(grid_dim):
                node_id = f"node_{i}_{j}"
                
                # Calculate position (approximate lat/lon offset)
                lat_offset = (i * self.config.grid_size) / 111000.0
                lon_offset = (j * self.config.grid_size) / (111000.0 * math.cos(math.radians(self.config.base_latitude)))
                
                node = NodeConfig(
                    id=node_id,
                    latitude=self.config.base_latitude + lat_offset,
                    longitude=self.config.base_longitude + lon_offset,
                    has_signal=(i > 0 and i < grid_dim-1 and j > 0 and j < grid_dim-1),
                    signal_cycle=90,
                    signal_offset=random.randint(0, 30)
                )
                self.nodes.append(node)
    
    def _generate_random_network(self):
        """Generate random network within area"""
        area_size = self.config.grid_size * 3  # meters
        
        for i in range(self.config.num_nodes):
            # Random position within area
            lat_offset = random.uniform(0, area_size) / 111000.0
            lon_offset = random.uniform(0, area_size) / (111000.0 * math.cos(math.radians(self.config.base_latitude)))
            
            node = NodeConfig(
                id=f"node_{i}",
                latitude=self.config.base_latitude + lat_offset,
                longitude=self.config.base_longitude + lon_offset,
                has_signal=random.random() < 0.3,
                signal_cycle=random.choice([60, 90, 120]),
                signal_offset=random.randint(0, 30)
            )
            self.nodes.append(node)
    
    def _generate_links(self):
        """Generate links between nodes"""
        if self.config.network_type == "grid":
            self._generate_grid_links()
        elif self.config.network_type == "random":
            self._generate_random_links()
        
        self.log(f"  ✓ Generated {len(self.links)} links")
        micro_count = sum(1 for link in self.links if link.simulation_mode == SimulationModeEnum.MICRO)
        self.log(f"  ✓ MICRO links: {micro_count} ({micro_count/len(self.links)*100:.1f}%)")
        self.log(f"  ✓ MESO links: {len(self.links)-micro_count} ({(len(self.links)-micro_count)/len(self.links)*100:.1f}%)")
    
    def _generate_grid_links(self):
        """Generate links for grid network"""
        grid_dim = int(math.sqrt(len(self.nodes)))
        link_id = 0
        
        for i in range(grid_dim):
            for j in range(grid_dim):
                current_node = f"node_{i}_{j}"
                
                # Link to right neighbor
                if j < grid_dim - 1:
                    neighbor = f"node_{i}_{j+1}"
                    self._create_bidirectional_link(link_id, current_node, neighbor)
                    link_id += 2
                
                # Link to bottom neighbor
                if i < grid_dim - 1:
                    neighbor = f"node_{i+1}_{j}"
                    self._create_bidirectional_link(link_id, current_node, neighbor)
                    link_id += 2
    
    def _generate_random_links(self):
        """Generate links for random network using proximity"""
        link_id = 0
        max_link_distance = self.config.grid_size * 1.5
        
        # Create point objects for distance calculation
        node_points = {node.id: Point(node.latitude, node.longitude) for node in self.nodes}
        
        # Connect each node to nearest neighbors
        for i, node_a in enumerate(self.nodes):
            point_a = node_points[node_a.id]
            
            # Find nearest neighbors
            distances = []
            for j, node_b in enumerate(self.nodes):
                if i != j:
                    point_b = node_points[node_b.id]
                    dist = point_a.distance_to(point_b)
                    if dist < max_link_distance:
                        distances.append((dist, node_b.id))
            
            # Connect to 2-3 nearest neighbors
            distances.sort()
            num_connections = min(random.randint(2, 3), len(distances))
            
            for _, neighbor_id in distances[:num_connections]:
                # Check if link already exists
                existing = any(
                    (link.from_node == node_a.id and link.to_node == neighbor_id)
                    for link in self.links
                )
                if not existing:
                    self._create_bidirectional_link(link_id, node_a.id, neighbor_id)
                    link_id += 2
    
    def _create_bidirectional_link(self, link_id: int, node_a: str, node_b: str):
        """Create bidirectional link between two nodes"""
        # Get node positions
        pos_a = next(Point(n.latitude, n.longitude) for n in self.nodes if n.id == node_a)
        pos_b = next(Point(n.latitude, n.longitude) for n in self.nodes if n.id == node_b)
        
        length = pos_a.distance_to(pos_b)
        
        # Determine if this should be MICRO or MESO
        is_micro = random.random() < self.config.micro_link_ratio
        sim_mode = SimulationModeEnum.MICRO if is_micro else SimulationModeEnum.MESO
        
        # Randomly vary parameters
        lanes = random.choice([1, 2, 2, 3, 3, 4])  # weighted toward 2-3 lanes
        speed_limit = random.choice([30, 40, 50, 60, 80])
        
        # Create lane configurations
        lane_configs = []
        for lane_id in range(lanes):
            lane_type = LaneTypeEnum.NORMAL.value
            # 10% chance for special lanes
            if random.random() < 0.1:
                lane_type = random.choice([LaneTypeEnum.BUS_LANE.value, LaneTypeEnum.BIKE_LANE.value])
            
            lane_configs.append(LaneConfig(
                laneId=lane_id,
                type=lane_type,
                width=3.5,
                speedLimit=None
            ))
        
        # Forward link
        self.links.append(LinkConfig(
            id=f"link_{link_id}",
            from_node=node_a,
            to_node=node_b,
            length=length,
            lanes=lanes,
            speed_limit=speed_limit,
            free_speed=speed_limit,
            capacity=self.config.default_capacity * lanes,
            simulation_mode=sim_mode,
            lane_configurations=lane_configs if is_micro else [],
            link_type=self._determine_link_type(speed_limit, lanes)
        ))
        
        # Reverse link
        self.links.append(LinkConfig(
            id=f"link_{link_id+1}",
            from_node=node_b,
            to_node=node_a,
            length=length,
            lanes=lanes,
            speed_limit=speed_limit,
            free_speed=speed_limit,
            capacity=self.config.default_capacity * lanes,
            simulation_mode=sim_mode,
            lane_configurations=lane_configs if is_micro else [],
            link_type=self._determine_link_type(speed_limit, lanes)
        ))
    
    def _determine_link_type(self, speed_limit: float, lanes: int) -> str:
        """Determine link type based on characteristics"""
        if speed_limit >= 80:
            return "motorway" if lanes >= 3 else "trunk"
        elif speed_limit >= 60:
            return "primary"
        elif speed_limit >= 50:
            return "secondary"
        else:
            return "residential"
    
    def _generate_vehicles(self):
        """Generate vehicles with random origins and destinations"""
        # Calculate vehicle counts by type
        vehicle_counts = {}
        remaining = self.config.num_vehicles
        
        for vtype, ratio in self.config.vehicle_distribution.items():
            count = int(self.config.num_vehicles * ratio)
            vehicle_counts[vtype] = count
            remaining -= count
        
        # Add remaining to cars
        vehicle_counts[VehicleTypeEnum.CAR] += remaining
        
        vehicle_id = 0
        for vtype, count in vehicle_counts.items():
            for _ in range(count):
                # Random origin and destination
                origin = random.choice(self.nodes).id
                destination = random.choice([n.id for n in self.nodes if n.id != origin])
                
                # Random start time
                start_tick = random.randint(0, self.config.end_tick // 2)
                
                # Generate driver attributes with some variation
                driver_attrs = DriverAttributes(
                    aggressiveness=random.uniform(0.3, 0.9),
                    reactionTimeFactor=random.uniform(0.8, 1.2),
                    speedFactor=random.uniform(0.9, 1.1),
                    minGapFactor=random.uniform(0.8, 1.2)
                )
                
                vehicle = VehicleConfig(
                    id=f"{vtype.value.lower()}_{vehicle_id}",
                    type=vtype,
                    start_tick=start_tick,
                    origin=origin,
                    destination=destination,
                    driver_attributes=driver_attrs
                )
                
                self.vehicles.append(vehicle)
                vehicle_id += 1
        
        self.log(f"  ✓ Generated {len(self.vehicles)} vehicles")
        for vtype, count in vehicle_counts.items():
            self.log(f"    • {vtype.value}: {count}")
    
    def _write_output(self):
        """Write all output files"""
        # Create output directory
        self.config.output_dir.mkdir(parents=True, exist_ok=True)
        data_dir = self.config.output_dir / "data"
        data_dir.mkdir(exist_ok=True)
        
        # Write city map (graph structure)
        self._write_city_map(data_dir / "city_map.json")
        
        # Write actors (nodes)
        self._write_nodes(data_dir / "nodes.json")
        
        # Write actors (links)
        self._write_links(data_dir / "links.json")
        
        # Write actors (vehicles)
        self._write_vehicles(data_dir / "vehicles.json")
        
        # Write traffic signals
        self._write_traffic_signals(data_dir / "traffic_signals.json")
        
        # Write simulation.json
        self._write_simulation_json(self.config.output_dir / "simulation.json")
        
        # Write scenario metadata
        self._write_metadata(self.config.output_dir / "scenario_metadata.json")
        
        self.log(f"  ✓ Written all output files to {data_dir}")
    
    def _write_city_map(self, path: Path):
        """Write city map as graph structure"""
        # Build vertices
        vertices = {}
        for node in self.nodes:
            vertices[node.id] = {
                "latitude": node.latitude,
                "longitude": node.longitude
            }
        
        # Build edges
        edges = []
        for link in self.links:
            edges.append({
                "sourceId": link.from_node,
                "targetId": link.to_node,
                "weight": link.length,
                "label": link.id
            })
        
        city_map = {
            "vertices": vertices,
            "edges": edges
        }
        
        with open(path, 'w') as f:
            json.dump(city_map, f, indent=2)
        
        self.log(f"  ✓ City map: {path.name}")
    
    def _write_nodes(self, path: Path):
        """Write node actors"""
        actors = []
        
        for node in self.nodes:
            actor = {
                "id": f"htcaid:node;{node.id}",
                "typeActor": "hybrid.actor.Node",
                "data": {
                    "dataType": "model.hybrid.entity.state.NodeState",
                    "content": {
                        "startTick": 0,
                        "latitude": node.latitude,
                        "longitude": node.longitude,
                        "links": [f"htcaid:link;{link.id}" for link in self.links 
                                 if link.from_node == node.id or link.to_node == node.id],
                        "connections": {},
                        "signals": {},
                        "busStops": {},
                        "subwayStations": {},
                        "scheduleOnTimeManager": False
                    }
                },
                "dependencies": {}
            }
            actors.append(actor)
        
        with open(path, 'w') as f:
            json.dump(actors, f, indent=2)
        
        self.log(f"  ✓ Nodes: {path.name} ({len(actors)} nodes)")
    
    def _write_links(self, path: Path):
        """Write link actors"""
        actors = []
        
        for link in self.links:
            # Lane configurations for MICRO links
            lane_configs = []
            if link.simulation_mode == SimulationModeEnum.MICRO:
                lane_configs = [asdict(lc) for lc in link.lane_configurations]
            
            actor = {
                "id": f"htcaid:link;{link.id}",
                "typeActor": "hybrid.actor.Link",
                "data": {
                    "dataType": "model.hybrid.entity.state.LinkState",
                    "content": {
                        "startTick": 0,
                        "from": f"htcaid:node;{link.from_node}",
                        "to": f"htcaid:node;{link.to_node}",
                        "length": link.length,
                        "lanes": link.lanes,
                        "speedLimit": link.speed_limit,
                        "freeSpeed": link.free_speed,
                        "capacity": link.capacity,
                        "simulationMode": link.simulation_mode.value,
                        "microTimeStep": link.micro_time_step,
                        "microTicksPerGlobalTick": link.micro_ticks_per_global_tick,
                        "laneConfigurations": lane_configs,
                        "linkType": link.link_type,
                        "congestionFactor": 1.0,
                        "currentSpeed": link.free_speed,
                        "registered": [],
                        "vehiclesByLane": {},
                        "scheduleOnTimeManager": False
                    }
                },
                "dependencies": {
                    "from_node": {
                        "id": f"htcaid:node;{link.from_node}",
                        "classType": "hybrid.actor.Node"
                    },
                    "to_node": {
                        "id": f"htcaid:node;{link.to_node}",
                        "classType": "hybrid.actor.Node"
                    }
                }
            }
            actors.append(actor)
        
        with open(path, 'w') as f:
            json.dump(actors, f, indent=2)
        
        self.log(f"  ✓ Links: {path.name} ({len(actors)} links)")
    
    def _write_vehicles(self, path: Path):
        """Write vehicle actors"""
        actors = []
        
        for vehicle in self.vehicles:
            # Determine actor type and state type
            type_map = {
                VehicleTypeEnum.CAR: ("hybrid.actor.Car", "model.hybrid.entity.state.CarState", 4.5),
                VehicleTypeEnum.BUS: ("hybrid.actor.Bus", "model.hybrid.entity.state.BusState", 12.0),
                VehicleTypeEnum.BICYCLE: ("hybrid.actor.Bicycle", "model.hybrid.entity.state.BicycleState", 2.0),
                VehicleTypeEnum.MOTORCYCLE: ("hybrid.actor.Motorcycle", "model.hybrid.entity.state.MotorcycleState", 2.5),
            }
            
            actor_type, state_type, size = type_map[vehicle.type]
            
            content = {
                "startTick": vehicle.start_tick,
                "origin": f"htcaid:node;{vehicle.origin}",
                "destination": f"htcaid:node;{vehicle.destination}",
                "actorType": vehicle.type.value,
                "size": size,
                "currentSimulationMode": "MESO",
                "microState": None,
                "status": "START",
                "bestRoute": None,
                "currentNode": f"htcaid:node;{vehicle.origin}",
                "distance": 0.0,
                "eventCount": 0,
                "scheduleOnTimeManager": True
            }
            
            # Add driver attributes if present
            if vehicle.driver_attributes:
                content["driverAttributes"] = asdict(vehicle.driver_attributes)
            
            # Add bus-specific fields
            if vehicle.type == VehicleTypeEnum.BUS:
                content.update({
                    "label": f"Bus Line {vehicle.id}",
                    "capacity": 80,
                    "busStops": {},
                    "people": {},
                    "nextBusStop": None
                })
            
            actor = {
                "id": f"htcaid:{vehicle.type.value.lower()};{vehicle.id}",
                "typeActor": actor_type,
                "data": {
                    "dataType": state_type,
                    "content": content
                },
                "dependencies": {
                    "from_node": {
                        "id": f"htcaid:node;{vehicle.origin}",
                        "classType": "hybrid.actor.Node"
                    },
                    "to_node": {
                        "id": f"htcaid:node;{vehicle.destination}",
                        "classType": "hybrid.actor.Node"
                    }
                }
            }
            actors.append(actor)
        
        with open(path, 'w') as f:
            json.dump(actors, f, indent=2)
        
        self.log(f"  ✓ Vehicles: {path.name} ({len(actors)} vehicles)")
    
    def _write_traffic_signals(self, path: Path):
        """Write traffic signal actors"""
        actors = []
        signal_id = 0
        
        for node in self.nodes:
            if not node.has_signal:
                continue
            
            # Get links connected to this node
            incoming_links = [link for link in self.links if link.to_node == node.id]
            
            if len(incoming_links) < 2:
                continue  # Need at least 2 approaches
            
            # Create phases (simplified 2-phase signal)
            phases = [
                {
                    "phaseId": 0,
                    "duration": node.signal_cycle // 2,
                    "greenLinks": [f"htcaid:link;{link.id}" for i, link in enumerate(incoming_links) if i % 2 == 0],
                    "yellowDuration": 3,
                    "allRedDuration": 2
                },
                {
                    "phaseId": 1,
                    "duration": node.signal_cycle // 2,
                    "greenLinks": [f"htcaid:link;{link.id}" for i, link in enumerate(incoming_links) if i % 2 == 1],
                    "yellowDuration": 3,
                    "allRedDuration": 2
                }
            ]
            
            # Create signal states for each link
            signal_states = {}
            for link in incoming_links:
                signal_states[f"htcaid:link;{link.id}"] = {
                    "state": "RED",
                    "timeInState": 0
                }
            
            actor = {
                "id": f"htcaid:signal;signal_{signal_id}",
                "typeActor": "hybrid.actor.TrafficSignal",
                "data": {
                    "dataType": "model.hybrid.entity.state.TrafficSignalState",
                    "content": {
                        "startTick": 0,
                        "cycleDuration": node.signal_cycle,
                        "offset": node.signal_offset,
                        "nodes": [f"htcaid:node;{node.id}"],
                        "phases": phases,
                        "signalStates": signal_states,
                        "scheduleOnTimeManager": True
                    }
                },
                "dependencies": {
                    "node": {
                        "id": f"htcaid:node;{node.id}",
                        "classType": "hybrid.actor.Node"
                    }
                }
            }
            actors.append(actor)
            signal_id += 1
        
        with open(path, 'w') as f:
            json.dump(actors, f, indent=2)
        
        self.log(f"  ✓ Traffic signals: {path.name} ({len(actors)} signals)")
    
    def _write_simulation_json(self, path: Path):
        """Write simulation.json configuration file"""
        simulation_config = {
            "simulation": {
                "name": self.config.name,
                "description": self.config.description,
                "startTick": self.config.start_tick,
                "endTick": self.config.end_tick,
                "tickDuration": self.config.tick_duration,
                "randomSeed": self.config.random_seed,
                "actorsDataSources": [
                    {
                        "id": "nodes",
                        "classType": "hybrid.actor.Node",
                        "creationType": "LoadBalancedDistributed",
                        "dataSource": {
                            "type": "json",
                            "info": {
                                "path": "data/nodes.json"
                            }
                        }
                    },
                    {
                        "id": "links",
                        "classType": "hybrid.actor.Link",
                        "creationType": "LoadBalancedDistributed",
                        "dataSource": {
                            "type": "json",
                            "info": {
                                "path": "data/links.json"
                            }
                        }
                    },
                    {
                        "id": "vehicles",
                        "classType": "hybrid.actor.Car",
                        "creationType": "LoadBalancedDistributed",
                        "dataSource": {
                            "type": "json",
                            "info": {
                                "path": "data/vehicles.json"
                            }
                        }
                    },
                    {
                        "id": "traffic_signals",
                        "classType": "hybrid.actor.TrafficSignal",
                        "creationType": "LoadBalancedDistributed",
                        "dataSource": {
                            "type": "json",
                            "info": {
                                "path": "data/traffic_signals.json"
                            }
                        }
                    }
                ],
                "cityMapFile": "data/city_map.json"
            }
        }
        
        with open(path, 'w') as f:
            json.dump(simulation_config, f, indent=2)
        
        self.log(f"  ✓ Configuration: {path.name}")
    
    def _write_metadata(self, path: Path):
        """Write scenario metadata"""
        metadata = {
            "name": self.config.name,
            "description": self.config.description,
            "generated": datetime.now().isoformat(),
            "version": "1.0.0",
            "statistics": {
                "nodes": len(self.nodes),
                "links": len(self.links),
                "vehicles": len(self.vehicles),
                "microLinks": sum(1 for link in self.links if link.simulation_mode == SimulationModeEnum.MICRO),
                "mesoLinks": sum(1 for link in self.links if link.simulation_mode == SimulationModeEnum.MESO),
                "vehiclesByType": {
                    vtype.value: sum(1 for v in self.vehicles if v.type == vtype)
                    for vtype in VehicleTypeEnum
                },
                "signalizedIntersections": sum(1 for node in self.nodes if node.has_signal)
            },
            "configuration": {
                "startTick": self.config.start_tick,
                "endTick": self.config.end_tick,
                "tickDuration": self.config.tick_duration,
                "networkType": self.config.network_type,
                "randomSeed": self.config.random_seed
            }
        }
        
        with open(path, 'w') as f:
            json.dump(metadata, f, indent=2)
        
        self.log(f"  ✓ Metadata: {path.name}")
    
    def _generate_reports(self):
        """Generate human-readable reports"""
        report_path = self.config.output_dir / "SCENARIO_REPORT.md"
        
        micro_links = [link for link in self.links if link.simulation_mode == SimulationModeEnum.MICRO]
        meso_links = [link for link in self.links if link.simulation_mode == SimulationModeEnum.MESO]
        
        report = f"""# Scenario Report: {self.config.name}

**Generated:** {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}

## Description
{self.config.description}

## Network Statistics

### Topology
- **Network Type:** {self.config.network_type}
- **Nodes:** {len(self.nodes)} intersections
- **Links:** {len(self.links)} road segments
- **Signalized Intersections:** {sum(1 for n in self.nodes if n.has_signal)}

### Link Distribution
- **MICRO Links:** {len(micro_links)} ({len(micro_links)/len(self.links)*100:.1f}%)
- **MESO Links:** {len(meso_links)} ({len(meso_links)/len(self.links)*100:.1f}%)

### Link Characteristics
- **Average Length:** {sum(link.length for link in self.links)/len(self.links):.1f} meters
- **Average Lanes:** {sum(link.lanes for link in self.links)/len(self.links):.1f}
- **Speed Limits:** {min(link.speed_limit for link in self.links):.0f} - {max(link.speed_limit for link in self.links):.0f} km/h

## Vehicle Statistics

### Total Vehicles: {len(self.vehicles)}

"""
        
        for vtype in VehicleTypeEnum:
            count = sum(1 for v in self.vehicles if v.type == vtype)
            if count > 0:
                report += f"- **{vtype.value}:** {count} ({count/len(self.vehicles)*100:.1f}%)\n"
        
        report += f"""
### Temporal Distribution
- **Start Tick Range:** 0 - {max(v.start_tick for v in self.vehicles)}
- **Average Start Tick:** {sum(v.start_tick for v in self.vehicles)/len(self.vehicles):.0f}

## Simulation Configuration

- **Start Tick:** {self.config.start_tick}
- **End Tick:** {self.config.end_tick}
- **Tick Duration:** {self.config.tick_duration} seconds
- **Total Duration:** {self.config.end_tick * self.config.tick_duration / 60:.1f} minutes
- **Random Seed:** {self.config.random_seed}

## Hybrid Configuration

### MICRO Simulation
- **Time Step:** 0.1 seconds
- **Sub-ticks per Global Tick:** 10
- **Car-Following Model:** Krauss (default)

### MESO Simulation
- **Speed Calculation:** Density-based aggregate
- **Flow Model:** Link-level macroscopic

## Files Generated

```
{self.config.output_dir.name}/
├── data/
│   ├── city_map.json          # Graph structure
│   ├── nodes.json              # Node actors
│   ├── links.json              # Link actors
│   ├── vehicles.json           # Vehicle actors
│   └── traffic_signals.json    # Traffic signal actors
├── application.conf            # Simulation configuration
├── scenario_metadata.json      # Machine-readable metadata
└── SCENARIO_REPORT.md          # This report
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

- This is a **hybrid scenario** with both MICRO and MICRO links
- Vehicles will automatically switch between modes when entering different link types
- MICRO links use car-following models (Krauss) for detailed vehicle dynamics
- MESO links use aggregate speed calculations for efficiency
- All vehicles have randomized driver attributes for behavioral diversity

---
*Generated by Hybrid Scenario Generator v1.0*
"""
        
        with open(report_path, 'w') as f:
            f.write(report)
        
        self.log(f"  ✓ Report: {report_path.name}")
    
    def log(self, message: str):
        """Log message if verbose"""
        if self.config.verbose:
            print(message)

# ============================================================================
# COMMAND-LINE INTERFACE
# ============================================================================

def load_config_from_yaml(yaml_path: Path) -> ScenarioConfig:
    """Load scenario configuration from YAML file"""
    with open(yaml_path, 'r') as f:
        data = yaml.safe_load(f)
    
    # Convert vehicle distribution strings to enums
    vehicle_dist = {}
    if 'vehicle_distribution' in data:
        for key, value in data['vehicle_distribution'].items():
            vehicle_dist[VehicleTypeEnum[key.upper()]] = value
        data['vehicle_distribution'] = vehicle_dist
    
    # Convert output_dir to Path
    if 'output_dir' in data:
        data['output_dir'] = Path(data['output_dir'])
    
    return ScenarioConfig(**data)

def interactive_mode() -> ScenarioConfig:
    """Interactive configuration mode"""
    print("\n" + "="*80)
    print("🎯 Interactive Scenario Generator")
    print("="*80 + "\n")
    
    name = input("Scenario name [Hybrid Test]: ").strip() or "Hybrid Test"
    description = input("Description [Test scenario]: ").strip() or "Test scenario"
    output_dir = input("Output directory [./output]: ").strip() or "./output"
    
    print("\n--- Network Configuration ---")
    network_type = input("Network type (grid/random) [grid]: ").strip() or "grid"
    num_nodes = int(input("Number of nodes [9]: ").strip() or "9")
    
    print("\n--- Simulation Configuration ---")
    end_tick = int(input("End tick [3600]: ").strip() or "3600")
    num_vehicles = int(input("Number of vehicles [100]: ").strip() or "100")
    micro_ratio = float(input("MICRO link ratio (0.0-1.0) [0.3]: ").strip() or "0.3")
    
    return ScenarioConfig(
        name=name,
        description=description,
        output_dir=Path(output_dir),
        network_type=network_type,
        num_nodes=num_nodes,
        end_tick=end_tick,
        num_vehicles=num_vehicles,
        micro_link_ratio=micro_ratio,
        verbose=True
    )

def main():
    parser = argparse.ArgumentParser(
        description="Generate hybrid simulation scenarios for Hyperbolic Time Chamber",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Interactive mode
  python generate_hybrid_scenario.py --interactive
  
  # From YAML config
  python generate_hybrid_scenario.py --config scenario.yaml
  
  # Quick test scenario
  python generate_hybrid_scenario.py --quick-test
  
  # Custom grid scenario
  python generate_hybrid_scenario.py --name "My Scenario" --nodes 16 --vehicles 200 --output ./my_scenario
        """
    )
    
    parser.add_argument('--config', type=Path, help='YAML configuration file')
    parser.add_argument('--interactive', action='store_true', help='Interactive mode')
    parser.add_argument('--quick-test', action='store_true', help='Generate quick test scenario')
    
    # Quick configuration options
    parser.add_argument('--name', help='Scenario name')
    parser.add_argument('--output', type=Path, help='Output directory')
    parser.add_argument('--nodes', type=int, help='Number of nodes')
    parser.add_argument('--vehicles', type=int, help='Number of vehicles')
    parser.add_argument('--micro-ratio', type=float, help='MICRO link ratio (0.0-1.0)')
    parser.add_argument('--network-type', choices=['grid', 'random'], help='Network topology type')
    parser.add_argument('--seed', type=int, help='Random seed')
    
    args = parser.parse_args()
    
    # Determine configuration source
    if args.config:
        config = load_config_from_yaml(args.config)
    elif args.interactive:
        config = interactive_mode()
    elif args.quick_test:
        config = ScenarioConfig(
            name="Quick Test",
            description="Quick test scenario for development",
            output_dir=Path("./test_scenario"),
            num_nodes=9,
            num_vehicles=20,
            end_tick=600,
            micro_link_ratio=0.5,
            verbose=True
        )
    else:
        # Build config from command-line args
        config = ScenarioConfig(
            name=args.name or "Hybrid Scenario",
            description="Generated hybrid scenario",
            output_dir=args.output or Path("./output"),
            num_nodes=args.nodes or 9,
            num_vehicles=args.vehicles or 100,
            micro_link_ratio=args.micro_ratio or 0.3,
            network_type=args.network_type or "grid",
            random_seed=args.seed or 42,
            verbose=True
        )
    
    # Generate scenario
    generator = HybridScenarioGenerator(config)
    generator.generate()

if __name__ == "__main__":
    main()
