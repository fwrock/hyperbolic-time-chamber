# Migration Script: Mobility → Hybrid Model

Script para migrar cenários do modelo **mobility** (MESO puro) para o modelo **híbrido** (MICRO/MESO).

---

## ⚠️ IMPORTANT UPDATE - v2.0 (Latest)

**Major architectural changes!** See [MIGRATION_UPDATE_SUMMARY.md](MIGRATION_UPDATE_SUMMARY.md) for complete details.

### Key Changes:

🔄 **ALL Links Get MICRO Fields**  
All links now have complete microscopic fields regardless of `simulationMode`. This enables **dynamic runtime switching** between MICRO and MESO modes via API.

🚗 **Vehicle Conversion = Private Vehicles Only**  
Cars can only convert to bicycles or motorcycles. Buses are now **generated as public transport infrastructure**.

🚌 **Public Transport Generation** (NEW)  
- Bus stops at ~15% of nodes
- Subway stations at ~5% of nodes
- Bus routes with multiple buses
- Subway lines with multiple trains
- Complete schedules and capacities

👤 **Person Actor Generation** (NEW)  
Person actors now generated (default: 2x vehicle count) to enable proper **person-centric model testing**.

---

## 📋 Visão Geral

O script `migrate_to_hybrid.py` transforma cenários existentes do modelo antigo (mobility) para o novo modelo híbrido, preservando mapas válidos e adicionando os campos necessários para suporte MICRO/MESO.

### O que o script faz:

✅ **Lê o modelo antigo** (mobility - MESO puro)  
✅ **Migra nós** para `hybrid.actor.Node` com campos híbridos  
✅ **Migra links** para `hybrid.actor.Link` com modo MICRO/MESO (ALL with MICRO fields)  
✅ **Migra veículos privados** para atores híbridos (Car, Bicycle, Motorcycle)  
✅ **Converte tipos de veículos** (opcional - private vehicles only)  
✅ **Gera infraestrutura de transporte público** (bus stops, subway stations)  
✅ **Gera rotas e veículos de transporte público** (buses, trains)  
✅ **Gera atores Person** para testes do modelo person-centric  
✅ **Gera configurações de pistas** para TODOS os links (dynamic switching)  
✅ **Gera sinais de trânsito** (opcional)  
✅ **Divide arquivos grandes** em chunks configuráveis  
✅ **Preserva conectividade** do grafo e IDs originais  
✅ **Gera relatórios** detalhados da migração

## 🚀 Uso Rápido

### 1. Migração Básica

```bash
cd scripts

# Migração simples com configuração padrão
python migrate_to_hybrid.py \
  --input ./input/cenario_1000_viagens \
  --output ./output/hybrid_scenario
```

### 2. Com Arquivo de Configuração YAML

```bash
# Usar configuração pré-definida
python migrate_to_hybrid.py --config example_configs/migration_config.yaml

# Configuração simples (sem conversão de veículos)
python migrate_to_hybrid.py --config example_configs/migration_simple.yaml

# Configuração MICRO intensiva (muitos links MICRO)
python migrate_to_hybrid.py --config example_configs/migration_micro_intensive.yaml
```

### 3. Personalização via Linha de Comando

```bash
# Controlar razão de links MICRO
python migrate_to_hybrid.py \
  --input ./input/cenario_1000_viagens \
  --output ./output/hybrid_30_micro \
  --micro-ratio 0.3

# Converter veículos para diferentes tipos
python migrate_to_hybrid.py \
  --input ./input/cenario_1000_viagens \
  --output ./output/hybrid_mixed_vehicles \
  --convert-vehicles \
  --car-ratio 0.6 \
  --bus-ratio 0.2 \
  --bicycle-ratio 0.1 \
  --motorcycle-ratio 0.1

# Estratégia de seleção de links MICRO
python migrate_to_hybrid.py \
  --input ./input/cenario_1000_viagens \
  --output ./output/hybrid_arterial \
  --micro-ratio 0.4 \
  --micro-strategy arterial  # arterial, highway, random

# Controlar tamanho dos arquivos
python migrate_to_hybrid.py \
  --input ./input/cenario_1000_viagens \
  --output ./output/hybrid_small_files \
  --items-per-file 2000

# Gerar sinais de trânsito
python migrate_to_hybrid.py \
  --input ./input/cenario_1000_viagens \
  --output ./output/hybrid_with_signals \
  --signal-coverage 0.3  # 30% dos nós terão sinais
```

