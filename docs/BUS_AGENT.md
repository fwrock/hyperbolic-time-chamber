# Agente Ônibus (`Bus`) — Documentação Técnica

> Pacote: `model.hybrid.actor.Bus`  
> Arquivo principal: [src/main/scala/model/hybrid/actor/Bus.scala](../src/main/scala/model/hybrid/actor/Bus.scala)

---

## 1. Visão Geral

O `Bus` é um ator de simulação que representa um veículo de transporte público coletivo. Ao
contrário dos veículos privados (Car, Bicycle, Motorcycle), o Bus:

- **Opera de forma autônoma** seguindo uma rota de linha pré-definida (origem → destino com
  paradas intermediárias).
- **Transporta passageiros** gerenciando embarque e desembarque em `BusStop` ao longo da rota.
- **Suporta dois modos de simulação** simultâneos: MESO (agregado) e MICRO (individual).
- **Não é controlado por um agente Person** — é uma infraestrutura de transporte público que
  circula independentemente.

### Hierarquia

```
SimulationBaseActor[T]
    └── Movable[BusState]
            └── Bus
```

`Movable` encapsula a lógica de roteamento e navegação em links. `Bus` sobrepõe esse comportamento
adicionando gerenciamento de passageiros, paradas e suporte MICRO.

---

## 2. Estado (`BusState`)

> Arquivo: [src/main/scala/model/hybrid/entity/state/BusState.scala](../src/main/scala/model/hybrid/entity/state/BusState.scala)

```scala
case class BusState(
  startTick: Tick,
  label: String,               // Rótulo da linha: "Bus Line 1"
  capacity: Int,               // Capacidade máxima de passageiros
  distance: Double = 0.0,      // Distância total percorrida (m)
  countUnloadPassenger: Int,   // Passageiros desembarcados no stop atual
  countUnloadReceived: Int,    // Respostas de desembarque recebidas
  busStops: Map[String, String], // busStopId -> nodeId (paradas da linha)
  numberOfPorts: Int,          // Número de portas (embarque/desembarque)
  people: mutable.Map[String, Identify], // Passageiros a bordo: personId -> Identify
  currentPathPosition: Int,    // Posição atual na rota
  origin: String,
  destination: String,
  size: Double,                // Tamanho do veículo
  currentSimulationMode: SimulationModeEnum = MESO,
  microState: Option[MicroBusState] = None,
  storedBestRoute: Option[List[(String, String)]] = None,
  speedFactor: Double = 1.0    // Fator de velocidade desejada no modo MICRO [0.5, 1.5]
)
```

### Campos principais

| Campo | Tipo | Descrição |
|---|---|---|
| `label` | `String` | Identificador da linha (usado por pessoas para embarcar) |
| `capacity` | `Int` | Capacidade máxima de passageiros |
| `busStops` | `Map[String, String]` | Paradas da linha: ID do BusStop → ID do nó |
| `people` | `mutable.Map` | Passageiros a bordo |
| `numberOfPorts` | `Int` | Número de portas — afeta tempo de embarque/desembarque |
| `currentSimulationMode` | `MESO` / `MICRO` | Modo ativo de simulação |
| `microState` | `Option[MicroBusState]` | Estado microscópico (ativo apenas em links MICRO) |
| `speedFactor` | `Double` (default `1.0`) | Fator multiplicativo da `desiredVelocity` no modo MICRO. Limitado ao intervalo `[0.5, 1.5]`. Propagado pelo `BusStation` via `BusInformation.speedFactor`. |

### Métodos de conveniência

| Método | Retorno |
|---|---|
| `availableCapacity` | `capacity - people.size` |
| `occupancyPercentage` | `(people.size / capacity) × 100` |
| `isMicroMode` / `isMesoMode` | `Boolean` |
| `activateMicroMode(initial)` | Ativa MICRO e inicializa `microState` |
| `deactivateMicroMode()` | Volta ao MESO, zera `microState` |

