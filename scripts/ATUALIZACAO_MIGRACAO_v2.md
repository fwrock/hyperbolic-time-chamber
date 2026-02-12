# 🚀 Atualização do Script de Migração - Versão 2.0

## 📋 Resumo Executivo

Script de migração `migrate_to_hybrid.py` foi **completamente refatorado** baseado nos seus requisitos:

1. ✅ **TODOS os links** agora têm campos MICRO (não apenas os selecionados)
2. ✅ **Remoção de ônibus** da conversão de veículos (apenas veículos privados)
3. ✅ **Geração de infraestrutura** de transporte público (pontos, estações, rotas)
4. ✅ **Geração de ônibus e trens** como atores separados com rotas e horários
5. ✅ **Geração de atores Person** para testar o modelo person-centric

---

## 🎯 Mudanças Principais

### 1. Switching Dinâmico em Runtime 🔄

**Antes:** Apenas links marcados como MICRO tinham campos microscópicos.

**Agora:** **TODOS os links** têm campos MICRO completos:
```json
{
  "id": "htcaid:link;123",
  "typeActor": "hybrid.actor.Link",
  "data": {
    "content": {
      "simulationMode": "MESO",  // ← Comportamento DEFAULT (pode mudar em runtime)
      "laneConfigurations": [...],  // ← SEMPRE presente
      "vehiclesByLane": {},          // ← SEMPRE presente
      "microTimeStep": 0.1,          // ← SEMPRE presente
      "microTicksPerGlobalTick": 10  // ← SEMPRE presente
    }
  }
}
```

**Benefício:** Via API, você pode mudar `simulationMode` de qualquer link entre MICRO↔MESO **em tempo real** sem recarregar o cenário!

### 2. Veículos Privados vs. Transporte Público 🚗🚌

**Antes:** Carros podiam ser convertidos em ônibus.

**Agora:** Separação clara:

#### Veículos Privados (conversão de carros)
```yaml
vehicle_conversion_ratios:
  car: 0.80       # 80% continuam carros
  bicycle: 0.10   # 10% viram bicicletas
  motorcycle: 0.10  # 10% viram motos
  # Sem ônibus!
```

#### Transporte Público (gerado separadamente)
```yaml
public_transport:
  generate: true
  
  bus:
    stop_coverage: 0.15     # 15% dos nós viram pontos de ônibus
    num_routes: 5           # 5 rotas de ônibus
    buses_per_route: [3, 8] # 3-8 ônibus por rota
    capacity: 80            # Capacidade por ônibus
    
  subway:
    station_coverage: 0.05   # 5% dos nós viram estações de metrô
    num_lines: 3             # 3 linhas de metrô
    trains_per_line: [2, 5]  # 2-5 trens por linha
    capacity: 200            # Capacidade por trem
```

### 3. Infraestrutura de Transporte Público 🏢

O script agora gera **entidades completas** de transporte público:

#### Pontos de Ônibus
```json
{
  "id": "htcaid:bus_stop;node_123",
  "typeActor": "hybrid.actor.BusStop",
  "data": {
    "content": {
      "nodeId": "htcaid:node;123",
      "routesServed": ["htcaid:bus_route;1", "htcaid:bus_route;3"],
      "waitingPassengers": []
    }
  }
}
```

#### Estações de Metrô
```json
{
  "id": "htcaid:subway_station;node_456",
  "typeActor": "hybrid.actor.SubwayStation",
  "data": {
    "content": {
      "nodeId": "htcaid:node;456",
      "linesServed": ["htcaid:subway_route;1"],
      "platforms": 2
    }
  }
}
```

#### Rotas de Ônibus
```json
{
  "id": "htcaid:bus_route;1",
  "typeActor": "hybrid.actor.BusRoute",
  "data": {
    "content": {
      "label": "Bus Route 1",
      "stops": [
        "htcaid:bus_stop;node_123",
        "htcaid:bus_stop;node_456",
        "htcaid:bus_stop;node_789"
      ],
      "headway": 600  // 10 minutos entre ônibus
    }
  }
}
```

#### Ônibus (atores)
```json
{
  "id": "htcaid:bus;route_1_bus_0",
  "typeActor": "hybrid.actor.Bus",
  "data": {
    "content": {
      "routeId": "htcaid:bus_route;1",
      "capacity": 80,
      "currentPassengers": 0,
      "nextStop": "htcaid:bus_stop;node_123",
      "status": "Start"
    }
  }
}
```

### 4. Atores Person 👤

**NOVO!** Geração de atores Person para testar o modelo person-centric:

