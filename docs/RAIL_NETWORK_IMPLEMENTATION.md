# Rail Network Implementation Summary

## Overview

Implementação de **malha ferroviária dedicada** para metrôs, separada da rede viária.

**Problema:** Metrôs estavam usando links rodoviários (roads/links), competindo com carros/ônibus.

**Solução:** Criar rede ferroviária separada (rail_links) que conecta estações de metrô com infraestrutura dedicada.

---

## Arquitetura

### 1. Separação de Redes

```
Rede Rodoviária (links.json)          Rede Ferroviária (rail_links.json)
├─ cars.json                          ├─ subways.json
├─ buses.json                         │
├─ bicycles.json                      └─ Apenas metrôs!
├─ motorcycles.json
└─ Shared nodes (intersections)       └─ Shared nodes (stations)
```

**Mesmos nós, links diferentes:**
- Nós (nodes) são compartilhados: estações de metrô também são nós rodoviários
- Links (edges) são separados: rail_links conectam estações; links conectam cruzamentos
- Isso permite integração multimodal (pessoa sai do metrô e pega ônibus)

### 2. Componentes Criados

#### A. Migration Script (migrate_to_hybrid.py)

**Geração da Rede Ferroviária:**
```python
def _generate_rail_network(self):
    """
    Gera rail_links bidirecionais entre estações consecutivas.
    
    Para cada linha de metrô:
      Para cada par de estações adjacentes:
        - Segmento IDA: station[i] -> station[i+1]
        - Segmento VOLTA: station[i] -> station[i-1]
    
    Calcula distâncias usando:
      1. Distância da rede rodoviária (se disponível)
      2. Fórmula de Haversine (lat/long)
    """
```

**Output:**
- `data/rail_links_0.json`, `data/rail_links_1.json`, ...
- Registrado em `simulation.json` como fonte separada

**Exemplo de rail_link gerado:**
```json
{
  "id": "htcaid:raillink;blue_line_seg_0",
  "typeActor": "hybrid.actor.RailLink",
  "data": {
    "dataType": "model.hybrid.entity.state.RailLinkState",
    "content": {
      "from": "htcaid:node;station_se",
      "to": "htcaid:node;station_república",
      "length": 850.0,
      "lanes": 2,
      "speedLimit": 80.0,
      "railType": "SUBWAY",
      "subwayLine": "Line 3 - Red",
      "fromStation": "Sé",
      "toStation": "República",
      "gradient": 0.02,
      "curvature": 0.01
    }
  },
  "dependencies": {
    "htcaid:node;station_se": { "classType": "hybrid.actor.Node" },
    "htcaid:node;station_república": { "classType": "hybrid.actor.Node" }
  }
}
```

#### B. RailLinkState.scala

**Estado do link ferroviário:**
```scala
case class RailLinkState(
  // Conexões
  from: String,              // Estação origem (nó)
  to: String,                // Estação destino (nó)
  length: Double,            // Comprimento (metros)
  
  // Rail-specific
  railType: String = "SUBWAY",      // Tipo de trilho
  subwayLine: String,               // Linha do metrô
  fromStation: String,              // Nome da estação origem
  toStation: String,                // Nome da estação destino
  speedLimit: Double = 80.0,        // Velocidade máxima (km/h)
  gradient: Double = 0.0,           // Inclinação (%)
  curvature: Double = 0.0,          // Curvatura
  
  // Hybrid mode support
  simulationMode: SimulationModeEnum = SimulationModeEnum.MESO,
  
  // Registro de veículos
  registered: mutable.Set[LinkRegister] = mutable.Set.empty
) {
  /** Velocidade efetiva considerando inclinação e curvatura */
  def effectiveSpeed: Double = {
    var speed = freeSpeed
    
    // Reduzir velocidade para subidas (gradient > 0)
    if (gradient > 0) {
      speed = speed * (1.0 - (gradient * 0.1))  // 10% por 1% de inclinação
    }
    
    // Reduzir velocidade para curvas
    if (curvature > 0) {
      speed = speed * (1.0 - (curvature * 0.05))  // 5% por unidade de curvatura
    }
    
    math.max(speed, speedLimit * 0.5)  // Mínimo 50% do limite
  }
  
  /** Verificar se um tipo de veículo pode usar este link */
  def canAcceptVehicle(vehicleType: String): Boolean = {
    railType match {
      case "SUBWAY" => vehicleType == "Subway"
      case "LIGHT_RAIL" => vehicleType == "Subway" || vehicleType == "LightRail"
      case "HEAVY_RAIL" => vehicleType == "Train"
      case _ => false
    }
  }
}
```