---

## 3. Ciclo de Vida (Máquina de Estados)

O Bus opera como uma máquina de estados explícita. Cada `actSpontaneous` avalia
`state.status` e age de acordo:

```
[Start]
   │  requestRoute() via GPSUtil.calcRoute
   ▼
[Ready]
   │  chegou num nó com BusStop?
   ├── SIM ──► requestUnloadPeopleData() ──► [WaitingUnloadPassenger]
   └── NÃO ──► enterLink() ──► [Moving]

[Moving]
   ├── MESO: aguarda mesoExitTick ──► requestSignalState()
   └── MICRO: aguarda posição >= comprimento ──► leavingLink()

[WaitingSignalState]
   │  recebe SignalStateData
   ├── Red ──► [WaitingSignal] (dorme até nextTick do sinal)
   └── Green/Yellow ──► leavingLink()

[WaitingSignalState]
   │  recebe SignalStateData → leavingLink() ou WaitingSignal
   │  cada tick sem resposta: signalStateRetryCounter++
   └── retryCounter > 100 ──► leavingLink() forçado (anti-deadlock)

[WaitingSignal]
   │  signalWaitUntilTick atingido
   └──► leavingLink() ──► [Ready]

[WaitingUnloadPassenger]
   │  recebe BusUnloadPassengerData de cada pessoa
   │  quando todos responderam ──► delay de desembarque
   └──► requestLoadPassenger() ──► [WaitingLoadPassenger]

[WaitingLoadPassenger]
   │  recebe BusLoadPassengerData do BusStop
   │  delay de embarque
   └──► enterLink() ──► [Moving]

[Finished]
   └── selfDestruct()
```

---

## 4. Navegação e Roteamento

### Roteamento inicial

Na fase `Start`, o Bus herda de `Movable.requestRoute()`, que chama
`GPSUtil.calcRoute(origin, destination)` — **Dijkstra com pesos dinâmicos**.

O Bus tem um comportamento de rota especial: diferente dos veículos privados que usam uma fila
consumível, o Bus usa um **índice circular** (`currentPathPosition`) na rota. Isso permite que a
linha de ônibus **percorra a rota ciclicamente** (ida e volta).

```scala
override def getNextPath: Option[(String, String)] =
  state.bestRoute match {
    case Some(path) =>
      if (state.currentPathPosition < path.size) {
        val nextPath = path(state.currentPathPosition)
        state.currentPathPosition += 1
        Some(nextPath)
      } else {
        state.currentPathPosition = 0          // volta ao início
        Some(path(state.currentPathPosition))
      }
    case None => None
  }
```

### Velocidade em links MESO

A velocidade no modo mesoscópico usa o modelo **BPR-like de densidade de fluxo** implementado
em `SpeedUtil.linkDensitySpeed`:

$$v = v_{free} \cdot \left(1 - \left(\frac{n}{C}\right)^\beta\right)^\alpha$$

onde:
- $v_{free}$ = velocidade de fluxo livre do link (m/s)
- $n$ = número de veículos no link
- $C$ = capacidade do link
- $\alpha = \beta = 1$ (parametrização atual — modelo linear)
- Se $n \geq C$: $v = 1{,}0\ \text{m/s}$ (velocidade mínima)

Tempo de travessia: $t = \text{length} / v$ ticks (1 tick = 1 segundo).

**Referência:** Baseado em Greenshields (1935) e BPR (Bureau of Public Roads, 1964) para
relação fluxo-densidade.

---

## 5. Gerenciamento de Passageiros

### 5.1 Detecção de parada

Ao sair de cada link (`leavingLink`), o Bus salva o nó de chegada em `currentStopNode`.
Na fase `Ready`, verifica:

```scala
if (findBusStopAtNode(nodeId).isDefined) {
  requestUnloadPeopleData()     // há parada → gerencia passageiros
} else {
  enterLink()                   // sem parada → segue viagem
}
```

