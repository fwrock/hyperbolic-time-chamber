# Agente Estação de Metrô (`SubwayStation`) — Documentação Técnica

> Pacote: `model.hybrid.actor.SubwayStation`  
> Arquivos principais:
> - [src/main/scala/model/hybrid/actor/SubwayStation.scala](../src/main/scala/model/hybrid/actor/SubwayStation.scala)
> - [src/main/scala/model/hybrid/actor/Subway.scala](../src/main/scala/model/hybrid/actor/Subway.scala)

---

## 1. Visão Geral

A `SubwayStation` é um ator de infraestrutura com **duas responsabilidades distintas**:

1. **Fábrica de trens** (`garage=true`): cria e despacha atores `Subway` em intervalos
   regulares de acordo com o headway de cada linha.
2. **Hub de passageiros**: recebe e armazena passageiros aguardando embarque, e entrega-os ao
   trem quando solicitado.

O ator `Subway` (trem) é **criado dinamicamente** pela estação em tempo de simulação — ele não
existe no JSON de entrada. Apenas as estações com `garage=true` despacham trens; as demais apenas
gerenciam passageiros.

### Relação entre atores

```
SubwayStation (fábrica + hub)
    │
    ├── cria dinamicamente ──► Subway (trem em movimento)
    │                              │
    │                              ├── percorre RailLink → RailLink → ...
    │                              ├── para em estações (SubwayStation)
    │                              └── embarca/desembarca Person
    │
    └── recebe Person ──► fila de espera por linha
```

---

## 2. Estado (`SubwayStationState`)

> Arquivo: [src/main/scala/model/hybrid/entity/state/SubwayStationState.scala](../src/main/scala/model/hybrid/entity/state/SubwayStationState.scala)

```scala
case class SubwayStationState(
  startTick: Tick,
  name: String,
  nodeId: String,               // Nó do mapa viário onde a estação está
  terminal: Boolean,            // É estação terminal da linha?
  garage: Boolean,              // Tem garagem? (despacha trens)

  // Configuração das linhas
  lines: mutable.Map[String, SubwayLineInformation],
  // Trens pré-configurados aguardando despacho: linha -> fila de SubwayInformation
  subways: mutable.Map[String, mutable.Queue[SubwayInformation]],
  // Rota de cada linha: linha -> sequência de (SubwayStationNode, railLinkId)
  linesRoute: mutable.Map[String, mutable.Queue[SubwayRouteEntry]],

  // Passageiros aguardando por linha
  people: mutable.Map[String, mutable.Seq[Identify]] = mutable.Map.empty,

  var status: SubwayStationStateEnum = Start
)
```

### Estruturas de dados aninhadas

#### `SubwayLineInformation`
```scala
case class SubwayLineInformation(
  interval: Tick,       // Headway da linha (ticks entre trens consecutivos)
  var nextTick: Tick    // Próximo tick em que um trem deve ser despachado
)
```

#### `SubwayInformation` (especificação do trem)
```scala
case class SubwayInformation(
  line: String,         // Linha (ex: "Linha 1")
  actorId: String,      // ID futuro do ator Subway
  capacity: Int,        // Capacidade de passageiros
  numberOfPorts: Int,   // Número de portas
  velocity: Double,     // Velocidade em km/h (atenção: não m/s!)
  stopTime: Tick        // Tempo de parada em estações (ticks)
)
```

#### `SubwayRouteEntry` (entrada da rota ferroviária)
```scala
case class SubwayRouteEntry(
  stationNode: SubwayStationNode,  // (stationId, nodeId)
  railLinkId: String               // ID do RailLink até este nó
)
```

---

## 3. Ciclo de Vida da Estação

A estação opera como uma **máquina de estados simples com agendamento periódico**:

```
[Start]
   │  cria trens das linhas configuradas (tick inicial)
   ▼
[Working]
   │  a cada tick agendado:
   │    ├─ filtra linhas com nextTick <= currentTick
   │    ├─ para cada linha: retira trem da fila (subways) e cria ator Subway
   │    └─ atualiza nextTick = currentTick + interval
   │  agenda próximo wake-up = min(nextTick de todas as linhas)
   └──► repete até fim da simulação
```

