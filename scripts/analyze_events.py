#!/usr/bin/env python3
"""
Análise de eventos JSONL do Hyperbolic Time Chamber.

Este script lê um arquivo JSONL com eventos de simulação e gera análises
estatísticas e visualizações sobre o comportamento da simulação.

Uso:
    python analyze_events.py <arquivo.jsonl> [--output=output_dir]
"""

import json
import sys
import os
from pathlib import Path
from collections import defaultdict, Counter
from dataclasses import dataclass, field
from typing import Dict, List, Any, Optional
import argparse
from datetime import datetime

try:
    import numpy as np
    import pandas as pd
    import matplotlib.pyplot as plt
    import seaborn as sns
    from matplotlib.gridspec import GridSpec
    PLOTTING_AVAILABLE = True
except ImportError:
    PLOTTING_AVAILABLE = False
    print("WARNING: matplotlib, seaborn, pandas, or numpy not available. Plots will not be generated.")
    print("Install with: pip install matplotlib seaborn pandas numpy")


@dataclass
class EventStats:
    """Estatísticas agregadas de eventos."""
    total_events: int = 0
    event_types: Counter = field(default_factory=Counter)
    vehicles: set = field(default_factory=set)
    actors: set = field(default_factory=set)
    ticks: set = field(default_factory=set)
    min_tick: int = float('inf')
    max_tick: int = 0
    
    # Estatísticas por tipo de veículo
    vehicle_events: Dict[str, int] = field(default_factory=lambda: defaultdict(int))
    link_events: Dict[str, int] = field(default_factory=lambda: defaultdict(int))
    signal_waits: int = 0
    micro_mode_events: int = 0
    meso_mode_events: int = 0
    
    # Tempos de viagem
    journey_times: List[int] = field(default_factory=list)
    distances: List[float] = field(default_factory=list)
    average_speeds: List[float] = field(default_factory=list)
    
    # Atrasos em sinais
    signal_wait_times: List[int] = field(default_factory=list)