`findBusStopAtNode` faz lookup no mapa `busStops: Map[busStopId, nodeId]` por valor.

### 5.2 Protocolo de desembarque

```
Bus ──[BusRequestUnloadPassengerData(nodeId, nodeRef)]──► cada Person a bordo
                      │
Person ──[BusUnloadPassengerData(isArrival)]──► Bus
```

1. Bus envia `BusRequestUnloadPassengerData` para **todos** os passageiros a bordo.
2. Registra `expectedUnloadResponses = people.size`.
3. Cada Person responde com `isArrival=true` se o `nodeId` é o `alightingNodeId` do seu
   `PTWaitState` atual (ver [PERSON_AGENT.md](PERSON_AGENT.md) — antigo `ptAlightingNodeId`, hoje
   carregado em `TripExecutionState.Traveling.ptWait.alightingNodeId`).
4. Bus remove passageiros que alightam de `state.people`.
5. Quando todas as respostas chegam (`countUnloadReceived >= expectedUnloadResponses`):
   - Se houve desembarques → aplica delay e avança para embarque.
   - Se ninguém desceu → avança para embarque imediatamente.
6. Bus cede o TM durante a espera (`onFinishSpontaneous(None)`).

### 5.3 Tempo de desembarque (`BusUtil.unloadPersonTime`)

$$t_{unload} = \left\lceil \frac{n_{unload} \cdot t_{individual} \cdot f}{p_{portas}} \right\rceil$$

| Parâmetro | Valor padrão | Descrição |
|---|---|---|
| $t_{individual}$ | 5 ticks/s | Tempo por passageiro |
| $f$ | 1,5 | Fator de ajuste operacional |
| $p_{portas}$ | `numberOfPorts` | Portas paralelas |

### 5.4 Protocolo de embarque

```
Bus ──[BusRequestPassengerData(label, availableSpace)]──► BusStop
                      │
BusStop ──[BusLoadPassengerData(people)]──► Bus
```

1. Bus envia `BusRequestPassengerData` ao `BusStop` com `label` (linha) e vagas disponíveis.
2. `BusStop` retorna a lista de passageiros aguardando aquela linha (limitado ao `availableSpace`).
3. Bus adiciona passageiros a `state.people` e aplica o delay de embarque.
4. Após o delay, retoma a navegação (`enterLink`).

### 5.5 Tempo de embarque (`BusUtil.loadPersonTime`)

Mesma fórmula do desembarque:

$$t_{load} = \left\lceil \frac{n_{load} \cdot t_{individual} \cdot f}{p_{portas}} \right\rceil$$

**Referência:** Baseado em modelos de dwell time de ônibus urbano. Ver:
- **Dueker, K. J., et al. (2004).** "Measuring Transit Stop Accessibility." *Transportation
  Research Record*, 1887. — Parâmetros típicos de embarque/desembarque em stops urbanos.

---

## 6. Modo MICRO (Microscópico)

### 6.1 Estado microscópico (`MicroBusState`)

Ativado quando o Bus entra num link com `simulationMode = MICRO`:

```scala
case class MicroBusState(
  positionInLink: Double,       // metros do início do link
  velocity: Double,             // m/s
  acceleration: Double,         // m/s²
  currentLane: Int,             // lane 0-indexed
  leaderVehicle: Option[String],
  gapToLeader: Double,          // metros
  leaderVelocity: Double,       // m/s

  // Parâmetros específicos do ônibus
  maxAcceleration: Double = 1.2,   // m/s² (mais lento que carro: 2.6)
  maxDeceleration: Double = 3.5,   // m/s²
  minGap: Double = 3.0,            // m (maior que carro: 2.0)
  desiredVelocity: Double = 11.11, // m/s = 40 km/h
  reactionTime: Double = 1.5,      // s (mais lento que carro: 1.0)
  vehicleLength: Double = 12.0,    // m (muito maior que carro: 4.5)

  // Gestão de passageiros em micro
  capacity: Int,
  currentPassengers: Int,
  nextBusStop: Option[String],
  busLaneRestricted: Boolean = true,
  canChangeLane: Boolean = false   // ônibus geralmente fixos na faixa
)
```