### Agendamento inteligente

A estação **não acorda a cada tick**. Calcula o próximo tick necessário como o mínimo entre todos
os `nextTick` das linhas ativas:

```scala
val nextTickOpt = state.lines.values
  .map(line => line.nextTick)
  .filter(_ < simulationEnd)
  .sorted
  .headOption
onFinishSpontaneous(nextTickOpt, destruct = false)
```

Isso garante eficiência: uma estação com headway de 10 min (600 ticks) acorda apenas a cada 600
ticks, não a cada tick.

---

## 4. Criação Dinâmica de Trens

Quando uma linha está pronta para despacho (`nextTick <= currentTick`):

1. Retira o próximo `SubwayInformation` da fila `state.subways(line)`.
2. Constrói a rota ferroviária a partir de `state.linesRoute(line)`:
   ```
   linesRoute: Queue[SubwayRouteEntry] → Queue[(railLinkId, nodeId)]
   ```
3. Monta as `subwayStations` (mapa `stationId → nodeId`) para o trem saber onde parar.
4. Instancia um `SubwayState` com rota pré-computada e cria o ator via `createShardedActorSeveralArgs`.
5. Atualiza `nextTick = currentTick + interval`.

Se a rota ou as estações estiverem vazias, a criação falha com `IllegalStateException` e o trem é
devolvido à fila para retentar no próximo ciclo.

---

## 5. Ator `Subway` (Trem)

> Arquivo: [src/main/scala/model/hybrid/actor/Subway.scala](../src/main/scala/model/hybrid/actor/Subway.scala)

### Diferença fundamental em relação ao Bus

| Característica | Bus | Subway |
|---|---|---|
| Roteamento | Dijkstra dinâmico | **Rota pré-fixada** (rail links) |
| Infraestrutura | Links viários | **RailLinks** exclusivos |
| Criação | JSON de entrada | **Criado pela SubwayStation** em runtime |
| Congestionamento | Afetado (SpeedUtil) | **Não** — velocidade constante |
| Velocidade | m/s | **km/h** (convertido internamente) |

### Estado (`SubwayState`)

```scala
case class SubwayState(
  startTick: Tick,
  capacity: Int,
  numberOfPorts: Int,
  velocity: Double,              // km/h
  stopTime: Tick,                // ticks de parada em cada estação
  line: String,
  boardingTimeByPassenger: Double = 1.5,  // ticks por passageiro
  subwayStations: mutable.Map[String, String],  // stationId -> nodeId
  passengers: mutable.Map[String, Identify],    // a bordo
  nodeState: SubwayNodeState,    // flags de carga/descarga completas
  currentPathPosition: Int,      // índice circular na rota
  ...
)
```

#### `SubwayNodeState` — sincronização de parada

```scala
case class SubwayNodeState(
  var isLoaded: Boolean = false,    // Embarque concluído?
  var isUnloaded: Boolean = false   // Desembarque concluído?
)
```

Embarque e desembarque acontecem **em paralelo** (duas mensagens enviadas simultaneamente).
O trem só avança quando **ambos** os flags estão `true`.

---

## 6. Ciclo de Vida do Trem (`Subway`)

```
[Start] ──► status=Ready ──► enterLink()
               │
            [Moving]
               │  recebe LinkInfoData do RailLink
               │  t = ceil((length / velocity) × 3600) ticks
               │  dorme até chegar na próxima estação
               │
               ▼
       chegou em nó com estação?
          ├── SIM ──► [Stopped]
          │               ├── requestUnloadPeopleData() (todos passageiros)
          │               ├── requestLoadPassenger() (SubwayStation)
          │               └── onFinishSpontaneous(None) — cede TM
          │                   quando isLoaded && isUnloaded:
          │                   scheduleEvent(currentTick + stopTime)
          │               ──► [Stopped] ──► leavingLink() ──► [Ready]
          └── NÃO ──► leavingLink() ──► [Ready]
```

### Modelo de tempo de viagem entre estações

$$t_{trecho} = \left\lceil \frac{d \times 3600}{v} \right\rceil$$

