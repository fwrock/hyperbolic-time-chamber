# Análise de Eventos JSONL - Guia de Uso

Este diretório contém scripts Python para analisar e visualizar eventos gerados pela simulação do Hyperbolic Time Chamber.

## Requisitos

### Instalação das dependências:

```bash
# Dependências básicas (mínimas)
pip install pandas

# Dependências completas (incluindo plots)
pip install pandas numpy matplotlib seaborn
```

## Scripts Disponíveis

### 1. `analyze_events.py` - Análise Geral e Visualizações

Script principal para análise completa com geração automática de gráficos.

#### Uso Básico:

```bash
# Análise simples
python analyze_events.py simulation_events.jsonl

# Com diretório de saída customizado
python analyze_events.py simulation_events.jsonl --output results/

# Sem gráficos (apenas estatísticas)
python analyze_events.py simulation_events.jsonl --no-plots

# Sem exportar CSV
python analyze_events.py simulation_events.jsonl --no-csv
```

#### Saídas Geradas:

**Gráficos (PNG):**
- `01_event_distribution.png` - Top 15 tipos de eventos
- `02_temporal_evolution.png` - Eventos por tick ao longo do tempo
- `03_journey_times.png` - Distribuição de tempos de viagem
- `04_average_speeds.png` - Distribuição de velocidades
- `05_vehicles_by_type.png` - Eventos por tipo de veículo
- `06_signal_waits.png` - Distribuição de atrasos em sinais
- `07_meso_vs_micro.png` - Proporção de eventos MESO vs MICRO

**Dados CSV:**
- `event_types.csv` - Contagem de eventos por tipo
- `journey_times.csv` - Tempos de viagem de cada jornada
- `average_speeds.csv` - Velocidades médias

**Resumo em Consola:**
```
======================================================================
RESUMO DA SIMULAÇÃO
======================================================================

Eventos:
  Total de eventos: 45,231
  Período de simulação: tick 0 - 5000 (5000 ticks)
  Número de atores: 150
  Número de veículos: 50

Eventos por tipo (Top 10):
  enter_link                       12,451 (27.5%)
  leave_link                       12,398 (27.4%)
  ...

[... mais estatísticas ...]
```

### 2. `advanced_events_analysis.py` - Análise Avançada e Filtrada

Script para análises específicas com filtros customizáveis.

#### Exemplos de Uso:

```bash
# Analisar veículo específico
python advanced_events_analysis.py simulation_events.jsonl --vehicle CAR_1

# Filtrar por tipo de veículo
python advanced_events_analysis.py simulation_events.jsonl --vehicle-type bus

# Filtrar por tipo de evento
python advanced_events_analysis.py simulation_events.jsonl --event-type leave_link

# Analisar link específico
python advanced_events_analysis.py simulation_events.jsonl --link LINK_downtown_main_st

# Filtrar por modo de simulação
python advanced_events_analysis.py simulation_events.jsonl --mode MICRO

# Filtrar por período de ticks
python advanced_events_analysis.py simulation_events.jsonl --tick-min 1000 --tick-max 2000

# Combinações de filtros
python advanced_events_analysis.py simulation_events.jsonl \
  --vehicle-type car \
  --mode MESO \
  --tick-min 0 \
  --tick-max 3000

# Top N links
python advanced_events_analysis.py simulation_events.jsonl --links-top 20
```

#### Saídas Geradas:

**Jornada de Veículo (quando usar --vehicle):**
```
======================================================================
JORNADA DE VEÍCULO: CAR_1
======================================================================

Resumo:
  Total de eventos: 156
  Links atravessados: 12
  Distância total: 4523.5 m
  Entradas em links: 12
  Saídas de links: 12
  Esperas em sinais: 3
  Mudanças de modo: 2

Links traversados:
    - LINK_start_main
    - LINK_main_secondary
    ...

Cronologia de eventos (primeiros 20):
   1. Tick    100 - journey_started
   2. Tick    105 - route_planned
   ...
```

**Análise de Links:**
```
======================================================================
ANÁLISE DE LINKS (Top 10)
======================================================================

Link ID                        Entradas    Saídas   MESO  MICRO Velocidade
----... [tabela com links mais usados]
```

**CSV Filtrado:**
- `simulation_events_filtered.csv` - Eventos após aplicação de filtros

## Formato de Eventos JSONL Esperado

Cada linha do arquivo JSONL contém um evento com a seguinte estrutura:

```json
{
  "entityId": "CAR_1",
  "tick": 100,
  "lamportTick": 150,
  "timestamp": 1707000000000000000,
  "data": {
    "event_type": "enter_link",
    "vehicle_type": "car",
    "vehicle_id": "CAR_1",
    "link_id": "LINK_downtown_main_st",
    "mode": "MESO",
    "link_length": 500.0,
    "calculated_speed": 45.5,
    "travel_time": 39.5,
    "tick": 100
  },
  "label": "enter_link"
}
```