## ⚙️ Configuração

### Arquivo YAML de Configuração

Exemplo completo (`example_configs/migration_config.yaml`):

```yaml
# Diretórios
input_dir: "./input/cenario_1000_viagens"
output_dir: "./output/hybrid_scenario"

# Configuração de links híbridos
micro_link_ratio: 0.3              # 30% links MICRO
micro_selection_strategy: "arterial"  # random, arterial, highway
micro_time_step: 0.1               # segundos
micro_ticks_per_global_tick: 10

# Conversão de tipos de veículos
convert_vehicles: true
vehicle_conversion_ratios:
  car: 0.65        # 65% carros
  bus: 0.15        # 15% ônibus
  bicycle: 0.10    # 10% bicicletas
  motorcycle: 0.10 # 10% motos

# Divisão de arquivos
items_per_file: 5000  # Máximo de itens por arquivo JSON

# Sinais de trânsito
generate_traffic_signals: true
signal_coverage_ratio: 0.25  # 25% dos nós com sinais

# Outras opções
random_seed: 42
preserve_ids: true
verbose: true
```

### Opções de Linha de Comando

```
Obrigatório:
  --input DIR              Diretório de entrada (modelo mobility)
  --output DIR             Diretório de saída (modelo híbrido)
  OU
  --config FILE            Arquivo YAML de configuração

Configuração Híbrida:
  --micro-ratio FLOAT      Razão de links MICRO (0.0-1.0, padrão: 0.3)
  --micro-strategy STR     Estratégia de seleção (random, arterial, highway)

Conversão de Veículos:
  --convert-vehicles       Converter carros em outros tipos
  --car-ratio FLOAT        Razão de carros (padrão: 0.7)
  --bus-ratio FLOAT        Razão de ônibus (padrão: 0.1)
  --bicycle-ratio FLOAT    Razão de bicicletas (padrão: 0.1)
  --motorcycle-ratio FLOAT Razão de motos (padrão: 0.1)

Divisão de Arquivos:
  --items-per-file INT     Máximo de itens por arquivo (padrão: 5000)

Sinais de Trânsito:
  --no-signals             Não gerar sinais de trânsito
  --signal-coverage FLOAT  Razão de nós com sinais (padrão: 0.2)

Outras:
  --seed INT               Seed aleatória (padrão: 42)
  --quiet                  Suprimir saída
```

## 📊 Estrutura de Entrada

O script espera o seguinte formato de entrada (modelo mobility):

```
input/cenario_1000_viagens/
├── data/
│   └── city_map.json          # Grafo da cidade (nós e arestas)
├── nodes_1.json ... nodes_N.json   # Nós (intersecções)
├── links_1.json ... links_N.json   # Links (segmentos de rua)
├── cars_1.json ... cars_N.json     # Veículos (carros)
└── simulation.json                 # Configuração da simulação
```

### Formato dos Arquivos de Entrada

**Nós (nodes_*.json):**
```json
{
  "id": "htcaid:node;1001568643",
  "typeActor": "mobility.actor.Node",
  "data": {
    "dataType": "mobility.entity.state.NodeState",
    "content": {
      "latitude": "-7347433.28816257",
      "longitude": "-2852981.6323715686"
    }
  }
}
```

**Links (links_*.json):**
```json
{
  "id": "htcaid:link;1",
  "typeActor": "mobility.actor.Link",
  "data": {
    "dataType": "model.mobility.entity.state.LinkState",
    "content": {
      "from_node": "htcaid:node;394923340",
      "to_node": "htcaid:node;2033271141",
      "length": 13.54,
      "lanes": 1,
      "freeSpeed": 4.17,
      "capacity": 600.0
    }
  }
}
```

**Carros (cars_*.json):**
```json
{
  "id": "htcaid:car;trip_1",
  "typeActor": "mobility.actor.Car",
  "data": {
    "dataType": "model.mobility.entity.state.CarState",
    "content": {
      "startTick": 154,
      "origin": "htcaid:node;60609822",
      "destination": "htcaid:node;4922987596"
    }
  }
}
```

