#!/usr/bin/env python3
"""
Análise avançada de eventos JSONL - Gerador de relatórios customizáveis.

Este script oferece análises mais profundas e personalizáveis dos eventos,
permitindo filtros e análises específicas por veículo, link, período, etc.

Uso:
    python advanced_events_analysis.py <arquivo.jsonl> [opções]
    python advanced_events_analysis.py <arquivo.jsonl> --vehicle CAR_1
    python advanced_events_analysis.py <arquivo.jsonl> --event-type leave_link
    python advanced_events_analysis.py <arquivo.jsonl> --tick-range 0 1000
"""

import json
import sys
from pathlib import Path
from collections import defaultdict
from typing import Dict, List, Any, Optional, Set
import argparse
from dataclasses import dataclass

try:
    import pandas as pd
    PANDAS_AVAILABLE = True
except ImportError:
    PANDAS_AVAILABLE = False


@dataclass
class FilterConfig:
    """Configuração de filtros para análise."""
    vehicle_id: Optional[str] = None
    vehicle_type: Optional[str] = None
    event_type: Optional[str] = None
    link_id: Optional[str] = None
    tick_min: int = 0
    tick_max: int = float('inf')
    simulation_mode: Optional[str] = None  # MESO or MICRO


def parse_jsonl(filepath: str) -> List[Dict[str, Any]]:
    """Lê arquivo JSONL."""
    events = []
    with open(filepath, 'r') as f:
        for line in f:
            line = line.strip()
            if line:
                try:
                    events.append(json.loads(line))
                except json.JSONDecodeError:
                    continue
    return events


def extract_event_data(event: Dict[str, Any]) -> Dict[str, Any]:
    """Extrai dados do evento."""
    try:
        data = event.get('data', {})
        if isinstance(data, str):
            data = json.loads(data)
        return data
    except:
        return {}


def apply_filters(events: List[Dict[str, Any]], filters: FilterConfig) -> List[Dict[str, Any]]:
    """Aplica filtros aos eventos."""
    filtered = []
    
    for event in events:
        data = extract_event_data(event)
        tick = event.get('tick', 0)
        entity_id = event.get('entityId', '')
        event_type = data.get('event_type', event.get('label', ''))
        
        # Aplicar filtros
        if tick < filters.tick_min or tick > filters.tick_max:
            continue
        
        if filters.vehicle_id and data.get('vehicle_id') != filters.vehicle_id:
            if data.get('car_id') != filters.vehicle_id:
                if data.get('bus_id') != filters.vehicle_id:
                    continue
        
        if filters.vehicle_type and data.get('vehicle_type') != filters.vehicle_type:
            continue
        
        if filters.event_type and event_type != filters.event_type:
            continue
        
        if filters.link_id and data.get('link_id') != filters.link_id:
            continue
        
        if filters.simulation_mode and data.get('mode') != filters.simulation_mode:
            continue
        
        filtered.append(event)
    
    return filtered


def analyze_vehicle_journey(events: List[Dict[str, Any]], vehicle_id: str) -> Dict[str, Any]:
    """Analisa jornada completa de um veículo."""
    journey = {
        'vehicle_id': vehicle_id,
        'events': [],
        'enter_count': 0,
        'leave_count': 0,
        'signal_waits': 0,
        'micro_mode_time': 0,
        'meso_mode_time': 0,
        'total_distance': 0,
        'links_traversed': set(),
        'mode_switches': 0,
        'last_mode': None,
    }
    
    for event in events:
        data = extract_event_data(event)
        tick = event.get('tick', 0)
        event_type = data.get('event_type', event.get('label', ''))
        
        vehicle_check = (
            data.get('vehicle_id') == vehicle_id or
            data.get('car_id') == vehicle_id or
            data.get('bus_id') == vehicle_id
        )
        
        if not vehicle_check:
            continue
        
        journey['events'].append({
            'tick': tick,
            'type': event_type,
            'mode': data.get('mode'),
            'link_id': data.get('link_id'),
            'data': data
        })
        
        if event_type in ['enter_link', 'enter_micro_link']:
            journey['enter_count'] += 1
            if data.get('link_id'):
                journey['links_traversed'].add(data['link_id'])
        
        elif event_type in ['leave_link', 'leave_micro_link']:
            journey['leave_count'] += 1
            journey['total_distance'] += data.get('distance_traveled', data.get('link_length', 0))
        
        elif event_type == 'signal_wait':
            journey['signal_waits'] += 1
        
        # Rastrear mudanças de modo
        current_mode = data.get('mode')
        if current_mode and current_mode != journey['last_mode']:
            if journey['last_mode'] is not None:
                journey['mode_switches'] += 1
            journey['last_mode'] = current_mode
    
    journey['links_traversed'] = list(journey['links_traversed'])
    return journey


