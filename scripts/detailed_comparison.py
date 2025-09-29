#!/usr/bin/env python3
"""
Script para análise detalhada de diferenças entre simulações supostamente idênticas
"""

import pandas as pd
import json
import sys
from pathlib import Path

# Add the parent directory to Python path
sys.path.append(str(Path(__file__).parent))

from data_sources.cassandra_source import CassandraDataSource

def analyze_detailed_differences(sim1_id, sim2_id, sample_size=1000):
    """
    Análise detalhada das diferenças entre duas simulações
    """
    print(f"🔍 ANÁLISE DETALHADA: {sim1_id} vs {sim2_id}")
    print("=" * 60)
    
    # Load data
    cassandra = CassandraDataSource()
    
    print(f"📊 Carregando dados de {sim1_id}...")
    df1 = cassandra.get_vehicle_flow_data(simulation_id=sim1_id, limit=sample_size)
    
    print(f"📊 Carregando dados de {sim2_id}...")
    df2 = cassandra.get_vehicle_flow_data(simulation_id=sim2_id, limit=sample_size)
    
    if df1.empty or df2.empty:
        print("❌ Erro: Não foi possível carregar dados")
        return
    
    print(f"\n📈 ESTATÍSTICAS BÁSICAS:")
    print(f"  • {sim1_id}: {len(df1)} registros")
    print(f"  • {sim2_id}: {len(df2)} registros")
    
    # Compare basic distributions
    print(f"\n🎯 COMPARAÇÃO DE MÉTRICAS:")
    
    # Calculated Speed
    if 'calculated_speed' in df1.columns and 'calculated_speed' in df2.columns:
        speed1 = df1['calculated_speed'].dropna()
        speed2 = df2['calculated_speed'].dropna()
        
        print(f"📊 Calculated Speed:")
        print(f"  • {sim1_id}: μ={speed1.mean():.6f}, σ={speed1.std():.6f}")
        print(f"  • {sim2_id}: μ={speed2.mean():.6f}, σ={speed2.std():.6f}")
        print(f"  • Diferença em média: {abs(speed1.mean() - speed2.mean()):.6f}")
    
    # Travel Time
    if 'travel_time' in df1.columns and 'travel_time' in df2.columns:
        time1 = df1['travel_time'].dropna()
        time2 = df2['travel_time'].dropna()
        
        print(f"📊 Travel Time:")
        print(f"  • {sim1_id}: μ={time1.mean():.6f}, σ={time1.std():.6f}")
        print(f"  • {sim2_id}: μ={time2.mean():.6f}, σ={time2.std():.6f}")
        print(f"  • Diferença em média: {abs(time1.mean() - time2.mean()):.6f}")
    
    # Event timing analysis
    print(f"\n⏰ ANÁLISE TEMPORAL (baseada em TICK):")
    
    # Group by tick to see if same events happen at same times
    if 'tick' in df1.columns and 'tick' in df2.columns:
        events_by_tick1 = df1.groupby('tick').size()
        events_by_tick2 = df2.groupby('tick').size()
        
        # Find common ticks
        common_ticks = set(events_by_tick1.index) & set(events_by_tick2.index)
        print(f"  • Ticks em comum: {len(common_ticks)}")
        print(f"  • Ticks únicos em {sim1_id}: {len(set(events_by_tick1.index) - common_ticks)}")
        print(f"  • Ticks únicos em {sim2_id}: {len(set(events_by_tick2.index) - common_ticks)}")
        
        # Sample some events at same tick to see detailed differences
        if common_ticks:
            sample_tick = sorted(list(common_ticks))[len(common_ticks)//2]  # Middle tick
            
            events1_at_tick = df1[df1['tick'] == sample_tick]
            events2_at_tick = df2[df2['tick'] == sample_tick]
            
            print(f"\n🔬 EVENTOS NO TICK {sample_tick}:")
            print(f"  • {sim1_id}: {len(events1_at_tick)} eventos")
            print(f"  • {sim2_id}: {len(events2_at_tick)} eventos")
            
            # Compare event types
            if 'event_type' in df1.columns:
                types1 = events1_at_tick['event_type'].value_counts()
                types2 = events2_at_tick['event_type'].value_counts()
                
                print(f"  • Tipos de eventos:")
                for event_type in set(types1.index) | set(types2.index):
                    count1 = types1.get(event_type, 0)
                    count2 = types2.get(event_type, 0)
                    print(f"    - {event_type}: {count1} vs {count2}")
    
    # Vehicle analysis
    print(f"\n🚗 ANÁLISE DE VEÍCULOS:")
    
    if 'car_id' in df1.columns and 'car_id' in df2.columns:
        vehicles1 = set(df1['car_id'].unique())
        vehicles2 = set(df2['car_id'].unique())
        
        common_vehicles = vehicles1 & vehicles2
        unique1 = vehicles1 - vehicles2
        unique2 = vehicles2 - vehicles1
        
        print(f"  • Veículos em comum: {len(common_vehicles)}")
        print(f"  • Únicos em {sim1_id}: {len(unique1)}")
        print(f"  • Únicos em {sim2_id}: {len(unique2)}")
        
        if unique1:
            print(f"  • Primeiros únicos em {sim1_id}: {sorted(list(unique1))[:5]}")
        if unique2:
            print(f"  • Primeiros únicos em {sim2_id}: {sorted(list(unique2))[:5]}")
    
    print(f"\n🎯 POSSÍVEIS CAUSAS DE DIFERENÇAS:")
    print(f"  1. Ordem de processamento de eventos simultâneos")
    print(f"  2. Precisão numérica em cálculos de ponto flutuante")
    print(f"  3. Inicialização de geradores de números aleatórios")
    print(f"  4. Condições de corrida em processamento paralelo")
    print(f"  5. Diferentes versões de bibliotecas ou configurações")

if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Uso: python detailed_comparison.py <sim1_id> <sim2_id>")
        print("Exemplo: python detailed_comparison.py cenario_1000_viagens_1 cenario_1000_viagens_2")
        sys.exit(1)
    
    sim1_id = sys.argv[1]
    sim2_id = sys.argv[2]
    
    analyze_detailed_differences(sim1_id, sim2_id)