```json
{
  "id": "htcaid:person;person_0",
  "typeActor": "hybrid.actor.Person",
  "data": {
    "content": {
      "origin": "htcaid:node;123",
      "destination": "htcaid:node;789",
      "preferredModality": "BUS",  // ou CAR, SUBWAY, BICYCLE
      "currentMode": null,
      "status": "Start"
    }
  }
}
```

**Configuração:**
```yaml
person_generation:
  generate: true
  persons_per_vehicle: 2.0  # 2 pessoas por viagem de veículo
  
  modality_preferences:
    car: 0.4      # 40% preferem carro
    bus: 0.3      # 30% preferem ônibus
    subway: 0.2   # 20% preferem metrô
    bicycle: 0.1  # 10% preferem bicicleta
```

---

## 📊 Exemplo: cenario_1000_viagens

### Entrada (Mobility Model)
```
✅ 4,544 nós
✅ 7,072 links (todos MESO)
✅ 1,000 carros
❌ Sem infraestrutura de transporte público
❌ Sem pessoas
```

### Saída (Hybrid Model) - Config Padrão
```
✅ 4,544 nós
✅ 7,072 links (TODOS com campos MICRO, 30% começam em MICRO mode)
✅ 1,000 veículos privados:
   • 800 carros
   • 100 bicicletas
   • 100 motos
   
✅ Infraestrutura de transporte público:
   • ~680 pontos de ônibus (15% dos nós)
   • ~227 estações de metrô (5% dos nós)
   
✅ Sistema de transporte público:
   • 5 rotas de ônibus
   • ~25 ônibus atores
   • 2 linhas de metrô
   • ~6 trens atores
   
✅ ~2,000 atores Person (2x veículos)

✅ Opcional: ~1,136 sinais de trânsito (25% dos nós)
```

---

## 🚀 Como Usar

### Migração Básica
```bash
cd scripts

python3 migrate_to_hybrid.py \
  --input ../simulations/input/cenario_1000_viagens \
  --output ../simulations/output/hybrid_1000 \
  --micro-ratio 0.3
```

### Migração com Conversão de Veículos
```bash
python3 migrate_to_hybrid.py \
  --input ../simulations/input/cenario_1000_viagens \
  --output ../simulations/output/hybrid_1000 \
  --micro-ratio 0.3 \
  --convert-vehicles \
  --car-ratio 0.8 \
  --bicycle-ratio 0.1 \
  --motorcycle-ratio 0.1
```

### Migração Completa (tudo habilitado)
```bash
python3 migrate_to_hybrid.py \
  --input ../simulations/input/cenario_1000_viagens \
  --output ../simulations/output/hybrid_1000_completo \
  --micro-ratio 0.3 \
  --convert-vehicles \
  --car-ratio 0.8 \
  --bicycle-ratio 0.1 \
  --motorcycle-ratio 0.1 \
  --bus-stop-coverage 0.15 \
  --subway-station-coverage 0.05 \
  --num-bus-routes 5 \
  --num-subway-routes 2 \
  --persons-per-vehicle 2.0 \
  --signal-coverage 0.25 \
  --items-per-file 500
```

### Usando Arquivo de Configuração
```bash
# Ver exemplo de config atualizado
cat example_configs/migration_config.yaml

# Executar
python3 migrate_to_hybrid.py --config example_configs/migration_config.yaml
```

---

## 📁 Arquivos Gerados

```
output/hybrid_scenario/
├── simulation.json              # Config principal com todas as fontes de dados
├── scenario_metadata.json       # Estatísticas do cenário
├── data/
│   ├── nodes_0.json            # Nós (split em arquivos de 500)
│   ├── links_0.json            # Links (TODOS com campos MICRO)
│   ├── vehicles_0.json         # Veículos privados (car, bicycle, motorcycle)
│   │
│   ├── bus_stops_0.json        # 🆕 Pontos de ônibus
│   ├── subway_stations_0.json  # 🆕 Estações de metrô
│   ├── bus_routes_0.json       # 🆕 Rotas de ônibus
│   ├── subway_routes_0.json    # 🆕 Linhas de metrô
│   ├── buses_0.json            # 🆕 Ônibus (atores)
│   ├── subways_0.json          # 🆕 Trens de metrô (atores)
│   └── persons_0.json          # 🆕 Pessoas (atores)
```

---

## 🔧 Parâmetros CLI Atualizados

### Novos Parâmetros
```bash
# Transporte Público
--no-public-transport              # Desabilitar geração de transporte público
--bus-stop-coverage 0.15           # Ratio de nós com pontos de ônibus
--subway-station-coverage 0.05     # Ratio de nós com estações de metrô
--num-bus-routes 5                 # Número de rotas de ônibus
--num-subway-routes 2              # Número de linhas de metrô

# Pessoas
--no-persons                       # Desabilitar geração de pessoas
--persons-per-vehicle 2.0          # Média de pessoas por viagem de veículo
```

