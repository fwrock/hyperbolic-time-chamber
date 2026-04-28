# Infraestrutura Viária — `Link`, `Node`, `TrafficSignal`

> Arquivos principais:
> - [src/main/scala/model/hybrid/actor/Link.scala](../src/main/scala/model/hybrid/actor/Link.scala)
> - [src/main/scala/model/hybrid/actor/Node.scala](../src/main/scala/model/hybrid/actor/Node.scala)
> - [src/main/scala/model/hybrid/actor/TrafficSignal.scala](../src/main/scala/model/hybrid/actor/TrafficSignal.scala)

---

## Visão Geral do Grafo Viário

O mapa da cidade é um **grafo dirigido** onde:Dois modos completamente distintos de operação:

MESO: sem TM próprio. Veículo entra → link adiciona ao registered → calcula v = vfree × (1 - n/C) → devolve LinkInfoData com tempo de travessia → veículo sai quando acordar. O link é passivo.
MICRO: TM próprio, acorda a cada tick. Mantém posição/velocidade de cada veículo por faixa (VehicleInLane), executa 10 sub-ticks de car-following Krauss a cada tick global, envia MicroUpdateData a cada sub-tick e MicroLeaveLinkData quando o veículo atinge o fim.
Node — a interseção
Puramente reativo. Três funções:

Armazena referências de BusStop e SubwayStation que se registram ao inicializar.
Mantém o estado atual de cada semáforo (signals), atualizado pelo TrafficSignal.
Responde a RequestSignalStateData dos veículos — se não houver sinal, responde Green imediatamente.
TrafficSignal — o semáforo
Acorda uma vez por ciclo (ex: 90 ticks) — não a cada tick. Calcula currentCycleTick = (tick - start + offset) % cycle, determina Green/Red para cada fase, e notifica os Nodes. Veículos nunca falam diretamente com o semáforo — sempre via Node.

- **`Node`** = vértice (interseção, entroncamento, ponto de interesse)
- **`Link`** = aresta dirigida (trecho de via entre dois nós)
- **`TrafficSignal`** = controlador de semáforo associado a um ou mais nós

```
Node(A) ──[Link(A→B)]──► Node(B) ──[Link(B→C)]──► Node(C)
             │                          │
             │                TrafficSignal(B)
             │                (controla acesso a B→C)
```

Os três atores são **estáticos** — existem durante toda a simulação. Veículos (`Car`, `Bus`,
`Subway`, etc.) são dinâmicos e interagem com eles via mensagens.

---

---

# 1. Link

## 1.1 Responsabilidades

O `Link` representa um segmento de via e é responsável por:

1. **Controle de entrada/saída** de veículos (MESO e MICRO).
2. **Cálculo de velocidade** no modo MESO (modelo BPR-like via `SpeedUtil`).
3. **Simulação microscópica** no modo MICRO: sub-ticks, car-following, gerenciamento de faixas.
4. **Custo dinâmico** para roteamento: publica periodicamente para `DynamicWeightCache` (Kafka).
5. **Métricas** estilo SUMO: resumo por tick de veículos carregados, saídos, tempo de viagem.

## 1.2 Modo MESO vs. MICRO

O campo `simulationMode: SimulationModeEnum` determina o comportamento do link:

| Aspecto | MESO | MICRO |
|---|---|---|
| Controle de tempo | **Sem TM próprio** — veículo controla seu tick | **TM próprio** — link acorda a cada tick |
| Velocidade | `SpeedUtil.linkDensitySpeed` (agregado) | Krauss car-following por sub-tick |
| Veículos rastreados | `registered: Set[LinkRegister]` (só contagem) | `vehiclesByLane: Map[Int, Queue[VehicleInLane]]` (posição, velocidade) |
| Faixas | Não distingue | Múltiplas faixas, cada uma com fila de veículos |
| Comunicação com veículo | `LinkInfoData` ao entrar e ao sair | `MicroEnterLinkData`, `MicroUpdateData`, `MicroLeaveLinkData` |

## 1.3 Estado (`LinkState`)

```scala
case class LinkState(
  // Topologia
  from: String,        // ID do nó de origem
  to: String,          // ID do nó de destino
  length: Double,      // Comprimento (metros)
  lanes: Int,          // Número de faixas

  // Parâmetros de tráfego (MESO)
  speedLimit: Double,  // Limite de velocidade (m/s)
  capacity: Double,    // Capacidade máxima de veículos
  freeSpeed: Double,   // Velocidade em fluxo livre (m/s)
  currentSpeed: Double,       // Velocidade atual calculada
  congestionFactor: Double,   // Fator de congestionamento

  // Modo de simulação
  simulationMode: SimulationModeEnum,  // MESO | MICRO
  registered: mutable.Set[LinkRegister],  // veículos registrados (MESO)

  // MICRO
  microTimeStep: Double,           // Duração do sub-tick (s), padrão 0.1s
  microTicksPerGlobalTick: Int,    // Sub-ticks por tick global, padrão 10
  vehiclesByLane: Map[Int, mutable.Queue[VehicleInLane]],
  laneConfigurations: List[LaneConfig]
)
```