## 📦 Estrutura de Saída

O script gera a seguinte estrutura (modelo híbrido):

```
output/hybrid_scenario/
├── data/
│   ├── city_map.json                # Grafo migrado
│   ├── nodes_1.json ... nodes_N.json
│   ├── links_1.json ... links_N.json
│   ├── cars_1.json ... cars_N.json
│   ├── buses_1.json ... buses_N.json        (se convert_vehicles=true)
│   ├── bicycles_1.json ... bicycles_N.json  (se convert_vehicles=true)
│   ├── motorcycles_1.json ... motorcycles_N.json (se convert_vehicles=true)
│   └── traffic_signals_1.json               (se generate_traffic_signals=true)
├── simulation.json              # Configuração atualizada
├── scenario_metadata.json       # Metadados da migração
└── MIGRATION_REPORT.md          # Relatório detalhado
```

### Formato dos Arquivos de Saída

**Nós híbridos:**
```json
{
  "id": "htcaid:node;1001568643",
  "typeActor": "hybrid.actor.Node",
  "data": {
    "dataType": "model.hybrid.entity.state.NodeState",
    "content": {
      "latitude": "-7347433.28816257",
      "longitude": "-2852981.6323715686",
      "links": ["htcaid:link;1", "htcaid:link;2"],
      "hasHybridConnections": true,
      "conflictZones": []
    }
  }
}
```

**Links híbridos (MICRO):**
```json
{
  "id": "htcaid:link;1",
  "typeActor": "hybrid.actor.Link",
  "data": {
    "dataType": "model.hybrid.entity.state.LinkState",
    "content": {
      "from": "htcaid:node;394923340",
      "to": "htcaid:node;2033271141",
      "length": 13.54,
      "lanes": 2,
      "speedLimit": 50.0,
      "simulationMode": "MICRO",
      "microTimeStep": 0.1,
      "microTicksPerGlobalTick": 10,
      "laneConfigurations": [
        {"laneId": 0, "type": "normal", "width": 3.5},
        {"laneId": 1, "type": "normal", "width": 3.5}
      ],
      "vehiclesByLane": {}
    }
  }
}
```

**Veículos híbridos:**
```json
{
  "id": "htcaid:car;trip_1",
  "typeActor": "hybrid.actor.Car",
  "data": {
    "dataType": "model.hybrid.entity.state.CarState",
    "content": {
      "startTick": 154,
      "origin": "htcaid:node;60609822",
      "destination": "htcaid:node;4922987596",
      "actorType": "Car",
      "size": 4.5,
      "currentSimulationMode": "MESO",
      "microState": null,
      "driverAttributes": {
        "aggressiveness": 0.65,
        "reactionTimeFactor": 1.05,
        "speedFactor": 1.02,
        "minGapFactor": 0.95
      }
    }
  }
}
```

## 🔄 Processo de Migração

O script executa as seguintes etapas:

### 1. **Carregamento dos Dados de Origem**
- Lê todos os arquivos JSON do diretório de entrada
- Carrega nós, links, veículos, city_map e configuração
- Valida a estrutura dos dados

### 2. **Migração de Nós**
- Atualiza `typeActor`: `mobility.actor.Node` → `hybrid.actor.Node`
- Atualiza `dataType`: `mobility.entity.state.NodeState` → `model.hybrid.entity.state.NodeState`
- Adiciona campos híbridos: `links`, `hasHybridConnections`, `conflictZones`
- Preserva latitude/longitude e outros campos

### 3. **Migração de Links**
- Atualiza `typeActor`: `mobility.actor.Link` → `hybrid.actor.Link`
- Atualiza `dataType`: `model.mobility.entity.state.LinkState` → `model.hybrid.entity.state.LinkState`
- **Seleciona links MICRO** baseado na estratégia configurada
- Gera `laneConfigurations` para links MICRO
- Converte velocidades de m/s para km/h
- Adiciona campos: `simulationMode`, `microTimeStep`, `microTicksPerGlobalTick`