### 6.2 Comparação de parâmetros: Bus × Car

| Parâmetro | Bus | Car | Impacto |
|---|---|---|---|
| `vehicleLength` | 12,0 m | 4,5 m | Maior ocupação do link; maior gap físico necessário |
| `maxAcceleration` | 1,2 m/s² | 2,6 m/s² | Aceleração mais lenta |
| `maxDeceleration` | 3,5 m/s² | 4,5 m/s² | Frenagem mais suave |
| `minGap` | 3,0 m | 2,0 m | Segue com maior distância de segurança |
| `desiredVelocity` | 11,11 m/s (40 km/h) | 13,89 m/s (50 km/h) | Velocidade de cruzeiro menor |
| `reactionTime` | 1,5 s | 1,0 s | Reflexo mais lento (veículo maior/mais pesado) |
| `canChangeLane` | `false` | `true` | Ônibus ficam fixos na faixa (restrição operacional) |

> **Nota:** Os valores padrão foram calibrados a partir dos defaults do tipo `bus` no Eclipse
> SUMO (Krajzewicz et al., 2012) e da literatura de car-following (Krauss, 1998; Wiedemann, 1974).
> Ver Seção 15 para referências completas e justificativa por parâmetro.

### 6.3 Fluxo de entrada/saída do modo MICRO

```
[MESO link] ──► enterLink ──► Link detecta mode=MICRO
                                   │
                              MicroEnterLinkData ──► Bus.handleMicroEnterLink
                                   │
                              activateMicroMode(MicroBusState)
                                   │
                     [sub-ticks gerenciados pelo LinkMicroTimeManager]
                                   │
                              MicroUpdateData ──► handleMicroUpdate (a cada sub-tick)
                                   │
                              position >= linkLength
                                   │
                              MicroLeaveLinkData ──► handleMicroLeaveLink
                                   │
                              deactivateMicroMode()
                                   │
                             [MESO link] ──► comportamento mesoscópico retoma
```

### 6.4 Paradas em modo MICRO — comportamento atual

Ao sair de um link MICRO (`handleMicroLeaveLink`), o Bus seta `currentStopNode` com o nó de
destino e `state.status = Ready` — exatamente o mesmo que ocorre no MESO. Com isso, na próxima
chamada de `actSpontaneous(Ready)`, o Bus verifica `findBusStopAtNode(currentStopNode)` e
executa o ciclo completo de embarque/desembarque antes do próximo link.

A função `checkBusStopAtPosition(position)` é chamada a cada `MicroUpdateData` durante a
travessia do link e está reservada para interações com paradas **intermediárias** (paradas
dentro do link, não apenas no nó destino). Essa funcionalidade está planejada para versões
futuras do simulador.

---

## 7. Sinalização (TrafficSignal)

Antes de sair de cada link, o Bus consulta o nó destino pelo estado do semáforo:

```
Bus ──[RequestSignalStateData(targetLinkId)]──► Node
                   │
Node ──[SignalStateData(phase, nextTick)]──► Bus
```

- **`Green` / `Yellow`**: executa `leavingLink()` imediatamente.
- **`Red`**: transita para `WaitingSignal`, dorme até `nextTick` (fim da fase vermelha).

### Prevenção de deadlock — retry de sinal

O Bus implementa o mesmo mecanismo anti-deadlock do `Car`. Se o `Node` não responder ao
`RequestSignalStateData` (ex.: falha de mensagem, shard reiniciado), o Bus ficaria preso em
`WaitingSignalState` indefinidamente.