def parse_jsonl(filepath: str) -> List[Dict[str, Any]]:
    """Lê arquivo JSONL e retorna lista de eventos."""
    events = []
    try:
        with open(filepath, 'r') as f:
            for line_num, line in enumerate(f, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    event = json.loads(line)
                    events.append(event)
                except json.JSONDecodeError as e:
                    print(f"Warning: erro ao parsear linha {line_num}: {e}")
                    continue
        print(f"✓ Carregados {len(events)} eventos de {filepath}")
        return events
    except FileNotFoundError:
        print(f"✗ Arquivo não encontrado: {filepath}")
        sys.exit(1)
    except Exception as e:
        print(f"✗ Erro ao ler arquivo: {e}")
        sys.exit(1)


def extract_event_data(event: Dict[str, Any]) -> Dict[str, Any]:
    """Extrai dados do evento."""
    try:
        # A estrutura pode variar dependendo de como report() serializa
        data = event.get('data', {})
        if isinstance(data, str):
            try:
                data = json.loads(data)
            except:
                data = {}
        return data
    except:
        return {}


def analyze_events(events: List[Dict[str, Any]]) -> EventStats:
    """Analisa eventos e retorna estatísticas."""
    stats = EventStats()
    
    journey_start_times = {}  # vehicle_id -> start_tick
    signal_wait_start = {}    # vehicle_id -> wait_start_tick
    
    for event in events:
        stats.total_events += 1
        
        # Extrair informações básicas
        entity_id = event.get('entityId', 'unknown')
        tick = event.get('tick', 0)
        data = extract_event_data(event)
        event_type = data.get('event_type', event.get('label', 'unknown'))
        
        stats.event_types[event_type] += 1
        stats.actors.add(entity_id)
        stats.ticks.add(tick)
        stats.min_tick = min(stats.min_tick, tick)
        stats.max_tick = max(stats.max_tick, tick)
        
        # Analisar por tipo de evento
        if 'vehicle_type' in data:
            vehicle_type = data['vehicle_type']
            vehicle_id = data.get('vehicle_id', entity_id)
            stats.vehicles.add(vehicle_id)
            stats.vehicle_events[vehicle_type] += 1
        
        # Contabilizar modos de simulação
        if data.get('mode') == 'MICRO':
            stats.micro_mode_events += 1
        elif data.get('mode') == 'MESO':
            stats.meso_mode_events += 1
        
        # Analisar eventos específicos
        if event_type == 'journey_started':
            journey_start_times[entity_id] = tick
        
        elif event_type == 'journey_completed':
            vehicle_id = entity_id
            if vehicle_id in journey_start_times:
                travel_time = tick - journey_start_times[vehicle_id]
                stats.journey_times.append(travel_time)
                del journey_start_times[vehicle_id]
            
            total_distance = data.get('total_distance', 0)
            if total_distance > 0:
                stats.distances.append(total_distance)
                if travel_time > 0:
                    avg_speed = total_distance / (travel_time / 60.0)  # Assumindo tick ~= 1 segundo
                    stats.average_speeds.append(avg_speed)
        
        elif event_type == 'signal_wait':
            stats.signal_waits += 1
            vehicle_id = data.get('vehicle_id', entity_id)
            wait_until_tick = data.get('wait_until_tick', 0)
            wait_time = wait_until_tick - tick
            if wait_time > 0:
                stats.signal_wait_times.append(wait_time)
                signal_wait_start[vehicle_id] = tick
        
        elif event_type in ['enter_link', 'enter_micro_link']:
            link_id = data.get('link_id', 'unknown')
            stats.link_events[link_id] += 1
        
        elif event_type == 'leave_link':
            avg_speed = data.get('average_speed', 0)
            if avg_speed > 0:
                stats.average_speeds.append(avg_speed)
    
    return stats


def print_summary(stats: EventStats) -> None:
    """Imprime resumo das estatísticas."""
    print("\n" + "="*70)
    print("RESUMO DA SIMULAÇÃO")
    print("="*70)
    
    print(f"\nEventos:")
    print(f"  Total de eventos: {stats.total_events:,}")
    print(f"  Período de simulação: tick {stats.min_tick} - {stats.max_tick} ({stats.max_tick - stats.min_tick} ticks)")
    print(f"  Número de atores: {len(stats.actors)}")
    print(f"  Número de veículos: {len(stats.vehicles)}")
    
    print(f"\nEventos por tipo (Top 10):")
    for event_type, count in stats.event_types.most_common(10):
        pct = (count / stats.total_events * 100) if stats.total_events > 0 else 0
        print(f"  {event_type:30} {count:8,} ({pct:5.1f}%)")
    
    print(f"\nModos de simulação:")
    print(f"  MESO: {stats.meso_mode_events:,} eventos")
    print(f"  MICRO: {stats.micro_mode_events:,} eventos")
    
    print(f"\nVeículos por tipo:")
    for vehicle_type, count in sorted(stats.vehicle_events.items()):
        print(f"  {vehicle_type:15} {count:8,} eventos")
    
    print(f"\nEstruturas:")
    print(f"  Links únicos: {len(stats.link_events)}")
    print(f"  Eventos em sinais de trânsito: {stats.signal_waits:,}")
    
    print(f"\nDesempenho de viagens:")
    if stats.journey_times:
        print(f"  Viagens completadas: {len(stats.journey_times)}")
        print(f"  Tempo médio: {np.mean(stats.journey_times):.1f} ticks")
        print(f"  Tempo min/max: {min(stats.journey_times)}/{max(stats.journey_times)} ticks")
    else:
        print(f"  Sem viagens completadas nos eventos")
    
    if stats.distances:
        print(f"  Distância média: {np.mean(stats.distances):.1f} m")
        print(f"  Distância total: {sum(stats.distances):.1f} m")
    
    if stats.average_speeds:
        print(f"  Velocidade média: {np.mean(stats.average_speeds):.1f} km/h")
        print(f"  Velocidade min/max: {min(stats.average_speeds):.1f}/{max(stats.average_speeds):.1f} km/h")
    
    if stats.signal_wait_times:
        print(f"\nAtrasos em sinais:")
        print(f"  Esperas: {len(stats.signal_wait_times)}")
        print(f"  Espera média: {np.mean(stats.signal_wait_times):.1f} ticks")
        print(f"  Espera total: {sum(stats.signal_wait_times):,} ticks")
    
    print("="*70 + "\n")


def create_visualizations(stats: EventStats, output_dir: str, events: List[Dict[str, Any]]) -> None:
    """Cria visualizações dos dados."""
    if not PLOTTING_AVAILABLE:
        print("⚠ Impossível criar visualizações (matplotlib/seaborn não disponíveis)")
        return
    
    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)
    
    print(f"Criando visualizações em {output_dir}...")
    
    # 1. Distribuição de eventos por tipo
    if stats.event_types:
        fig, ax = plt.subplots(figsize=(12, 6))
        event_types = dict(stats.event_types.most_common(15))
        ax.barh(list(event_types.keys()), list(event_types.values()), color='steelblue')
        ax.set_xlabel('Número de eventos')
        ax.set_title('Top 15 Tipos de Eventos')
        ax.invert_yaxis()
        plt.tight_layout()
        plt.savefig(output_path / '01_event_distribution.png', dpi=150)
        plt.close()
        print("  ✓ 01_event_distribution.png")
    
    # 2. Evolução temporal de eventos
    tick_events = defaultdict(int)
    for event in events:
        tick = event.get('tick', 0)
        tick_events[tick] += 1
    
    if tick_events:
        fig, ax = plt.subplots(figsize=(14, 6))
        ticks = sorted(tick_events.keys())
        counts = [tick_events[t] for t in ticks]
        ax.plot(ticks, counts, linewidth=1.5, color='darkblue')
        ax.fill_between(ticks, counts, alpha=0.3, color='steelblue')
        ax.set_xlabel('Tick')
        ax.set_ylabel('Eventos por tick')
        ax.set_title('Evolução Temporal de Eventos')
        ax.grid(True, alpha=0.3)
        plt.tight_layout()
        plt.savefig(output_path / '02_temporal_evolution.png', dpi=150)
        plt.close()
        print("  ✓ 02_temporal_evolution.png")
    
    # 3. Distribuição de tempos de viagem
    if stats.journey_times and len(stats.journey_times) > 1:
        fig, ax = plt.subplots(figsize=(10, 6))
        ax.hist(stats.journey_times, bins=30, color='seagreen', edgecolor='black', alpha=0.7)
        ax.axvline(np.mean(stats.journey_times), color='red', linestyle='--', linewidth=2, label=f'Média: {np.mean(stats.journey_times):.1f} ticks')
        ax.set_xlabel('Tempo de viagem (ticks)')
        ax.set_ylabel('Frequência')
        ax.set_title('Distribuição de Tempos de Viagem')
        ax.legend()
        ax.grid(True, alpha=0.3, axis='y')
        plt.tight_layout()
        plt.savefig(output_path / '03_journey_times.png', dpi=150)
        plt.close()
        print("  ✓ 03_journey_times.png")
    
    # 4. Velocidades médias
    if stats.average_speeds and len(stats.average_speeds) > 1:
        fig, ax = plt.subplots(figsize=(10, 6))
        ax.hist(stats.average_speeds, bins=30, color='coral', edgecolor='black', alpha=0.7)
        ax.axvline(np.mean(stats.average_speeds), color='red', linestyle='--', linewidth=2, label=f'Média: {np.mean(stats.average_speeds):.1f} km/h')
        ax.set_xlabel('Velocidade (km/h)')
        ax.set_ylabel('Frequência')
        ax.set_title('Distribuição de Velocidades Médias')
        ax.legend()
        ax.grid(True, alpha=0.3, axis='y')
        plt.tight_layout()
        plt.savefig(output_path / '04_average_speeds.png', dpi=150)
        plt.close()
        print("  ✓ 04_average_speeds.png")
    
    # 5. Veículos por tipo
    if stats.vehicle_events:
        fig, ax = plt.subplots(figsize=(10, 6))
        vehicle_types = dict(sorted(stats.vehicle_events.items()))
        colors = ['#FF6B6B', '#4ECDC4', '#45B7D1', '#FFA07A', '#98D8C8']
        ax.bar(vehicle_types.keys(), vehicle_types.values(), color=colors[:len(vehicle_types)], edgecolor='black', alpha=0.8)
        ax.set_ylabel('Número de eventos')
        ax.set_title('Eventos por Tipo de Veículo')
        ax.grid(True, alpha=0.3, axis='y')
        plt.xticks(rotation=45, ha='right')
        plt.tight_layout()
        plt.savefig(output_path / '05_vehicles_by_type.png', dpi=150)
        plt.close()
        print("  ✓ 05_vehicles_by_type.png")
    
    # 6. Atrasos em sinais
    if stats.signal_wait_times and len(stats.signal_wait_times) > 1:
        fig, ax = plt.subplots(figsize=(10, 6))
        ax.hist(stats.signal_wait_times, bins=30, color='mediumpurple', edgecolor='black', alpha=0.7)
        ax.axvline(np.mean(stats.signal_wait_times), color='red', linestyle='--', linewidth=2, label=f'Média: {np.mean(stats.signal_wait_times):.1f} ticks')
        ax.set_xlabel('Tempo de espera (ticks)')
        ax.set_ylabel('Frequência')
        ax.set_title('Distribuição de Tempos de Espera em Sinais')
        ax.legend()
        ax.grid(True, alpha=0.3, axis='y')
        plt.tight_layout()
        plt.savefig(output_path / '06_signal_waits.png', dpi=150)
        plt.close()
        print("  ✓ 06_signal_waits.png")
    
    # 7. Modo MESO vs MICRO
    if stats.meso_mode_events + stats.micro_mode_events > 0:
        fig, ax = plt.subplots(figsize=(8, 6))
        modes = ['MESO', 'MICRO']
        counts = [stats.meso_mode_events, stats.micro_mode_events]
        colors = ['#FFD93D', '#6BCB77']
        ax.pie(counts, labels=modes, autopct='%1.1f%%', colors=colors, startangle=90)
        ax.set_title('Distribuição MESO vs MICRO')
        plt.tight_layout()
        plt.savefig(output_path / '07_meso_vs_micro.png', dpi=150)
        plt.close()
        print("  ✓ 07_meso_vs_micro.png")
    
    print(f"✓ Visualizações criadas em {output_dir}\n")