def print_vehicle_journey(journey: Dict[str, Any]) -> None:
    """Imprime análise de jornada de veículo."""
    print(f"\n{'='*70}")
    print(f"JORNADA DE VEÍCULO: {journey['vehicle_id']}")
    print(f"{'='*70}")
    
    print(f"\nResumo:")
    print(f"  Total de eventos: {len(journey['events'])}")
    print(f"  Links atravessados: {len(journey['links_traversed'])}")
    print(f"  Distância total: {journey['total_distance']:.1f} m")
    print(f"  Entradas em links: {journey['enter_count']}")
    print(f"  Saídas de links: {journey['leave_count']}")
    print(f"  Esperas em sinais: {journey['signal_waits']}")
    print(f"  Mudanças de modo: {journey['mode_switches']}")
    
    if journey['links_traversed']:
        print(f"\nLinks traversados:")
        for link_id in journey['links_traversed'][:10]:
            print(f"    - {link_id}")
        if len(journey['links_traversed']) > 10:
            print(f"    ... e {len(journey['links_traversed']) - 10} mais")
    
    print(f"\nCronologia de eventos (primeiros 20):")
    for i, evt in enumerate(journey['events'][:20], 1):
        mode_str = f" [{evt['mode']}]" if evt['mode'] else ""
        print(f"  {i:2}. Tick {evt['tick']:6} - {evt['type']:25}{mode_str}")
    
    if len(journey['events']) > 20:
        print(f"  ... ({len(journey['events']) - 20} eventos adicionais)")
    
    print(f"{'='*70}\n")


def analyze_link_usage(events: List[Dict[str, Any]]) -> Dict[str, Dict[str, Any]]:
    """Analisa uso de links."""
    links = defaultdict(lambda: {
        'enter_count': 0,
        'leave_count': 0,
        'meso_entries': 0,
        'micro_entries': 0,
        'vehicles': set(),
        'avg_speed': [],
        'travel_times': [],
    })
    
    for event in events:
        data = extract_event_data(event)
        event_type = data.get('event_type', event.get('label', ''))
        link_id = data.get('link_id')
        
        if not link_id:
            continue
        
        if event_type in ['enter_link', 'enter_micro_link']:
            links[link_id]['enter_count'] += 1
            if data.get('mode') == 'MESO':
                links[link_id]['meso_entries'] += 1
            elif data.get('mode') == 'MICRO':
                links[link_id]['micro_entries'] += 1
            
            vehicle_id = data.get('vehicle_id') or data.get('car_id') or data.get('bus_id')
            if vehicle_id:
                links[link_id]['vehicles'].add(vehicle_id)
        
        elif event_type in ['leave_link', 'leave_micro_link']:
            links[link_id]['leave_count'] += 1
            
            avg_speed = data.get('average_speed')
            if avg_speed:
                links[link_id]['avg_speed'].append(avg_speed)
            
            travel_time = data.get('travel_time_seconds') or data.get('travel_time_ticks')
            if travel_time:
                links[link_id]['travel_times'].append(travel_time)
    
    # Processar médias
    for link_id, link_data in links.items():
        link_data['vehicles'] = len(link_data['vehicles'])
        if link_data['avg_speed']:
            link_data['avg_speed'] = sum(link_data['avg_speed']) / len(link_data['avg_speed'])
        else:
            link_data['avg_speed'] = 0
        
        if link_data['travel_times']:
            link_data['avg_travel_time'] = sum(link_data['travel_times']) / len(link_data['travel_times'])
        else:
            link_data['avg_travel_time'] = 0
    
    return dict(links)


def print_link_analysis(links: Dict[str, Dict[str, Any]], top_n: int = 10) -> None:
    """Imprime análise de links."""
    print(f"\n{'='*70}")
    print(f"ANÁLISE DE LINKS (Top {top_n})")
    print(f"{'='*70}\n")
    
    # Ordenar por mais usados
    sorted_links = sorted(
        links.items(),
        key=lambda x: x[1]['enter_count'],
        reverse=True
    )
    
    print(f"{'Link ID':30} {'Entradas':>10} {'Saídas':>10} {'MESO':>8} {'MICRO':>8} {'Velocidade':>12}")
    print("-" * 88)
    
    for link_id, data in sorted_links[:top_n]:
        meso = data['meso_entries']
        micro = data['micro_entries']
        speed = data['avg_speed']
        entries = data['enter_count']
        exits = data['leave_count']
        print(f"{link_id:30} {entries:10} {exits:10} {meso:8} {micro:8} {speed:12.1f} km/h")
    
    if len(sorted_links) > top_n:
        print(f"... e {len(sorted_links) - top_n} links adicionais\n")
    else:
        print()
    
    print(f"{'='*70}\n")