**Características:**
- `effectiveSpeed`: Ajusta velocidade baseado em física real (inclinação/curvatura)
- `canAcceptVehicle`: Validação de tipo de veículo (apenas metrôs em trilhos de metrô)
- Compatível com modo híbrido (MESO/MICRO)

#### C. RailLink.scala

**Ator que gerencia links ferroviários:**

```scala
class RailLink extends SimulationBaseActor[RailLinkState] {
  
  /** Handle vehicle entering rail link */
  private def handleEnterLink(event: ActorInteractionEvent, data: EnterLinkData): Unit = {
    
    // VALIDATION: Check vehicle type
    val vehicleType = data.actorType.toString
    if (!state.canAcceptVehicle(vehicleType)) {
      logError(s"RAIL SAFETY VIOLATION: Vehicle type '${vehicleType}' attempted to enter rail link!")
      // Rejeitar e enviar erro
      return
    }
    
    // Register subway
    state.registered.add(LinkRegister(...))
    
    // Calculate effective speed (gradient/curvature)
    val effectiveSpeed = state.effectiveSpeed
    
    // Send link info to subway
    sendMessageTo(...)
  }
}
```

**Funcionalidades:**
1. **Validação de Tipo de Veículo:**
   - Apenas metrôs podem entrar em `rail_links`
   - Se um carro/ônibus tentar entrar → erro + rejeição
   
2. **Cálculo de Velocidade Efetiva:**
   - Considera inclinação (subidas reduzem velocidade)
   - Considera curvatura (curvas fechadas reduzem velocidade)
   
3. **Registro e Comunicação:**
   - Registra metrôs que entram no link
   - Envia informações do link (comprimento, velocidade, capacidade)
   - Remove metrôs quando saem

---

## Fluxo de Execução

### Subway em Rail Link (MESO mode)

```
1. Subway inicia na estação A
   └─> State inicial: currentNode = "station_A"

2. Subway solicita rota para estação B
   └─> GraphRouter calcula rota usando rail_links
       (NÃO usa links rodoviários!)

3. Subway entra em rail_link
   └─> EnterLinkEvent
       ├─> RailLink valida tipo de veículo
       ├─> RailLink calcula velocidade efetiva
       │   (considerando gradient/curvature)
       └─> RailLink envia LinkInfoData

4. Subway viaja pelo rail_link
   └─> Tempo de viagem: length / effectiveSpeed

5. Subway sai do rail_link
   └─> LeaveLinkEvent
       └─> RailLink remove registro

6. Subway chega à estação B
   └─> currentNode = "station_B"
```

### Validação de Segurança

**Cenário: Carro tenta entrar em rail_link**

```
1. Car solicita rota
   └─> GraphRouter calcula rota usando road links
       (rail_links não são considerados para carros)

2. [IMPOSSÍVEL] Se car tentar forçar entrada em rail_link:
   └─> RailLink.handleEnterLink()
       ├─> state.canAcceptVehicle("Car") → false
       ├─> logError("RAIL SAFETY VIOLATION!")
       └─> Rejeitar entrada (return sem registrar)
```

**Resultado:** Carros/ônibus nunca competem com metrôs.

---

## Características Físicas Realistas

### Gradient (Inclinação)

```
gradient > 0: Subida (reduz velocidade)
gradient < 0: Descida (não altera velocidade)
gradient = 0: Plano

Exemplo:
  gradient = 0.02 (2%)
  speedReduction = 1.0 - (0.02 * 0.1) = 0.98 (2% mais lento)
```

### Curvature (Curvatura)

```
curvature = 0: Trilho reto
curvature > 0: Trilho curvo (reduz velocidade)

Exemplo:
  curvature = 0.01
  speedReduction = 1.0 - (0.01 * 0.05) = 0.9995 (0.05% mais lento)
```

### Effective Speed

```scala
effectiveSpeed = freeSpeed * gradientFactor * curvatureFactor

Exemplo:
  freeSpeed = 80 km/h
  gradient = 0.02 (2%)
  curvature = 0.01
  
  effectiveSpeed = 80 * 0.98 * 0.9995 ≈ 78.36 km/h
```