O mecanismo de recuperação:

```
WaitingSignalState (cada tick espontâneo sem resposta):
  signalStateRetryCounter += 1
  se signalStateRetryCounter > MaxSignalStateRetries (100):
    logWarn("Node não respondeu em 100 ticks — forçando leavingLink()")
    signalStateRetryCounter = 0
    leavingLink()   ← assume sinal verde como fallback seguro
  senão:
    requestSignalState()   ← reenvía a consulta
```

O contador é zerado em dois momentos:
- Quando `handleSignalState` recebe a resposta do `Node`.
- Quando `leavingLink()` é chamado (por sinal verde, timeout ou saída de link MICRO).

Os campos `signalWaitUntilTick` e `mesoExitTick` previnem que ticks obsoletos da fila do TM
acordem o Bus prematuramente (guards de condição de corrida).

---

## 8. Comunicação entre Atores

### Mensagens enviadas pelo Bus

| Mensagem | Destino | Condição |
|---|---|---|
| `EnterLinkData` | `Link` | Ao entrar num link |
| `LeaveLinkData` | `Link` | Ao sair de um link |
| `RequestSignalStateData` | `Node` | Antes de sair do link (MESO) |
| `BusRequestUnloadPassengerData` | cada `Person` a bordo | Chegou numa parada |
| `BusRequestPassengerData` | `BusStop` | Após desembarque |

### Mensagens recebidas pelo Bus

| Mensagem | Remetente | Handler |
|---|---|---|
| `LinkInfoData` (enter) | `Link` | `actHandleReceiveEnterLinkInfo` |
| `LinkInfoData` (leave) | `Link` | `actHandleReceiveLeaveLinkInfo` |
| `SignalStateData` | `Node` | `handleSignalState` |
| `MicroEnterLinkData` | `Link` (MICRO) | `handleMicroEnterLink` |
| `MicroUpdateData` | `LinkMicroTimeManager` | `handleMicroUpdate` |
| `MicroLeaveLinkData` | `Link` (MICRO) | `handleMicroLeaveLink` |
| `BusLoadPassengerData` | `BusStop` | `handleBusLoadPeople` |
| `BusUnloadPassengerData` | `Person` | `handleUnloadPassenger` |

---

## 9. Ator `BusStop`

> Arquivo: [src/main/scala/model/hybrid/actor/BusStop.scala](../src/main/scala/model/hybrid/actor/BusStop.scala)

O `BusStop` é um ator de infraestrutura (não se move). Responsabilidades:

1. **Registrar-se no Node** ao inicializar (`RegisterBusStopData`), associando-se ao nó do mapa.
2. **Aceitar passageiros** (`RegisterPassengerData`) de `Person` que chegam para embarcar.
3. **Responder ao Bus** (`BusRequestPassengerData`) entregando passageiros por linha e por
   vagas disponíveis.

```
Person ──[RegisterPassengerData(label)]──► BusStop.state.people[label].enqueue(person)

Bus ──[BusRequestPassengerData(label, availableSpace)]──► BusStop
BusStop ──[BusLoadPassengerData(people.take(availableSpace))]──► Bus
```

O BusStop organiza passageiros por **linha** (`Map[label, Queue[Identify]]`), garantindo que
cada passageiro embarca no veículo correto.

---

## 10. Gerenciamento do TimeManager

| Situação | Ação |
|---|---|
| Atravessando link MESO | Dorme até `mesoExitTick` |
| Aguardando sinal vermelho | Dorme até `signalWaitUntilTick` |
| Aguardando respostas de desembarque | `onFinishSpontaneous(None)` — cede TM |
| Aguardando resposta do BusStop | `onFinishSpontaneous(None)` — cede TM |
| Delay de embarque/desembarque | `scheduleEvent(nextTickTime)` |
| Modo MICRO | TM gerenciado pelo `LinkMicroTimeManager` (sub-ticks) |