onde:
- $d$ = comprimento do RailLink (metros)
- $v$ = velocidade do trem (km/h)
- Fator $3600$ converte km/h → m/s implicitamente: $\frac{d[\text{m}]}{v[\text{km/h}]} \times 3600[\text{s/h}] = t[\text{s}]$
- 1 tick = 1 segundo

Exemplo: trem a 80 km/h em trecho de 2 km → $\lceil 2000 \times 3600 / 80 \rceil = \lceil 90000 \rceil = 90000\ \text{?}$

> **Atenção:** a fórmula em `SubwayUtil.timeToNextStation` é `ceil((distance / velocity) * 3600)`.
> Com `distance` em metros e `velocity` em km/h:
> $\lceil (d[\text{m}] / v[\text{km/h}]) \times 3600 \rceil$
> Para $d=2000$ m, $v=80$ km/h: $\lceil (2000/80) \times 3600 \rceil = \lceil 90000 \rceil = 90000$ ticks — implica que a velocidade deve ser fornecida em **m/s** na prática (ex: 22,2 m/s ≈ 80 km/h → $\lceil (2000/22.2) \times 3600 \rceil$ ≠ correto). **Verificar unidade do campo `velocity` na configuração.**

---

## 7. Protocolo de Parada (Embarque + Desembarque)

### 7.1 Desembarque

```
Subway ──[SubwayRequestUnloadPassengerData(nodeId)]──► cada Person a bordo
Person ──[SubwayUnloadPassengerData(isArrival)]──► Subway
```

- Enviado a **todos** os passageiros simultaneamente.
- Person responde `isArrival=true` se `nodeId == ptAlightingNodeId`.
- Subway remove passageiros que alightam de `state.passengers`.
- Concluído quando `countUnloadReceived >= countUnloadPassenger + passengers.remaining`.

### 7.2 Embarque

```
Subway ──[SubwayRequestPassengerData(line, availableSpace)]──► SubwayStation
SubwayStation ──[SubwayLoadPassengerData(people)]──► Subway
```

Vagas disponíveis para embarque limitadas pelo menor valor entre:
- Vagas físicas: `capacity - passengers.size`
- Vagas de fluxo por parada: $\lfloor n_{portas} \times C_{porta} \times \frac{t_{stop}}{t_{boarding}} \rfloor$

```scala
val availableSpace = min(
  capacity - passengers.size,
  ceil(numberOfPorts * portsCapacity * (stopTime / boardingTimeByPassenger)).toInt
)
```

### 7.3 Sincronização dual (flag duplo)

Desembarque e embarque são iniciados **ao mesmo tempo** (linha 53 do Subway: dois métodos
chamados sequencialmente, ambos com `onFinishSpontaneous(None)`). Cada um seta seu flag
(`isUnloaded`, `isLoaded`) ao concluir. O trem só agenda o próximo tick quando **ambos** estão
`true`:

```scala
private def onFinishNodeState(): Unit =
  if (isEndNodeState) {             // isLoaded && isUnloaded
    state.nodeState.isLoaded = false
    state.nodeState.isUnloaded = false
    scheduleEvent(currentTick + state.stopTime)   // dwell time fixo
  }
```

---

## 8. Comunicação entre Atores

### Mensagens enviadas pela `SubwayStation`

| Mensagem | Destino | Condição |
|---|---|---|
| `RegisterSubwayStationData(lines)` | `Node` | Inicialização — registra estação no nó |
| `SubwayLoadPassengerData(people)` | `Subway` | Resposta ao pedido de embarque |
| *(cria ator)* | `Subway` | Cada vez que `nextTick <= currentTick` (garage=true) |

### Mensagens recebidas pela `SubwayStation`

| Mensagem | Remetente | Handler |
|---|---|---|
| `RegisterSubwayPassengerData(line)` | `Person` | `handleRegisterPassenger` — enfileira Person |
| `SubwayRequestPassengerData(line, space)` | `Subway` | `handleSubwayRequestPassenger` — entrega passageiros |

### Mensagens enviadas pelo `Subway`

| Mensagem | Destino | Condição |
|---|---|---|
| `EnterLinkData` | `RailLink` | Ao entrar num trecho ferroviário |
| `LeaveLinkData` | `RailLink` | Ao sair de um trecho ferroviário |
| `SubwayRequestUnloadPassengerData` | cada `Person` | Chegou numa estação |
| `SubwayRequestPassengerData` | `SubwayStation` | Após iniciar desembarque |