### Parâmetros Removidos
```bash
--bus-ratio     # ❌ REMOVIDO - ônibus não são mais convertidos de carros
```

### Parâmetros de Conversão de Veículos (atualizados)
```bash
--convert-vehicles       # Habilitar conversão (privados apenas)
--car-ratio 0.8          # 80% continuam carros
--bicycle-ratio 0.1      # 10% viram bicicletas
--motorcycle-ratio 0.1   # 10% viram motos
# Ratios devem somar 1.0
```

---

## 🎯 Casos de Uso

### 1. Teste de Switching Dinâmico
```bash
# Gerar cenário com todos os links preparados
python3 migrate_to_hybrid.py \
  --input ../simulations/input/cenario_1000_viagens \
  --output ../test_dynamic_switching \
  --micro-ratio 0.2  # Apenas 20% começam MICRO

# Resultado: 80% MESO, mas TODOS prontos para switch via API
```

### 2. Teste de Transporte Público
```bash
# Gerar cenário focado em transporte público
python3 migrate_to_hybrid.py \
  --input ../simulations/input/cenario_1000_viagens \
  --output ../test_public_transport \
  --bus-stop-coverage 0.25 \
  --num-bus-routes 10 \
  --subway-station-coverage 0.10 \
  --num-subway-routes 5

# Resultado: Rede densa de transporte público
```

### 3. Teste Person-Centric
```bash
# Gerar cenário focado em pessoas
python3 migrate_to_hybrid.py \
  --input ../simulations/input/cenario_1000_viagens \
  --output ../test_person_centric \
  --persons-per-vehicle 3.0 \
  --bus-stop-coverage 0.20 \
  --num-bus-routes 8

# Resultado: 3,000 pessoas com acesso a transporte público
```

---

## ✅ Validação do Script

```bash
# Teste rápido
python3 migrate_to_hybrid.py --help

# Validação estrutural
python3 -c "
import migrate_to_hybrid as m
assert hasattr(m, 'HybridMigrator')
assert hasattr(m, 'VehicleTypeEnum')
assert hasattr(m, 'PublicTransportTypeEnum')
assert not hasattr(m.VehicleTypeEnum, 'BUS')
assert hasattr(m.PublicTransportTypeEnum, 'BUS')
print('✅ Script validado!')
"
```

---

## 📚 Documentação

- **[MIGRATION_UPDATE_SUMMARY.md](MIGRATION_UPDATE_SUMMARY.md)** - Resumo completo em inglês (11,000+ palavras)
- **[MIGRATION_GUIDE.md](MIGRATION_GUIDE.md)** - Guia completo de uso (atualizado)
- **[Este arquivo]** - Resumo em português

---

## 🎉 Próximos Passos

1. ✅ **Testar com cenario_1000_viagens**
   ```bash
   python3 migrate_to_hybrid.py \
     --input ../simulations/input/cenario_1000_viagens \
     --output ../simulations/output/hybrid_1000_test \
     --convert-vehicles \
     --items-per-file 500
   ```

2. ✅ **Validar estrutura de saída**
   ```bash
   ls -la ../simulations/output/hybrid_1000_test/data/
   # Verificar presença de todos os novos tipos de entidades
   ```

3. ✅ **Carregar na simulação**
   ```bash
   export HTC_SIMULATION_DATA_PATH=$(pwd)/../simulations/output/hybrid_1000_test
   cd .. && ./build-and-run.sh
   ```

4. ✅ **Desenvolver API de switching dinâmico**
   - API REST para mudar `simulationMode` de links em runtime
   - Endpoint: `PUT /api/links/{linkId}/mode` → `{"mode": "MICRO"}`

---

## 🏆 Benefícios da Atualização

1. **Flexibilidade Total:** Switching MICRO↔MESO em qualquer link, qualquer momento
2. **Transporte Público Realista:** Rotas, horários, capacidades, paradas
3. **Modelo Person-Centric:** Testável com atores Person reais
4. **Separação Clara:** Veículos privados vs. público separados logicamente
5. **Escalabilidade:** Transporte público escala independente de veículos privados
6. **Preparado para Futuro:** Todos os links prontos para features microscópicas

---

**Versão:** 2.0  
**Data:** 2025  
**Linhas de Código:** 1,699 (anteriormente 1,400)  
**Novas Features:** 7+ (infraestrutura pública, pessoas, switching dinâmico)  
**Status:** ✅ Pronto para uso e testes