---

## 11. Relatórios emitidos (`report`)

| Label | Quando emitido |
|---|---|
| `journey_started` | Início da jornada (`Start → Ready`) |
| `enter_link` | Entra num link MESO |
| `leave_link` | Sai de um link MESO |
| `enter_micro_link` | Entra num link MICRO |
| `leave_micro_link` | Sai de um link MICRO |
| `signal_wait` | Aguarda sinal vermelho |
| `bus_load_passengers` | Passageiros embarcam |
| `bus_unload_passengers` | Passageiros desembarcam |
| `journey_completed` | Chegou ao destino |
| `sumo_tripinfo` | Métricas SUMO-compatíveis da viagem completa |

### Métricas SUMO (`sumo_tripinfo`)

O Bus coleta métricas compatíveis com o formato SUMO `<tripinfo>`:

| Métrica | Descrição |
|---|---|
| `depart` / `arrival` | Tick de partida/chegada |
| `duration` | Duração total da viagem (ticks) |
| `routeLength` | Distância total percorrida (m) |
| `waitingTime` | Tempo parado < 0,1 m/s (s) |
| `waitingCount` | Número de paradas completas |
| `stopTime` | Tempo em paradas de ônibus (s) |
| `timeLoss` | `duration - idealTravelTime` |
| `departSpeed` / `arrivalSpeed` | Velocidades no início/fim |

---

## 12. Configuração JSON do Agente

```json
{
  "id": "htcaid:bus;bus_line1_001",
  "typeActor": "hybrid.actor.Bus",
  "data": {
    "dataType": "model.hybrid.entity.state.BusState",
    "content": {
      "startTick": 0,
      "label": "Bus Line 1",
      "capacity": 80,
      "numberOfPorts": 3,
      "size": 12.0,
      "speedFactor": 1.0,
      "origin": "htcaid:node;terminal_norte",
      "destination": "htcaid:node;terminal_sul",
      "busStops": {
        "htcaid:busstop;busstop_01": "htcaid:node;node_101",
        "htcaid:busstop;busstop_02": "htcaid:node;node_205",
        "htcaid:busstop;busstop_03": "htcaid:node;node_312"
      }
    }
  }
}
```

```json
{
  "id": "htcaid:busstop;busstop_01",
  "typeActor": "hybrid.actor.BusStop",
  "data": {
    "dataType": "model.hybrid.entity.state.BusStopState",
    "content": {
      "label": "Bus Line 1",
      "nodeId": "htcaid:node;node_101"
    }
  },
  "dependencies": {
    "htcaid:node;node_101": { "id": "htcaid:node;node_101", "classType": "hybrid.actor.Node" }
  }
}
```

---

## 13. Diagrama de Sequência — Parada com Passageiros

```
TM(Bus)    Bus        BusStop     Person1    Person2    TM(Bus)
   │         │              │          │          │         │
   │─actSp──►│              │          │          │         │
   │         │ (leavingLink, currentStopNode=N)            │
   │         │─BusRequestUnload(N)──────►│          │         │
   │         │─BusRequestUnload(N)────────────────►│         │
   │         │◄onFinish(None)            │          │         │
   │         │◄─UnloadData(true)─────────│          │         │
   │         │◄─UnloadData(false)──────────────────│         │
   │         │ (delay unload) scheduleEvent ───────────────►│
   │         │◄─actSp────────────────────────────────────────│
   │         │─BusRequestPassenger(label)──►│      │         │
   │         │◄onFinish(None)               │      │         │
   │         │◄─BusLoadData(people)─────────│      │         │
   │         │ (delay load) scheduleEvent ────────────────►│
   │         │◄─actSp────────────────────────────────────────│
   │         │─enterLink()                               │   │
```

---

## 14. Limitações e Pontos de Extensão

