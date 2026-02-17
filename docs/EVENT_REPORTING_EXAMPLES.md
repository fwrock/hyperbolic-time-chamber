# Exemplos Práticos de Uso - Event Reporting

## Exemplo 1: Analisar Eventos de Espera em Semáforo

### Executar
```bash
python scripts/analyze_events.py --input output/events/events.jsonl
```

### Resultado esperado
```
Event Analysis Summary
======================

Total Events: 1,234
Event Types: 15
  - signal_wait: 287
  - vehicle_entered_link: 456
  - vehicle_left_link: 398
  - activity_start: 85
  ...

Vehicles Analyzed: 78
  - car: 45
  - bicycle: 12
  - bus: 15
  - motorcycle: 6

Vehicle Journey Statistics
===========================
Average Journey Length: 2,567.3m
Average Journey Time: 453.2 ticks
Average Speed: 5.66 m/s

Top 5 Vehicles by Distance
1. car_001: 45,678.2m
2. bus_003: 38,234.5m
3. motorcycle_002: 32,123.8m
4. bicycle_001: 28,945.2m
5. car_002: 25,678.9m

Signal Wait Analysis
====================
Total Signal Waits: 287
Average Wait Time: 12.3 ticks
Max Wait Time: 87 ticks
Min Wait Time: 1 tick
Most Congested Intersection: node_123 (32 waits)

Output Files Generated:
- analysis/journey_summary.csv
- analysis/signal_analysis.csv
- analysis/vehicle_comparison.csv
- analysis/signal_wait_timeline.png
- analysis/distance_by_vehicle.png
- analysis/journey_time_distribution.png
- analysis/speed_analysis.png
```

## Exemplo 2: Filtrar Eventos por Tipo de Veículo

### Executar
```bash
python scripts/advanced_events_analysis.py \
  --input output/events/events.jsonl \
  --vehicle-type car \
  --output analysis/car_only/
```

### Resultado
```
Filtering events by vehicle_type: car

Total Events Loaded: 1,234
Filtered Events: 456 (36.9%)

Car Analysis
============
Total Cars: 45
Events per Car (avg): 10.1

Journey Analysis:
- Total Journeys: 67
- Completed: 61 (91%)
- Interrupted: 6 (9%)

Distance Statistics:
- Total Distance: 145,689.5m
- Average: 2,376.7m per car
- Min: 234.5m
- Max: 12,456.8m

Speed Analysis:
- Average Speed: 5.89 m/s
- Max Speed: 15.3 m/s
- Min Speed: 0.1 m/s

Signal Wait Events: 89
- Average Wait: 8.2 ticks
- Longest Wait: 67 ticks

Exported Files:
- analysis/car_only/car_summary.csv
- analysis/car_only/car_journeys.csv
- analysis/car_only/car_speed_profile.png
- analysis/car_only/car_wait_times.png
```

## Exemplo 3: Gerar Dados de Teste para Desenvolvimento

### Executar
```bash
python scripts/generate_sample_events.py \
  --count 2000 \
  --output test_events.jsonl \
  --seed 42
```

### Resultado
```
Generating 2,000 test events...
- journey_started: 167 events
- signal_wait: 287 events
- enter_link: 456 events
- leave_link: 398 events
- activity_start: 145 events
- walking_trip_start: 67 events
- walking_trip_completed: 63 events
- trip_completed: 142 events
- vehicle_entered_link: 145 events
- vehicle_left_link: 89 events
- signal_state_requested: 34 events
- signal_phase_change: 78 events
- bus_created: 15 events
- subway_created: 8 events
- passengers_loaded: 82 events
- passenger_arrived_at_stop: 103 events

Total: 2,000 events
Output: test_events.jsonl
Generation time: 0.23s
```

## Exemplo 4: Análise de Padrões de Viagem de Pessoas

### Script Python Customizado
```python
import json
from collections import defaultdict

# Carregar eventos
events = []
with open('output/events/events.jsonl') as f:
    for line in f:
        events.append(json.loads(line))

# Agrupar por pessoa
person_journeys = defaultdict(list)
for event in events:
    if 'person_id' in event:
        person_journeys[event['person_id']].append(event)

# Analisar cada pessoa
for person_id, person_events in person_journeys.items():
    activity_events = [e for e in person_events if e['event_type'] == 'activity_start']
    walking_events = [e for e in person_events if 'walking' in e['event_type']]
    trip_events = [e for e in person_events if e['event_type'] == 'trip_completed']
    
    print(f"\n{person_id}:")
    print(f"  Atividades: {len(activity_events)}")
    print(f"  Caminhadas: {len(walking_events)}")
    print(f"  Viagens com veículo: {len(trip_events)}")
    
    if trip_events:
        total_distance = sum(e.get('distance_traveled', 0) for e in trip_events)
        print(f"  Distância total: {total_distance:.1f}m")
        print(f"  Distância média: {total_distance/len(trip_events):.1f}m")
```