def print_mode_statistics(events: List[Dict[str, Any]]) -> None:
    """Imprime estatísticas de modo MESO vs MICRO."""
    meso_count = 0
    micro_count = 0
    
    for event in events:
        data = extract_event_data(event)
        if data.get('mode') == 'MESO':
            meso_count += 1
        elif data.get('mode') == 'MICRO':
            micro_count += 1
    
    total = meso_count + micro_count
    
    print(f"\n{'='*70}")
    print(f"ESTATÍSTICAS DE MODO")
    print(f"{'='*70}\n")
    
    if total > 0:
        meso_pct = (meso_count / total) * 100
        micro_pct = (micro_count / total) * 100
        
        print(f"MESO:  {meso_count:>8} eventos ({meso_pct:>5.1f}%)")
        print(f"MICRO: {micro_count:>8} eventos ({micro_pct:>5.1f}%)")
    else:
        print("Sem informações de modo disponíveis")
    
    print(f"\n{'='*70}\n")


def main():
    parser = argparse.ArgumentParser(
        description='Análise avançada de eventos JSONL'
    )
    parser.add_argument('input', help='Arquivo JSONL com eventos')
    parser.add_argument('--vehicle', help='Analisar veículo específico')
    parser.add_argument('--vehicle-type', help='Filtrar por tipo de veículo (car, bus, etc)')
    parser.add_argument('--event-type', help='Filtrar por tipo de evento')
    parser.add_argument('--link', help='Analisar link específico')
    parser.add_argument('--tick-min', type=int, default=0, help='Tick mínimo')
    parser.add_argument('--tick-max', type=int, default=float('inf'), help='Tick máximo')
    parser.add_argument('--mode', choices=['MESO', 'MICRO'], help='Filtrar por modo')
    parser.add_argument('--links-top', type=int, default=10, help='Top N links para exibir')
    
    args = parser.parse_args()
    
    # Carregar eventos
    print(f"📖 Carregando eventos de {args.input}...")
    events = parse_jsonl(args.input)
    print(f"✓ {len(events)} eventos carregados\n")
    
    # Aplicar filtros
    filters = FilterConfig(
        vehicle_id=args.vehicle,
        vehicle_type=args.vehicle_type,
        event_type=args.event_type,
        link_id=args.link,
        tick_min=args.tick_min,
        tick_max=args.tick_max,
        simulation_mode=args.mode,
    )
    
    filtered_events = apply_filters(events, filters)
    print(f"📊 {len(filtered_events)} eventos após filtros\n")
    
    if not filtered_events:
        print("⚠ Nenhum evento correspondente aos filtros\n")
        return
    
    # Análise específica por veículo
    if args.vehicle:
        journey = analyze_vehicle_journey(filtered_events, args.vehicle)
        print_vehicle_journey(journey)
    
    # Análise de modo
    if not args.vehicle:
        print_mode_statistics(filtered_events)
    
    # Análise de links
    if not args.link:
        links = analyze_link_usage(filtered_events)
        print_link_analysis(links, args.links_top)
    
    # Exportar para CSV se pandas disponível
    if PANDAS_AVAILABLE and len(filtered_events) > 0:
        print("💾 Exportando dados filtrados para CSV...")
        
        # Preparar DataFrame
        data_list = []
        for event in filtered_events:
            data = extract_event_data(event)
            data_list.append({
                'tick': event.get('tick'),
                'entity_id': event.get('entityId'),
                'event_type': data.get('event_type', event.get('label')),
                'vehicle_type': data.get('vehicle_type', ''),
                'vehicle_id': data.get('vehicle_id', ''),
                'link_id': data.get('link_id', ''),
                'mode': data.get('mode', ''),
                'data': json.dumps(data),
            })
        
        df = pd.DataFrame(data_list)
        output_file = Path(args.input).stem + '_filtered.csv'
        df.to_csv(output_file, index=False)
        print(f"✓ {output_file}\n")


if __name__ == '__main__':
    main()