### 4. **Migração de Veículos**
- **Opção A (convert_vehicles=false):** Migra todos os carros para `hybrid.actor.Car`
- **Opção B (convert_vehicles=true):** Converte carros em diferentes tipos (Car, Bus, Bicycle, Motorcycle)
- Adiciona `driverAttributes` com variação aleatória
- Adiciona campos híbridos: `currentSimulationMode`, `microState`
- Adiciona campos específicos por tipo (capacidade de ônibus, preferências de bicicleta, etc.)
- Remove dependências de GPS (não necessário no modelo híbrido)

### 5. **Migração do City Map**
- Atualiza todos os `classType` de nós e links
- Preserva estrutura do grafo (nós e arestas)
- Valida conectividade

### 6. **Escrita dos Arquivos de Saída**
- Divide dados em arquivos conforme `items_per_file`
- Gera `simulation.json` atualizado com novos data sources
- Gera `scenario_metadata.json` com estatísticas
- Gera `MIGRATION_REPORT.md` com relatório detalhado

## 🎯 Estratégias de Seleção de Links MICRO

O script oferece três estratégias para selecionar quais links serão MICRO:

### 1. **Random** (Padrão)
```bash
--micro-strategy random
```
- Seleciona links aleatoriamente
- Distribuição uniforme pela rede
- Bom para testes e validação

### 2. **Arterial**
```bash
--micro-strategy arterial
```
- Prioriza vias arteriais e principais
- Baseado no `linkType` (motorway, trunk, primary, secondary)
- Considera número de pistas
- **Recomendado para cenários urbanos realistas**

### 3. **Highway**
```bash
--micro-strategy highway
```
- Prioriza vias de alta velocidade
- Baseado no `freeSpeed`
- Bom para simulações rodoviárias

## 📈 Exemplos de Uso

### Exemplo 1: Migração Simples (Apenas Adicionar Campos Híbridos)

```bash
python migrate_to_hybrid.py \
  --input ./input/cenario_1000_viagens \
  --output ./output/hybrid_simple \
  --micro-ratio 0.2 \
  --items-per-file 10000 \
  --no-signals
```

**Resultado:**
- 20% dos links são MICRO
- Todos os veículos permanecem carros
- Sem sinais de trânsito
- Arquivos grandes (10k itens cada)

### Exemplo 2: Migração Completa com Diversidade de Veículos

```bash
python migrate_to_hybrid.py \
  --input ./input/cenario_1000_viagens \
  --output ./output/hybrid_complete \
  --micro-ratio 0.3 \
  --micro-strategy arterial \
  --convert-vehicles \
  --car-ratio 0.6 \
  --bus-ratio 0.2 \
  --bicycle-ratio 0.1 \
  --motorcycle-ratio 0.1 \
  --signal-coverage 0.3
```

**Resultado:**
- 30% dos links MICRO (vias arteriais prioritárias)
- 60% carros, 20% ônibus, 10% bicicletas, 10% motos
- 30% dos nós com sinais de trânsito
- Arquivos divididos em 5k itens

### Exemplo 3: Cenário MICRO-Intensivo para Estudo Detalhado

```bash
python migrate_to_hybrid.py \
  --config example_configs/migration_micro_intensive.yaml
```

**Configuração:**
```yaml
micro_link_ratio: 0.6  # 60% MICRO
micro_selection_strategy: "arterial"
convert_vehicles: true
vehicle_conversion_ratios:
  car: 0.6
  bus: 0.2
  bicycle: 0.1
  motorcycle: 0.1
signal_coverage_ratio: 0.4  # 40% dos nós
items_per_file: 2000
```

## 📊 Relatórios Gerados

### 1. MIGRATION_REPORT.md

Relatório detalhado em Markdown com:
- Estatísticas de origem e destino
- Contagem de entidades migradas
- Distribuição de links MICRO/MESO
- Distribuição de tipos de veículos
- Arquivos gerados
- Configurações aplicadas
- Mudanças aplicadas (checklist)
- Instruções de uso

### 2. scenario_metadata.json

Metadados em JSON com:
```json
{
  "name": "hybrid_scenario",
  "migrated": "2026-02-12T10:30:00",
  "source": {
    "directory": "./input/cenario_1000_viagens",
    "model": "mobility (MESO-only)"
  },
  "statistics": {
    "source_nodes": 3000,
    "source_links": 8000,
    "source_vehicles": 1000,
    "micro_links": 2400,
    "meso_links": 5600,
    "vehicles_car": 600,
    "vehicles_bus": 200,
    "vehicles_bicycle": 100,
    "vehicles_motorcycle": 100
  }
}
```

