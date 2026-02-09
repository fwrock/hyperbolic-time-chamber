#!/usr/bin/env python3
"""
Script to split a combined vehicles.json file into separate files by vehicle type.

This script reads a vehicles.json file containing mixed vehicle types (cars, buses,
bicycles, motorcycles) and splits them into separate JSON files organized by type.

Usage:
    python split_vehicles.py <input_file> <output_dir>
    
Example:
    python split_vehicles.py data/vehicles.json data/
"""

import json
import sys
import os
from pathlib import Path
from collections import defaultdict


def identify_vehicle_type(vehicle_data):
    """
    Identify the type of vehicle from the actor data.
    
    Args:
        vehicle_data: Dictionary containing vehicle information
        
    Returns:
        str: Vehicle type ('car', 'bus', 'bicycle', 'motorcycle', 'subway', 'unknown')
    """
    # Check typeActor field
    if 'typeActor' in vehicle_data:
        type_actor = vehicle_data['typeActor'].lower()
        if 'car' in type_actor and 'bicycle' not in type_actor:
            return 'car'
        elif 'bus' in type_actor:
            return 'bus'
        elif 'bicycle' in type_actor:
            return 'bicycle'
        elif 'motorcycle' in type_actor:
            return 'motorcycle'
        elif 'subway' in type_actor:
            return 'subway'
    
    # Check data.dataType field
    if 'data' in vehicle_data and 'dataType' in vehicle_data['data']:
        data_type = vehicle_data['data']['dataType'].lower()
        if 'carstate' in data_type:
            return 'car'
        elif 'busstate' in data_type:
            return 'bus'
        elif 'bicyclestate' in data_type:
            return 'bicycle'
        elif 'motorcyclestate' in data_type:
            return 'motorcycle'
        elif 'subwaystate' in data_type:
            return 'subway'
    
    # Check actorType field in content
    if 'data' in vehicle_data and 'content' in vehicle_data['data']:
        content = vehicle_data['data']['content']
        if 'actorType' in content:
            actor_type = content['actorType'].upper()
            if actor_type == 'CAR':
                return 'car'
            elif actor_type == 'BUS':
                return 'bus'
            elif actor_type == 'BICYCLE':
                return 'bicycle'
            elif actor_type == 'MOTORCYCLE':
                return 'motorcycle'
            elif actor_type == 'SUBWAY':
                return 'subway'
    
    # Check ID prefix
    if 'id' in vehicle_data:
        vehicle_id = vehicle_data['id'].lower()
        if 'car' in vehicle_id and 'bicycle' not in vehicle_id:
            return 'car'
        elif 'bus' in vehicle_id:
            return 'bus'
        elif 'bicycle' in vehicle_id or 'bike' in vehicle_id:
            return 'bicycle'
        elif 'motorcycle' in vehicle_id or 'moto' in vehicle_id:
            return 'motorcycle'
        elif 'subway' in vehicle_id or 'train' in vehicle_id:
            return 'subway'
    
    return 'unknown'


def split_vehicles_file(input_file, output_dir):
    """
    Split a combined vehicles.json file into separate files by vehicle type.
    
    Args:
        input_file: Path to the input vehicles.json file
        output_dir: Directory where split files will be saved
    """
    # Read input file
    print(f"Reading: {input_file}")
    with open(input_file, 'r') as f:
        vehicles = json.load(f)
    
    if not isinstance(vehicles, list):
        print(f"Error: Expected a JSON array, got {type(vehicles)}")
        return
    
    print(f"Found {len(vehicles)} vehicles")
    
    # Group vehicles by type
    vehicles_by_type = defaultdict(list)
    for vehicle in vehicles:
        vehicle_type = identify_vehicle_type(vehicle)
        vehicles_by_type[vehicle_type].append(vehicle)
    
    # Create output directory if it doesn't exist
    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)
    
    # Write separate files for each vehicle type
    stats = {}
    for vehicle_type, vehicle_list in vehicles_by_type.items():
        if vehicle_list:
            output_file = output_path / f"{vehicle_type}s.json"
            print(f"Writing {len(vehicle_list)} {vehicle_type}(s) to: {output_file}")
            
            with open(output_file, 'w') as f:
                json.dump(vehicle_list, f, indent=2)
            
            stats[vehicle_type] = len(vehicle_list)
    
    # Print summary
    print("\n" + "="*60)
    print("SUMMARY")
    print("="*60)
    for vehicle_type, count in sorted(stats.items()):
        print(f"  {vehicle_type.capitalize()}s: {count}")
    print(f"  Total: {sum(stats.values())}")
    print("="*60)
    
    # Warn about unknown types
    if 'unknown' in stats:
        print(f"\n⚠️  WARNING: {stats['unknown']} vehicles could not be classified!")
        print("   Check unknowns.json for details")


def update_simulation_json(simulation_file, output_dir):
    """
    Update simulation.json to reference the split vehicle files.
    
    Args:
        simulation_file: Path to simulation.json
        output_dir: Directory containing the split vehicle files
    """
    print(f"\nUpdating simulation configuration: {simulation_file}")
    
    with open(simulation_file, 'r') as f:
        config = json.load(f)
    
    # Find and remove the combined vehicles data source
    data_sources = config.get('simulation', {}).get('actorsDataSources', [])
    new_data_sources = []
    vehicles_removed = False
    
    for source in data_sources:
        if source.get('id') == 'vehicles':
            vehicles_removed = True
            continue
        new_data_sources.append(source)
    
    if not vehicles_removed:
        print("  No 'vehicles' data source found in simulation.json")
        return
    
    # Add separate data sources for each vehicle type
    vehicle_types = [
        ('cars', 'hybrid.actor.Car'),
        ('buses', 'hybrid.actor.Bus'),
        ('bicycles', 'hybrid.actor.Bicycle'),
        ('motorcycles', 'hybrid.actor.Motorcycle'),
        ('subways', 'hybrid.actor.Subway')
    ]
    
    output_path = Path(output_dir)
    for vehicle_id, class_type in vehicle_types:
        vehicle_file = output_path / f"{vehicle_id}.json"
        if vehicle_file.exists():
            new_data_sources.append({
                "id": vehicle_id,
                "classType": class_type,
                "creationType": "LoadBalancedDistributed",
                "dataSource": {
                    "type": "json",
                    "info": {
                        "path": f"data/{vehicle_id}.json"
                    }
                }
            })
            print(f"  Added data source: {vehicle_id}")
    
    # Update the configuration
    config['simulation']['actorsDataSources'] = new_data_sources
    
    # Write backup
    backup_file = simulation_file + '.backup'
    print(f"  Creating backup: {backup_file}")
    with open(backup_file, 'w') as f:
        json.dump(config, f, indent=2)
    
    # Write updated file
    with open(simulation_file, 'w') as f:
        json.dump(config, f, indent=2)
    
    print("  ✓ Updated simulation.json successfully")


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    
    input_file = sys.argv[1]
    output_dir = sys.argv[2]
    
    if not os.path.exists(input_file):
        print(f"Error: Input file not found: {input_file}")
        sys.exit(1)
    
    split_vehicles_file(input_file, output_dir)
    
    # Try to update simulation.json if it exists in the parent directory
    simulation_file = Path(output_dir).parent / 'simulation.json'
    if simulation_file.exists():
        response = input(f"\nUpdate {simulation_file}? [y/N]: ")
        if response.lower() == 'y':
            update_simulation_json(str(simulation_file), output_dir)
    
    print("\n✓ Done!")


if __name__ == '__main__':
    main()