### Mensagens recebidas pelo `Subway`

| Mensagem | Remetente | Handler |
|---|---|---|
| `LinkInfoData` (enter) | `RailLink` | `actHandleReceiveEnterLinkInfo` — calcula tempo de viagem |
| `LinkInfoData` (leave) | `RailLink` | `actHandleReceiveLeaveLinkInfo` — acumula distância |
| `SubwayLoadPassengerData` | `SubwayStation` | `handleBusLoadPeople` — embarca passageiros |
| `SubwayUnloadPassengerData` | `Person` | `handleUnloadPassenger` — processa resposta |

---

## 9. Gerenciamento do TimeManager

| Situação | Ação |
|---|---|
| Entre despachos de trens | `onFinishSpontaneous(Some(minNextTick))` — dorme até o próximo headway |
| Atravessando RailLink | `onFinishSpontaneous(Some(arrivalTick))` — dorme pelo tempo de viagem |
| Aguardando load + unload | `onFinishSpontaneous(None)` — cede TM; retoma via `scheduleEvent` |
| Dwell time na estação | `scheduleEvent(currentTick + stopTime)` |
| Fim da simulação | `onFinishSpontaneous(None)` — para permanentemente |

---

## 10. Infraestrutura: `RailLink`

O metrô usa **RailLinks** (trilhos), não links viários. Diferenças:

| Característica | Link viário | RailLink |
|---|---|---|
| Acesso | Qualquer veículo | **Apenas Subway** |
| Congestionamento | Sim (SpeedUtil) | **Não** — trilho exclusivo |
| Velocidade | Calculada por densidade | **Fixa** (parâmetro do trem) |
| Dados no grafo | `city_map.json` | Separados (rota pré-definida em `linesRoute`) |

---

## 11. Relatórios emitidos

### `SubwayStation`

| Label | Quando emitido |
|---|---|
| `subway_created` | Cada vez que um trem é instanciado |

### `Subway`

O `Subway` herda os relatórios de `Movable` (enter/leave link) mais os de passageiros (via
`handleBusLoadPeople` e `handleUnloadPassenger`).

---

## 12. Configuração JSON

### Estação com garagem (despacha trens)

```json
{
  "id": "htcaid:subwaystation;station_01",
  "typeActor": "hybrid.actor.SubwayStation",
  "data": {
    "dataType": "model.hybrid.entity.state.SubwayStationState",
    "content": {
      "startTick": 0,
      "name": "Terminal Norte",
      "nodeId": "htcaid:node;node_terminal_norte",
      "terminal": true,
      "garage": true,
      "lines": {
        "Linha 1": { "interval": 300, "nextTick": 0 }
      },
      "subways": {
        "Linha 1": [
          {
            "line": "Linha 1",
            "actorId": "htcaid:subway;subway_L1_001",
            "capacity": 200,
            "numberOfPorts": 4,
            "velocity": 80.0,
            "stopTime": 30
          },
          {
            "line": "Linha 1",
            "actorId": "htcaid:subway;subway_L1_002",
            "capacity": 200,
            "numberOfPorts": 4,
            "velocity": 80.0,
            "stopTime": 30
          }
        ]
      },
      "linesRoute": {
        "Linha 1": [
          {
            "stationNode": { "stationId": "htcaid:subwaystation;station_02", "nodeId": "htcaid:node;node_centro" },
            "railLinkId": "htcaid:raillink;rl_norte_centro"
          },
          {
            "stationNode": { "stationId": "htcaid:subwaystation;station_03", "nodeId": "htcaid:node;node_sul" },
            "railLinkId": "htcaid:raillink;rl_centro_sul"
          }
        ]
      }
    }
  },
  "dependencies": {
    "htcaid:node;node_terminal_norte": {
      "id": "htcaid:node;node_terminal_norte",
      "classType": "hybrid.actor.Node"
    }
  }
}
```

### Estação intermediária (apenas passageiros, sem garagem)