def export_csv(stats: EventStats, output_dir: str, events: List[Dict[str, Any]]) -> None:
    """Exporta dados em CSV."""
    if not PLOTTING_AVAILABLE:
        return
    
    output_path = Path(output_dir)
    
    # Eventos por tipo
    event_types_df = pd.DataFrame(
        stats.event_types.most_common(),
        columns=['event_type', 'count']
    )
    event_types_df['percentage'] = event_types_df['count'] / event_types_df['count'].sum() * 100
    event_types_df.to_csv(output_path / 'event_types.csv', index=False)
    print(f"✓ event_types.csv")
    
    # Tempos de viagem
    if stats.journey_times:
        journey_df = pd.DataFrame({
            'journey_time_ticks': stats.journey_times
        })
        journey_df.to_csv(output_path / 'journey_times.csv', index=False)
        print(f"✓ journey_times.csv")
    
    # Velocidades
    if stats.average_speeds:
        speed_df = pd.DataFrame({
            'average_speed_kmh': stats.average_speeds
        })
        speed_df.to_csv(output_path / 'average_speeds.csv', index=False)
        print(f"✓ average_speeds.csv")


def main():
    parser = argparse.ArgumentParser(
        description='Análise de eventos JSONL do Hyperbolic Time Chamber'
    )
    parser.add_argument('input', help='Arquivo JSONL com eventos')
    parser.add_argument('--output', '-o', default='event_analysis', help='Diretório de saída')
    parser.add_argument('--no-plots', action='store_true', help='Não gerar gráficos')
    parser.add_argument('--no-csv', action='store_true', help='Não exportar CSV')
    
    args = parser.parse_args()
    
    print(f"🔍 Analisando eventos de: {args.input}")
    
    # Carregar eventos
    events = parse_jsonl(args.input)
    
    if not events:
        print("✗ Nenhum evento encontrado")
        sys.exit(1)
    
    # Analisar
    stats = analyze_events(events)
    
    # Imprimir resumo
    print_summary(stats)
    
    # Criar diretório de saída
    output_dir = args.output
    Path(output_dir).mkdir(parents=True, exist_ok=True)
    
    # Gerar visualizações
    if not args.no_plots:
        create_visualizations(stats, output_dir, events)
    
    # Exportar CSV
    if not args.no_csv and PLOTTING_AVAILABLE:
        print("Exportando dados em CSV...")
        export_csv(stats, output_dir, events)
        print()
    
    print(f"✓ Análise completa! Resultados em: {output_dir}\n")


if __name__ == '__main__':
    main()