### 3. Saída do Console

Durante a execução, o script imprime:
```
================================================================================
🔄 Mobility → Hybrid Model Migration
================================================================================
Input:  ./input/cenario_1000_viagens
Output: ./output/hybrid_scenario
================================================================================

📖 Step 1/6: Loading source data...
  📂 Loading 5 node files...
  ✓ Loaded 3000 nodes
  📂 Loading 8 link files...
  ✓ Loaded 8000 links
  ...

✅ Migration completed successfully!

📊 Migration Statistics:
  Source Entities:
    • Nodes:    3000
    • Links:    8000
    • Vehicles: 1000
  
  Migrated Entities:
    • Nodes:    3000
    • Links:    8000 (2400 MICRO, 5600 MESO)
    • Vehicles: 1000
      - Car: 600
      - Bus: 200
      - Bicycle: 100
      - Motorcycle: 100
```

## 🔍 Validação

O script valida automaticamente:

✅ **Conectividade do Grafo**
- Verifica se todas as arestas referenciam nós válidos
- Detecta nós isolados
- Identifica becos sem saída (dead ends)

✅ **Integridade das Dependências**
- Valida que todas as dependências de veículos existem
- Atualiza referências de nós e links

✅ **Conversão de Dados**
- Converte velocidades de m/s para km/h
- Gera atributos de motorista com variação realista
- Cria configurações de pistas válidas para links MICRO

## 🐛 Troubleshooting

### Erro: "Input directory does not exist"
```bash
# Verifique o caminho
ls -la ./input/cenario_1000_viagens

# Use caminho absoluto se necessário
python migrate_to_hybrid.py \
  --input /home/dean/PhD/hyperbolic-time-chamber/scripts/input/cenario_1000_viagens \
  --output ./output/hybrid
```

### Aviso: "Graph may not be connected"
- Normal para grafos grandes
- Verifique se o número de arestas é razoável
- O script reporta nós isolados (se houver)

### Memória insuficiente para cenários grandes
```bash
# Reduza items_per_file para processar em chunks menores
python migrate_to_hybrid.py \
  --input ./input/large_scenario \
  --output ./output/hybrid \
  --items-per-file 1000
```

## 🎓 Diferenças entre Modelos

| Aspecto | Modelo Mobility (Antigo) | Modelo Híbrido (Novo) |
|---------|--------------------------|------------------------|
| **Modo de Simulação** | MESO apenas | MICRO + MESO |
| **Atores de Nó** | `mobility.actor.Node` | `hybrid.actor.Node` |
| **Atores de Link** | `mobility.actor.Link` | `hybrid.actor.Link` |
| **Configuração de Pistas** | ❌ Não | ✅ Sim (MICRO links) |
| **Tipos de Veículos** | Car apenas | Car, Bus, Bicycle, Motorcycle |
| **Atributos de Motorista** | ❌ Não | ✅ Sim |
| **Car-Following Models** | ❌ Não | ✅ Sim (Krauss, IDM, etc.) |
| **Mudança de Faixa** | ❌ Não | ✅ Sim (MICRO mode) |
| **Sinais de Trânsito** | Simples | Avançado (fases, conflitos) |

## 📚 Próximos Passos

Após a migração:

1. **Validar o cenário gerado:**
   ```bash
   python validate_scenario.py ./output/hybrid_scenario
   ```

2. **Gerar estatísticas:**
   ```bash
   python scenario_stats.py ./output/hybrid_scenario
   ```

3. **Executar a simulação:**
   ```bash
   export HTC_SIMULATION_DATA_PATH=/path/to/hybrid_scenario
   ./build-and-run.sh
   ```

## 🤝 Contribuindo

Para melhorar o script de migração:
- Adicione novas estratégias de seleção de links MICRO
- Melhore heurísticas de geração de sinais
- Adicione validações extras
- Otimize para cenários muito grandes (>1M veículos)

## 📄 Licença

Parte do projeto Hyperbolic Time Chamber.