| Limitação | Descrição |
|---|---|
| Velocidade de cruzeiro bus = car | No MESO, usa o mesmo `SpeedUtil` dos carros; não há penalidade de velocidade específica de ônibus |
| Rota circular simplificada | `getNextPath` reinicia do índice 0 — não modela retorno real ou frequência |
| Paradas intermediárias em MICRO | `checkBusStopAtPosition` registra posição mas não gerencia dwell time para paradas dentro do link (apenas no nó destino) |
| Sem capacidade de ultrapassagem | `canChangeLane = false` permanente |
| Headway não modelado | Múltiplos ônibus da mesma linha não se coordenam entre si |

### Como adicionar velocidade específica de ônibus no MESO

Sobrescrever `actHandleReceiveEnterLinkInfo` aplicando um fator de velocidade:

```scala
override def actHandleReceiveEnterLinkInfo(event, data) = {
  val busSpeedFactor = 0.85  // ônibus ~15% mais lento que carros
  val speed = linkDensitySpeed(...) * busSpeedFactor
  ...
}
```

---

## 15. Referências

### Modelo de car-following e parâmetros MICRO

- **Krauss, S. (1998).** *Microscopic Modeling of Traffic Flow: Investigation of Collision Free
  Vehicle Dynamics.* Ph.D. thesis, DLR / Universität Köln. — Modelo de car-following utilizado;
  define os parâmetros τ (reaction time), b (deceleration) e o cálculo de velocidade segura:
  $v_{safe} = -\tau b + \sqrt{(\tau b)^2 + v_\ell^2 + 2b \cdot gap}$.

- **Wiedemann, R. (1974).** *Simulation des Strassenverkehrsflusses.* Schriftenreihe Institut
  für Verkehrswesen, Univ. Karlsruhe. — Modelo psicofísico de seguimento de veículo; base para
  os parâmetros de gap (`minGap = s₀`) e tempo de reação.

- **Krajzewicz, D., Erdmann, J., Behrisch, M. & Bieker, L. (2012).** "Recent Development and
  Applications of SUMO — Simulation of Urban MObility." *International Journal On Advances in
  Systems and Measurements*, 5(3&4), 128–138. — Fonte primária para os defaults do tipo de
  veículo `bus` no SUMO: `length=12m`, `accel=1.2 m/s²`, `decel=4.0 m/s²`, `minGap=2.5m`,
  `tau=1.0s`. Os valores deste simulador seguem esses defaults com ajustes documentados abaixo.

- **Treiber, M. & Kesting, A. (2013).** *Traffic Flow Dynamics: Data, Models and Simulation.*
  Springer. — Distribuições de `v₀` (desired speed) e `speedFactor` para frotas urbanas;
  fundamento teórico para a parametrização de `speedFactor ∈ [0.5, 1.5]`.

- **Petzoldt, T. (2014).** "On the relationship between pedestrian gap acceptance and time to
  arrival estimates." *Accident Analysis & Prevention*, 72, 127–133. — Dados empíricos de tempo
  de reação de motoristas profissionais de veículos de grande porte (0,9–1,5 s), justificando
  `reactionTime = 1.5 s` (acima do `tau = 1.0 s` do SUMO para carros).

### Tabela de justificativas por parâmetro

| Parâmetro | Valor | Fonte / Justificativa |
|---|---|---|
| `vehicleLength` | 12,0 m | ABNT NBR 15570:2011 (ônibus simples 12–15 m); SUMO `bus` default; Vuchic (2005) |
| `maxAcceleration` | 1,2 m/s² | SUMO `bus` default; TRB TCQSM 3ª ed. (2013): 0,8–1,5 m/s² em condições urbanas |
| `maxDeceleration` | 3,5 m/s² | Frenagem de serviço confortável; SUMO `bus` usa 4,0 m/s² (máximo); ECE R13 ≥ 4,0 m/s² (emergência) |
| `minGap` | 3,0 m | SUMO default 2,5 m; aumentado para 3,0 m para refletir maior inércia; Wiedemann (1974) `s₀` |
| `desiredVelocity` | 11,11 m/s (40 km/h) | CONTRAN Resolução 432/2013 (40 km/h em vias locais); TRB TCQSM: 40–50 km/h em arteriais |
| `reactionTime` | 1,5 s | Petzoldt (2014): 0,9–1,5 s para motoristas profissionais de veículos pesados; Krauss (1998) parâmetro τ |
| `speedFactor ∈ [0,5; 1,5]` | default 1,0 | SUMO speed factor distribution; Treiber & Kesting (2013) Cap. 11; limites operacionais de serviço regulado |