### `LinkRegister` (MESO)
```scala
case class LinkRegister(
  actorId: String,
  shardId: String,
  actorType: ActorTypeEnum,
  actorCreationType: CreationTypeEnum,
  actorSize: Double
)
```

### `VehicleInLane` (MICRO)
```scala
case class VehicleInLane(
  actorId: String,
  shardId: String,
  position: Double,      // metros desde o início do link
  velocity: Double,      // m/s
  acceleration: Double,  // m/s²
  vehicleLength: Double, // metros
  entryTick: Tick
) {
  def rearPosition: Double = position - vehicleLength
  def gapTo(leader: VehicleInLane): Double = leader.rearPosition - this.position
}
```

### `LaneConfig` (MICRO)
```scala
case class LaneConfig(
  laneId: Int,
  laneType: LaneTypeEnum,     // NORMAL, BUS_LANE, BIKE_LANE, ...
  speedLimit: Option[Double], // limite específico da faixa (m/s)
  width: Double = 3.5         // largura (m)
)
```

## 1.4 Modelo de Velocidade MESO (BPR-like)

$$v = v_{free} \times \left(1 - \left(\frac{n}{C}\right)^\beta\right)^\alpha$$

com $\alpha = \beta = 1$ (simplificação linear):

$$v = v_{free} \times \left(1 - \frac{n}{C}\right)$$

onde:
- $v_{free}$ = velocidade em fluxo livre (`freeSpeed`, m/s)
- $n$ = número de veículos no link (`registered.size`)
- $C$ = capacidade do link (`capacity`)
- Mínimo de `1.0 m/s` quando $n \geq C$

```scala
// SpeedUtil.scala
def linkDensitySpeed(length, capacity, numberOfCars, freeSpeed, lanes): Double =
  if (numberOfCars >= capacity) 1.0
  else freeSpeed * pow(1 - pow(numberOfCars / capacity, beta=1), alpha=1)
```

O tempo de travessia no modo MESO é:

$$t = \left\lceil \frac{\text{length}}{v} \right\rceil + 1 \quad \text{(ticks)}$$

## 1.5 Modo MICRO — Sub-ticks e Car-Following

No modo MICRO:

1. O `Link` possui TM próprio — acorda a cada tick global.
2. Cada tick global executa `microTicksPerGlobalTick` sub-ticks de `microTimeStep` segundos.
3. Para cada sub-tick, `MicroSimulationStrategy` (padrão: `DefaultMicroSimulationStrategy`)
   calcula a nova velocidade e posição de cada veículo via modelo de Krauss.
4. Quando um veículo atinge `position >= length`, o link envia proativamente `MicroLeaveLinkData`.

### Período de graça (grace period)

Quando `vehiclesByLane` esvazia, o link permanece agendado no TM por mais `MICRO_GRACE_TICKS = 5`
ticks antes de se desregistrar. Isso evita race condition com veículos do próximo batch que ainda
não chegaram.

## 1.6 Custo Dinâmico para Roteamento

A cada `costPublishInterval` ticks (padrão: 10), o link calcula e publica seu custo:

$$\text{cost} = \text{length} \times \text{congestionFactor} + \frac{\text{length}}{\text{currentSpeed}}$$

O custo é enviado ao `DynamicWeightCache` (via Kafka) para uso pelos algoritmos de roteamento
(`GPSUtil.calcRoute`). TTL do cache configurável (`cache-ttl`, padrão: 60 ticks).

## 1.7 Comunicação entre Atores

### Mensagens recebidas pelo `Link`

| Mensagem | Remetente | Handler |
|---|---|---|
| `EnterLinkData` | Qualquer `Movable` | `handleEnterLink` → MESO ou MICRO |
| `LeaveLinkData` | Qualquer `Movable` | `handleLeaveLink` → MESO ou MICRO |

### Mensagens enviadas pelo `Link`