```json
{
  "id": "htcaid:subwaystation;station_02",
  "typeActor": "hybrid.actor.SubwayStation",
  "data": {
    "dataType": "model.hybrid.entity.state.SubwayStationState",
    "content": {
      "startTick": 0,
      "name": "Centro",
      "nodeId": "htcaid:node;node_centro",
      "terminal": false,
      "garage": false,
      "lines": {
        "Linha 1": { "interval": 300, "nextTick": 0 }
      },
      "subways": {},
      "linesRoute": {}
    }
  },
  "dependencies": {
    "htcaid:node;node_centro": {
      "id": "htcaid:node;node_centro",
      "classType": "hybrid.actor.Node"
    }
  }
}
```

### Registro de passageiro via `Person` (para embarcar)

```json
{
  "sequence": 2,
  "activityType": "Work",
  "nodeId": "htcaid:node;node_sul",
  "endTime": "61200",
  "arrivalLogistics": {
    "mode": "subway",
    "line": "Linha 1",
    "boardingStopId": "htcaid:subwaystation;station_01",
    "boardingStopClassType": "hybrid.actor.SubwayStation",
    "alightingNodeId": "htcaid:node;node_sul"
  }
}
```

---

## 13. Diagrama de Sequência — Despacho e Parada

```
TM(Stn)   SubwayStation   RailLink    Subway    Person1   SubwayStation(2)
   │             │              │          │         │              │
   │─actSp──────►│              │          │         │              │
   │             │─createSubway─────────────────────────────────────
   │             │              │◄─EnterLink│         │              │
   │             │              │─LinkInfo─►│         │              │
   │             │              │          │ (viagem) │              │
   │             │              │◄─EnterLink│ (chega) │              │
   │             │              │─LinkInfo─►│         │              │
   │             │              │          │─UnloadReq►│              │
   │             │              │          │─RequestPassenger────────►│
   │             │              │          │◄UnloadData(true)─────────│
   │             │              │          │◄LoadData(people)─────────│
   │             │              │          │  (isLoaded && isUnloaded)│
   │             │              │          │  scheduleEvent(+stopTime) │
   │             │              │◄─LeaveLink│         │              │
   │             │              │─LinkInfo─►│         │              │
```

---

## 14. Limitações e Pontos de Extensão

| Limitação | Descrição |
|---|---|
| Trens não retornam | `garage=true` cria trens mas não os reaproveita na volta; cada trem é destruído ao atingir o destino |
| Rota sempre em avanço | `getNextPath` reinicia do índice 0 ao atingir o fim — pode modelar circular, mas não ida-e-volta real |
| Velocidade constante | Sem aceleração/desaceleração por trecho; sem curvas ou gradientes |
| `velocity` em km/h | Campo pode ser confundido com m/s; verificar documentação da configuração |
| `stopTime` fixo | Dwell time igual em todas as estações independente da demanda |
| Sem `terminal` lógico | O campo `terminal` existe no estado, mas a lógica de terminação está apenas no `getNextPath` |

---

## 15. Referências

- **Vuchic, V. R. (2005).** *Urban Transit: Operations, Planning and Economics.* Wiley. —
  Parâmetros operacionais de metrô urbano (headway, dwell time, capacidade).
- **Dueker, K. J., et al. (2004).** "Measuring Transit Stop Accessibility." *Transportation
  Research Record*, 1887. — Modelo de embarque/desembarque em paradas PT.
- [src/main/scala/model/hybrid/actor/SubwayStation.scala](../src/main/scala/model/hybrid/actor/SubwayStation.scala)
- [src/main/scala/model/hybrid/actor/Subway.scala](../src/main/scala/model/hybrid/actor/Subway.scala)
- [src/main/scala/model/hybrid/entity/state/SubwayStationState.scala](../src/main/scala/model/hybrid/entity/state/SubwayStationState.scala)
- [src/main/scala/model/hybrid/entity/state/SubwayState.scala](../src/main/scala/model/hybrid/entity/state/SubwayState.scala)
- [src/main/scala/model/hybrid/util/SubwayUtil.scala](../src/main/scala/model/hybrid/util/SubwayUtil.scala)
- [PERSON_AGENT.md](PERSON_AGENT.md) — Perspectiva do passageiro
- [BUS_AGENT.md](BUS_AGENT.md) — Comparação com ônibus