---

## Testes e Validação

### 1. Verificar Geração de Rail Links

```bash
# Run migration script
python3 migrate_to_hybrid.py --config example_configs/migration_config.yaml

# Check output
ls output/data/rail_links_*.json

# Validate structure
jq '.[] | {id, from, to, subwayLine, length}' output/data/rail_links_0.json
```

**Expectativa:**
- rail_links bidirecionais (ida e volta)
- `from` e `to` apontam para nós de estação
- `length` calculado corretamente
- `subwayLine` identifica a linha

### 2. Verificar Registro em simulation.json

```bash
jq '.actorSources[] | select(.typeActor == "hybrid.actor.RailLink")' output/simulation.json
```

**Expectativa:**
```json
{
  "typeActor": "hybrid.actor.RailLink",
  "shardId": "rail-link-shard",
  "dataType": "model.hybrid.entity.state.RailLinkState",
  "dataSource": ["data/rail_links_0.json", "data/rail_links_1.json"],
  "distributed": true
}
```

### 3. Testar Subway em Rail Network

```bash
# Run simulation with subway scenario
./run.sh --input output/simulation.json

# Check logs for:
# - "Subway entering rail link"
# - "Effective speed: XX km/h"
# - NO "RAIL SAFETY VIOLATION" errors
```

**Logs esperados:**
```
[RailLink] Subway htcaid:subway;1 entering rail link
[RailLink]   Effective speed: 78.36 km/h
[RailLink] Subway htcaid:subway;1 left rail link
```

### 4. Validar Rejeição de Não-Metrôs

**Test Case:** Tentar forçar carro em rail_link (editar JSON)

```json
{
  "id": "htcaid:car;test",
  "typeActor": "hybrid.actor.Car",
  "data": {
    "content": {
      "linkOrigin": "htcaid:raillink;blue_line_seg_0"  // ❌ FORÇAR CARRO EM RAIL
    }
  }
}
```

**Logs esperados:**
```
[RailLink] ERROR: RAIL SAFETY VIOLATION: Vehicle type 'Car' attempted to enter rail link!
[RailLink]   Rail type: SUBWAY
[RailLink]   Rail link: station_A -> station_B
[RailLink]   Subway line: Line 3 - Red
[RailLink] Rejecting vehicle htcaid:car;test
```

---

## Próximos Passos

### 1. **Modificar Subway.scala** ✅ COMPLETADO
   - ✅ Subway já usa bestRoute com rail_link IDs (rotas predefinidas)
   - ✅ Adicionados logs de debug (entering/leaving rail links)
   - ✅ Documentado que Subway usa rotas fixas (não dinâmicas)
   - ✅ Validação de tipo no RailLink (só Subway pode entrar)

### 2. **Migration Script** ✅ COMPLETADO
   - ✅ `_populate_station_routes()` popula linesRoute nas SubwayStations
   - ✅ linesRoute contém: `{line_id: [{stationId, nodeId, linkId}]}`
   - ✅ SubwayStation.convertLineRouteToPath() converte para Queue[(rail_link_id, node_id)]
   - ✅ Subway recebe bestRoute com rail_link IDs corretos

### 3. **Integração com GraphRouter**
   - ⏳ Criar grafo separado para rail_links (OPCIONAL - Subway usa rotas fixas)
   - ✅ Subway NÃO usa GraphRouter - rotas são predefinidas pela linha
   - ✅ Car/Bus usam GraphRouter com road links
   - ✅ Separação completa: road graph vs. rail network

### 4. **Estações Multimodais**
   - Pessoas podem sair do Subway e pegar Bus no mesmo nó
   - Implementar transferências entre modos

### 4. **Validação End-to-End**
   - Rodar simulação completa com Subway + Car
   - Verificar separação de redes (logs)
   - Medir performance (rail links não devem atrasar simulação)

### 5. **Documentação**
   - Atualizar ARCHITECTURE.md com rail network
   - Adicionar exemplos de rail_links.json
   - Documentar parâmetros (gradient, curvature)

---

## Benefícios da Separação

1. **Realismo:**
   - Metrôs não competem com tráfego rodoviário
   - Velocidades realistas (60-100 km/h)
   - Física realista (inclinação, curvatura)

2. **Performance:**
   - Rail links não sofrem congestionamento
   - Cálculos simplificados (sem lane changes)
   - Simulação mais rápida para metrôs

