# Documentação: Entidades do Modelo Híbrido — Lógica, Interações e Ciclo de Vida

## Índice

1. [Visão Geral da Arquitetura](#1-visão-geral-da-arquitetura)
2. [Hierarquia de Classes](#2-hierarquia-de-classes)
3. [Relação com TimeManager](#3-relação-com-timemanager)
4. [Relação com ReportManager](#4-relação-com-reportmanager)
5. [Person — Agente Pessoa](#5-person--agente-pessoa)
6. [Car — Veículo Privado](#6-car--veículo-privado)
7. [Link — Segmento de Via](#7-link--segmento-de-via)
8. [Node — Interseção](#8-node--interseção)
9. [Bus — Ônibus](#9-bus--ônibus)
10. [BusStation — Garagem/Gerenciador de Linha](#10-busstation--garagemgerenciador-de-linha)
11. [BusStop — Parada de Ônibus](#11-busstop--parada-de-ônibus)
12. [Subway — Metrô](#12-subway--metrô)
13. [SubwayStation — Estação de Metrô](#13-subwaystation--estação-de-metrô)
14. [RailLink — Segmento Ferroviário](#14-raillink--segmento-ferroviário)
15. [TrafficSignal — Semáforo](#15-trafficsignal--semáforo)
16. [Bicycle — Bicicleta](#16-bicycle--bicicleta)
17. [Motorcycle — Motocicleta](#17-motorcycle--motocicleta)
18. [Fluxos de Mensagem Completos](#18-fluxos-de-mensagem-completos)
19. [Protocolos de Comunicação](#19-protocolos-de-comunicação)
20. [Relatórios Emitidos](#20-relatórios-emitidos)

---

## 1. Visão Geral da Arquitetura

O sistema é uma simulação de tráfego distribuída baseada em atores (Apache Pekko). Quatro entidades centrais participam do modelo de mobilidade híbrido (meso/micro):

| Entidade | Papel | Classe Base |
|----------|-------|-------------|
| **Person** | Agente-pessoa que gerencia agenda diária e escolha modal | `SimulationBaseActor[PersonState]` |
| **Car** | Veículo privado que navega pela rede viária | `Movable[CarState]` + `PrivateVehicle[CarState]` |
| **Link** | Segmento de via (aresta do grafo) que gerencia fluxo de veículos | `SimulationBaseActor[LinkState]` |
| **Node** | Interseção (nó do grafo) que gerencia semáforos e conexões | `SimulationBaseActor[NodeState]` |
| **Bus** | Ônibus que transporta passageiros em rota fixa | `Movable[BusState]` |
| **BusStation** | Garagem que gerencia a linha e cria ônibus | `SimulationBaseActor[BusStationState]` |
| **BusStop** | Parada onde passageiros embarcam/desembarcam | `SimulationBaseActor[BusStopState]` |
| **Subway** | Trem/metrô que percorre trilhos dedicados | `Movable[SubwayState]` |
| **SubwayStation** | Estação que gerencia linhas e cria metrôs | `SimulationBaseActor[SubwayStationState]` |
| **RailLink** | Segmento ferroviário exclusivo para metrô | `SimulationBaseActor[RailLinkState]` |
| **TrafficSignal** | Semáforo que controla fases verde/vermelho | `SimulationBaseActor[TrafficSignalState]` |
| **Bicycle** | Bicicleta — veículo privado de baixa velocidade | `Movable[BicycleState]` + `PrivateVehicle[BicycleState]` |
| **Motorcycle** | Motocicleta — veículo ágil com lane filtering | `Movable[MotorcycleState]` + `PrivateVehicle[MotorcycleState]` |

### Comunicação entre Entidades

Todas as entidades comunicam-se exclusivamente por **troca de mensagens assíncronas** (eventos). Existem dois tipos fundamentais de eventos:

- **SpontaneousEvent**: Disparado pelo TimeManager em um tick agendado. O ator processa lógica interna.
- **ActorInteractionEvent**: Mensagem enviada por outro ator via `sendMessageTo()`.

---

## 2. Hierarquia de Classes

```
SimulationBaseActor[T]          ← Classe base do framework
  ├── Person                    ← SimulationBaseActor[PersonState]
  ├── Link                      ← SimulationBaseActor[LinkState]
  ├── RailLink                  ← SimulationBaseActor[RailLinkState]
  ├── Node                      ← SimulationBaseActor[NodeState]
  ├── BusStation                ← SimulationBaseActor[BusStationState]
  ├── BusStop                   ← SimulationBaseActor[BusStopState]
  ├── SubwayStation             ← SimulationBaseActor[SubwayStationState]
  ├── TrafficSignal             ← SimulationBaseActor[TrafficSignalState]
  └── Movable[T <: MovableState]  ← Classe base para entidades móveis
        ├── Car                 ← Movable[CarState] with PrivateVehicle[CarState]
        ├── Bus                 ← Movable[BusState]
        ├── Subway              ← Movable[SubwayState]
        ├── Bicycle             ← Movable[BicycleState] with PrivateVehicle[BicycleState]
        └── Motorcycle          ← Movable[MotorcycleState] with PrivateVehicle[MotorcycleState]
```

### SimulationBaseActor — Métodos-Chave Herdados

| Método | Descrição |
|--------|-----------|
| `actSpontaneous(event)` | Callback executado quando o TimeManager acorda o ator |
| `actInteractWith(event)` | Callback executado ao receber mensagem de outro ator |
| `onFinishSpontaneous(Some(tick))` | Finaliza processamento do tick atual e agenda o próximo tick no TimeManager |
| `onFinishSpontaneous(None)` | Finaliza processamento e **desregistra** do TimeManager (para de receber ticks) |
| `scheduleEvent(tick)` | Registra o ator no pool do TimeManager para um tick específico (usado para primeiro registro) |
| `sendMessageTo(entityId, shardId, data, eventType)` | Envia `ActorInteractionEvent` para outro ator |
| `report(data, label)` | Envia evento de report ao ReportManager |
| `selfDestruct()` | Destrói o ator permanentemente |
| `currentTick` | Tick atual do ator (definido pelo último SpontaneousEvent recebido) |

### Movable — Classe Base para Veículos

Adiciona lógica de navegação pela rede viária:

| Método | Descrição |
|--------|-----------|
| `requestRoute()` | Calcula rota usando `GPSUtil.calcRoute()` |
| `enterLink()` | Envia `EnterLinkData` ao Link atual da rota |
| `leavingLink()` | Envia `LeaveLinkData` ao Link atual e avança para próximo segmento |
| `getCurrentNode` | Retorna o nó associado ao path atual |
| `getNextLink` | Retorna o próximo link da rota |

### PrivateVehicle — Trait para Veículos Privados

Adiciona protocolo de ativação/desativação por Person:

| Método | Descrição |
|--------|-----------|
| `handleStartTrip(event, data)` | Ativa veículo: Parked → Start, registra no TimeManager |
| `onFinishPrivateVehicle(nodeId)` | Envia `TripCompletedData` ao Person dono e desativa veículo |
| `reportTripCompletion(reason, nodeId)` | Calcula distância/tempo e envia TripCompleted |
| `deactivateVehicle()` | Retorna veículo ao estado Parked e desregistra do TimeManager |

---

## 3. Relação com TimeManager

O **TimeManager (TM)** é o relógio central da simulação. Ele avança tick por tick e acorda os atores registrados.

### Mecanismo de Registro/Desregistro

```
Ator registrado no TM ──────── recebe SpontaneousEvent a cada tick agendado
Ator desregistrado (None) ──── NÃO recebe mais SpontaneousEvents
```

| Operação | Método | Efeito |
|----------|--------|--------|
| Agendar próximo tick | `onFinishSpontaneous(Some(tick))` | TM acordará o ator no tick especificado |
| Desregistrar | `onFinishSpontaneous(None)` | TM não acorda mais o ator |
| Primeiro registro | `scheduleEvent(tick)` | Registra via pool router do TM (para atores passivos) |

### Quem se Registra no TimeManager

| Entidade | Quando se registra | Quando se desregistra |
|----------|-------------------|----------------------|
| **Person** | Ao iniciar (agenda diária) | Durante trip com veículo; ao completar agenda |
| **Car** | Quando ativado por Person via `StartTrip` | Quando em MICRO mode; quando Parked; quando Finished |
| **Link (MICRO)** | Quando primeiro veículo entra | Quando não há mais veículos no link |
| **Link (MESO)** | Não usa TM (veículos gerenciam seus ticks) | N/A |
| **Node** | Responde a interações; não agenda ticks próprios | Sempre desregistra (`onFinishSpontaneous(None)`) |
| **Bus** | Ao ser criado pela BusStation (com rota pré-calculada) | Quando Finished; durante espera por passageiros (temporário) |
| **BusStation** | No início da simulação (status Start) | Ao completar criação de todos os ônibus; fim da simulação |
| **BusStop** | Não usa TM (puramente reativo a interações) | N/A |
| **Subway** | Ao ser criado pela SubwayStation (com rota pré-definida) | Durante espera por embarque/desembarque; fim da simulação |
| **SubwayStation** | No início da simulação (status Start) | Quando `simulationEnd` atingido |
| **RailLink** | Não usa TM (`scheduleOnTimeManager = false`) | N/A |
| **TrafficSignal** | Em `onInitialize()` via `scheduleEvent(startTick + offset)` | Quando `simulationEnd` atingido |
| **Bicycle** | Quando ativado por Person via `StartTrip` | Quando Parked; quando Finished; durante espera |
| **Motorcycle** | Quando ativado por Person via `StartTrip` | Quando Parked; quando Finished; durante espera |

### Propriedade do TimeManager — Quem "Acorda" Quem

Um conceito crítico é a **propriedade do TM** durante diferentes fases:

```
┌─────────────────────────────────────────────────────┐
│ Fase "Activity"     → Person possui o TM            │
│ Fase "Walking Trip" → Person possui o TM            │
│ Fase "Vehicle Trip" → Car possui o TM (MESO)        │
│                       Link possui o TM (MICRO)      │
│ Fase "PT Trip"      → Bus/Subway possui o TM        │
└─────────────────────────────────────────────────────┘
```

**Regra fundamental**: Em qualquer momento, **apenas um ator** por "viagem" está registrado no TM. Isso evita conflitos e garante progressão correta da simulação.

---

## 4. Relação com ReportManager

Todos os atores emitem relatórios via `report(data, label)`. O ReportManager é um ator separado que:

1. Recebe `ReportEvent` de qualquer ator
2. Persiste os dados conforme estratégia configurada (CSV, Kafka/Avro, etc.)
3. Incrementa métricas Prometheus automáticas (labels "journey_started", "journey_completed")

### Estrutura de um Relatório

```scala
report(
  data = Map(
    "event_type" -> "...",    // Tipo do evento (string descritiva)
    "entity_id"  -> "...",    // ID da entidade que reporta
    // ... campos específicos do evento
    "tick"       -> currentTick  // Tick em que ocorreu
  ),
  label = "nome_do_label"     // Label para categorização e métricas
)
```

Os relatórios são **fire-and-forget**: o ator envia e não espera resposta.

---

## 5. Person — Agente Pessoa

### Estado (`PersonState`)

| Campo | Tipo | Descrição |
|-------|------|-----------|
| `activities` | `List[Activity]` | Agenda diária ordenada |
| `currentActivityIndex` | `Int` | Índice da atividade atual |
| `currentTripVehicleId` | `Option[String]` | ID do veículo se em viagem |
| `currentTripStartTick` | `Option[Tick]` | Tick de início da viagem atual |
| `ownedVehicles` | `Map[String, Identify]` | Veículos pertencentes (mode → ref) |
| `completedTrips` | `Int` | Contador de viagens completadas |
| `totalDistanceTraveled` | `Double` | Distância total percorrida |
| `ptAlightingNodeId` | `Option[String]` | Nó de desembarque (PT) |
| `ptLine` | `Option[String]` | Linha PT atual |

### Ciclo de Vida

```
INÍCIO
  │
  ▼
[Activity: Home] ───── endTime atingido? ────── NÃO → sleep até endTime (TM)
  │                                              │
  SIM                                            │
  │                                              │
  ▼                                              │
startNextTrip() ◄────────────────────────────────┘
  │
  ├── nextActivity.arrivalLogistics.mode?
  │
  ├── "walk" ─────────────── initiateWalkingTrip()
  │                            │ calcula rota e distância
  │                            │ walkingTime = distance / 1.4 m/s
  │                            │ agenda arrivalTick no TM
  │                            │ state.currentTripVehicleId = "walking"
  │                            ▼
  │                          [Sleeping até arrivalTick]
  │                            │
  │                            ▼
  │                          advanceToNextActivity()
  │
  ├── "car"/"bicycle"/"motorcycle" ── initiatePrivateVehicleTrip()
  │                                      │ envia StartTripData ao veículo
  │                                      │ state.currentTripVehicleId = vehicleId
  │                                      │ desregistra do TM (None)
  │                                      ▼
  │                                    [Aguardando TripCompletedData]
  │                                      │ (handleTripCompleted)
  │                                      ▼
  │                                    advanceToNextActivity()
  │
  ├── "bus"/"subway"/"transit" ────── initiatePTTrip()
  │                                    │ envia RegisterPassengerData ao stop
  │                                    │ state.currentTripVehicleId = "pt:mode:line"
  │                                    │ desregistra do TM (None)
  │                                    ▼
  │                                  [Aguardando BusRequestUnloadPassengerData]
  │                                    │ (handlePTUnloadRequest)
  │                                    │ responde com BusUnloadPassengerData
  │                                    │ se isArrival → advanceToNextActivity()
  │                                    ▼
  │                                  [Re-registra no TM]
  │
  └── "instant" ──────────────────── advanceToNextActivity() direto
          │
          ▼
  [Activity: próxima] ─── repete ciclo
          │
          (sem mais atividades)
          │
          ▼
  onFinishSpontaneous(None) ── Person desregistra definitivamente
```

### Eventos Recebidos (actInteractWith)

| Evento | Origem | Ação |
|--------|--------|------|
| `TripCompletedData` | Car/Bicycle/Motorcycle | `handleTripCompleted()` → `advanceToNextActivity()` |
| `BusRequestUnloadPassengerData` | Bus | `handlePTUnloadRequest()` → responde com `BusUnloadPassengerData` |
| `SubwayRequestUnloadPassengerData` | Subway | `handlePTUnloadRequest()` → responde com `SubwayUnloadPassengerData` |

### Eventos Enviados

| Evento | Destino | Quando |
|--------|---------|--------|
| `StartTripData` | Car/Bicycle/Motorcycle | `initiatePrivateVehicleTrip()` — ativa veículo |
| `RegisterPassengerData` | BusStop | `initiatePTTrip()` — registra para embarque |
| `RegisterSubwayPassengerData` | SubwayStation | `initiatePTTrip()` — registra para embarque |
| `BusUnloadPassengerData` | Bus | `handlePTUnloadRequest()` — resposta de desembarque |
| `SubwayUnloadPassengerData` | Subway | `handlePTUnloadRequest()` — resposta de desembarque |

### Relatórios Emitidos

| Label | Quando | Campos-Chave |
|-------|--------|-------------|
| `person_walking_start` | Início de viagem a pé | origin, destination, distance, walking_time_ticks |
| `person_walking_completed` | Fim de viagem a pé | travel_time, arrival_tick |
| `person_pt_trip_start` | Início de viagem PT | mode, line, boarding_stop, alighting_node |
| `person_pt_trip_completed` | Desembarque de PT | pt_type, vehicle_id, line, travel_time |
| `person_trip_completed` | Veículo privado completou viagem | vehicle_id, distance_traveled, travel_time, completion_reason |
| `person_activity_start` | Chegada em nova atividade | activity_type, node_id, end_time |
| `person_schedule_complete` | Agenda diária finalizada | total_trips, total_distance |

---

## 6. Car — Veículo Privado

### Estado (`CarState`)

Herda de `MovableState` e adiciona:

| Campo | Tipo | Descrição |
|-------|------|-----------|
| `origin` | `String` | Nó de origem da viagem |
| `destination` | `String` | Nó de destino |
| `startTick` | `Tick` | Tick planejado de partida |
| `bestRoute` | `Option[Queue[(String, String)]]` | Rota calculada (linkId, nodeId) |
| `distance` | `Double` | Distância percorrida acumulada |
| `status` / `movableStatus` | `MovableStatusEnum` | Estado da máquina de estados |
| `microState` | `Option[MicroCarState]` | Estado microscópico (ativo em modo MICRO) |
| `precomputedRoute` | `Option[List[RoutePathItem]]` | Rota pré-calculada (input JSON) |

### Variáveis Internas (não-estado)

| Variável | Tipo | Descrição |
|----------|------|-----------|
| `journeyFinishedReported` | `Boolean` | Trava para garantir report único de finalização |
| `currentLinkId` | `Option[String]` | Link onde o carro está atualmente |
| `currentLinkLength` | `Double` | Comprimento do link atual |
| `linkEntryTick` | `Option[Tick]` | Tick de entrada no link atual |
| `mesoExitTick` | `Option[Tick]` | Tick de saída previsto (MESO) |
| `signalWaitUntilTick` | `Option[Tick]` | Tick até o qual espera sinal verde |
| `sumo*` | Variados | Variáveis para relatório compatível com SUMO |

### Máquina de Estados (`MovableStatusEnum`)

```
Parked ──── StartTripData ────► Start
                                  │
                                  │ requestRoute()
                                  ▼
                                Ready
                                  │
                                  │ enterLink()
                                  ▼
                              Waiting ◄──── (aguardando resposta do Link)
                                  │
                                  │ LinkInfoData / MicroEnterLinkData
                                  ▼
                              Moving ──────── (atravessando link)
                                  │
                              ┌───┤ (chegou fim do link)
                              │   │
                              │   ▼
                              │ WaitingSignalState ──── (pediu sinal ao Node)
                              │   │
                              │   │ SignalStateData
                              │   │
                              │   ├── Red → WaitingSignal (espera nextTick)
                              │   │          │
                              │   │          ▼ (sinal ficou verde)
                              │   │        leavingLink()
                              │   │          │
                              │   └── Green → leavingLink()
                              │                │
                              │                ▼
                              │              Ready (próximo link)
                              │                │
                              │                ▼
                              │            enterLink() ─── repete ciclo
                              │
                              └── (destino alcançado)
                                    │
                                    ▼
                                  Finished
                                    │
                                    │ finishJourney() + selfDestruct()
                                    ▼
                                  [Ator destruído]
```

### Ciclo de Vida Completo

#### Fase 1: Dormindo (Parked)
- Car é criado com `scheduleOnTimeManager = false` (não recebe ticks)
- Estado: **Parked**
- Aguarda mensagem `StartTripData` de seu Person dono

#### Fase 2: Ativação (StartTrip)
1. Person envia `StartTripData(personId, origin, destination, driverAttributes)`
2. `PrivateVehicle.handleStartTrip()`:
   - Guarda referência ao Person (`ownerPersonRef`)
   - Aplica `driverAttributes` (agressividade, velocidade máxima, tempo de reação)
   - Transição: Parked → Start
   - Registra no TM via `scheduleEvent(currentTick + 1)`

#### Fase 3: Cálculo de Rota
3. TM acorda Car em `actSpontaneous()`
4. `Movable.actSpontaneous()` detecta status `Start` → chama `requestRoute()`
5. `Car.requestRoute()`:
   - Tenta usar rota pré-computada (do JSON de input)
   - Senão, tenta usar `state.bestRoute` (pré-carregada)
   - Senão, calcula via `GPSUtil.calcRoute(origin, destination)`
6. Se rota encontrada → monta `bestRoute` como `Queue[(linkId, nodeId)]`
7. Emite reports: `journey_started`, `route_planned`

#### Fase 4: Navegação (Link por Link)

**Modo MESO:**
```
Car                           Link                          Node
 │                             │                             │
 │── EnterLinkData ──────────►│                             │
 │                             │── LinkInfoData ───────────►│ (enter)
 │◄── LinkInfoData ────────────│                             │
 │                             │                             │
 │ [calcula speed via          │                             │
 │  linkDensitySpeed()]        │                             │
 │ [calcula time = len/speed]  │                             │
 │ [agenda exitTick no TM]     │                             │
 │                             │                             │
 │ ... (aguarda exitTick) ...  │                             │
 │                             │                             │
 │── RequestSignalStateData ──────────────────────────────►│
 │                             │                             │
 │◄── SignalStateData (Green/Red) ─────────────────────────│
 │                             │                             │
 │ [se Red: espera nextTick]   │                             │
 │ [se Green: leavingLink()]   │                             │
 │                             │                             │
 │── LeaveLinkData ──────────►│                             │
 │◄── LinkInfoData ────────────│ (leave)                    │
 │                             │                             │
 │ [distance += linkLength]    │                             │
 │ [avança para próximo link]  │                             │
```

**Modo MICRO:**
```
Car                           Link                          Node
 │                             │                             │
 │── EnterLinkData ──────────►│                             │
 │                             │ [atribui lane]              │
 │                             │ [agenda micro tick no TM]   │
 │◄── MicroEnterLinkData ─────│                             │
 │                             │                             │
 │ [Car desregistra do TM]     │                             │
 │ [Car fica passivo]          │                             │
 │                             │                             │
 │                    TM ────►│ [actSpontaneous]            │
 │                             │ [executa N sub-ticks]       │
 │                             │ [car-following model]       │
 │◄── MicroUpdateData ────────│ (por sub-tick)              │
 │                             │                             │
 │ [atualiza microState]       │                             │
 │ [se position >= linkLength] │                             │
 │                             │                             │
 │── LeaveLinkData ──────────►│                             │
 │◄── MicroLeaveLinkData ─────│                             │
 │                             │                             │
 │ [deactivateMicroMode()]     │                             │
 │ [re-registra no TM]         │                             │
 │                             │                             │
 │── RequestSignalStateData ──────────────────────────────►│
 │◄── SignalStateData ─────────────────────────────────────│
 │                             │                             │
```

#### Fase 5: Chegada ao Destino
8. Rota esgotada OU `destination == currentPathNode`
9. `finishJourney(reason, finalNode)`:
   - Emite reports: `journey_completed`, `vehicle_event_count`, `sumo_tripinfo`
   - Status → Finished
10. `onFinishPrivateVehicle(nodeId)`:
    - Calcula distância e tempo da viagem
    - Envia `TripCompletedData` ao Person dono
    - Desativa veículo (Parked)
    - Desregistra do TM
11. `selfDestruct()` — ator é destruído

#### Fase 6: Destruição Forçada
- Se simulação termina antes da chegada: `onDestruct()` chama `finishJourney("actor_destructed_before_completion")`
- Se tempo excedido: `actSpontaneous()` detecta `currentTick >= simulationEndTick` e força finalização

### Eventos Recebidos (actInteractWith)

| Evento | Origem | Ação |
|--------|--------|------|
| `StartTripData` | Person | Ativa veículo, começa viagem |
| `ParkVehicleData` | Person | Estaciona veículo |
| `LinkInfoData` (EnterLink) | Link (MESO) | Calcula velocidade e agenda saída |
| `LinkInfoData` (LeaveLink) | Link (MESO) | Acumula distância, avança rota |
| `SignalStateData` | Node | Decide se espera (Red) ou avança (Green) |
| `MicroEnterLinkData` | Link (MICRO) | Inicializa microState, desregistra do TM |
| `MicroUpdateData` | Link (MICRO) | Atualiza posição/velocidade/aceleração |
| `MicroLeaveLinkData` | Link (MICRO) | Finaliza link micro, re-registra no TM |

### Eventos Enviados

| Evento | Destino | Quando |
|--------|---------|--------|
| `EnterLinkData` | Link | Entrando em um novo link da rota |
| `LeaveLinkData` | Link | Saindo do link (após sinal verde ou micro completo) |
| `RequestSignalStateData` | Node | Após completar travessia do link |
| `TripCompletedData` | Person | Viagem finalizada — via `PrivateVehicle.reportTripCompletion()` |

### Relatórios Emitidos

| Label | Quando | Campos-Chave |
|-------|--------|-------------|
| `journey_started` | Rota calculada | origin, destination, route_cost, route_length |
| `route_planned` | Rota calculada | route_links, route_nodes, route_source |
| `enter_link` | Entrando link MESO | link_id, link_length, calculated_speed, travel_time |
| `enter_micro_link` | Entrando link MICRO | link_id, lane, speed_limit, initial_velocity |
| `leave_link` | Saindo link MESO | link_id, link_length, total_distance |
| `leave_micro_link` | Saindo link MICRO | final_position, final_velocity, waiting_time_seconds |
| `signal_wait` | Esperando sinal vermelho | phase, wait_until_tick |
| `journey_completed` | Viagem finalizada | final_node, reached_destination, completion_reason, total_distance |
| `vehicle_event_count` | Viagem finalizada | car_id |
| `sumo_tripinfo` | Viagem finalizada | depart, arrival, duration, routeLength, waitingTime, timeLoss, departSpeed, arrivalSpeed |

---

## 7. Link — Segmento de Via

### Estado (`LinkState`)

| Campo | Tipo | Descrição |
|-------|------|-----------|
| `from` / `to` | `String` | Nós de origem/destino do link |
| `length` | `Double` | Comprimento em metros |
| `lanes` | `Int` | Número de faixas |
| `speedLimit` | `Double` | Velocidade limite (km/h) |
| `capacity` | `Double` | Capacidade de veículos |
| `freeSpeed` | `Double` | Velocidade em fluxo livre (m/s) |
| `simulationMode` | `SimulationModeEnum` | MESO ou MICRO |
| `registered` | `Set[LinkRegister]` | Veículos registrados no link |
| `vehiclesByLane` | `Map[Int, Queue[VehicleInLane]]` | Veículos por faixa (MICRO) |
| `microTimeStep` | `Double` | Passo de tempo micro (padrão: 0.1s) |
| `microTicksPerGlobalTick` | `Int` | Sub-ticks por tick global (padrão: 10) |
| `currentSpeed` | `Double` | Velocidade média atual |
| `congestionFactor` | `Double` | Fator de congestionamento |

### Ciclo de Vida

#### Inicialização
1. `onInitialize()`:
   - Se modo MICRO: `initializeMicroMode()` — inicializa filas por lane, estratégias de car-following
   - Publica custo dinâmico inicial via `publishDynamicCost()`

#### Modo MESO — Fluxo de Operação

No modo MESO, o Link é **passivo** em relação ao TimeManager. Não precisa receber ticks espontâneos. Toda a comunicação é feita via mensagens de veículos:

```
Veículo                        Link (MESO)
  │                              │
  │── EnterLinkData ───────────►│
  │                              │ [adiciona ao registered]
  │                              │ [monta LinkInfoData]
  │◄── LinkInfoData (enter) ────│
  │                              │
  │ ... (veículo gerencia       │
  │      seus próprios ticks)   │
  │                              │
  │── LeaveLinkData ───────────►│
  │                              │ [remove do registered]
  │                              │ [monta LinkInfoData]
  │◄── LinkInfoData (leave) ────│
```

**Resposta ao EnterLink (MESO):**
- Verifica duplicata
- Adiciona veículo ao `state.registered`
- Responde com `LinkInfoData(length, capacity, numberOfCars, freeSpeed, lanes)`
- O veículo usa esses dados para calcular velocidade via `linkDensitySpeed()`

**Resposta ao LeaveLink (MESO):**
- Remove veículo do `state.registered`
- Responde com `LinkInfoData` para o veículo registrar saída

#### Modo MICRO — Fluxo de Operação

No modo MICRO, o Link é **ativo** — ele se registra no TimeManager e executa simulação microscópica a cada tick:

```
TimeManager                    Link (MICRO)                    Veículos
  │                              │                              │
  │── SpontaneousEvent ────────►│                              │
  │                              │ handleGlobalTick(tick)       │
  │                              │                              │
  │                              │ [publica custo dinâmico]     │
  │                              │                              │
  │                              │ executeSubTick() × N         │
  │                              │  ├── car-following (Krauss)  │
  │                              │  ├── atualiza position       │
  │                              │  ├── atualiza velocity       │
  │                              │  └── detecta halting         │
  │                              │                              │
  │                              │── MicroUpdateData ─────────►│
  │                              │── MicroUpdateData ─────────►│
  │                              │                              │
  │                              │ [emite sumo_summary_step]    │
  │                              │                              │
  │◄── FinishEvent + Schedule ──│ (se há veículos: agenda +1) │
```

**Sub-ticks:** A cada tick global, o Link executa `microTicksPerGlobalTick` sub-ticks (tipicamente 10, correspondendo a 0.1s cada). Usa o modelo de car-following de Krauss para calcular aceleração, velocidade e posição de cada veículo.

**Auto-agendamento:** O Link agenda-se no TM apenas quando há veículos. Quando o último veículo sai, ele desregistra (`microTickScheduled = false`).

### Estratégias (Strategy Pattern)

| Estratégia | Interface | Implementação Padrão |
|------------|-----------|---------------------|
| Car-following | `MicroSimulationStrategy` | `DefaultMicroSimulationStrategy` (Krauss) |
| Lane change | `LaneChangeStrategy` | `NoLaneChangeStrategy` (sem mudança de faixa) |

### Publicação de Custo Dinâmico

A cada `costPublishInterval` ticks (padrão: 10), o Link publica custo dinâmico via Kafka:

```scala
cost = length × congestionFactor + length / currentSpeed
```

Usado pelo sistema de roteamento dinâmico para recalcular rotas.

### Eventos Recebidos (actInteractWith)

| Evento | Origem | Ação |
|--------|--------|------|
| `EnterLinkData` | Car/Movable | `handleEnterLink()` → MESO ou MICRO |
| `LeaveLinkData` | Car/Movable | `handleLeaveLink()` → remove veículo, responde |

### Eventos Enviados

| Evento | Destino | Quando |
|--------|---------|--------|
| `LinkInfoData` (ReceiveEnterLinkInfo) | Car (MESO) | Após veículo entrar |
| `LinkInfoData` (ReceiveLeaveLinkInfo) | Car (MESO) | Após veículo sair |
| `MicroEnterLinkData` | Car (MICRO) | Após veículo entrar em link MICRO |
| `MicroUpdateData` | Car (MICRO) | A cada sub-tick com posição/velocidade atualizada |
| `MicroLeaveLinkData` | Car (MICRO) | Quando veículo é removido do link MICRO |
| `DestructEvent` (forward) | Todos veículos registrados | Em `onDestruct()` do Link |

### Relatórios Emitidos

| Label | Quando | Campos-Chave |
|-------|--------|-------------|
| `link_vehicle_entered` | Veículo entra | vehicle_id, simulation_mode, vehicles_in_link |
| `link_vehicle_left` | Veículo sai | vehicle_id, vehicles_remaining |
| `sumo_summary_step` | A cada tick (MICRO) | running, halting, meanSpeed, meanWaitingTime, meanTravelTime |

### Destruição

`onDestruct()` encaminha `DestructEvent` para todos os veículos registrados. Isso é crítico em modo MICRO onde veículos não gerenciam seus próprios ticks.

---

## 8. Node — Interseção

### Estado (`NodeState`)

| Campo | Tipo | Descrição |
|-------|------|-----------|
| `latitude` / `longitude` | `Double` | Coordenadas geográficas |
| `connections` | `Map[String, Identify]` | Mapa linkId → sinal (Identify) |
| `signals` | `Map[String, SignalState]` | Estado dos semáforos (por ID do sinal) |
| `busStops` | `Map[String, Identify]` | Paradas de ônibus registradas |
| `subwayStations` | `Map[String, Identify]` | Estações de metrô registradas |

### Ciclo de Vida

O Node é um ator **reativo puro**. Ele:
- **Não agenda ticks próprios** — `actSpontaneous()` sempre chama `onFinishSpontaneous(None)`
- **Responde apenas a interações** — funciona como lookup/router de estados de semáforo

### Fluxo de Consulta de Semáforo

```
Car                            Node                           TrafficSignal
 │                              │                              │
 │── RequestSignalStateData ──►│                              │
 │   (targetLinkId)             │                              │
 │                              │ state.connections            │
 │                              │   .get(targetLinkId)         │
 │                              │     → signalIdentify         │
 │                              │                              │
 │                              │ state.signals                │
 │                              │   .get(signalId)             │
 │                              │     → SignalState            │
 │                              │                              │
 │◄── SignalStateData ─────────│                              │
 │   (phase, nextTick)          │                              │
```

### Lógica de Resolução de Semáforo

```
connections.get(targetLinkId)?
  ├── Sim → signals.get(signalId)?
  │          ├── Sim → responde com fase e nextTick do sinal
  │          └── Não → responde Green (sem sinal = via livre)
  └── Não → responde Green (sem conexão = via livre)
```

Quando `state == null` (ator não inicializado): responde Green como fallback.

### Registro de Infraestrutura

O Node também serve como ponto de registro para paradas de transporte público:

| Evento | Ação |
|--------|------|
| `RegisterBusStopData` | `state.busStops.put(label, identify)` |
| `RegisterSubwayStationData` | `state.subwayStations.put(line, identify)` |
| `TrafficSignalChangeStatusData` | `state.signals.put(phaseOrigin, signalState)` |

### Eventos Recebidos (actInteractWith)

| Evento | Origem | Ação |
|--------|--------|------|
| `RequestSignalStateData` | Car/Movable | Consulta semáforo e responde com `SignalStateData` |
| `RegisterBusStopData` | BusStop | Registra parada de ônibus |
| `RegisterSubwayStationData` | SubwayStation | Registra estação de metrô |
| `TrafficSignalChangeStatusData` | TrafficSignal | Atualiza estado do semáforo |

### Eventos Enviados

| Evento | Destino | Quando |
|--------|---------|--------|
| `SignalStateData` | Car/Movable | Em resposta a `RequestSignalStateData` |

### Relatórios Emitidos

| Label | Quando | Campos-Chave |
|-------|--------|-------------|
| `node_signal_requested` | Consulta de semáforo (com sinal existente) | node_id, link_id, signal_id, phase_state, vehicle_id |

---

## 9. Bus — Ônibus

### Visão Geral

O Bus é um veículo de transporte público que opera em rota fixa circular (ida e volta entre paradas). Diferencia-se do Car por:
- **Rota pré-definida**: calculada pelo BusStation, percorrida ciclicamente
- **Gestão de passageiros**: embarque/desembarque em paradas (BusStop)
- **Parâmetros físicos distintos**: comprimento 12m, aceleração menor (1.2 m/s²), velocidade máxima menor (40 km/h)
- **Interação com Person**: solicita desembarque a cada parada; carrega novos passageiros
- **Ciclo infinito**: rota ida → volta → ida → volta (até fim da simulação)

### Estado (`BusState`)

| Campo | Tipo | Descrição |
|-------|------|-----------|
| `startTick` | `Tick` | Tick de partida |
| `origin` / `destination` | `String` | Nós extremos da linha |
| `label` | `String` | Identificador da linha (ex: "101") |
| `capacity` | `Int` | Capacidade máxima de passageiros |
| `numberOfPorts` | `Int` | Número de portas (afeta tempo de embarque) |
| `busStops` | `Map[String, String]` | Mapa busStopId → nodeId |
| `people` | `Map[String, Identify]` | Passageiros a bordo (personId → ref) |
| `bestRoute` | `Option[Queue[(String, String)]]` | Rota completa (linkId, nodeId) |
| `currentPathPosition` | `Int` | Posição atual na rota (cíclica) |
| `distance` | `Double` | Distância total percorrida |
| `status` | `MovableStatusEnum` | Estado da máquina de estados |
| `microState` | `Option[MicroBusState]` | Estado microscópico (MICRO mode) |
| `countUnloadReceived` / `countUnloadPassenger` | `Int` | Contadores de respostas de desembarque |

### Variáveis Internas

| Variável | Tipo | Descrição |
|----------|------|-----------|
| `currentLinkId` | `Option[String]` | Link atual |
| `linkEntryTick` | `Option[Tick]` | Tick de entrada no link |
| `mesoExitTick` | `Option[Tick]` | Tick previsto de saída MESO |
| `signalWaitUntilTick` | `Option[Tick]` | Tick de espera no sinal |
| `expectedUnloadResponses` | `Int` | Respostas de desembarque esperadas |
| `currentStopNode` | `Option[String]` | Nó da parada atual (salvo antes de `leavingLink`) |
| `sumo*` | Variados | Métricas SUMO |

### Máquina de Estados (`MovableStatusEnum`)

```
Start ──────── actSpontaneous ──────► Ready
                                       │
                                       │ enterLink()
                                       ▼
                                     Waiting ◄── (aguardando resposta do Link)
                                       │
                                       │ LinkInfoData / MicroEnterLinkData
                                       ▼
                                     Moving ──── (atravessando link)
                                       │
                                       │ (chegou ao fim do link)
                                       ▼
                                  WaitingSignalState ── (pediu sinal ao Node)
                                       │
                                       ├── Red → WaitingSignal (espera nextTick)
                                       │            │
                                       │            ▼ (verde)
                                       │          leavingLink()
                                       │            │
                                       └── Green → leavingLink()
                                                     │
                                                     │ currentStopNode salvo
                                                     │ status → Ready
                                                     ▼
                                         [Nó tem BusStop?]
                                            │
                                    ┌───────┴────────┐
                                    SIM              NÃO
                                    │                 │
                                    ▼                 ▼
                         requestUnloadPeopleData()  enterLink()
                                    │
                                    ▼
                         WaitingUnloadPassenger
                                    │
                    [envia BusRequestUnloadPassengerData
                     para cada passageiro a bordo]
                                    │
                    [desregistra do TM (None)]
                                    │
                    [aguarda BusUnloadPassengerData × N]
                                    │ (todas respostas recebidas)
                                    ▼
                         requestLoadPassenger()
                                    │
                    [envia BusRequestPassengerData ao BusStop]
                    [desregistra do TM (None)]
                                    │
                    [aguarda BusLoadPassengerData]
                                    │
                                    ▼
                         WaitingLoadPassenger
                                    │
                    [scheduleEvent(currentTick + loadTime)]
                                    │
                                    ▼
                                  Ready → enterLink() → repete ciclo
```

### Ciclo de Vida Completo

#### Fase 1: Criação
1. BusStation cria o Bus com rota pré-calculada e `startTick`
2. Bus recebe `actSpontaneous` no `startTick`
3. Status: Start → emite report `journey_started` → Ready

#### Fase 2: Navegação (link por link)
O Bus navega pela rede viária da mesma forma que o Car, usando `Movable`:
- MESO: Recebe `LinkInfoData`, calcula velocidade com `linkDensitySpeed()`, agenda `exitTick`
- MICRO: Recebe `MicroEnterLinkData`, inicializa `MicroBusState` com parâmetros de ônibus
- Ao fim de cada link: `requestSignalState()` ao Node

#### Fase 3: Parada em BusStop
Ao sair de um link (`leavingLink()`), o Bus salva `currentStopNode` e verifica se o nó tem um BusStop:

```
Bus                          Person₁ (a bordo)     Person₂ (a bordo)     BusStop
 │                              │                     │                    │
 │──BusReqUnloadPassenger ────►│                     │                    │
 │   (nodeId)                   │                     │                    │
 │──BusReqUnloadPassenger ──────────────────────────►│                    │
 │   (nodeId)                   │                     │                    │
 │                              │                     │                    │
 │◄──BusUnloadPassenger ────────│                     │                    │
 │   (isArrival=false)          │                     │                    │
 │◄──BusUnloadPassenger ──────────────────────────────│                    │
 │   (isArrival=true)           │                     │                    │
 │                              │                     │                    │
 │ [remove Person₂]             │                     │                    │
 │ [tempo de desembarque]       │                     │                    │
 │                              │                     │                    │
 │──BusRequestPassengerData ──────────────────────────────────────────────►│
 │   (label, availableSpace)    │                     │                    │
 │                              │                     │                    │
 │◄──BusLoadPassengerData ────────────────────────────────────────────────│
 │   (people: [Person₃])        │                     │                    │
 │                              │                     │                    │
 │ [adiciona Person₃]           │                     │                    │
 │ [tempo de embarque]          │                     │                    │
 │                              │                     │                    │
 │ [continua rota]              │                     │                    │
```

#### Fase 4: Rota Cíclica
O Bus reutiliza `getNextPath` de forma cíclica:
- Quando `currentPathPosition` atinge o fim da rota, volta a 0
- Isso cria um ciclo ida → volta → ida → volta
- O Bus opera indefinidamente até o fim da simulação

#### Fase 5: Finalização
- Se rota esgotada ou `simulationEndTick` atingido: `finishJourney()` + `selfDestruct()`
- Em `onDestruct()`: reporta `sumo_tripinfo` se ainda não reportou

### Gestão do TimeManager

O Bus tem um padrão complexo de registro/desregistro do TM:

| Situação | TM | Método |
|----------|-----|--------|
| Navegando (MESO) | Registrado — gerencia próprios ticks | `onFinishSpontaneous(Some(exitTick))` |
| Navegando (MICRO) | Registrado — recebe ticks para checar posição | `onFinishSpontaneous(Some(currentTick + 1))` |
| Aguardando respostas de desembarque | **Desregistrado** | `onFinishSpontaneous(None)` |
| Aguardando resposta de embarque | **Desregistrado** | `onFinishSpontaneous(None)` |
| Após embarque/desembarque | Re-registrado | `scheduleEvent(nextTickTime)` |
| Sinal vermelho | Registrado — espera próximo tick verde | `onFinishSpontaneous(Some(data.nextTick))` |

**Nota importante**: O Bus usa `scheduleEvent()` (pool router) em vez de `onFinishSpontaneous()` para re-registrar após interações com passageiros, pois `currentTimeManager` pode ser null em contextos de interação.

### Eventos Recebidos (actInteractWith)

| Evento | Origem | Ação |
|--------|--------|------|
| `LinkInfoData` (EnterLink) | Link (MESO) | Calcula velocidade, agenda saída |
| `LinkInfoData` (LeaveLink) | Link (MESO) | Acumula distância, avança rota |
| `SignalStateData` | Node | Espera (Red) ou avança (Green) |
| `MicroEnterLinkData` | Link (MICRO) | Inicializa `MicroBusState` |
| `MicroUpdateData` | Link (MICRO) | Atualiza posição/velocidade |
| `MicroLeaveLinkData` | Link (MICRO) | Finaliza modo micro |
| `BusLoadPassengerData` | BusStop | Carrega passageiros, calcula delay |
| `BusUnloadPassengerData` | Person | Processa resposta de desembarque |

### Eventos Enviados

| Evento | Destino | Quando |
|--------|---------|--------|
| `EnterLinkData` | Link | Entrando em novo link |
| `LeaveLinkData` | Link | Saindo do link |
| `RequestSignalStateData` | Node | Fim do link, antes de sair |
| `BusRequestUnloadPassengerData` | Person (cada passageiro) | Ao chegar em nó com BusStop |
| `BusRequestPassengerData` | BusStop | Após desembarque, para carregar novos |

### Relatórios Emitidos

| Label | Quando | Campos-Chave |
|-------|--------|-------------|
| `journey_started` | Início da operação | bus_id, origin, destination, route_length |
| `enter_link` | Entrando link MESO | bus_id, passengers, occupancy, travel_time |
| `enter_micro_link` | Entrando link MICRO | bus_id, passengers, capacity, lane |
| `leave_link` | Saindo link MESO | bus_id, passengers, total_distance |
| `leave_micro_link` | Saindo link MICRO | bus_id, passengers, travel_time, average_speed |
| `signal_wait` | Sinal vermelho | vehicle_type="bus", current_passengers |
| `bus_load_passengers` | Passageiros embarcados | passengers_loaded, total_passengers, occupancy |
| `bus_unload_passengers` | Passageiros desembarcados | passengers_unloaded, remaining_passengers |
| `journey_completed` | Finalização | completion_reason, total_distance |
| `sumo_tripinfo` | Finalização | duration, routeLength, waitingTime, stopTime, timeLoss |

### MicroBusState — Parâmetros Específicos

| Parâmetro | Valor | Comparação com Car |
|-----------|-------|--------------------|
| `vehicleLength` | 12.0 m | Car: 4.5 m |
| `maxAcceleration` | 1.2 m/s² | Car: 2.6 m/s² |
| `maxDeceleration` | 3.5 m/s² | Car: 4.5 m/s² |
| `minGap` | 3.0 m | Car: 2.0 m |
| `desiredVelocity` | 11.11 m/s (40 km/h) | Car: 13.89 m/s (50 km/h) |
| `reactionTime` | 1.5 s | Car: 1.0 s |
| `busLaneRestricted` | true | N/A |
| `canChangeLane` | false | Car: sim |

---

## 10. BusStation — Garagem/Gerenciador de Linha

### Visão Geral

O BusStation é a entidade **gestora** de uma linha de ônibus. Ele:
- Calcula as rotas de ida e volta entre todas as paradas da linha
- Cria os atores Bus com rotas pré-calculadas
- Espaça a criação de ônibus por um intervalo configurável (`state.interval`)
- Não transporta passageiros nem se move — é puramente administrativo

### Estado (`BusStationState`)

| Campo | Tipo | Descrição |
|-------|------|-----------|
| `origin` / `destination` | `String` | Nós extremos da linha |
| `busStops` | `LinkedHashMap[String, String]` | Paradas ordenadas (busStopId → nodeId) |
| `buses` | `Queue[BusInformation]` | Fila de ônibus a serem criados |
| `interval` | `Tick` | Intervalo entre criações de ônibus |
| `status` | `BusStationStateEnum` | Estado da máquina de estados |
| `goingRoute` | `Option[Map[SubRoutePair, Queue[(Identify, Identify)]]]` | Rotas de ida |
| `returningRoute` | `Option[Map[SubRoutePair, Queue[(Identify, Identify)]]]` | Rotas de volta |

### Máquina de Estados (`BusStationStateEnum`)

```
Start ──── actSpontaneous() ────► RouteWaiting
                                     │
                                     │ calculateRoutesFromMap()
                                     │  ├── calcula rota ida (parada₁→₂→₃→...→N)
                                     │  └── calcula rota volta (N→...→₃→₂→₁)
                                     │
                              ┌──────┴───────┐
                              │              │
                          completo       incompleto
                              │              │
                              ▼              ▼
                            Ready      WorkingWithOutBus
                              │              │
                              │ cria 1°Bus   │ desregistra TM
                              ▼              │
                           Working           │
                              │              │
                              │ a cada `interval` ticks:
                              │   buses.dequeue() → createBus()
                              │
                              ├── buses.nonEmpty → cria próximo Bus
                              │     agenda (currentTick + interval)
                              │
                              └── buses.isEmpty → WorkingWithOutBus
                                    agenda (currentTick + interval)
                                    [mantém TM para potencial restart]
```

### Ciclo de Vida

#### Fase 1: Cálculo de Rotas
1. TM acorda BusStation no `startTick`
2. `calculateRoutesFromMap()` calcula rotas usando `GPSUtil.calcRoute()`:
   - **Ida**: para cada par consecutivo de paradas (stop₁→stop₂, stop₂→stop₃, ...)
   - **Volta**: mesma lógica em ordem reversa
3. Armazena em `goingRoute` e `returningRoute` como `Map[SubRoutePair, Queue]`

#### Fase 2: Criação de Ônibus
4. Se rotas completas → `createBus()`:
   - Monta rota completa: going + returning
   - Cria `BusState` com rota, capacidade, paradas
   - Instancia ator Bus via `createShardedActorSeveralArgs()`
   - Emite report `bus_created`
5. Agenda próxima criação em `currentTick + interval`
6. Repete até fila `buses` esvaziar

#### Fase 3: Operação Contínua
7. Após criar todos os ônibus: status → `WorkingWithOutBus`
8. Continua agendando ticks (pode receber mais ônibus futuramente)
9. Em `onDestruct()`: status → `Finish`

### Cálculo de Rota do Bus

```
Paradas: [Stop₁, Stop₂, Stop₃]     (ordenadas por sufixo numérico)

Ida:    Stop₁ ──route──► Stop₂ ──route──► Stop₃
Volta:  Stop₃ ──route──► Stop₂ ──route──► Stop₁

Rota final do Bus = [ida₁₂, ida₂₃, volta₃₂, volta₂₁]
                  = percurso circular completo
```

Cada segmento é um `Queue[(linkId, nodeId)]` calculado via `GPSUtil.calcRoute()`.

### Relação com TimeManager

| Situação | TM |
|----------|-----|
| Calculando rotas (Start→RouteWaiting) | Registrado |
| Criando ônibus (Working) | Registrado — agenda a cada `interval` |
| Sem mais ônibus (WorkingWithOutBus) | Registrado — mantém presença |
| Simulação encerrada | Desregistra (`onFinishSpontaneous(None)`) |

### Eventos Recebidos

Nenhum evento de interação é processado. `actInteractWith()` apenas loga "Event not handled".

### Eventos Enviados

Nenhum evento enviado diretamente. Cria atores Bus programaticamente.

### Relatórios Emitidos

| Label | Quando | Campos-Chave |
|-------|--------|-------------|
| `bus_created` | Ao criar cada ônibus | station_id, bus_id, capacity, route_length, label, start_tick |

---

## 11. BusStop — Parada de Ônibus

### Visão Geral

O BusStop é um ator **estacionário e reativo** que serve como mediador entre Person e Bus. Ele:
- Registra-se no Node pai durante inicialização
- Mantém fila de passageiros aguardando por linha
- Responde a requisições de embarque do Bus
- Registra novos passageiros enviados pelo Person

### Estado (`BusStopState`)

| Campo | Tipo | Descrição |
|-------|------|-----------|
| `nodeId` | `String` | ID do Node onde a parada está localizada |
| `label` | `String` | Label da linha servida (ex: "101") |
| `people` | `Map[String, Seq[Identify]]` | Fila de passageiros por linha (label → lista) |

### Ciclo de Vida

#### Inicialização (`onInitialize`)
1. BusStop obtém dependência do Node via `getDependencyOption(state.nodeId)`
2. Envia `RegisterBusStopData(label)` ao Node
3. Node armazena referência do BusStop em `state.busStops`

```
BusStop                          Node
  │                               │
  │── RegisterBusStopData ───────►│
  │   (label = "101")             │
  │                               │ state.busStops.put("101", identify)
```

#### Operação
O BusStop **não se registra no TimeManager**. Não recebe `actSpontaneous`. Toda sua lógica é ativada por `actInteractWith`.

### Fluxo de Embarque

```
Person                        BusStop                         Bus
  │                              │                              │
  │── RegisterPassengerData ───►│                              │
  │   (label = "101")           │                              │
  │                              │ people["101"] += Person     │
  │                              │                              │
  ... (Person fica aguardando, desregistrado do TM) ...        │
  │                              │                              │
  │                              │◄── BusRequestPassengerData ──│
  │                              │    (label, availableSpace)   │
  │                              │                              │
  │                              │ people.take(availableSpace)  │
  │                              │                              │
  │                              │── BusLoadPassengerData ─────►│
  │                              │   (people: [Person₁, ...])   │
  │                              │                              │
  │                              │                              │ bus.people += loaded
```

### Eventos Recebidos (actInteractWith)

| Evento | Origem | Ação |
|--------|--------|------|
| `RegisterPassengerData` | Person | Adiciona Person à fila da linha especificada |
| `BusRequestPassengerData` | Bus | Envia passageiros disponíveis (até `availableSpace`) |

### Eventos Enviados

| Evento | Destino | Quando |
|--------|---------|--------|
| `RegisterBusStopData` | Node | Em `onInitialize()` — registra-se no nó |
| `BusLoadPassengerData` | Bus | Em resposta a `BusRequestPassengerData` |

### Relatórios Emitidos

| Label | Quando | Campos-Chave |
|-------|--------|-------------|
| `bus_stop_passengers_loaded` | Bus solicitou embarque | bus_stop_id, bus_id, passengers_loaded, available_space, passengers_waiting |
| `bus_stop_passenger_arrived` | Person registrou-se na parada | bus_stop_id, person_id, route_label, passengers_waiting |

### Relação com TimeManager

O BusStop **NÃO** se registra no TimeManager. É puramente reativo:
- Recebe `ActorInteractionEvent` de Person e Bus
- Nunca recebe `SpontaneousEvent`
- Não agenda ticks

---

## 12. Subway — Metrô

### Visão Geral

O Subway é um veículo de transporte público que percorre **trilhos dedicados** (RailLinks) em rotas fixas pré-definidas. Diferencia-se do Bus por:
- **Infraestrutura exclusiva**: usa `RailLink` em vez de `Link` (rede separada da rodoviária)
- **Rota pré-definida**: calculada pelo SubwayStation; nunca usa roteamento dinâmico (GraphRouter)
- **Sem congestionamento**: trilhos dedicados, sem competição com tráfego rodoviário
- **Velocidade fixa**: determinada pelo estado do Subway (`velocity`), não pela densidade do link
- **Sem semáforos**: navega direto entre estações, sem `RequestSignalState`
- **Embarque/desembarque paralelo**: usa flags duais (`isLoaded` + `isUnloaded`) via `SubwayNodeState`
- **Ciclo infinito**: rota circular (ida → volta → ida) igual ao Bus

### Estado (`SubwayState`)

| Campo | Tipo | Descrição |
|-------|------|-----------|
| `startTick` | `Tick` | Tick de partida |
| `capacity` | `Int` | Capacidade máxima de passageiros |
| `numberOfPorts` | `Int` | Número de portas (afeta taxa de embarque) |
| `velocity` | `Double` | Velocidade em km/h |
| `stopTime` | `Tick` | Tempo de parada na estação (ticks) |
| `boardingTimeByPassenger` | `Double` | Tempo de embarque por passageiro (s) |
| `line` | `String` | Identificador da linha (ex: "L1-Azul") |
| `origin` / `destination` | `String` | Nós extremos da rota |
| `bestRoute` | `Option[Queue[(String, String)]]` | Rota: `(railLinkId, nodeId)` |
| `currentPathPosition` | `Int` | Posição atual na rota (cíclica) |
| `subwayStations` | `Map[String, String]` | Mapa stationId → nodeId |
| `passengers` | `Map[String, Identify]` | Passageiros a bordo |
| `distance` | `Double` | Distância total percorrida |
| `nodeState` | `SubwayNodeState` | Flags de embarque/desembarque |
| `countUnloadReceived` / `countUnloadPassenger` | `Int` | Contadores de respostas de desembarque |
| `status` | `MovableStatusEnum` | Estado da máquina de estados |

### SubwayNodeState — Controle Dual de Estação

```scala
case class SubwayNodeState(
  var isLoaded: Boolean = false,   // true quando embarque concluído
  var isUnloaded: Boolean = false  // true quando desembarque concluído
)
```

Ambas flags devem ser `true` para que `onFinishNodeState()` agende a retomada do metrô.

### Máquina de Estados (`MovableStatusEnum`)

```
Start ─────── actSpontaneous ──────► Ready
                                      │
                                      │ enterLink() → RailLink
                                      ▼
                                    Moving ────── (viajando pelo RailLink)
                                      │
                                      │ (chegou ao nó destino)
                                      ▼
                                  [Nó tem SubwayStation?]
                                      │
                              ┌───────┴────────┐
                              SIM              NÃO
                              │                 │
                              ▼                 ▼
                           Stopped          leavingLink()
                              │                 │
                              │                 ▼
                              │              Ready → enterLink()
                              │
                    ┌─────────┴─────────┐
                    │                   │
           requestUnloadPeopleData()  requestLoadPassenger()
                    │                   │
          [envia SubwayReqUnload   [envia SubwayReqPassenger
           a cada passageiro]      à SubwayStation]
                    │                   │
          [desregistra do TM          [aguarda
           (onFinishSpontaneous        SubwayLoadPassengerData]
            None)]                     │
                    │                   │
          [aguarda SubwayUnload   [isLoaded = true]
           ×N respostas]               │
                    │                   │
          [isUnloaded = true]          │
                    │                   │
                    └────────┬──────────┘
                             │
                      onFinishNodeState()
                             │
                    [isLoaded && isUnloaded?]
                             │ SIM
                             ▼
                    scheduleEvent(currentTick + stopTime)
                             │
                             ▼
                          Stopped → actSpontaneous → leavingLink()
                             │
                             ▼
                           Ready → enterLink() → repete ciclo
```

### Ciclo de Vida Completo

#### Fase 1: Criação
1. SubwayStation cria o Subway com rota pré-definida de `rail_link_ids`
2. Subway recebe `actSpontaneous` no `startTick`
3. Status: Start → Ready → `enterLink()`

#### Fase 2: Navegação (rail link por rail link)
O Subway usa `Movable.enterLink()` para enviar `EnterLinkData` ao RailLink:
- RailLink valida tipo de veículo (`canAcceptVehicle("Subway")` → true)
- RailLink envia `LinkInfoData` com comprimento e velocidade efetiva
- Subway recebe em `actHandleReceiveEnterLinkInfo()`: calcula tempo de viagem via `SubwayUtil.timeToNextStation(distance, velocity)`
- Status → Moving, agenda `onFinishSpontaneous(Some(currentTick + time))`

#### Fase 3: Chegada na Estação
Ao receber `actSpontaneous` em estado `Moving`:
1. Verifica se nó atual tem SubwayStation via `retrieveSubwayStationFromNodeId()`
2. **Sem estação**: `leavingLink()` (envia `LeaveLinkData` ao RailLink)
3. **Com estação**: Status → `Stopped`, inicia embarque E desembarque **em paralelo**:
   - `requestUnloadPeopleData()`: envia `SubwayRequestUnloadPassengerData` a cada passageiro
   - `requestLoadPassenger()`: envia `SubwayRequestPassengerData` à SubwayStation
   - Desregistra do TM (`onFinishSpontaneous(None)`)

#### Fase 4: Embarque/Desembarque Paralelo

```
Subway                    Person₁ (a bordo)   Person₂ (a bordo)   SubwayStation
  │                          │                    │                    │
  │──SubwayReqUnload ───────►│                    │                    │
  │──SubwayReqUnload ────────────────────────────►│                    │
  │──SubwayReqPassenger ──────────────────────────────────────────────►│
  │                          │                    │                    │
  │  [3 mensagens enviadas em paralelo]           │                    │
  │  [desregistra do TM]     │                    │                    │
  │                          │                    │                    │
  │◄─SubwayUnload(F) ───────│                    │                    │
  │  (Person₁ fica no trem)  │                    │                    │
  │                          │                    │                    │
  │◄─SubwayUnload(T) ────────────────────────────│                    │
  │  (Person₂ desce)         │                    │                    │
  │  [remove Person₂]        │                    │                    │
  │  [countReceived >= total] │                    │                    │
  │  → isUnloaded = true     │                    │                    │
  │                          │                    │                    │
  │◄─SubwayLoadPassenger ────────────────────────────────────────────│
  │  (people: [Person₃])     │                    │                    │
  │  [adiciona Person₃]      │                    │                    │
  │  → isLoaded = true       │                    │                    │
  │                          │                    │                    │
  │ [isLoaded && isUnloaded] │                    │                    │
  │ → scheduleEvent(tick+stopTime)                │                    │
  │                          │                    │                    │
  │ [após stopTime]          │                    │                    │
  │ → Stopped → leavingLink()│                    │                    │
```

**Diferença crítica vs Bus**: O Bus faz desembarque → embarque **sequencialmente**. O Subway faz ambos **em paralelo** usando flags `isLoaded`/`isUnloaded`, e só prossegue quando ambas são `true`.

#### Fase 5: Rota Cíclica
Idêntico ao Bus: `getNextPath` reseta `currentPathPosition` para 0 ao atingir o fim da rota.

### Cálculo de Tempo de Viagem

```
time = ⌈(distance / velocity) × 3600⌉  (em ticks)

Onde:
  distance = comprimento do RailLink (metros)
  velocity = velocidade do Subway (km/h)
  3600 = conversão hora → segundos (1 tick = 1s)
```

Implementado em `SubwayUtil.timeToNextStation()`.

### Cálculo de Capacidade de Embarque

```
maxPassengers = ⌈numberOfPorts × portsCapacity × (stopTime / boardingTimeByPassenger)⌉
```

O espaço disponível real é `min(maxPassengers, capacity - currentPassengers)`.

### Gestão do TimeManager

| Situação | TM | Método |
|----------|-----|--------|
| Navegando (Moving) | Registrado — agenda tick de chegada | `onFinishSpontaneous(Some(currentTick + time))` |
| Em estação (load/unload) | **Desregistrado** | `onFinishSpontaneous(None)` |
| Após embarque/desembarque completo | Re-registrado | `scheduleEvent(currentTick + stopTime)` |
| Após stopTime (Stopped) | Registrado — `leavingLink()` + `enterLink()` | `onFinishSpontaneous(Some(currentTick + 1))` |

### Eventos Recebidos (actInteractWith)

| Evento | Origem | Ação |
|--------|--------|------|
| `LinkInfoData` (EnterLink) | RailLink | Calcula tempo via `timeToNextStation()`, agenda chegada |
| `LinkInfoData` (LeaveLink) | RailLink | Acumula distância, status → Ready |
| `SubwayLoadPassengerData` | SubwayStation | Adiciona passageiros, `isLoaded = true` |
| `SubwayUnloadPassengerData` | Person | Processa resposta; ao completar todas, `isUnloaded = true` |

### Eventos Enviados

| Evento | Destino | Quando |
|--------|---------|--------|
| `EnterLinkData` | RailLink | Entrando em novo rail link |
| `LeaveLinkData` | RailLink | Saindo do rail link |
| `SubwayRequestUnloadPassengerData` | Person (cada passageiro) | Ao chegar em estação |
| `SubwayRequestPassengerData` | SubwayStation | Ao chegar em estação |

### Relatórios Emitidos

O Subway não emite relatórios diretamente no código atual. Os relatórios são gerados pelo SubwayStation (`subway_created`) e pelo framework base.

---

## 13. SubwayStation — Estação de Metrô

### Visão Geral

O SubwayStation é a entidade que **gerencia linhas de metrô, armazena passageiros e cria trens**. Ele combina funcionalidades que no sistema de ônibus estão separadas em BusStation + BusStop:
- **Como BusStation**: calcula rotas e cria Subways com `createSubway()`
- **Como BusStop**: armazena passageiros e responde a `SubwayRequestPassengerData`
- **Registro no Node**: registra suas linhas no Node via `RegisterSubwayStationData`

### Estado (`SubwayStationState`)

| Campo | Tipo | Descrição |
|-------|------|-----------|
| `startTick` | `Tick` | Tick de início |
| `name` | `String` | Nome da estação |
| `nodeId` | `String` | ID do Node onde está localizada |
| `terminal` | `Boolean` | Se é estação terminal (início/fim de linha) |
| `garage` | `Boolean` | Se pode criar trens (apenas garagens criam) |
| `lines` | `Map[String, SubwayLineInformation]` | Linhas servidas (intervalo entre trens) |
| `subways` | `Map[String, Queue[SubwayInformation]]` | Fila de trens a criar por linha |
| `linesRoute` | `Map[String, Queue[SubwayRouteEntry]]` | Rotas pré-definidas por linha |
| `people` | `Map[String, Seq[Identify]]` | Passageiros por linha |
| `status` | `SubwayStationStateEnum` | Estado da máquina de estados |

### Modelos de Suporte

```scala
case class SubwayLineInformation(
  interval: Tick,          // Intervalo entre criações de trens
  var nextTick: Tick = -1  // Próximo tick para criar trem
)

case class SubwayInformation(
  line: String,            // Linha do trem
  actorId: String,         // ID do ator a criar
  capacity: Int,           // Capacidade de passageiros
  numberOfPorts: Int,      // Número de portas
  velocity: Double,        // Velocidade (km/h)
  stopTime: Tick           // Tempo de parada em estações
)

case class SubwayRouteEntry(
  stationNode: SubwayStationNode,  // Nó da estação
  railLinkId: String                // ID do RailLink
)
```

### Máquina de Estados (`SubwayStationStateEnum`)

```
Start ──── actSpontaneous() ────► Working
                                     │
                                     │ createSubwayFrom(state.lines)
                                     │ scheduleNextTick()
                                     │
                                     ▼
                                  Working ◄──────── loop
                                     │
                                     │ filterLinesByNextTick()
                                     │   → quais linhas precisam de trem agora?
                                     │ createSubwayFrom(filteredLines)
                                     │ scheduleNextTick()
                                     │
                                     ├── subways restantes → continua Working
                                     │
                                     └── simulationEnd atingido → onFinishSpontaneous(None)
```

### Ciclo de Vida

#### Fase 1: Inicialização
1. `onInitialize()`: registra-se no Node via `RegisterSubwayStationData(lines)`
2. Node armazena referência em `state.subwayStations` por linha

```
SubwayStation                    Node
  │                               │
  │── RegisterSubwayStationData ─►│
  │   (lines: ["L1-Azul"])        │
  │                               │ subwayStations.put("L1-Azul", identify)
```

#### Fase 2: Criação de Trens (apenas se `garage = true`)
1. TM acorda SubwayStation no `startTick`
2. Para cada linha com `nextTick <= currentTick`:
   - Desenfila `SubwayInformation` da fila `subways(line)`
   - `createSubway()`: monta rota de `rail_link_ids` via `convertLineRouteToPath()`
   - Cria ator Subway via `createShardedActorSeveralArgs()`
   - Emite report `subway_created`
   - Atualiza `nextTick = currentTick + interval`
3. `scheduleNextTick()`: encontra o menor `nextTick` de todas as linhas e agenda

#### Fase 3: Gestão de Passageiros
SubwayStation atua como ponto de embarque (similar ao BusStop):
- **Person registra-se**: `RegisterSubwayPassengerData(line)` → pessoa adicionada a `state.people(line)`
- **Subway solicita passageiros**: `SubwayRequestPassengerData(line, availableSpace)` → estação retorna até `availableSpace` passageiros via `SubwayLoadPassengerData`

### Conversão de Rota

```
linesRoute(line) = Queue[SubwayRouteEntry]
  = Queue(
      SubwayRouteEntry(stationNode=A, railLinkId="rail_1"),
      SubwayRouteEntry(stationNode=B, railLinkId="rail_2"),
      SubwayRouteEntry(stationNode=C, railLinkId="rail_3")
    )

convertLineRouteToPath(line) → Queue[(String, String)]
  = Queue(
      ("rail_1", nodeId_A),
      ("rail_2", nodeId_B),
      ("rail_3", nodeId_C)
    )
```

### Relação com TimeManager

| Situação | TM |
|----------|-----|
| Criando trens (Working) | Registrado — agenda `nextTick` da próxima linha |
| `simulationEnd` atingido | Desregistra (`onFinishSpontaneous(None)`) |
| Respondendo `SubwayRequestPassengerData` | Não altera TM (responde inline) |

### Eventos Recebidos (actInteractWith)

| Evento | Origem | Ação |
|--------|--------|------|
| `RegisterSubwayPassengerData` | Person | Adiciona pessoa à fila da linha |
| `SubwayRequestPassengerData` | Subway | Retorna passageiros disponíveis (até `availableSpace`) |

### Eventos Enviados

| Evento | Destino | Quando |
|--------|---------|--------|
| `RegisterSubwayStationData` | Node | Em `onInitialize()` — registra linhas no nó |
| `SubwayLoadPassengerData` | Subway | Em resposta a `SubwayRequestPassengerData` |

### Relatórios Emitidos

| Label | Quando | Campos-Chave |
|-------|--------|-------------|
| `subway_created` | Ao criar cada trem | station_id, subway_id, line, capacity, velocity, stop_time, route_length, number_of_stations |

---

## 14. RailLink — Segmento Ferroviário

### Visão Geral

O RailLink é a infraestrutura dedicada para metrô/trem. Forma uma **rede separada** da rede rodoviária (Link). Características:
- **Exclusivo para metrô**: valida tipo de veículo — apenas `Subway` pode entrar em `SUBWAY` rail links
- **Sem congestionamento**: não calcula densidade nem velocidade agregada (diferente do Link MESO)
- **Velocidade efetiva**: considera gradiente e curvatura do trilho
- **Sem semáforos**: veículos passam direto, sem consultar Node
- **Sem modo MICRO**: tipicamente opera apenas em MESO
- **Protocolo simples**: EnterLinkData → LinkInfoData / LeaveLinkData → LinkInfoData

### Estado (`RailLinkState`)

| Campo | Tipo | Descrição |
|-------|------|-----------|
| `from` / `to` | `String` | Nós extremos (origem → destino) |
| `length` | `Double` | Comprimento em metros |
| `lanes` | `Int` | Número de trilhos (tipicamente 2) |
| `speedLimit` | `Double` | Velocidade máxima (km/h) |
| `capacity` | `Double` | Capacidade máxima (trens/hora) |
| `freeSpeed` | `Double` | Velocidade de fluxo livre (km/h) |
| `railType` | `String` | Tipo: `SUBWAY`, `LIGHT_RAIL`, `HEAVY_RAIL` |
| `subwayLine` | `String` | Linha de metrô a que pertence |
| `fromStation` / `toStation` | `String` | IDs das estações extremas |
| `gradient` | `Double` | Gradiente do trilho (+ = subida, - = descida) |
| `curvature` | `Double` | Curvatura (maior = mais curvo) |
| `simulationMode` | `SimulationModeEnum` | Modo de simulação (tipicamente MESO) |
| `registered` | `Set[LinkRegister]` | Veículos registrados no link |

### Cálculo de Velocidade Efetiva

```
effectiveSpeed = freeSpeed
  × (1 - gradient × 0.1)     se gradient > 0 (subida: -10% por 1% de gradiente)
  × (1 - curvature × 0.05)   se curvature > 0 (-5% por unidade de curvatura)

  com mínimo de speedLimit × 0.5 (nunca abaixo de 50% do limite)
```

**Exemplo**: `freeSpeed=80`, `gradient=2.0`, `curvature=1.0`:
- Após gradiente: 80 × (1 - 0.2) = 64 km/h
- Após curvatura: 64 × (1 - 0.05) = 60.8 km/h

### Validação de Tipo de Veículo

| railType | Veículos Aceitos |
|----------|------------------|
| `SUBWAY` | Apenas `Subway` |
| `LIGHT_RAIL` | `Subway` ou `LightRail` |
| `HEAVY_RAIL` | Apenas `Train` |

Se um veículo inválido tenta entrar, o RailLink:
1. Loga erro `RAIL SAFETY VIOLATION`
2. Envia `LinkInfoData` vazio (length=0, capacity=0) como rejeição
3. **NÃO registra** o veículo

### Protocolo de Comunicação

```
Subway ──── EnterLinkData ──────► RailLink
     actorId, shardId, actorType

     [RailLink valida vehicleType]
     [Calcula effectiveSpeed]

RailLink ── LinkInfoData ───────► Subway
     linkLength, linkCapacity,
     linkNumberOfCars, linkFreeSpeed=effectiveSpeed,
     linkLanes
     eventType = "ReceiveEnterLinkInfo"
```

```
Subway ──── LeaveLinkData ──────► RailLink

RailLink ── LinkInfoData ───────► Subway
     (mesmos campos)
     eventType = "ReceiveLeaveLinkInfo"
```

### Relação com TimeManager

O RailLink **NÃO** se registra no TimeManager (`scheduleOnTimeManager = false`). É puramente reativo:
- Recebe `ActorInteractionEvent` de Subway
- Nunca recebe `SpontaneousEvent`
- Não agenda ticks

### Eventos Recebidos (actInteractWith)

| Evento | Origem | Ação |
|--------|--------|------|
| `EnterLinkData` | Subway | Valida veículo, registra, envia `LinkInfoData` |
| `LeaveLinkData` | Subway | Desregistra veículo, envia `LinkInfoData` |

### Eventos Enviados

| Evento | Destino | Quando |
|--------|---------|--------|
| `LinkInfoData` (ReceiveEnterLinkInfo) | Subway | Em resposta a `EnterLinkData` |
| `LinkInfoData` (ReceiveLeaveLinkInfo) | Subway | Em resposta a `LeaveLinkData` |

### Relatórios Emitidos

O RailLink não emite relatórios no código atual.

### Comparação: Link vs RailLink

| Aspecto | Link (rodoviário) | RailLink (ferroviário) |
|---------|-------------------|------------------------|
| Veículos | Car, Bus, Bicycle, Motorcycle | Subway (exclusivo) |
| Modos | MESO + MICRO | Apenas MESO |
| Congestionamento | Sim (densidade → velocidade) | Não |
| Semáforos | Veículo consulta Node | Não aplicável |
| Velocidade | Função de densidade | Fixa (effectiveSpeed) |
| Lanes | Multi-lane + lane change | Trilhos simples |
| TM | MICRO: registrado; MESO: não | Nunca registrado |

---

## 15. TrafficSignal — Semáforo

### Visão Geral

O TrafficSignal é um ator cíclico que controla fases verde/vermelho em interseções. Ele:
- Opera em **ciclos fixos** com offset configurável (coordenação de onda verde)
- Notifica Nodes (não veículos) sobre mudanças de fase
- Cada Node armazena o estado do sinal em `state.signals` para consultas de veículos
- Pode controlar múltiplas fases (uma por approach/link de origem)

### Estado (`TrafficSignalState`)

| Campo | Tipo | Descrição |
|-------|------|-----------|
| `startTick` | `Tick` | Tick de início |
| `cycleDuration` | `Tick` | Duração total do ciclo (ticks) |
| `offset` | `Tick` | Offset para coordenação de onda verde |
| `nodes` | `List[String]` | IDs dos Nodes controlados |
| `phases` | `List[Phase]` | Fases do semáforo |
| `signalStates` | `Map[String, SignalState]` | Estado atual de cada fase |

### Modelos de Suporte

```scala
case class Phase(
  origin: String,           // Link de origem da fase
  greenStart: Tick,         // Início do verde no ciclo
  greenDuration: Tick,      // Duração do verde
  state: TrafficSignalPhaseStateEnum  // Estado inicial
)

case class SignalState(
  var state: TrafficSignalPhaseStateEnum,  // Green | Red
  var remainingTime: Tick,                  // Tempo restante na fase atual
  var nextTick: Tick                        // Próximo tick de mudança
)
```

### Ciclo de Operação

```
onInitialize()
  │
  │ scheduleEvent(startTick + offset)
  ▼
handlePhaseTransition(currentTick)
  │
  │ Para cada Phase:
  │   ├── calcNewState(currentCycleTick, phase)
  │   │     currentCycleTick = (currentTick - startTick + offset) % cycleDuration
  │   │
  │   │     se currentCycleTick ∈ [greenStart, greenStart+greenDuration) → Green
  │   │     senão → Red
  │   │
  │   ├── Se estado mudou:
  │   │     notifyNodes(SignalState, nodes, phaseOrigin, nextTick)
  │   │       ├── report("signal_phase_change")
  │   │       └── sendMessageTo(Node,
  │   │             TrafficSignalChangeStatusData(signalState, phaseOrigin, nextTick))
  │   │
  │   └── Atualiza signalStates[phase.origin] com novo estado e remainingTime
  │
  │ Agenda próximo tick (início do próximo ciclo):
  │   nextCycleStart = ⌊(ticksSinceStart / cycleDuration) + 1⌋ × cycleDuration
  │   nextTickTime = startTick + nextCycleStart - offset
  │   onFinishSpontaneous(Some(nextTickTime))
  │
  └── Se nextTickTime >= simulationEnd → onFinishSpontaneous()
```

### Fluxo: TrafficSignal → Node → Vehicle

```
TrafficSignal                Node                   Car/Bus
  │                           │                       │
  │ [ciclo muda fase]         │                       │
  │                           │                       │
  │─ TrafficSignalChangeStatus►│                       │
  │  (signalState, phaseOrigin,│                       │
  │   nextTick)               │                       │
  │                           │ signals.put(          │
  │                           │   phaseOrigin,        │
  │                           │   signalState)        │
  │                           │                       │
  │                           │               ... tempo passa ...
  │                           │                       │
  │                           │◄─ RequestSignalState ─│
  │                           │   (targetLinkId)      │
  │                           │                       │
  │                           │ connections(linkId)   │
  │                           │   → signalId          │
  │                           │ signals(signalId)     │
  │                           │   → SignalState       │
  │                           │                       │
  │                           │── SignalStateData ───►│
  │                           │   (phase, nextTick)   │
  │                           │                       │
```

**Fallback**: Se o Node não encontra sinal para o link solicitado (sem semáforo naquela approach), responde com `Green` + `nextTick = currentTick`.

### Coordenação de Onda Verde

O campo `offset` permite coordenar semáforos em corredores:
- Semáforo A: `offset = 0`, `cycleDuration = 60`
- Semáforo B: `offset = 10`, `cycleDuration = 60`
- Resultado: B abre 10 ticks após A → veículos trafegam sem parar

### Relação com TimeManager

| Situação | TM |
|----------|-----|
| Inicialização | Registrado via `scheduleEvent(startTick + offset)` |
| Operação | Registrado — agenda início de cada novo ciclo |
| `simulationEnd` atingido | Desregistra (`onFinishSpontaneous()`) |

### Eventos Recebidos

Nenhum. O TrafficSignal não processa `actInteractWith` — é puramente gerador de sinais.

### Eventos Enviados

| Evento | Destino | Quando |
|--------|---------|--------|
| `TrafficSignalChangeStatusData` | Node(s) | A cada mudança de fase |

### Relatórios Emitidos

| Label | Quando | Campos-Chave |
|-------|--------|-------------|
| `signal_phase_change` | A cada mudança de fase | signal_id, phase_origin, phase_state, remaining_time, next_tick, affected_nodes |

---

## 16. Bicycle — Bicicleta

### Visão Geral

O Bicycle é um **veículo privado** de baixa velocidade que pertence a uma Person. Comportamento idêntico ao Car em termos de ciclo de vida (PrivateVehicle), mas com parâmetros físicos distintos:
- **Velocidade reduzida**: 20 km/h típico (MESO: constante; MICRO: via car-following)
- **Baixa aceleração**: 1.0 m/s² (vs 2.6 do Car)
- **Preferência por ciclovia**: em MICRO, busca `bike_lane` se disponível (lane 0 em links com 3+ faixas)
- **Veículo pequeno**: 2.0 m de comprimento
- **Vulnerabilidade**: menor gap mínimo (1.5 m), reação mais lenta (1.2 s)
- **Modelo Person-Centric**: passivo (Parked) até ativado por Person via `StartTrip`

### Estado (`BicycleState`)

| Campo | Tipo | Descrição |
|-------|------|-----------|
| `startTick` | `Tick` | Tick de partida |
| `origin` / `destination` | `String` | Nós extremos da rota |
| `bestRoute` | `Option[Queue[(String, String)]]` | Rota calculada |
| `distance` | `Double` | Distância total percorrida |
| `status` | `MovableStatusEnum` | Estado da máquina de estados |
| `actorType` | `ActorTypeEnum` | Tipo do ator |
| `size` | `Double` | Tamanho do veículo |
| `currentSimulationMode` | `SimulationModeEnum` | MESO ou MICRO |
| `microState` | `Option[MicroBicycleState]` | Estado microscópico (ativado em links MICRO) |

### Máquina de Estados

Idêntica ao Car (compartilha padrão `PrivateVehicle`):

```
Parked (passivo, sem TM)
  │
  │ Person envia StartTripData
  ▼
Start ─── requestRoute() ───► Ready
                                │
                                │ enterLink()
                                ▼
                              Moving ──── (atravessando link)
                                │
                                │ (fim do link)
                                ▼
                         WaitingSignalState ── (pediu sinal ao Node)
                                │
                                ├── Red → WaitingSignal (espera nextTick)
                                │            │
                                │            ▼ (verde)
                                │          leavingLink()
                                │
                                └── Green → leavingLink()
                                              │
                                              ▼
                                            Ready → enterLink() → repete
                                              │
                                              │ (destino alcançado)
                                              ▼
                                           Finished
                                              │
                                              │ TripCompletedData → Person
                                              │ selfDestruct()
                                              ▼
                                           Parked (aguarda próximo uso)
```

### Comportamento MESO

- Velocidade fixa: **5.56 m/s (20 km/h)** — não usa `linkDensitySpeed()` (diferente do Car)
- Tempo de travessia: `linkLength / 5.56` ticks
- Simples: ignora congestionamento

### Comportamento MICRO

- Usa Krauss car-following com **randomness = 0.3** (maior que Car: 0.15)
- Inicializa `MicroBicycleState` ao entrar em link MICRO
- Busca ciclovia: `findBikeLane()` → lane 0 se link tem 3+ faixas

### MicroBicycleState — Parâmetros

| Parâmetro | Valor | Comparação com Car |
|-----------|-------|--------------------|
| `vehicleLength` | 2.0 m | Car: 4.5 m |
| `maxAcceleration` | 1.0 m/s² | Car: 2.6 m/s² |
| `maxDeceleration` | 3.0 m/s² | Car: 4.5 m/s² |
| `minGap` | 1.5 m | Car: 2.0 m |
| `desiredVelocity` | 5.56 m/s (20 km/h) | Car: 13.89 m/s (50 km/h) |
| `reactionTime` | 1.2 s | Car: 1.0 s |
| `prefersBikeLane` | true | N/A |
| `canUseSidewalk` | false | N/A |

### Gestão do TimeManager

Idêntica ao Car (padrão PrivateVehicle):

| Situação | TM | Método |
|----------|-----|--------|
| Parked (inativo) | Desregistrado | `onFinishSpontaneous(None)` |
| Navegando MESO | Registrado — agenda exitTick | `onFinishSpontaneous(Some(exitTick))` |
| Navegando MICRO | Registrado — check a cada tick | `onFinishSpontaneous(Some(currentTick + 1))` |
| Sinal vermelho | Registrado — espera nextTick | `onFinishSpontaneous(Some(data.nextTick))` |
| WaitingSignalState | Registrado — retry | `onFinishSpontaneous(Some(currentTick + 1))` |
| Finished | Desregistrado | `onFinishSpontaneous(None)` + `selfDestruct()` |

### Eventos Recebidos (actInteractWith)

| Evento | Origem | Ação |
|--------|--------|------|
| `StartTripData` | Person | Ativa veículo (PrivateVehicle) |
| `LinkInfoData` (EnterLink) | Link (MESO) | Calcula tempo de travessia (20 km/h fixo) |
| `LinkInfoData` (LeaveLink) | Link (MESO) | Acumula distância |
| `SignalStateData` | Node | Espera (Red) ou avança (Green) |
| `MicroEnterLinkData` | Link (MICRO) | Inicializa `MicroBicycleState` |
| `MicroUpdateData` | Link (MICRO) | Atualiza posição/velocidade |
| `MicroLeaveLinkData` | Link (MICRO) | Finaliza modo micro |

### Eventos Enviados

| Evento | Destino | Quando |
|--------|---------|--------|
| `EnterLinkData` | Link | Entrando em novo link |
| `LeaveLinkData` | Link | Saindo do link |
| `RequestSignalStateData` | Node | Fim do link, antes de sair |
| `TripCompletedData` | Person | Viagem finalizada |

### Relatórios Emitidos

| Label | Quando | Campos-Chave |
|-------|--------|-------------|
| `journey_started` | Rota calculada | bicycle_id, origin, destination, route_length |
| `enter_link` | Entrando link MESO | bicycle_id, speed=5.56, travel_time |
| `enter_micro_link` | Entrando link MICRO | bicycle_id, prefers_bike_lane, lane, initial_velocity |
| `leave_link` | Saindo link MESO | bicycle_id, total_distance |
| `leave_micro_link` | Saindo link MICRO | bicycle_id, travel_time, distance_traveled, average_speed |
| `signal_wait` | Sinal vermelho | vehicle_type="bicycle" |
| `journey_completed` | Finalização | completion_reason, total_distance |
| `sumo_tripinfo` | Finalização | vType="bicycle", duration, routeLength, waitingTime, timeLoss |

---

## 17. Motorcycle — Motocicleta

### Visão Geral

O Motorcycle é um **veículo privado** ágil e agressivo que pertence a uma Person. Distingue-se do Car por:
- **Alta aceleração**: 3.5 m/s² (vs 2.6 do Car) — o mais rápido entre veículos privados
- **Lane filtering**: pode filtrar entre faixas em trânsito lento (< 30 km/h)
- **Agressividade configurável**: fator [0-1] que afeta aceitação de gaps e aceleração
- **Gaps menores aceitos**: `effectiveMinGap = minGap × (1 - aggressiveness × 0.3)`
- **Velocidade MESO**: 1.2× velocidade dos carros (atravessa tráfego mais rápido)
- **Veículo compacto**: 2.5 m de comprimento
- **Modelo Person-Centric**: passivo (Parked) até ativado por Person via `StartTrip`

### Estado (`MotorcycleState`)

| Campo | Tipo | Descrição |
|-------|------|-----------|
| `startTick` | `Tick` | Tick de partida |
| `origin` / `destination` | `String` | Nós extremos da rota |
| `bestRoute` | `Option[Queue[(String, String)]]` | Rota calculada |
| `distance` | `Double` | Distância total percorrida |
| `status` | `MovableStatusEnum` | Estado da máquina de estados |
| `actorType` | `ActorTypeEnum` | Tipo do ator |
| `size` | `Double` | Tamanho do veículo |
| `currentSimulationMode` | `SimulationModeEnum` | MESO ou MICRO |
| `microState` | `Option[MicroMotorcycleState]` | Estado microscópico (ativado em links MICRO) |

### Máquina de Estados

Idêntica ao Car/Bicycle (padrão PrivateVehicle). A mesma máquina de estados do Bicycle (§16) se aplica.

**Diferença em Moving (MICRO)**: a cada tick, verifica `shouldAttemptLaneFiltering()` para decidir se filtra entre faixas.

### Comportamento MESO

- Velocidade: **1.2× velocidade baseada em densidade** (`linkDensitySpeed() × 1.2`)
- Motocicletas são mais rápidas que carros em tráfego congestionado
- Tempo de travessia: `linkLength / (motorcycleSpeed)`

### Comportamento MICRO

- Usa Krauss car-following com **randomness = 0.15** (mais agressivo/previsível)
- Inicializa `MicroMotorcycleState` com velocidade inicial = 90% do speedLimit
- **Lane filtering**: ativado quando tráfego é lento E gap é pequeno E agressividade alta

### Lane Filtering (Filtragem de Faixas)

```
shouldAttemptLaneFiltering(micro) =
  micro.canFilterLanes == true
  AND micro.leaderVelocity < 8.33 m/s (30 km/h)   // tráfego lento
  AND micro.gapToLeader < 20.0 m                   // gap pequeno
  AND micro.aggressiveness > 0.5                    // piloto agressivo
```

Quando ativado: `filteringBetweenLanes = true` e motocicleta avança entre faixas.

### MicroMotorcycleState — Parâmetros

| Parâmetro | Valor | Comparação com Car |
|-----------|-------|--------------------|
| `vehicleLength` | 2.5 m | Car: 4.5 m |
| `maxAcceleration` | 3.5 m/s² | Car: 2.6 m/s² (**+35%**) |
| `maxDeceleration` | 5.0 m/s² | Car: 4.5 m/s² (**+11%**) |
| `minGap` | 1.5 m | Car: 2.0 m |
| `desiredVelocity` | 16.67 m/s (60 km/h) | Car: 13.89 m/s (50 km/h) |
| `reactionTime` | 0.9 s | Car: 1.0 s (**mais rápido**) |
| `canFilterLanes` | true | N/A |
| `aggressiveness` | 0.7 | N/A |
| `filteringBetweenLanes` | false (dinâmico) | N/A |

**Gap efetivo**: `effectiveMinGap = 1.5 × (1 - 0.7 × 0.3) = 1.185 m` (com agressividade padrão)

### DriverAttributes e Agressividade

Quando Person ativa a Motorcycle, os `DriverAttributes` são aplicados:
```scala
desiredVelocity *= attrs.maxSpeedFactor
reactionTime = attrs.reactionTime
minGap *= attrs.minGapFactor
aggressiveness = attrs.aggressiveness        // sobrescreve
maxAcceleration *= (0.9 + 0.2 × aggressiveness)  // escala com agressividade
```

### Gestão do TimeManager

Idêntica ao Car/Bicycle (padrão PrivateVehicle). Mesma tabela do Bicycle (§16).

### Eventos Recebidos (actInteractWith)

| Evento | Origem | Ação |
|--------|--------|------|
| `StartTripData` | Person | Ativa veículo (PrivateVehicle) |
| `LinkInfoData` (EnterLink) | Link (MESO) | Calcula tempo (1.2× velocidade de tráfego) |
| `LinkInfoData` (LeaveLink) | Link (MESO) | Acumula distância |
| `SignalStateData` | Node | Espera (Red) ou avança (Green) |
| `MicroEnterLinkData` | Link (MICRO) | Inicializa `MicroMotorcycleState` |
| `MicroUpdateData` | Link (MICRO) | Atualiza posição; verifica lane filtering |
| `MicroLeaveLinkData` | Link (MICRO) | Finaliza modo micro |

### Eventos Enviados

| Evento | Destino | Quando |
|--------|---------|--------|
| `EnterLinkData` | Link | Entrando em novo link |
| `LeaveLinkData` | Link | Saindo do link |
| `RequestSignalStateData` | Node | Fim do link, antes de sair |
| `TripCompletedData` | Person | Viagem finalizada |

### Relatórios Emitidos

| Label | Quando | Campos-Chave |
|-------|--------|-------------|
| `journey_started` | Rota calculada | motorcycle_id, origin, destination, aggressiveness |
| `enter_link` | Entrando link MESO | motorcycle_id, speed, speed_multiplier=1.2 |
| `enter_micro_link` | Entrando link MICRO | motorcycle_id, can_filter_lanes, aggressiveness, lane |
| `leave_link` | Saindo link MESO | motorcycle_id, total_distance |
| `leave_micro_link` | Saindo link MICRO | motorcycle_id, travel_time, distance_traveled, average_speed |
| `signal_wait` | Sinal vermelho | vehicle_type="motorcycle", aggressiveness |
| `journey_completed` | Finalização | completion_reason, total_distance |
| `sumo_tripinfo` | Finalização | vType="motorcycle", duration, routeLength, waitingTime, timeLoss |

### Comparação: Todos os Veículos Privados

| Aspecto | Car | Bicycle | Motorcycle |
|---------|-----|---------|------------|
| **Comprimento** | 4.5 m | 2.0 m | 2.5 m |
| **Aceleração máx** | 2.6 m/s² | 1.0 m/s² | 3.5 m/s² |
| **Desaceleração máx** | 4.5 m/s² | 3.0 m/s² | 5.0 m/s² |
| **Gap mínimo** | 2.0 m | 1.5 m | 1.5 m (efetivo: ~1.2 m) |
| **Velocidade desejada** | 50 km/h | 20 km/h | 60 km/h |
| **Tempo de reação** | 1.0 s | 1.2 s | 0.9 s |
| **Krauss randomness** | — | 0.3 | 0.15 |
| **Velocidade MESO** | `linkDensitySpeed()` | 5.56 m/s (fixo) | `linkDensitySpeed() × 1.2` |
| **Lane filtering** | Não | Não | Sim |
| **Preferência de faixa** | Nenhuma | Ciclovia | Nenhuma |
| **PrivateVehicle** | Sim | Sim | Sim |

---

## 18. Fluxos de Mensagem Completos

### Fluxo 1: Viagem Completa Person → Car → Links → Nodes → Person

> Descrito na seção anterior (replicado abaixo para referência).

```
Person                Car                  Link₁ (MESO)       Node₁              Link₂ (MICRO)       Node₂
  │                    │                    │                   │                   │                   │
  │─ StartTripData ──►│                    │                   │                   │                   │
  │                    │                    │                   │                   │                   │
  │ [deregistra TM]    │─ EnterLinkData ──►│                   │                   │                   │
  │                    │◄─ LinkInfoData ───│                   │                   │                   │
  │                    │                    │                   │                   │                   │
  │                    │ [calcula speed]    │                   │                   │                   │
  │                    │ [agenda exitTick]  │                   │                   │                   │
  │                    │                    │                   │                   │                   │
  │                    │ ... (travel) ...   │                   │                   │                   │
  │                    │                    │                   │                   │                   │
  │                    │─ ReqSignalState ──────────────────────►│                   │                   │
  │                    │◄─ SignalState(G) ──────────────────────│                   │                   │
  │                    │                    │                   │                   │                   │
  │                    │─ LeaveLinkData ──►│                   │                   │                   │
  │                    │◄─ LinkInfoData ───│                   │                   │                   │
  │                    │                    │                   │                   │                   │
  │                    │─ EnterLinkData ──────────────────────────────────────────►│                   │
  │                    │◄─ MicroEnterLink ────────────────────────────────────────│                   │
  │                    │                    │                   │                   │                   │
  │                    │ [deregistra TM]    │                   │     TM ────────►│                   │
  │                    │                    │                   │                   │─ MicroUpdate ───►│ (Car)
  │                    │                    │                   │                   │─ MicroUpdate ───►│
  │                    │                    │                   │                   │                   │
  │                    │ [position>=length] │                   │                   │                   │
  │                    │─ LeaveLinkData ──────────────────────────────────────────►│                   │
  │                    │◄─ MicroLeaveLink ────────────────────────────────────────│                   │
  │                    │                    │                   │                   │                   │
  │                    │─ ReqSignalState ──────────────────────────────────────────────────────────────►│
  │                    │◄─ SignalState(G) ──────────────────────────────────────────────────────────────│
  │                    │                    │                   │                   │                   │
  │                    │ [destino alcançado]│                   │                   │                   │
  │                    │ [finishJourney()]  │                   │                   │                   │
  │◄─ TripCompleted ──│                    │                   │                   │                   │
  │                    │ [selfDestruct()]   │                   │                   │                   │
  │                    │                    │                   │                   │                   │
  │ [advanceActivity]  │                   │                   │                   │                   │
  │ [re-registra TM]   │                   │                   │                   │                   │
```

### Fluxo 2: Viagem a Pé (Sem interação com Link/Node)

```
Person                TimeManager
  │                       │
  │ [calcula rota]        │
  │ [calcula distância]   │
  │ [walkTime = dist/1.4] │
  │                       │
  │── onFinishSpontaneous(arrivalTick) ──►│
  │                       │
  │ ... (sleep) ...       │
  │                       │
  │◄── SpontaneousEvent ──│ (no arrivalTick)
  │                       │
  │ [advanceToNextActivity]│
  │ [report walking_completed]│
```

### Fluxo 3: Viagem de Transporte Público

```
Person               BusStop/Station      Bus/Subway           Node
  │                       │                   │                  │
  │─ RegisterPassenger ──►│                   │                  │
  │                       │                   │                  │
  │ [deregistra TM]       │                   │                  │
  │                       │                   │                  │
  │                       │    ... (PT opera  │                  │
  │                       │    normalmente)   │                  │
  │                       │                   │                  │
  │                       │◄── chegou stop ───│                  │
  │                       │                   │                  │
  │◄─ BusReqUnload ───────────────────────────│                  │
  │   (nodeId)            │                   │                  │
  │                       │                   │                  │
  │ [nodeId == alighting?] │                  │                  │
  │                       │                   │                  │
  │── BusUnloadPassenger ─────────────────────►│                 │
  │   (isArrival=true)    │                   │                  │
  │                       │                   │                  │
  │ [completeTrip()]      │                   │                  │
  │ [advanceActivity()]   │                   │                  │
  │ [re-registra TM]      │                   │                  │
```

---

### Fluxo 4: Ciclo Completo do Transporte Público (BusStation → Bus → BusStop → Person)

```
BusStation              Bus                  Link           Node          BusStop         Person
  │                      │                    │               │              │               │
  │ [calcRoutesFromMap]  │                    │               │              │               │
  │ [createBus(rota)]    │                    │               │              │               │
  │─────── cria ────────►│                    │               │              │               │
  │                      │                    │               │              │               │
  │                      │── EnterLinkData ──►│               │              │               │
  │                      │◄── LinkInfoData ───│               │              │               │
  │                      │ [moving...]        │               │              │               │
  │                      │─ ReqSignalState ──────────────────►│              │               │
  │                      │◄─ SignalState(G) ──────────────────│              │               │
  │                      │── LeaveLinkData ──►│               │              │               │
  │                      │◄── LinkInfoData ───│               │              │               │
  │                      │                    │               │              │               │
  │                      │ [nó tem BusStop?]  │               │              │               │
  │                      │     SIM            │               │              │               │
  │                      │                    │               │              │               │
  │                      │── BusReqUnload ────────────────────────────────────────────────────►│ (Person₁)
  │                      │── BusReqUnload ────────────────────────────────────────────────────►│ (Person₂)
  │                      │                    │               │              │               │
  │                      │◄── BusUnload(F) ──────────────────────────────────────────────────│ (Person₁: fica)
  │                      │◄── BusUnload(T) ──────────────────────────────────────────────────│ (Person₂: desce)
  │                      │                    │               │              │               │
  │                      │ [remove Person₂]   │               │              │               │
  │                      │ [delay desembarque] │               │              │               │
  │                      │                    │               │              │               │
  │                      │── BusReqPassenger ────────────────────────────────►│               │
  │                      │   (label, space)   │               │              │               │
  │                      │◄── BusLoadPass ───────────────────────────────────│               │
  │                      │   (people:[P₃])    │               │              │               │
  │                      │                    │               │              │               │
  │                      │ [adiciona P₃]      │               │              │               │
  │                      │ [delay embarque]   │               │              │               │
  │                      │                    │               │              │               │
  │                      │── EnterLinkData ──►│  (próximo link)│              │               │
  │                      │   ... continua ... │               │              │               │
```

---

## 19. Protocolos de Comunicação

### Protocolo: Entrada em Link (MESO)

```
Car ─────── EnterLinkData ─────────► Link
     actorId, shardId, actorType,
     actorSize, actorCreationType

Link ────── LinkInfoData ──────────► Car
     linkLength, linkCapacity,
     linkNumberOfCars, linkFreeSpeed,
     linkLanes
     eventType = "ReceiveEnterLinkInfo"
```

### Protocolo: Saída de Link (MESO)

```
Car ─────── LeaveLinkData ────────► Link
     actorId, shardId, actorType,
     actorSize, actorCreationType

Link ────── LinkInfoData ──────────► Car
     linkLength, linkCapacity,
     linkNumberOfCars, linkFreeSpeed,
     linkLanes
     eventType = "ReceiveLeaveLinkInfo"
```

### Protocolo: Entrada em Link (MICRO)

```
Car ─────── EnterLinkData ────────► Link
     (mesmos campos do MESO)

Link ────── MicroEnterLinkData ───► Car
     linkId, mode=MICRO,
     assignedLane, linkLength,
     speedLimit, numberOfLanes,
     microTimeStep, ticksPerGlobalTick
```

### Protocolo: Atualização Micro (Link → Car)

```
Link ────── MicroUpdateData ──────► Car
     subTick, position, velocity,
     acceleration, currentLane,
     leaderVehicle, gapToLeader,
     leaderVelocity, safeVelocity
```

### Protocolo: Saída de Link (MICRO)

```
Car ─────── LeaveLinkData ────────► Link
     (mesmos campos)

Link ────── MicroLeaveLinkData ───► Car
     linkId, finalPosition,
     finalVelocity, travelTime,
     distanceTraveled, averageSpeed,
     waitingTimeSeconds
```

### Protocolo: Consulta de Semáforo

```
Car ─────── RequestSignalStateData ► Node
     targetLinkId

Node ────── SignalStateData ────────► Car
     phase (Green/Red),
     nextTick (quando muda)
```

### Protocolo: Ativação de Veículo Privado

```
Person ──── StartTripData ─────────► Car
     personId, origin, destination,
     driverAttributes, startTick

Car ─────── TripCompletedData ─────► Person
     vehicleId, personId,
     distanceTraveled, travelTime,
     finalNode, completionTick,
     completionReason
```

### Protocolo: Entrada em RailLink (Subway)

```
Subway ──── EnterLinkData ────────► RailLink
     actorId, shardId, actorType

     [RailLink valida canAcceptVehicle()]
     [RailLink calcula effectiveSpeed]

RailLink ── LinkInfoData ─────────► Subway
     linkLength, linkCapacity,
     linkNumberOfCars, linkFreeSpeed=effectiveSpeed,
     linkLanes
     eventType = "ReceiveEnterLinkInfo"
```

### Protocolo: Saída de RailLink (Subway)

```
Subway ──── LeaveLinkData ────────► RailLink

RailLink ── LinkInfoData ─────────► Subway
     (mesmos campos)
     eventType = "ReceiveLeaveLinkInfo"
```

### Protocolo: Embarque/Desembarque Subway (Paralelo)

```
[Desembarque — enviado a cada passageiro]
Subway ──── SubwayRequestUnloadPassengerData ─► Person (×N)
     nodeId, nodeRef

Person ──── SubwayUnloadPassengerData ────────► Subway (×N)
     isArrival (true=desce, false=fica)

[Embarque — enviado à SubwayStation]
Subway ──── SubwayRequestPassengerData ───────► SubwayStation
     line, availableSpace

SubwayStation ── SubwayLoadPassengerData ─────► Subway
     people: Seq[Identify]

[Ambos ocorrem em paralelo. Subway só prossegue
 quando isLoaded=true AND isUnloaded=true]
```

### Protocolo: Atualização de Semáforo (TrafficSignal → Node)

```
TrafficSignal ── TrafficSignalChangeStatusData ─► Node
     signalState (state, remainingTime, nextTick)
     phaseOrigin
     nextTick

[Node armazena em signals(phaseOrigin) = signalState]
[Veículos consultam via RequestSignalStateData]
```

### Protocolo: Registro de Estação de Metrô

```
SubwayStation ── RegisterSubwayStationData ────► Node
     lines: List[String]

[Node armazena em subwayStations(line) = identify]
```

### Protocolo: Registro de Passageiro no Metrô

```
Person ──── RegisterSubwayPassengerData ──────► SubwayStation
     line

[SubwayStation armazena em people(line) += person]
```

---

## 20. Relatórios Emitidos

### Resumo por Entidade

| Entidade | Labels de Report |
|----------|------------------|
| **Person** | `person_walking_start`, `person_walking_completed`, `person_pt_trip_start`, `person_pt_trip_completed`, `person_trip_completed`, `person_activity_start`, `person_schedule_complete` |
| **Car** | `journey_started`, `route_planned`, `enter_link`, `enter_micro_link`, `leave_link`, `leave_micro_link`, `signal_wait`, `journey_completed`, `vehicle_event_count`, `sumo_tripinfo` |
| **Link** | `link_vehicle_entered`, `link_vehicle_left`, `sumo_summary_step` |
| **Node** | `node_signal_requested` |
| **Bus** | `journey_started`, `enter_link`, `enter_micro_link`, `leave_link`, `leave_micro_link`, `signal_wait`, `bus_load_passengers`, `bus_unload_passengers`, `journey_completed`, `sumo_tripinfo` |
| **BusStation** | `bus_created` |
| **BusStop** | `bus_stop_passengers_loaded`, `bus_stop_passenger_arrived` |
| **Subway** | *(sem reports diretos no código atual)* |
| **SubwayStation** | `subway_created` |
| **RailLink** | *(sem reports no código atual)* |
| **TrafficSignal** | `signal_phase_change` |
| **Bicycle** | `journey_started`, `enter_link`, `enter_micro_link`, `leave_link`, `leave_micro_link`, `signal_wait`, `journey_completed`, `sumo_tripinfo` |
| **Motorcycle** | `journey_started`, `enter_link`, `enter_micro_link`, `leave_link`, `leave_micro_link`, `signal_wait`, `journey_completed`, `sumo_tripinfo` |

### Fluxo de um Relatório

```
Ator                ReportManager              Storage
  │                      │                       │
  │── report(data, label)│                       │
  │      │               │                       │
  │      ▼               │                       │
  │   ReportEvent(       │                       │
  │     entityId,        │                       │
  │     tick,            │                       │
  │     lamportTick,     │                       │
  │     data,            │                       │
  │     label            │                       │
  │   )                  │                       │
  │      │               │                       │
  │      └──────────────►│                       │
  │                      │── persist ───────────►│
  │                      │   (CSV/Kafka/Avro)    │
  │                      │                       │
  │                      │── Prometheus.inc() ──►│ (métricas)
```

### Métricas Prometheus Automáticas

| Métrica | Quando |
|---------|--------|
| `eventsProcessed(label)` | Qualquer report com label |
| `journeysStarted(vehicleType)` | Report com label "journey_started" |
| `journeysCompleted(vehicleType)` | Report com label "journey_completed" |

---

## Apêndice: Tabela de Tipos de Eventos

| eventType (String) | Data Class | Direção |
|---------------------|-----------|---------|
| `"EnterLink"` | `EnterLinkData` | Car → Link |
| `"LeaveLink"` | `LeaveLinkData` | Car → Link |
| `"ReceiveEnterLinkInfo"` | `LinkInfoData` | Link → Car |
| `"ReceiveLeaveLinkInfo"` | `LinkInfoData` | Link → Car |
| `"MicroEnterLink"` | `MicroEnterLinkData` | Link → Car |
| `"MicroUpdate"` | `MicroUpdateData` | Link → Car |
| `"MicroLeaveLink"` | `MicroLeaveLinkData` | Link → Car |
| `"RequestSignalState"` | `RequestSignalStateData` | Car → Node |
| `"ReceiveSignalState"` | `SignalStateData` | Node → Car |
| `"StartTrip"` | `StartTripData` | Person → Car |
| `"TripCompleted"` | `TripCompletedData` | Car → Person |
| `"RegisterPassenger"` | `RegisterPassengerData` | Person → BusStop |
| `"UnloadPassengerResponse"` | `BusUnloadPassengerData` | Person → Bus |
| `"RequestUnloadPassenger"` | `BusRequestUnloadPassengerData` | Bus → Person |
| `"RequestPassenger"` | `BusRequestPassengerData` | Bus → BusStop |
| (resposta) | `BusLoadPassengerData` | BusStop → Bus |
| (init) | `RegisterBusStopData` | BusStop → Node |
| `"RegisterSubwayStation"` | `RegisterSubwayStationData` | SubwayStation → Node |
| `"RegisterSubwayPassenger"` | `RegisterSubwayPassengerData` | Person → SubwayStation |
| (request) | `SubwayRequestPassengerData` | Subway → SubwayStation |
| (response) | `SubwayLoadPassengerData` | SubwayStation → Subway |
| (request) | `SubwayRequestUnloadPassengerData` | Subway → Person |
| (response) | `SubwayUnloadPassengerData` | Person → Subway |
| `"TrafficSignalChangeStatus"` | `TrafficSignalChangeStatusData` | TrafficSignal → Node |
| `"EnterLink"` | `EnterLinkData` | Subway → RailLink |
| `"LeaveLink"` | `LeaveLinkData` | Subway → RailLink |
| `"ReceiveEnterLinkInfo"` | `LinkInfoData` | RailLink → Subway |
| `"ReceiveLeaveLinkInfo"` | `LinkInfoData` | RailLink → Subway |
| `"StartTrip"` | `StartTripData` | Person → Bicycle |
| `"TripCompleted"` | `TripCompletedData` | Bicycle → Person |
| `"EnterLink"` | `EnterLinkData` | Bicycle → Link |
| `"LeaveLink"` | `LeaveLinkData` | Bicycle → Link |
| `"RequestSignalState"` | `RequestSignalStateData` | Bicycle → Node |
| `"StartTrip"` | `StartTripData` | Person → Motorcycle |
| `"TripCompleted"` | `TripCompletedData` | Motorcycle → Person |
| `"EnterLink"` | `EnterLinkData` | Motorcycle → Link |
| `"LeaveLink"` | `LeaveLinkData` | Motorcycle → Link |
| `"RequestSignalState"` | `RequestSignalStateData` | Motorcycle → Node |