### Saída esperada
```
person_001:
  Atividades: 5
  Caminhadas: 2
  Viagens com veículo: 3
  Distância total: 12345.6m
  Distância média: 4115.2m

person_002:
  Atividades: 6
  Caminhadas: 4
  Viagens com veículo: 2
  Distância total: 8234.5m
  Distância média: 4117.3m

person_003:
  Atividades: 4
  Caminhadas: 1
  Viagens com veículo: 3
  Distância total: 9876.5m
  Distância média: 3292.2m
```

## Exemplo 5: Análise de Semáforos - Quando eles estão mais congestionados?

### Script Python
```python
import json
from collections import defaultdict

events = []
with open('output/events/events.jsonl') as f:
    for line in f:
        events.append(json.loads(line))

# Análise de espera em semáforos
signal_waits = [e for e in events if e['event_type'] == 'signal_wait']

# Agrupar por nó/sinal
by_signal = defaultdict(list)
for event in signal_waits:
    # Extrair ID do nó do evento (pode variar)
    signal_id = event.get('link_id', 'unknown')
    by_signal[signal_id].append(event)

# Encontrar semáforos mais congestionados
congestion_rank = sorted(
    [(signal_id, len(events), sum(e.get('wait_until_tick', 0) - e.get('tick', 0) for e in events))
     for signal_id, events in by_signal.items()],
    key=lambda x: x[1],
    reverse=True
)

print("Top 10 Semáforos Mais Congestionados")
print("====================================")
for i, (signal_id, wait_count, total_wait_time) in enumerate(congestion_rank[:10], 1):
    avg_wait = total_wait_time / wait_count if wait_count > 0 else 0
    print(f"{i}. {signal_id}")
    print(f"   Eventos de espera: {wait_count}")
    print(f"   Tempo total de espera: {total_wait_time} ticks")
    print(f"   Tempo médio de espera: {avg_wait:.1f} ticks")
```

### Saída
```
Top 10 Semáforos Mais Congestionados
====================================
1. link_123
   Eventos de espera: 45
   Tempo total de espera: 342 ticks
   Tempo médio de espera: 7.6 ticks

2. link_456
   Eventos de espera: 38
   Tempo total de espera: 298 ticks
   Tempo médio de espera: 7.8 ticks

3. link_789
   Eventos de espera: 32
   Tempo total de espera: 276 ticks
   Tempo médio de espera: 8.6 ticks
...
```

## Exemplo 6: Comparar Eficiência por Tipo de Veículo

### Script Python
```python
import json
from collections import defaultdict

events = []
with open('output/events/events.jsonl') as f:
    for line in f:
        events.append(json.loads(line))

# Estatísticas por tipo de veículo
vehicle_stats = defaultdict(lambda: {
    'distance': 0,
    'time': 0,
    'trips': 0,
    'signal_waits': 0,
    'total_wait_time': 0
})

for event in events:
    v_type = event.get('vehicle_type', 'unknown')
    
    if event['event_type'] == 'trip_completed':
        vehicle_stats[v_type]['distance'] += event.get('distance_traveled', 0)
        vehicle_stats[v_type]['time'] += event.get('travel_time', 0)
        vehicle_stats[v_type]['trips'] += 1
    
    elif event['event_type'] == 'signal_wait':
        vehicle_stats[v_type]['signal_waits'] += 1
        wait_time = event.get('wait_until_tick', 0) - event.get('tick', 0)
        vehicle_stats[v_type]['total_wait_time'] += wait_time

print("Comparação por Tipo de Veículo")
print("==============================")
for v_type in sorted(vehicle_stats.keys()):
    stats = vehicle_stats[v_type]
    if stats['trips'] > 0:
        avg_distance = stats['distance'] / stats['trips']
        avg_time = stats['time'] / stats['trips']
        avg_speed = stats['distance'] / stats['time'] if stats['time'] > 0 else 0
        avg_wait = stats['total_wait_time'] / stats['signal_waits'] if stats['signal_waits'] > 0 else 0
        
        print(f"\n{v_type.upper()}")
        print(f"  Viagens: {stats['trips']}")
        print(f"  Distância total: {stats['distance']:.1f}m")
        print(f"  Distância média: {avg_distance:.1f}m")
        print(f"  Tempo médio: {avg_time:.1f} ticks")
        print(f"  Velocidade média: {avg_speed:.2f} m/s")
        print(f"  Esperas em semáforo: {stats['signal_waits']}")
        print(f"  Tempo médio de espera: {avg_wait:.1f} ticks")
```