| Mensagem | Destino | Modo | Conteúdo |
|---|---|---|---|
| `LinkInfoData` | Veículo | MESO | `linkLength`, `travelTime`, `speed` |
| `MicroEnterLinkData` | Veículo | MICRO | `lane`, `linkLength`, `speedLimit`, parâmetros micro |
| `MicroUpdateData` | Veículo | MICRO | posição, velocidade, aceleração por sub-tick |
| `MicroLeaveLinkData` | Veículo | MICRO | tempo de viagem, tempo de espera |

## 1.8 Métricas SUMO (`emitSumoSummaryStep`)

A cada tick, o link emite um resumo com:

| Campo | Descrição |
|---|---|
| `loaded` | Veículos que entraram neste tick |
| `inserted` | Veículos inseridos nas faixas (MICRO) |
| `arrived` | Veículos que saíram neste tick |
| `mean_travel_time` | Tempo médio de viagem (s) |
| `vehicles_count` | Total atual no link |
| `density` | Veículos/metro |
| `mean_speed` | Velocidade média (m/s) |
| `processing_duration_ms` | Tempo de processamento do tick (ms) |

## 1.9 Configuração JSON

### Modo MESO (padrão)
```json
{
  "id": "htcaid:link;2067",
  "typeActor": "hybrid.actor.Link",
  "data": {
    "dataType": "model.hybrid.entity.state.LinkState",
    "content": {
      "from": "htcaid:node;60609822",
      "to": "htcaid:node;4922987596",
      "length": 500.0,
      "lanes": 2,
      "speedLimit": 13.89,
      "capacity": 100.0,
      "freeSpeed": 13.89,
      "simulationMode": "MESO"
    }
  }
}
```

### Modo MICRO
```json
{
  "id": "htcaid:link;downtown_main",
  "typeActor": "hybrid.actor.Link",
  "data": {
    "dataType": "model.hybrid.entity.state.LinkState",
    "content": {
      "from": "htcaid:node;n01",
      "to": "htcaid:node;n02",
      "length": 500.0,
      "lanes": 3,
      "speedLimit": 13.89,
      "capacity": 150.0,
      "freeSpeed": 13.89,
      "simulationMode": "MICRO",
      "microTimeStep": 0.1,
      "microTicksPerGlobalTick": 10,
      "laneConfigurations": [
        { "laneId": 0, "laneType": "NORMAL" },
        { "laneId": 1, "laneType": "NORMAL" },
        { "laneId": 2, "laneType": "BUS_LANE" }
      ]
    }
  }
}
```

---

---

# 2. Node

## 2.1 Responsabilidades

O `Node` é o ator de **interseção** — o ponto de convergência de links, paradas de ônibus,
estações de metrô e semáforos. Suas responsabilidades:

1. **Registro de infraestrutura PT**: recebe e armazena referências de `BusStop` e `SubwayStation`.
2. **Mediador de semáforo**: responde a pedidos de estado de sinal dos veículos, consultando
   `state.signals` atualizado pelo `TrafficSignal`.
3. **Associação de sinais a links**: `connections` mapeia `linkId → phaseOrigin` para que um
   veículo saiba qual sinal consultar ao entrar no nó.

## 2.2 Estado (`NodeState`)

```scala
case class NodeState(
  latitude: Double,
  longitude: Double,
  links: List[String],   // IDs dos links que chegam/saem deste nó

  connections: mutable.Map[String, Identify],  // linkId → Identify do semáforo/fase
  signals: mutable.Map[String, SignalState],   // phaseOrigin → estado atual do sinal
  busStops: mutable.Map[String, Identify],     // label → Identify do BusStop
  subwayStations: mutable.Map[String, Identify]// line → Identify da SubwayStation
)
```

### `SignalState`
```scala
case class SignalState(
  var state: TrafficSignalPhaseStateEnum,  // Green | Red
  var remainingTime: Tick,                 // ticks até próxima mudança
  var nextTick: Tick                       // tick da próxima mudança
)
```

## 2.3 Fluxo de Consulta de Semáforo

```
Veículo ──[RequestSignalStateData(targetLinkId)]──► Node
Node: connections.get(targetLinkId) → Identify(phaseOrigin)
      signals.get(phaseOrigin) → SignalState
Node ──[SignalStateData(phase, nextTick)]──► Veículo
```

Se não houver sinal configurado para o link (`connections` sem entrada, ou `signals` sem o
phaseOrigin), o Node responde com **Green imediato** (`phase=Green, nextTick=currentTick`). Isso
garante que ausência de configuração não bloqueia o trânsito.

## 2.4 Registro de Infraestrutura PT

### BusStop → Node

```
BusStop ──[RegisterBusStopData(label)]──► Node
Node: state.busStops.put(label, event.toIdentity)
```