### Dimensões do veículo

- **ABNT NBR 15570:2011.** *Transporte — Especificações técnicas para fabricação de veículos de
  características urbanas para transporte coletivo de passageiros.* ABNT, Rio de Janeiro. —
  Define ônibus urbano simples: comprimento de 12,00 m a 15,00 m.

- **Vuchic, V. R. (2005).** *Urban Transit: Operations, Planning and Economics.* Wiley. —
  Tabela 2-1: ônibus standard 12 m, articulado 18 m.

### Capacidade e velocidade operacional

- **TRB. (2013).** *Transit Capacity and Quality of Service Manual*, 3rd ed. TCRP Report 165.
  Transportation Research Board. — Capacidade de ônibus urbano, velocidade comercial (20–35 km/h
  com paradas; 40–50 km/h em segmentos livres), dwell time e modelos de embarque/desembarque.

- **CONTRAN. (2013).** *Resolução Nº 432, de 23 de janeiro de 2013.* DENATRAN, Brasil. —
  Limites de velocidade urbanos no Brasil: 40 km/h (vias locais), 50 km/h (coletoras), 60 km/h
  (arteriais). Valor de `desiredVelocity = 40 km/h` representa o limite conservador em rede mista.

- **Dueker, K. J., et al. (2004).** "Measuring Transit Stop Accessibility." *Transportation
  Research Record*, 1887. — Parâmetros de dwell time em paradas de ônibus.

### Frenagem — normas técnicas

- **UNECE Regulation No. 13 (ECE R13).** *Uniform provisions concerning the approval of
  vehicles of categories M, N and O with regard to braking.* UNECE. — Requisito mínimo de
  deceleração de serviço para ônibus pesados (≥ 4,0 m/s²). O valor `maxDeceleration = 3,5 m/s²`
  representa frenagem sub-máxima para conforto dos passageiros em paradas.

### Modelo de velocidade em links MESO

- **Greenshields, B. D. (1935).** "A study of traffic capacity." *Proceedings of the Highway
  Research Board*, 14, 448–477. — Modelo de relação velocidade-densidade (base do SpeedUtil).

- **Bureau of Public Roads. (1964).** *Traffic Assignment Manual.* U.S. Dept. of Commerce. —
  Função BPR para tempo de link.
- [src/main/scala/model/hybrid/actor/Bus.scala](../src/main/scala/model/hybrid/actor/Bus.scala)
- [src/main/scala/model/hybrid/entity/state/BusState.scala](../src/main/scala/model/hybrid/entity/state/BusState.scala)
- [src/main/scala/model/hybrid/entity/state/MicroBusState.scala](../src/main/scala/model/hybrid/entity/state/MicroBusState.scala)
- [src/main/scala/model/hybrid/actor/BusStop.scala](../src/main/scala/model/hybrid/actor/BusStop.scala)
- [src/main/scala/model/hybrid/util/BusUtil.scala](../src/main/scala/model/hybrid/util/BusUtil.scala)
- [src/main/scala/model/hybrid/util/SpeedUtil.scala](../src/main/scala/model/hybrid/util/SpeedUtil.scala)
- [PERSON_AGENT.md](PERSON_AGENT.md) — Perspectiva do passageiro