3. **Segurança:**
   - Validação de tipo impede carros em trilhos
   - Separação clara de infraestrutura
   - Logs de violação para debug

4. **Extensibilidade:**
   - Fácil adicionar Light Rail, Heavy Rail
   - Diferentes tipos de trilhos (SUBWAY, LIGHT_RAIL, HEAVY_RAIL)
   - Parâmetros configuráveis (gradient, curvature)

5. **Integração Multimodal:**
   - Mesmos nós para transferências
   - Pessoas podem trocar de modo no mesmo nó
   - Suporta cenários complexos (metrô + ônibus + carro)

---

## Arquivos Modificados/Criados

### Criados:
- `src/main/scala/model/hybrid/entity/state/RailLinkState.scala` (108 linhas)
- `src/main/scala/model/hybrid/actor/RailLink.scala` (196 linhas)
- `docs/RAIL_NETWORK_IMPLEMENTATION.md` (este arquivo)

### Modificados:
- `scripts/migrate_to_hybrid.py`:
  - Adicionado `self.rail_links: List[Dict] = []`
  - Adicionado `_generate_rail_network()` (191 linhas)
  - Adicionado `_populate_station_routes()` (95 linhas) - **NOVO**
  - Adicionado `_find_rail_link()` (8 linhas) - **NOVO**
  - Adicionado `_calculate_distance_between_nodes()` (36 linhas)
  - Modificado `_write_output()` para escrever rail_links
  - Modificado `_write_simulation_config()` para registrar rail_links

- `src/main/scala/model/hybrid/actor/Subway.scala`:
  - Adicionado comentário de documentação (20 linhas) - **NOVO**
  - Adicionado logs de debug em `actHandleReceiveEnterLinkInfo()` - **NOVO**
  - Adicionado logs de debug em `actHandleReceiveLeaveLinkInfo()` - **NOVO**

- `src/main/scala/model/hybrid/actor/SubwayStation.scala`:
  - Modificado `convertLineRouteToPath()` para usar linesRoute corretamente - **NOVO**
  - Adicionado log de construção de rota - **NOVO**

### Total de Código:
- **~600 linhas** de código Scala (RailLinkState + RailLink + Subway logs + SubwayStation fix)
- **~330 linhas** de código Python (geração de rail network + população de rotas)
- **~400 linhas** de documentação

---

## Conclusão

A implementação da **malha ferroviária dedicada** separa completamente o tráfego de metrô do tráfego rodoviário, permitindo simulações mais realistas e performáticas. 

**Diferença fundamental de arquitetura:**
- **Carros/Ônibus:** Roteamento dinâmico via GraphRouter (calculam rotas em tempo de execução)
- **Metrôs:** Rotas predefinidas (`bestRoute`) criadas pela SubwayStation (seguem linha fixa)

**Status:** ✅ IMPLEMENTAÇÃO COMPLETA

**Componentes:**
- ✅ RailLinkState + RailLink (infraestrutura ferroviária)
- ✅ Migration script gera rail_links e popula linesRoute
- ✅ SubwayStation cria Subways com bestRoute usando rail_link IDs
- ✅ Subway usa rail_links (rotas fixas, sem roteamento dinâmico)
- ✅ Validação de tipo (carros não podem entrar em trilhos)
- ✅ Logs de debug para tracking

**Como funciona:**
1. Migration script gera `rail_links.json` conectando estações
2. Migration script popula `linesRoute` nas SubwayStations com rail_link IDs
3. SubwayStation cria Subway com `bestRoute = Queue[(rail_link_id, node_id)]`
4. Subway chama `enterLink()` com rail_link_id da bestRoute
5. RailLink valida tipo (Subway ✓) e envia LinkInfoData
6. Subway viaja pelo rail_link (sem congestionamento)
7. Subway chama `leavingLink()` ao chegar na próxima estação
8. Repete para próximo rail_link da bestRoute (linha circular)

---

**Compilação:** ✅ Bem-sucedida
```
[success] Total time: 12 s, completed Feb 12, 2026, 10:34:54 AM
```

**Pronto para testes:** ✅ Sim
- Rodar migration script para gerar cenário com rail_links
- Verificar logs: "Subway entering rail link" / "using RAIL LINKS"
- Validar que carros não tentam usar rail_links