### SubwayStation → Node

```
SubwayStation ──[RegisterSubwayStationData(lines)]──► Node
Node: para cada line → state.subwayStations.put(line, event.toIdentity)
```

O `Bus` e o `Subway` consultam o Node para descobrir qual `BusStop`/`SubwayStation` está no nó
atual, usando `busStops(label)` ou `subwayStations(line)`.

## 2.5 Atualização de Sinal pelo `TrafficSignal`

```
TrafficSignal ──[TrafficSignalChangeStatusData(phaseOrigin, signalState)]──► Node
Node: state.signals.put(phaseOrigin, signalState)
```

O `Node` armazena o estado mais recente de cada fase. Veículos sempre consultam o estado atual
diretamente no Node (pull model) — não há push para os veículos.

## 2.6 TimeManager

O `Node` **não usa o TimeManager** para operação normal. Ao receber um evento espontâneo, chama
`onFinishSpontaneous(None)` imediatamente.

## 2.7 Configuração JSON

```json
{
  "id": "htcaid:node;60609822",
  "typeActor": "hybrid.actor.Node",
  "data": {
    "dataType": "model.hybrid.entity.state.NodeState",
    "content": {
      "latitude": -23.5505,
      "longitude": -46.6333,
      "links": [
        "htcaid:link;2067",
        "htcaid:link;2068"
      ],
      "connections": {
        "htcaid:link;2068": {
          "id": "htcaid:trafficsignal;signal_01",
          "classType": "hybrid.actor.TrafficSignal"
        }
      }
    }
  }
}
```

---

---

# 3. TrafficSignal

## 3.1 Responsabilidades

O `TrafficSignal` controla o ciclo semafórico de uma interseção. A cada mudança de fase:

1. Calcula o novo estado (Verde/Vermelho) para cada fase configurada.
2. Notifica os `Node` afetados com `TrafficSignalChangeStatusData`.
3. Agenda o próximo tick de mudança com base no `cycleDuration`.

## 3.2 Estado (`TrafficSignalState`)

```scala
case class TrafficSignalState(
  startTick: Tick,
  cycleDuration: Tick,  // Duração total do ciclo (ticks = segundos)
  offset: Tick,         // Deslocamento inicial do ciclo
  nodes: List[String],  // IDs dos nós controlados por este sinal
  phases: List[Phase],  // Fases configuradas
  signalStates: mutable.Map[String, SignalState]  // phaseOrigin → estado atual
)
```

### `Phase`
```scala
case class Phase(
  origin: String,      // ID do link de origem controlado por esta fase
  greenStart: Tick,    // Início do verde no ciclo (ticks desde início do ciclo)
  greenDuration: Tick, // Duração do verde (ticks)
  state: TrafficSignalPhaseStateEnum
)
```

## 3.3 Algoritmo de Ciclo Semafórico

A cada tick agendado, o sinal calcula a posição atual no ciclo e o estado de cada fase:

```
currentCycleTick = (currentTick - startTick + offset) % cycleDuration

para cada fase:
  se greenStart ≤ currentCycleTick < greenStart + greenDuration → Green
  caso contrário → Red
```

O próximo tick é calculado como o início do próximo ciclo:

```
ticksSinceStart = currentTick - startTick + offset
nextCycleStart  = ((ticksSinceStart / cycleDuration) + 1) * cycleDuration
nextTickTime    = startTick + nextCycleStart - offset
```

Isso garante que o sinal acorda exatamente uma vez por ciclo, independente do número de fases.

## 3.4 Modelo de Fases

Um semáforo típico com duas fases (Norte-Sul / Leste-Oeste) em ciclo de 90s:

```
cycleDuration = 90

Fase A (link Norte→Sul): greenStart=0,  greenDuration=40  → verde: ticks 0-39,  vermelho: 40-89
Fase B (link Leste→Oeste): greenStart=45, greenDuration=40  → verde: ticks 45-84, vermelho: 0-44, 85-89

Intervalo de 5 ticks entre fases = "all-red" (segurança)
```

## 3.5 Comunicação

### Mensagens enviadas pelo `TrafficSignal`

| Mensagem | Destino | Condição |
|---|---|---|
| `TrafficSignalChangeStatusData(phaseOrigin, signalState, nextTick)` | cada `Node` em `nodes` | Toda mudança de estado de fase |

### Mensagens recebidas pelo `TrafficSignal`

Nenhuma — o `TrafficSignal` só age espontaneamente via TM. É um ator **unidirecional**.

## 3.6 Integração com o Veículo

O veículo **nunca fala diretamente com o `TrafficSignal`**. O fluxo é sempre via `Node`:

```
Veículo ──[RequestSignalStateData(linkId)]──► Node ──► SignalStateData(phase, nextTick) ──► Veículo

TrafficSignal ──[ChangeStatus]──► Node  (atualiza state.signals)
```

Se `phase == Red`, o veículo dorme até `nextTick` antes de cruzar o link de saída.

## 3.7 TimeManager

| Situação | Ação |
|---|---|
| Inicialização | `scheduleEvent(startTick + offset)` |
| Após cada ciclo | `onFinishSpontaneous(Some(nextTickTime))` |
| Fim da simulação | `onFinishSpontaneous(None)` |

`extendSimulationIfPendingEventsAfterEnd=true` na config → `simulationEndTick = Long.MaxValue`
(sinal nunca para).

## 3.8 Configuração JSON

```json
{
  "id": "htcaid:trafficsignal;signal_01",
  "typeActor": "hybrid.actor.TrafficSignal",
  "data": {
    "dataType": "model.hybrid.entity.state.TrafficSignalState",
    "content": {
      "startTick": 0,
      "cycleDuration": 90,
      "offset": 0,
      "nodes": [
        "htcaid:node;n01",
        "htcaid:node;n02"
      ],
      "phases": [
        {
          "origin": "htcaid:link;link_norte_sul",
          "greenStart": 0,
          "greenDuration": 40,
          "state": "Green"
        },
        {
          "origin": "htcaid:link;link_leste_oeste",
          "greenStart": 45,
          "greenDuration": 40,
          "state": "Red"
        }
      ],
      "signalStates": {
        "htcaid:link;link_norte_sul": { "state": "Green", "remainingTime": 40, "nextTick": 40 },
        "htcaid:link;link_leste_oeste": { "state": "Red", "remainingTime": 45, "nextTick": 45 }
      }
    }
  },
  "dependencies": {
    "htcaid:node;n01": { "id": "htcaid:node;n01", "classType": "hybrid.actor.Node" },
    "htcaid:node;n02": { "id": "htcaid:node;n02", "classType": "hybrid.actor.Node" }
  }
}
```

---

---

# 4. Interação Completa — Veículo Atravessando Interseção com Semáforo

```
Veículo     Link(A→B)       Node(B)      TrafficSignal    Link(B→C)
    │              │              │               │              │
    │─EnterLink───►│              │               │              │
    │◄─LinkInfoData(t=30ticks)────│               │              │
    │  (dorme 30 ticks)           │               │              │
    │─LeaveLinkData──────────────►│               │              │
    │◄─LinkInfoData(MESO: next)   │               │              │
    │                             │◄ChangeStatus──│(ciclo novo)  │
    │                             │ signals["linkB→C"]=Red       │
    │─RequestSignalState(B→C)────►│               │              │
    │◄─SignalStateData(Red, next=tick+20)──────────│              │
    │  (dorme até nextTick)        │               │              │
    │─RequestSignalState(B→C)────►│               │              │
    │◄─SignalStateData(Green)──────│               │              │
    │─EnterLink───────────────────────────────────────────────►│
```

---

# 5. Referências

- [BUS_AGENT.md](BUS_AGENT.md) — Como o Bus usa Link e Node
- [PERSON_AGENT.md](PERSON_AGENT.md) — Roteamento via CityMapUtil
- [src/main/scala/model/hybrid/actor/Link.scala](../src/main/scala/model/hybrid/actor/Link.scala)
- [src/main/scala/model/hybrid/actor/Node.scala](../src/main/scala/model/hybrid/actor/Node.scala)
- [src/main/scala/model/hybrid/actor/TrafficSignal.scala](../src/main/scala/model/hybrid/actor/TrafficSignal.scala)
- [src/main/scala/model/hybrid/util/SpeedUtil.scala](../src/main/scala/model/hybrid/util/SpeedUtil.scala)
- **Bureau of Public Roads (BPR). (1964).** *Traffic Assignment Manual.* US Dept. of Commerce. — Modelo de função volume-demora (base do `SpeedUtil`).
- **Greenshields, B. D. (1935).** "A study of traffic capacity." *HRB Proceedings*, 14, 448–477. — Relação linear velocidade-densidade (limite superior do modelo BPR com α=β=1).
- **Krauss, S. (1998).** *Microscopic Modeling of Traffic Flow.* DLR. — Modelo car-following usado no modo MICRO.
- **Webster, F. V. (1958).** *Traffic Signal Settings.* Road Research Technical Paper No. 39, HMSO. — Teoria de otimização de ciclo semafórico.