### Campos Esperados por Tipo de Evento:

#### `journey_started`
- `vehicle_type`: tipo de veículo
- `vehicle_id`: ID do veículo
- `origin`: nó de origem
- `destination`: nó de destino
- `route_cost`: custo da rota
- `route_length`: número de links na rota

#### `enter_link` / `enter_micro_link`
- `vehicle_type`: tipo de veículo
- `vehicle_id`: ID do veículo
- `link_id`: ID do link
- `mode`: "MESO" ou "MICRO"
- `link_length`: comprimento do link
- `calculated_speed`: velocidade calculada
- `travel_time`: tempo estimado de travessia

#### `leave_link` / `leave_micro_link`
- `vehicle_id`: ID do veículo
- `link_id`: ID do link
- `mode`: modo de simulação
- `total_distance`: distância total percorrida
- `distance_traveled`: distância neste link
- `average_speed`: velocidade média

#### `signal_wait`
- `vehicle_type`: tipo de veículo
- `vehicle_id`: ID do veículo
- `phase`: fase do sinal ("Red", "Green", etc)
- `wait_until_tick`: tick até quando espera

#### `bus_waiting`
- `vehicle_id`: ID do ônibus
- `status`: estado do ônibus
- `passengers_onboard`: número de passageiros
- `capacity`: capacidade total

## Dicas de Uso

### 1. Workflow Típico

```bash
# 1. Análise geral rápida
python analyze_events.py my_simulation.jsonl --output quick_analysis/

# 2. Investigar veículo específico
python advanced_events_analysis.py my_simulation.jsonl --vehicle CAR_1

# 3. Analisar período específico
python advanced_events_analysis.py my_simulation.jsonl --tick-min 1000 --tick-max 2000

# 4. Examinar links congestionados
python advanced_events_analysis.py my_simulation.jsonl --links-top 5
```

### 2. Entender Gráficos

- **event_distribution.png**: Mostra quais eventos ocorrem mais. Se houver muitos "wait" events, há congestionamento.
- **temporal_evolution.png**: Padrões de atividade ao longo do tempo. Picos indicam períodos de alta atividade.
- **journey_times.png**: Distribuição de tempos de viagem. Cauda longa indica congestionamento.
- **meso_vs_micro.png**: Proporção de uso de cada modo. Deve ser equilibrado para simulações híbridas.

### 3. Performance

Para arquivos muito grandes (>1GB):

```bash
# Usar filtros para reduzir dados processados
python advanced_events_analysis.py huge_file.jsonl --tick-max 5000 > results.txt

# Exportar para CSV parcial
python advanced_events_analysis.py huge_file.jsonl --event-type leave_link
```

### 4. Customização

Para análises mais específicas, edite os scripts Python:

- Em `analyze_events.py`: adicione novos gráficos em `create_visualizations()`
- Em `advanced_events_analysis.py`: estenda `FilterConfig` com novos filtros

## Troubleshooting

### "ModuleNotFoundError: No module named 'pandas'"

```bash
pip install pandas numpy matplotlib seaborn
```

### "Arquivo não encontrado"

Certifique-se que:
1. O arquivo JSONL existe
2. O caminho está correto
3. Permissões de leitura estão OK

```bash
ls -la my_events.jsonl
```

### Gráficos vazios ou sem dados

Verifique:
1. O arquivo JSONL tem eventos válidos
2. Os campos esperados estão presentes
3. Não há muitos filtros removendo todos os eventos

```bash
# Ver primeiras linhas
head -5 my_events.jsonl | python -m json.tool
```

### Saída muito grande

Use filtros para reduzir:

```bash
# Apenas últimas 1000 ticks
python analyze_events.py events.jsonl --output analysis/ < eventos

# Apenas um tipo de veículo
python advanced_events_analysis.py events.jsonl --vehicle-type car
```

## Integração com Simulação

Os scripts foram projetados para trabalhar com eventos gerados por:

```scala
// Em qualquer ator:
report(
  data = Map(
    "event_type" -> "my_event",
    "vehicle_id" -> getEntityId,
    "custom_field" -> value,
    "tick" -> currentTick
  ),
  label = "my_event"
)
```

Os dados são automaticamente serializados para JSONL pelos ReportManagers.

## Contribuindo

Para adicionar novos tipos de análise:

1. Estenda `FilterConfig` em `advanced_events_analysis.py`
2. Implemente função `analyze_*` para nova métrica
3. Adicione gráfico em `create_visualizations()`

Exemplo:

```python
# Em analyze_events.py, adicionar novo gráfico
def analyze_congestion(events):
    # Sua lógica aqui
    pass

# Em create_visualizations()
if should_plot_congestion:
    create_congestion_plot()
```

---

**Última atualização:** 2026-02-16
**Versão:** 1.0