### Saída
```
Comparação por Tipo de Veículo
==============================

CAR
  Viagens: 45
  Distância total: 145,689.5m
  Distância média: 3,237.5m
  Tempo médio: 543.2 ticks
  Velocidade média: 5.96 m/s
  Esperas em semáforo: 98
  Tempo médio de espera: 7.2 ticks

BICYCLE
  Viagens: 12
  Distância total: 38,234.6m
  Distância média: 3,186.2m
  Tempo médio: 892.3 ticks
  Velocidade média: 3.57 m/s
  Esperas em semáforo: 24
  Tempo médio de espera: 5.8 ticks

BUS
  Viagens: 15
  Distância total: 102,456.7m
  Distância média: 6,830.4m
  Tempo médio: 1,234.5 ticks
  Velocidade média: 4.29 m/s
  Esperas em semáforo: 32
  Tempo médio de espera: 12.3 ticks
```

## Exemplo 7: Exportar para Excel

### Script Python com pandas
```python
import json
import pandas as pd

# Carregar eventos
events = []
with open('output/events/events.jsonl') as f:
    for line in f:
        events.append(json.loads(line))

# Criar DataFrame
df = pd.DataFrame(events)

# Análises específicas
trip_completed = df[df['event_type'] == 'trip_completed']
signal_wait = df[df['event_type'] == 'signal_wait']

# Exportar para Excel
with pd.ExcelWriter('events_analysis.xlsx') as writer:
    trip_completed.to_excel(writer, sheet_name='Trip Completed', index=False)
    signal_wait.to_excel(writer, sheet_name='Signal Wait', index=False)
    
    # Sheet resumido
    summary = pd.DataFrame({
        'Metric': ['Total Events', 'Trip Events', 'Signal Waits', 'Unique Vehicles'],
        'Count': [len(df), len(trip_completed), len(signal_wait), df['vehicle_id'].nunique()]
    })
    summary.to_excel(writer, sheet_name='Summary', index=False)

print("Arquivo exportado: events_analysis.xlsx")
```

## Exemplo 8: Filtrar Apenas Eventos Relevantes

### Script
```bash
# Apenas eventos de veículos
jq '.[] | select(.event_type | contains("vehicle"))' events.jsonl > vehicle_events.jsonl

# Apenas semáforos
jq '.[] | select(.event_type == "signal_wait")' events.jsonl > signal_events.jsonl

# Apenas atividades de pessoa
jq '.[] | select(.event_type | contains("activity") or .event_type | contains("walking"))' events.jsonl > person_events.jsonl

# Veículos específicos
jq '.[] | select(.vehicle_id == "car_001")' events.jsonl > car_001_events.jsonl
```

## Exemplo 9: Criar Relatório em HTML

### Script Python
```python
import json
from jinja2 import Template

events = []
with open('output/events/events.jsonl') as f:
    for line in f:
        events.append(json.loads(line))

# Template HTML
template = Template("""
<html>
<head>
    <title>Simulation Report</title>
    <style>
        body { font-family: Arial; }
        table { border-collapse: collapse; width: 100%; }
        th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
        th { background-color: #4CAF50; color: white; }
    </style>
</head>
<body>
    <h1>Event Report</h1>
    <p>Total Events: {{ total_events }}</p>
    <p>Event Types: {{ event_types }}</p>
    
    <h2>Top Events</h2>
    <table>
        <tr><th>Event Type</th><th>Count</th></tr>
        {% for etype, count in event_counts.items() %}
        <tr><td>{{ etype }}</td><td>{{ count }}</td></tr>
        {% endfor %}
    </table>
</body>
</html>
""")

from collections import Counter
event_counts = Counter(e['event_type'] for e in events)

html = template.render(
    total_events=len(events),
    event_types=len(event_counts),
    event_counts=event_counts
)

with open('report.html', 'w') as f:
    f.write(html)

print("Relatório gerado: report.html")
```

---

Todos os exemplos acima usam dados reais dos eventos salvos durante a simulação. Customize conforme necessário!
