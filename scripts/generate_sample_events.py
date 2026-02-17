#!/usr/bin/env python3
"""
Gerar arquivo JSONL com eventos simulados para teste dos scripts de análise.

Este script cria um arquivo JSONL com eventos realistas para que você possa
testar os scripts de análise sem precisar executar a simulação completa.

Uso:
    python generate_sample_events.py [--output=events.jsonl] [--count=1000]
"""

import json
import random
import sys
from pathlib import Path
from datetime import datetime
from typing import Dict, Any
import argparse


class EventGenerator:
    """Gera eventos realistas para teste."""
    
    def __init__(self, seed: int = 42):
        random.seed(seed)
        self.vehicle_types = ['car', 'bus', 'bicycle', 'motorcycle']
        self.vehicles = {}
        self.current_tick = 0
        self.lamport_clock = 0
    
    def _generate_vehicle_id(self, vehicle_type: str, index: int) -> str:
        """Gera ID único para veículo."""
        prefixes = {
            'car': 'CAR',
            'bus': 'BUS',
            'bicycle': 'BIKE',
            'motorcycle': 'MOTO'
        }
        return f"{prefixes[vehicle_type]}_{index:04d}"
    
    def _generate_link_id(self, index: int) -> str:
        """Gera ID único para link."""
        link_names = [
            'downtown_main_st', 'uptown_avenue', 'harbor_boulevard',
            'central_park_way', 'industrial_zone_rd', 'suburban_loop',
            'river_crossing', 'highway_bypass', 'market_street', 'shopping_district'
        ]
        return f"LINK_{link_names[index % len(link_names)]}_{index // len(link_names)}"
    
    def _generate_node_id(self, index: int) -> str:
        """Gera ID único para nó."""
        return f"NODE_{index:05d}"
    
    def generate_journey_started(self, vehicle_type: str, vehicle_id: str) -> Dict[str, Any]:
        """Gera evento journey_started."""
        origin_node = self._generate_node_id(random.randint(1, 100))
        dest_node = self._generate_node_id(random.randint(1, 100))
        
        return {
            'entityId': vehicle_id,
            'tick': self.current_tick,
            'lamportTick': self.lamport_clock,
            'timestamp': int(datetime.now().timestamp() * 1e9),
            'data': {
                'event_type': 'journey_started',
                'vehicle_type': vehicle_type,
                'vehicle_id': vehicle_id,
                'origin': origin_node,
                'destination': dest_node,
                'route_cost': round(random.uniform(100, 5000), 2),
                'route_length': random.randint(5, 30),
                'tick': self.current_tick
            },
            'label': 'journey_started'
        }
    
    def generate_enter_link(self, vehicle_type: str, vehicle_id: str, mode: str = 'MESO') -> Dict[str, Any]:
        """Gera evento enter_link."""
        link_id = self._generate_link_id(random.randint(0, 50))
        link_length = random.choice([250, 500, 1000, 2000])
        free_speed = random.choice([40, 50, 60, 80])
        calculated_speed = free_speed * random.uniform(0.5, 1.0)
        travel_time = link_length / calculated_speed
        
        return {
            'entityId': vehicle_id,
            'tick': self.current_tick,
            'lamportTick': self.lamport_clock,
            'timestamp': int(datetime.now().timestamp() * 1e9),
            'data': {
                'event_type': 'enter_link' if mode == 'MESO' else 'enter_micro_link',
                'vehicle_type': vehicle_type,
                'vehicle_id': vehicle_id,
                'link_id': link_id,
                'mode': mode,
                'link_length': link_length,
                'link_capacity': random.randint(50, 200),
                'cars_in_link': random.randint(0, 50),
                'free_speed': free_speed,
                'calculated_speed': round(calculated_speed, 2),
                'travel_time': round(travel_time, 2),
                'lanes': random.randint(1, 4),
                'tick': self.current_tick
            },
            'label': 'enter_link'
        }
    
    def generate_leave_link(self, vehicle_type: str, vehicle_id: str, mode: str = 'MESO') -> Dict[str, Any]:
        """Gera evento leave_link."""
        link_id = self._generate_link_id(random.randint(0, 50))
        link_length = random.choice([250, 500, 1000, 2000])
        distance_traveled = link_length * random.uniform(0.8, 1.0)
        travel_time = random.uniform(20, 120)
        avg_speed = (distance_traveled / travel_time) * 3.6  # Convert m/s to km/h
        
        return {
            'entityId': vehicle_id,
            'tick': self.current_tick,
            'lamportTick': self.lamport_clock,
            'timestamp': int(datetime.now().timestamp() * 1e9),
            'data': {
                'event_type': 'leave_link' if mode == 'MESO' else 'leave_micro_link',
                'vehicle_id': vehicle_id,
                'link_id': link_id,
                'mode': mode,
                'link_length': link_length,
                'total_distance': round(random.uniform(5000, 50000), 2),
                'distance_traveled': round(distance_traveled, 2),
                'travel_time': round(travel_time, 2),
                'average_speed': round(avg_speed, 2),
                'tick': self.current_tick
            },
            'label': 'leave_link'
        }
    
    def generate_signal_wait(self, vehicle_type: str, vehicle_id: str) -> Dict[str, Any]:
        """Gera evento signal_wait."""
        wait_time = random.randint(5, 60)
        
        return {
            'entityId': vehicle_id,
            'tick': self.current_tick,
            'lamportTick': self.lamport_clock,
            'timestamp': int(datetime.now().timestamp() * 1e9),
            'data': {
                'event_type': 'signal_wait',
                'vehicle_type': vehicle_type,
                'vehicle_id': vehicle_id,
                'phase': 'Red',
                'wait_until_tick': self.current_tick + wait_time,
                'tick': self.current_tick
            },
            'label': 'signal_wait'
        }
    
    def generate_journey_completed(self, vehicle_type: str, vehicle_id: str) -> Dict[str, Any]:
        """Gera evento journey_completed."""
        origin_node = self._generate_node_id(random.randint(1, 100))
        dest_node = self._generate_node_id(random.randint(1, 100))
        reached = random.random() > 0.1  # 90% de sucesso
        
        return {
            'entityId': vehicle_id,
            'tick': self.current_tick,
            'lamportTick': self.lamport_clock,
            'timestamp': int(datetime.now().timestamp() * 1e9),
            'data': {
                'event_type': 'journey_completed',
                'vehicle_id': vehicle_id,
                'origin': origin_node,
                'destination': dest_node,
                'final_node': dest_node if reached else origin_node,
                'reached_destination': reached,
                'completion_reason': 'completed' if reached else 'timeout',
                'total_distance': round(random.uniform(5000, 50000), 2),
                'best_cost': round(random.uniform(100, 5000), 2),
                'tick': self.current_tick
            },
            'label': 'journey_completed'
        }
    
    def generate_bus_waiting(self) -> Dict[str, Any]:
        """Gera evento bus_waiting."""
        bus_id = self._generate_vehicle_id('bus', random.randint(1, 10))
        
        return {
            'entityId': bus_id,
            'tick': self.current_tick,
            'lamportTick': self.lamport_clock,
            'timestamp': int(datetime.now().timestamp() * 1e9),
            'data': {
                'event_type': 'bus_waiting',
                'vehicle_type': 'bus',
                'vehicle_id': bus_id,
                'status': random.choice(['WaitingLoadPassenger', 'WaitingUnloadPassenger']),
                'passengers_onboard': random.randint(0, 50),
                'capacity': 60,
                'tick': self.current_tick
            },
            'label': 'bus_waiting'
        }
    
    def generate_events(self, count: int) -> list:
        """Gera N eventos realistas."""
        events = []
        active_vehicles = {}  # Rastrear veículos ativos
        
        for i in range(count):
            # Simular tempo passando
            if i > 0 and i % 50 == 0:
                self.current_tick += random.randint(1, 5)
            
            self.lamport_clock += random.randint(1, 10)
            
            # Gerar evento aleatório
            event_type = random.choices(
                ['journey_started', 'enter_link', 'leave_link', 'signal_wait', 'journey_completed', 'bus_waiting'],
                weights=[10, 40, 40, 20, 10, 5],
                k=1
            )[0]
            
            if event_type == 'journey_started':
                vehicle_type = random.choice(self.vehicle_types)
                vehicle_id = self._generate_vehicle_id(vehicle_type, random.randint(1, 50))
                active_vehicles[vehicle_id] = vehicle_type
                event = self.generate_journey_started(vehicle_type, vehicle_id)
            
            elif event_type == 'journey_completed':
                if active_vehicles:
                    vehicle_id = random.choice(list(active_vehicles.keys()))
                    vehicle_type = active_vehicles[vehicle_id]
                    event = self.generate_journey_completed(vehicle_type, vehicle_id)
                    del active_vehicles[vehicle_id]
                else:
                    continue
            
            elif event_type == 'bus_waiting':
                event = self.generate_bus_waiting()
            
            else:
                if active_vehicles:
                    vehicle_id = random.choice(list(active_vehicles.keys()))
                    vehicle_type = active_vehicles[vehicle_id]
                    mode = random.choice(['MESO', 'MICRO']) if random.random() > 0.7 else 'MESO'
                    
                    if event_type == 'enter_link':
                        event = self.generate_enter_link(vehicle_type, vehicle_id, mode)
                    elif event_type == 'leave_link':
                        event = self.generate_leave_link(vehicle_type, vehicle_id, mode)
                    elif event_type == 'signal_wait':
                        event = self.generate_signal_wait(vehicle_type, vehicle_id)
                else:
                    continue
            
            events.append(event)
        
        return events


def write_jsonl(events: list, output_file: str) -> None:
    """Escreve eventos em arquivo JSONL."""
    with open(output_file, 'w') as f:
        for event in events:
            f.write(json.dumps(event) + '\n')
    print(f"✓ {len(events)} eventos escritos em {output_file}")


def main():
    parser = argparse.ArgumentParser(description='Gerar arquivo JSONL com eventos de exemplo')
    parser.add_argument('--output', '-o', default='sample_events.jsonl', help='Arquivo de saída')
    parser.add_argument('--count', '-c', type=int, default=1000, help='Número de eventos')
    parser.add_argument('--seed', '-s', type=int, default=42, help='Seed para aleatório')
    
    args = parser.parse_args()
    
    print(f"🔨 Gerando {args.count} eventos de exemplo...")
    generator = EventGenerator(seed=args.seed)
    events = generator.generate_events(args.count)
    
    print(f"💾 Salvando em {args.output}...")
    write_jsonl(events, args.output)
    print(f"\n✅ Arquivo gerado com sucesso!\n")
    print(f"Você pode agora analisar com:")
    print(f"  python analyze_events.py {args.output}")
    print(f"  python advanced_events_analysis.py {args.output} --vehicle-type car")


if __name__ == '__main__':
    main()
