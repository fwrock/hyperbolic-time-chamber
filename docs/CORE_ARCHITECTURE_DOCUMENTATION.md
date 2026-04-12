# Documentação: Arquitetura Core da Simulação — Managers, Classes Base e Fluxos

## Índice

1. [Visão Geral da Arquitetura Core](#1-visão-geral-da-arquitetura-core)
2. [Hierarquia de Classes Base](#2-hierarquia-de-classes-base)
3. [Tipos e Constantes Fundamentais](#3-tipos-e-constantes-fundamentais)
4. [BaseActor — Ator Genérico Base](#4-baseactor--ator-genérico-base)
5. [SimulationBaseActor — Ator de Simulação](#5-simulationbaseactor--ator-de-simulação)
6. [BaseManager — Manager Base](#6-basemanager--manager-base)
7. [SimulationManager — Orquestrador Principal](#7-simulationmanager--orquestrador-principal)
8. [Subsistema de Tempo (Time Managers)](#8-subsistema-de-tempo-time-managers)
9. [GlobalTimeManager — Coordenador Global de Tempo](#9-globaltimemanager--coordenador-global-de-tempo)
10. [LocalTimeManagerBase — Base dos TMs Locais](#10-localtimemanagerbase--base-dos-tms-locais)
11. [LocalDiscreteEventTimeManager — TM de Eventos Discretos](#11-localdiscreteeventtimemanager--tm-de-eventos-discretos)
12. [LocalTimeSteppedTimeManager — TM Time-Stepped](#12-localtimesteppedtimemanager--tm-time-stepped)
13. [MicroAwareTimeManager — Suporte Microscópico](#13-microawaretimemanager--suporte-microscópico)
14. [Subsistema de Carga (Load Data)](#14-subsistema-de-carga-load-data)
15. [LoadDataManager — Gerenciador de Carga Eager](#15-loaddatamanager--gerenciador-de-carga-eager)
16. [ProgressiveLoadDataManager — Carga Progressiva](#16-progressiveloaddatamanager--carga-progressiva)
17. [Creators — Criadores de Atores](#17-creators--criadores-de-atores)
18. [Estratégias de Carga (Load Strategies)](#18-estratégias-de-carga-load-strategies)
19. [Subsistema de Relatórios (Reporting)](#19-subsistema-de-relatórios-reporting)
20. [RandomSeedManager — Reprodutibilidade](#20-randomseedmanager--reprodutibilidade)
21. [LamportClock — Ordenação Causal](#21-lamportclock--ordenação-causal)
22. [MetricsServer — Observabilidade (Prometheus)](#22-metricsserver--observabilidade-prometheus)
23. [Properties — Configuração de Atores](#23-properties--configuração-de-atores)
24. [Fluxo de Inicialização Completo](#24-fluxo-de-inicialização-completo)
25. [Fluxo de Execução da Simulação](#25-fluxo-de-execução-da-simulação)
26. [Fluxo de Carga Progressiva](#26-fluxo-de-carga-progressiva)
27. [Decisões Arquiteturais](#27-decisões-arquiteturais)
28. [Configuração (application.conf)](#28-configuração-applicationconf)
29. [Apêndice: Tabela de Eventos Core](#29-apêndice-tabela-de-eventos-core)

---

## 1. Visão Geral da Arquitetura Core

O Hyperbolic Time Chamber (HTC) é um simulador de tráfego urbano distribuído, construído sobre **Apache Pekko** (fork do Akka). A arquitetura core fornece a infraestrutura para que entidades de simulação (Car, Person, Link, etc.) executem dentro de um framework distribuído, com gestão de tempo, carga de dados e relatórios.

### Componentes Core

| Componente | Papel | Padrão de Deploy |
|------------|-------|-------------------|
| **SimulationManager** | Orquestrador geral — inicializa todos os outros managers | ClusterSingleton |
| **GlobalTimeManager** | Coordenação global de tempo entre TMs locais | ClusterSingleton |
| **LocalTimeManager(s)** | Execução local de eventos — despacha `SpontaneousEvent` | ClusterRouterPool |
| **LoadDataManager** | Carga EAGER de atores antes da simulação | ClusterSingleton |
| **ProgressiveLoadDataManager** | Carga PROGRESSIVE durante a simulação | ClusterSingleton |
| **ReportManager** | Coordena reporters para persistência de dados | ClusterSingleton |
| **ReportData** (CSV/JSON) | Persiste eventos em disco | ClusterRouterPool |
| **CreatorLoadData** | Cria atores via Shard Region (LoadBalancedDistributed) | ClusterRouterPool |
| **CreatorPoolLoadData** | Cria atores via Pool (PoolDistributed) | ClusterRouterPool |
| **RandomSeedManager** | Reprodutibilidade determinística | Object (singleton global) |
| **MetricsServer** | Métricas Prometheus para observabilidade | Object (singleton global) |

### Diagrama de Componentes

```
┌──────────────────────────────────────────────────────────────────────┐
│                         CLUSTER PEKKO                                │
│                                                                      │
│  ┌─────────────────────┐                                            │
│  │  SimulationManager  │  (Singleton)                               │
│  │  - Orquestra tudo   │                                            │
│  └──────┬───┬───┬──────┘                                            │
│         │   │   │                                                    │
│    ┌────┘   │   └────┐                                              │
│    ▼        ▼        ▼                                              │
│  ┌─────┐ ┌──────┐ ┌────────┐                                       │
│  │ GTM │ │ LDM  │ │  RM    │                                       │
│  │     │ │      │ │        │                                        │
│  └──┬──┘ └──┬───┘ └──┬─────┘                                       │
│     │       │        │                                              │
│     ▼       ▼        ▼                                              │
│  ┌──────────────┐ ┌───────────┐ ┌──────────────┐                   │
│  │ LocalTM Pool │ │ Creators  │ │ ReportData   │                   │
│  │ (N instâncias)│ │ (Shard+  │ │ Pool (CSV/   │                   │
│  │              │ │  Pool)    │ │  JSON)       │                   │
│  └──────┬───────┘ └─────┬─────┘ └──────────────┘                   │
│         │               │                                           │
│         ▼               ▼                                           │
│  ┌─────────────────────────────────────────────┐                    │
│  │           SHARD REGIONS / ACTOR POOLS        │                    │
│  │  Car | Person | Link | Node | Bus | ...     │                    │
│  └─────────────────────────────────────────────┘                    │
└──────────────────────────────────────────────────────────────────────┘

GTM = GlobalTimeManager    LDM = LoadDataManager    RM = ReportManager
```

---

## 2. Hierarquia de Classes Base

```
PersistentActor (Pekko)
  └── ActorSerializable (trait)
        └── BaseActor[T <: BaseState]
              │
              ├── SimulationBaseActor[T <: BaseState]
              │     ├── Movable[T <: MovableState]
              │     │     ├── Car, Bus, Subway, Bicycle, Motorcycle
              │     │     └── (trait PrivateVehicle[T])
              │     ├── Link, Node, TrafficSignal, etc.
              │     ├── Person, BusStation, BusStop, SubwayStation, RailLink
              │     └── (todas as entidades de simulação)
              │
              └── BaseManager[T <: BaseState]
                    ├── TimeManagerBase
                    │     ├── GlobalTimeManager
                    │     └── LocalTimeManagerBase (+ MicroAwareTimeManager)
                    │           ├── LocalDiscreteEventTimeManager
                    │           └── LocalTimeSteppedTimeManager
                    ├── SimulationManager
                    ├── LoadDataManager
                    ├── ProgressiveLoadDataManager
                    └── ReportManager

BaseActor (sem TM)
  └── CreatorLoadData
  └── CreatorPoolLoadData
  └── LoadDataStrategy
        ├── JsonLoadData
        └── ProgressiveJsonLoadData
  └── ReportData
        ├── CsvReportData
        └── JsonReportData
```

### Separação de Responsabilidades

| Classe | Responsabilidade |
|--------|-----------------|
| `BaseActor[T]` | Gestão genérica: ID, estado, serialização, persistência, shard resolution |
| `SimulationBaseActor[T]` | Adiciona: TimeManager, LamportClock, Reporting, `sendMessageTo()`, `onFinishSpontaneous()` |
| `BaseManager[T]` | Adiciona: criação de singletons e proxies, referência a reporters |
| `TimeManagerBase` | Adiciona: estado de tempo (`scheduledActors`, `runningEvents`), protocolos de controle |

---

## 3. Tipos e Constantes Fundamentais

### Tipos Core (`core.types.CoreTypes`)

```scala
type Tick = Long         // Unidade de tempo da simulação
type EventId = Long      // Identificador de evento
type TickOffset = Tick   // Offset relativo ao tick inicial
type SubTick = Tick      // Sub-divisão de tick (micro-simulação)
```

### Constantes de Nomes de Atores (`ManagerConstantsUtil`)

| Constante | Valor | Uso |
|-----------|-------|-----|
| `GLOBAL_TIME_MANAGER_ACTOR_NAME` | `"global-time-manager"` | Singleton GTM |
| `LOCAL_TIME_MANAGER_ACTOR_NAME` | `"local-time-manager"` | Pool de TMs locais |
| `POOL_TIME_MANAGER_ACTOR_NAME` | `"pool-time-manager"` | Router do pool TM |
| `SIMULATION_MANAGER_ACTOR_NAME` | `"simulation-manager"` | Singleton SM |
| `LOAD_MANAGER_ACTOR_NAME` | `"load-manager"` | Singleton LDM |
| `REPORT_MANAGER_ACTOR_NAME` | `"report-manager"` | Singleton RM |
| `PROGRESSIVE_LOAD_MANAGER_ACTOR_NAME` | `"progressive-load-manager"` | Singleton PLM |
| `POOL_CREATOR_LOAD_DATA_ACTOR_NAME` | `"pool-creator-load-data"` | Pool de Creators (shard) |
| `POOL_CREATOR_POOL_LOAD_DATA_ACTOR_NAME` | `"pool-creator-pool-load-data"` | Pool de Creators (pool) |

---

## 4. BaseActor — Ator Genérico Base

### Localização

`core.actor.BaseActor[T <: BaseState]`

### Responsabilidades

- Gestão de identidade (`entityId`, `shardId`)
- Deserialização de estado via `JsonUtil.convertValue[T]`
- Roteamento de mensagens (`receive`)
- Resolução de shard regions e pool actors
- Controle de ciclo de vida (`selfDestruct()`, `onDestruct()`)
- Suporte a persistência (PersistentActor — atualmente desabilitada)

### Estado

| Campo | Tipo | Descrição |
|-------|------|-----------|
| `entityId` | `String` | ID único do ator (via Properties ou UUID determinístico) |
| `shardId` | `String` | Nome do shard region (= `getClass.getName`) |
| `state` | `T` | Estado tipado do ator |
| `config` | `Config` | Configuração carregada de `application.conf` |

### Receive Pipeline

```
receive:
  case DestructEvent           → destruct() → onDestruct() → context.stop(self)
  case EntityEnvelopeEvent     → handleEnvelopeEvent() → despacha para tipo correto
  case InitializeEvent         → onInitialize() → deserializa estado, chama onFinishInitialize()
  case ShardRegion.StartEntity → seta entityId
  case _                       → handleEvent() (override nas subclasses)
```

### Métodos Importantes

| Método | Descrição |
|--------|-----------|
| `onInitialize(event)` | Deserializa estado a partir de `InitializeEvent.data` |
| `onStart()` | Hook de inicialização — chamado em `preStart()` |
| `selfDestruct()` | `context.stop(self)` |
| `onDestruct(event)` | Hook para cleanup antes de destruição |
| `getShardRef(className)` | Obtém `ActorRef` do shard region pelo class name |
| `getActorPoolRef(entityId)` | Obtém `ActorSelection` para pool actors |
| `logInfo/logDebug/logWarn/logError()` | Logging com prefixo `entityId` |

---

## 5. SimulationBaseActor — Ator de Simulação

### Localização

`core.actor.SimulationBaseActor[T <: BaseState]`

### Responsabilidades

Estende `BaseActor` com capacidades específicas de simulação:
- **Gestão de tempo**: registro no TimeManager, agendamento de eventos
- **Comunicação inter-atores**: `sendMessageTo()` via shard ou pool
- **Relógio de Lamport**: ordenação causal de eventos
- **Reporting**: envio de dados ao `ReportManager`
- **Dependências**: resolução de atores dependentes

### Estado Adicional

| Campo | Tipo | Descrição |
|-------|------|-----------|
| `startTick` | `Tick` | Tick de início do ator |
| `currentTick` | `Tick` | Tick atual da simulação |
| `lamportClock` | `LamportClock` | Relógio lógico para ordenação |
| `timeManagers` | `Map[String, ActorRef]` | Mapa tipo→TM (`"discrete-event"` → ref) |
| `currentTimeManagerType` | `String` | TM em uso (padrão: `"discrete-event"`) |
| `reporters` | `Map[ReportTypeEnum, ActorRef]` | Reporters por tipo (csv, json) |
| `dependencies` | `Map[String, Dependency]` | Dependências de outros atores |
| `creatorManager` | `ActorRef` | Creator que criou este ator |

### Receive Pipeline (Override)

```
receive:
  case SpontaneousEvent      → handleSpontaneous() → actSpontaneous(event)
  case ActorInteractionEvent → handleInteractWith() → actInteractWith(event)
  case _                     → super.receive (BaseActor)
```

### Ciclo de Vida do Ator de Simulação

```
1. Creator envia ShardRegion.StartEntity(entityId)
   └→ Shard cria instância, chama preStart()
        └→ Se properties.data != null: deserializa estado, envia StartEntityAckEvent
        └→ Se scheduleOnTimeManager == true: registerOnTimeManager()
        └→ onStart()

2. Creator envia InitializeEvent (via EntityEnvelopeEvent)
   └→ onInitialize(): configura timeManagers, dependencies, reporters
   └→ Se scheduleOnTimeManager == true: registerOnTimeManager()
   └→ Envia InitializeEntityAckEvent de volta ao creator

3. TimeManager envia SpontaneousEvent(tick, actorRef=TM)
   └→ handleSpontaneous(): atualiza currentTick, chama actSpontaneous()
   └→ Ator processa lógica, chama onFinishSpontaneous(nextTick)

4. Outro ator envia ActorInteractionEvent
   └→ handleInteractWith(): atualiza LamportClock + currentTick, chama actInteractWith()

5. Ator chama selfDestruct() ou recebe DestructEvent
   └→ onDestruct() + context.stop(self)
```

### Protocolo onFinishSpontaneous

```scala
onFinishSpontaneous(scheduleTick: Option[Tick], destruct: Boolean = false)
```

Este é o método **mais crítico** da simulação. Regras:

1. **Sempre** envia `FinishEvent` ao TM que disparou o `SpontaneousEvent` (`currentTimeManager`)
2. Se `scheduleTick = Some(tick)`: envia `ScheduleEvent(tick)` ao **mesmo** TM
3. Se `scheduleTick = None`: TM remove o ator de **todos** os ticks futuros (desregistro)
4. Se `destruct = true`: TM envia `DestructEvent` ao ator

**Decisão crítica**: O `ScheduleEvent` é enviado ao `currentTimeManager` (o TM do `SpontaneousEvent`), não ao TM do pool via `getTimeManager()`. Isso evita inconsistências de cross-TM scheduling.

### sendMessageTo() — Comunicação Inter-Atores

```scala
sendMessageTo(entityId, shardId, data, eventType, actorType)
```

- Incrementa LamportClock
- Se `actorType == PoolDistributed`: resolve via `getActorPoolRef(entityId)`
- Senão: resolve via `getShardRef(shardId)`, envia `EntityEnvelopeEvent`
- O evento `ActorInteractionEvent` carrega: tick, lamportTick, actorRefId, data

### report() — Envio de Relatórios

```scala
report(data: Any, label: String)
```

- Cria `ReportEvent(entityId, tick, lamportTick, data, label)`
- Registra métricas Prometheus (journey_started, journey_completed)
- Envia ao reporter correto baseado em `state.getReporterType` ou `default-strategy`

---

## 6. BaseManager — Manager Base

### Localização

`core.actor.manager.BaseManager[T <: BaseState]`

### Responsabilidades

- Classe base para todos os managers (não-simulação)
- Criação de ClusterSingletons e ClusterSingleton Proxies
- Acesso a reporters e TM references

### Métodos de Infraestrutura

| Método | Descrição |
|--------|-----------|
| `createSingletonManager(props, name, terminateMessage)` | Cria ClusterSingleton via `ActorCreatorUtil` |
| `createSingletonProxy(name)` | Cria proxy para acessar singleton de qualquer nó |

---

## 7. SimulationManager — Orquestrador Principal

### Localização

`core.actor.manager.SimulationManager`

### Deploy

**ClusterSingleton** — instância única no cluster.

### Responsabilidades

- Carregar configuração da simulação (JSON)
- Criar todos os managers (GTM, LDM, RM, PLM)
- Orquestrar sequência de startup
- Coordenar shutdown gracioso

### Estado

| Campo | Tipo | Descrição |
|-------|------|-----------|
| `configuration` | `Simulation` | Configuração carregada do JSON |
| `timeSingletonManager` | `ActorRef` | Referência ao GTM singleton |
| `poolTimeManager` | `ActorRef` | Referência ao pool de TMs locais |
| `loadManager` | `ActorRef` | Referência ao LDM singleton |
| `reportManager` | `ActorRef` | Referência ao RM singleton |
| `progressiveLoadManager` | `ActorRef` | Referência ao PLM singleton |

### Eventos Recebidos

| Evento | Origem | Ação |
|--------|--------|------|
| `PrepareSimulationEvent` | self (onStart) | Carrega configuração, verifica cluster quorum |
| `TimeManagerRegisterEvent` | GTM (via pool) | Registra referência ao pool TM |
| `RegisterReportersEvent` | RM | Registra referência aos reporters |
| `FinishLoadDataEvent` | LDM | Inicia simulação (envia StartSimulationTimeEvent ao GTM) |
| `ProgressiveLoadingCompleteEvent` | PLM | Notifica GTM que carga progressiva terminou |
| `StopSimulationEvent` | GTM | Propaga stop para todos os managers |

### Fluxo de Startup

```
onStart()
  │
  ▼
PrepareSimulationEvent (self → self via proxy)
  │
  ├─ Carrega configuração (io-dispatcher, async)
  ├─ Verifica cluster quorum (min-nr-of-members)
  │    └─ Se insuficiente: retry em 5s
  │
  ├─ createSingletonTimeManager() → GTM
  └─ createSingletonReportManager() → RM
       │
       ▼
     RM.onStart() → cria reporters → RegisterReportersEvent → SM
     GTM.onStart() → cria pool TMs → TimeManagerRegisterEvent → SM
       │
       ▼ (quando ambos chegam)
     startLoadData()
       │
       ├─ createSingletonLoadManager() → LDM
       └─ LDM ! LoadDataEvent(actorsDataSources)
            │
            ▼
          (carga EAGER completa)
            │
            ▼
          LDM → SM ! FinishLoadDataEvent
            │
            ▼
          SM ! startSimulation()
            ├─ Se fontes PROGRESSIVE existem:
            │    ├─ Cria PLM singleton
            │    ├─ GTM ! RegisterProgressiveLoadManagerEvent
            │    └─ PLM ! StartProgressiveLoadingEvent
            │
            └─ GTM ! StartSimulationTimeEvent(startTick)
```

---

## 8. Subsistema de Tempo (Time Managers)

### Arquitetura em Duas Camadas

```
                    ┌───────────────────┐
                    │  GlobalTimeManager │  (Singleton)
                    │  - Barreira global │
                    │  - Coordena progr. │
                    └─────────┬─────────┘
                              │ UpdateGlobalTimeEvent (Broadcast)
                              │ LocalTimeReportEvent  (cada TM)
                              │
           ┌──────────────────┼──────────────────┐
           ▼                  ▼                  ▼
  ┌─────────────┐   ┌─────────────┐   ┌─────────────┐
  │  LocalTM #1 │   │  LocalTM #2 │   │  LocalTM #N │
  │ (Nó A)      │   │ (Nó B)      │   │ (Nó C)      │
  │ - Eventos   │   │ - Eventos   │   │ - Eventos   │
  │ - Despacho  │   │ - Despacho  │   │ - Despacho  │
  └──────┬──────┘   └──────┬──────┘   └──────┬──────┘
         │                 │                 │
         ▼                 ▼                 ▼
    ┌──────────┐     ┌──────────┐     ┌──────────┐
    │ Atores A │     │ Atores B │     │ Atores C │
    └──────────┘     └──────────┘     └──────────┘
```

### Protocolo de Sincronização

1. GTM envia `UpdateGlobalTimeEvent(tick)` via **Broadcast** a todos os TMs locais
2. Cada TM local processa tick `T`:
   - Despacha `SpontaneousEvent` para atores agendados
   - Espera todos `FinishEvent` retornarem
3. TM local reporta ao GTM: `LocalTimeReportEvent(nextTick, hasScheduled)`
4. GTM espera **todos** TMs reportarem (barreira)
5. GTM calcula `nextTick = min(reportedTicks)` entre TMs com `hasScheduled=true`
6. Se nenhum TM tem `hasScheduled`: simulação termina
7. GTM transmite próximo `UpdateGlobalTimeEvent(nextTick)`

### Tratamento de Falhas

| Cenário | Solução |
|---------|---------|
| TM local morre (nó crash) | GTM monitora via `context.watch()`, remove TM morto da barreira |
| Ator não responde FinishEvent | Watchdog (60s check, 300s force-clear) no LocalTM |
| ScheduleEvent para tick passado | LocalTM bumpa para `localTickOffset + 1` |
| Cluster quorum insuficiente | SM retry em 5s até atingir `min-nr-of-members` |

---

## 9. GlobalTimeManager — Coordenador Global de Tempo

### Localização

`core.actor.manager.GlobalTimeManager`

### Deploy

**ClusterSingleton** — instância única no cluster.

### Responsabilidades

- Criar pool de TMs locais (`ClusterRouterPool`)
- Sincronizar tempo global entre TMs via barreira
- Coordenar com `ProgressiveLoadDataManager` para carga incremental
- Métricas de progresso da simulação
- Terminação da simulação

### Estado

| Campo | Tipo | Descrição |
|-------|------|-----------|
| `localTimeManagers` | `Map[ActorRef, LocalTimeManagerTickInfo]` | Mapa TM→info de tick |
| `localTickOffset` | `Tick` | Tick global atual |
| `initialTick` | `Tick` | Tick inicial da simulação |
| `simulationDuration` | `Tick` | Duração configurada |
| `extendSimulationIfPendingEventsAfterEnd` | `Boolean` | Estender além da duração se houver eventos |
| `progressiveLoadManager` | `ActorRef` | Ref ao PLM |
| `progressiveLoadedUpToTick` | `Tick` | Último tick carregado progressivamente |
| `waitingForProgressiveLoad` | `Boolean` | Se GTM está bloqueado esperando PLM |
| `lastWindowTickRange` | `Tick` | Tamanho da última janela progressiva (prefetch adaptativo) |

### Eventos Recebidos

| Evento | Origem | Ação |
|--------|--------|------|
| `StartSimulationTimeEvent` | SM | Inicia simulação (ou aguarda janela progressiva inicial) |
| `ScheduleEvent` | Atores (via proxy) | Forward para pool de TMs locais |
| `LocalTimeReportEvent` | TM local | Acumula; quando todos reportam, calcula próximo tick |
| `TimeManagerRegisterEvent` | TM local | Registra TM local + `context.watch()` |
| `RegisterProgressiveLoadManagerEvent` | SM | Habilita coordenação com PLM |
| `TickWindowReady` | PLM | Libera simulação para avançar até tick carregado |
| `Terminated(ref)` | Pekko | Remove TM morto da barreira |

### Criação do Pool de TMs Locais

```scala
ClusterRouterPool(
  RoundRobinPool(0),
  ClusterRouterPoolSettings(
    totalInstances = config("htc.time-manager.total-instances"),  // default: 50
    maxInstancesPerNode = config("htc.time-manager.max-instances-per-node"),  // default: 1
    allowLocalRoutees = true
  )
).props(LocalDiscreteEventTimeManager.props(...))
```

**Decisão**: `maxInstancesPerNode = 1` garante que TMs são distribuídos entre pods, evitando hotspot de CPU no nó do singleton.

### Prefetch Progressivo Adaptativo

```
remainingBuffer = progressiveLoadedUpToTick - nextTick
prefetchThreshold = max(100, lastWindowTickRange × 0.4)

Se remainingBuffer < prefetchThreshold:
  → Dispara requestProgressiveLoad(nextTick) proativamente
```

### Terminação

A simulação termina quando:
1. `tickOffset >= simulationDuration` (se `extendSimulationIfPendingEventsAfterEnd = false`)
2. Nenhum TM local reporta `hasScheduled = true` (sem mais eventos)

---

## 10. LocalTimeManagerBase — Base dos TMs Locais

### Localização

`core.actor.manager.LocalTimeManagerBase`

### Mixin

`with MicroAwareTimeManager`

### Responsabilidades

- Registro de atores (`RegisterActorEvent`)
- Agendamento de eventos (`ScheduleEvent`)
- Despacho de `SpontaneousEvent` para atores
- Rastreamento de `runningEvents` (eventos em voo)
- Processamento de `FinishEvent`
- Watchdog contra eventos presos
- Reportar progresso ao GTM

### Estado

| Campo | Tipo | Descrição |
|-------|------|-----------|
| `registeredActors` | `Set[String]` | IDs de atores registrados |
| `scheduledActors` | `Map[Tick, Set[Identify]]` | Mapa tick → atores agendados |
| `runningEvents` | `Set[Identify]` | Eventos espontâneos em voo |
| `scheduledTicksOnFinish` | `Set[Tick]` | Ticks agendados via FinishEvent |
| `registeredIdentities` | `Map[String, Identify]` | Identidades para Prometheus |

### Protocolo RegisterActor → Schedule → Spontaneous → Finish

```
1. RegisterActorEvent(startTick, actorId, identify)
   └─ registeredActors.add(actorId)
   └─ scheduleEvent(ScheduleEvent(startTick, ...))

2. ScheduleEvent(tick, identify)
   └─ Se tick <= localTickOffset: bumpa para localTickOffset + 1  ⚠️ CRÍTICO
   └─ scheduledActors(effectiveTick).add(identify)

3. processTick(tick)  [implementação varia por subclasse]
   └─ Para cada identify em scheduledActors(tick):
       └─ sendSpontaneousEvent(tick, identify)
       └─ runningEvents.add(identify)

4. sendSpontaneousEvent(tick, identity)
   └─ Se LoadBalancedDistributed: envia via ShardRegion (EntityEnvelopeEvent)
   └─ Se PoolDistributed: envia via ActorSelection (diretamente)

5. FinishEvent(end, identify, scheduleTick, destruct)
   └─ runningEvents.remove(identify)
   └─ Se scheduleTick = None: remove identify de TODOS os ticks futuros
   └─ Se destruct = true: envia DestructEvent ao ator
   └─ Se runningEvents.isEmpty: advanceToNextTick()

6. advanceToNextTick()
   └─ Se runningEvents.isEmpty:
       └─ reportGlobalTimeManager(hasScheduled = nextTick.isDefined)
```

### Tratamento de Tick Passado (scheduleEvent)

```scala
val effectiveTick = if (event.tick <= localTickOffset) {
  localTickOffset + 1   // ⚠️ BUMPA: evita orphaned events
} else {
  event.tick
}
```

**Por que**: Quando um `ScheduleEvent` chega via pool router a um TM que já avançou além do tick solicitado, esse tick nunca será reprocessado. O bump garante processamento futuro.

### Watchdog de Eventos Presos

```
WATCHDOG_INTERVAL = 60s   — verifica a cada 60s
STALE_WARNING = 120s      — aviso se presos por 120s
FORCE_CLEAR = 300s        — limpa forçadamente após 300s + 2 verificações consecutivas

Se elapsed >= FORCE_CLEAR && consecutiveStaleChecks >= 2:
  → runningEvents.clear()
  → advanceToNextTick() forçado
```

---

## 11. LocalDiscreteEventTimeManager — TM de Eventos Discretos

### Localização

`core.actor.manager.LocalDiscreteEventTimeManager`

### Estratégia de Avanço

**Evento discreto**: só avança quando todos os eventos do tick atual terminam.

### Batch Processing

Para ticks com muitos atores (>5000), usa batching:

```
TICK_BATCH_SIZE = 5000

processTick(tick):
  Se actorsSet.size <= 5000:
    → Dispara todos de uma vez (fast path)
  
  Se actorsSet.size > 5000:
    → Enfileira todos em pendingTickActors
    → fireNextBatch(): dispara TICK_BATCH_SIZE por vez
    → Quando runningEvents esvazia: fireNextBatch() novamente
    → Quando pendingTickActors esvazia: advanceToNextTick()
```

### advanceToNextTick (Override)

```scala
override def advanceToNextTick(): Unit =
  if (runningEvents.isEmpty && pendingTickActors.nonEmpty)
    fireNextBatch()       // Ainda tem batches pendentes
  else
    super.advanceToNextTick()  // Report para GTM
```

---

## 12. LocalTimeSteppedTimeManager — TM Time-Stepped

### Localização

`core.actor.manager.LocalTimeSteppedTimeManager`

### Estratégia de Avanço

**Time-stepped**: avança tempo em incrementos fixos (`timeStep`, default: 1 tick), independente de eventos.

### Diferença do Discrete-Event

| Aspecto | Discrete-Event | Time-Stepped |
|---------|---------------|--------------|
| Avanço de tempo | Salta para próximo tick com eventos | Incrementa fixo (`timeStep`) |
| Atores disparados | Apenas agendados no tick | Agendados no tick (extensível para todos) |
| Casos de uso | Simulação padrão (meso) | Contínua, micro-simulação |

---

## 13. MicroAwareTimeManager — Suporte Microscópico

### Localização

`core.actor.manager.MicroAwareTimeManager` (trait)

### Responsabilidades

- Manter registro de links micro-habilitados
- Disparar `GlobalTickEvent` em links MICRO a cada tick global
- Links micro executam sub-ticks localmente via `LinkMicroTimeManager`

### API

| Método | Descrição |
|--------|-----------|
| `registerMicroLink(linkRef)` | Registra link MICRO |
| `unregisterMicroLink(linkRef)` | Remove link MICRO |
| `triggerMicroLinks(tick)` | Envia `GlobalTickEvent(tick)` a todos os links MICRO |
| `hasMicroLinks` | Retorna true se há links MICRO registrados |
| `getMicroLinkCount` | Contagem de links MICRO |

### Integração no LocalTimeManagerBase

```scala
private def syncWithGlobalTime(globalTick: Tick): Unit = {
  localTickOffset = globalTick
  if (isRunning && !isTerminated) {
    triggerMicroLinks(globalTick)  // ← MICRO primeiro
    processTick(localTickOffset)   // ← depois os regulares
  }
}
```

---

## 14. Subsistema de Carga (Load Data)

### Estratégias de Carga

| Estratégia | Quando | Classe |
|------------|--------|--------|
| **EAGER** | Antes da simulação iniciar | `LoadDataManager` + `JsonLoadData` |
| **PROGRESSIVE** | Durante a simulação, por janelas de tick | `ProgressiveLoadDataManager` + `ProgressiveJsonLoadData` |

### Tipos de Criação de Atores

| `CreationTypeEnum` | Distribuição | Creator | Resolução |
|--------------------|-------------|---------|-----------|
| `LoadBalancedDistributed` | Cluster Sharding | `CreatorLoadData` | `ShardRegion` (balanceado pelo Pekko) |
| `PoolDistributed` | Local Pool | `CreatorPoolLoadData` | `ActorSelection` (local) |

---

## 15. LoadDataManager — Gerenciador de Carga Eager

### Localização

`core.actor.manager.LoadDataManager`

### Deploy

**ClusterSingleton**

### Fluxo de Carga

```
SM → LoadDataEvent(actorsDataSources)
  │
  ├─ Separa fontes: EAGER vs PROGRESSIVE
  ├─ Cria pools de Creators (shard + pool)
  │
  ├─ Para cada fonte EAGER (por tipo, sequencial):
  │    └─ Cria JsonLoadData (io-dispatcher)
  │    └─ Envia LoadDataSourceEvent
  │         │
  │         ├─ JsonLoadData abre arquivo JSON
  │         ├─ Lê chunks de 100 atores
  │         ├─ Envia CreateActorsEvent ao Creator
  │         ├─ Espera FinishCreationEvent (back-pressure)
  │         ├─ Repete até EOF
  │         └─ Envia FinishLoadDataEvent → LDM
  │
  ├─ Quando todas as fontes EAGER completam:
  │    └─ SM ! FinishLoadDataEvent(progressiveSources)
  │
  └─ LDM é destruído (context.stop)
```

### Sequenciamento por Tipo

O LDM processa fontes **uma por tipo** por vez (via `sourcesInCreation`), mas tipos diferentes podem ser processados em paralelo.

```scala
sourcesToCreate = eagerSources.groupBy(_.classType)
// Para cada tipo, enfileira fontes
// sourcesInCreation garante que apenas 1 fonte por tipo está sendo carregada
```

---

## 16. ProgressiveLoadDataManager — Carga Progressiva

### Localização

`core.actor.manager.ProgressiveLoadDataManager`

### Deploy

**ClusterSingleton**

### Visão Geral

O PLM coordena a criação de atores **durante** a simulação, em janelas de tick. Isso permite simulações com milhões de atores sem carregar todos na memória antes do início.

### Arquitetura de Duas Fases

#### Fase 1: Indexação Leve (Light Index)

```
Para cada fonte PROGRESSIVE:
  ├─ Cria ProgressiveJsonLoadData (distribuído via RemoteScope no cluster)
  ├─ Envia LoadDataSourceEvent → buildTickIndexAsync()
  │    └─ Faz streaming do JSON, extrai apenas startTick de cada ator
  │    └─ Constrói Map[Tick, Int] (contagem por tick — sem reter objetos)
  │    └─ Retorna TickIndexBuiltEvent(tickCounts, totalActors, maxTick)
  │
  └─ PLM agrega contagens de todas as fontes → aggregatedTickCounts
```

**Batching de indexação**: Limita a `INDEX_BUILD_BATCH_SIZE = 30` fontes simultâneas para controlar I/O.

#### Fase 2: Streaming por Janela (Tick Window)

```
GTM → TickWindowRequest(currentTick, horizonTick)
  │
  ├─ PLM calcula horizonte adaptativo baseado em densidade
  │    └─ TARGET_ACTORS_PER_WINDOW = 50,000
  │    └─ Acumula atores tick-a-tick até atingir target
  │    └─ MIN_LOOK_AHEAD_TICKS = 100 (mínimo)
  │
  ├─ Para cada loader relevante (maxTick >= fromTick):
  │    └─ LoadActorsForTickRange(fromTick, toTick)
  │         ├─ Abre arquivo, faz streaming com chunks de 500
  │         ├─ Filtra atores no range de ticks
  │         ├─ Envia CreateActorsEvent ao Creator (back-pressure)
  │         └─ Retorna TickRangeLoadedEvent(sourceId, actorsLoaded)
  │
  ├─ Quando todos os loaders reportam:
  │    └─ GTM ! TickWindowReady(readyUpToTick, actorsCreated)
  │
  └─ Sliding window: max LOAD_BATCH_SIZE = 10 loaders simultâneos
```

### Janela Adaptativa

```scala
calculateAdaptiveHorizon(fromTick, maxHorizon):
  var actorCount = 0
  Para cada tick em [fromTick, maxHorizon]:
    Se actorCount + tickCount > 50,000 E tick > fromTick + 100:
      → Retorna tick anterior  // janela curta (denso)
    actorCount += tickCount
  → Retorna maxHorizon  // janela longa (esparso)
```

### Gate de Concorrência

O PLM usa `loadInFlight: Boolean` para garantir que apenas **uma** requisição de janela é processada por vez. Requisições que chegam durante carga são enfileiradas (apenas a última é mantida).

---

## 17. Creators — Criadores de Atores

### CreatorLoadData (Shard-Distributed)

| Aspecto | Detalhe |
|---------|---------|
| **Localização** | `core.actor.manager.load.CreatorLoadData` |
| **Distribuição** | ClusterRouterPool |
| **Tipo de ator criado** | `LoadBalancedDistributed` (via Cluster Sharding) |
| **Chunk size** | 1,000 atores por chunk |
| **Delay entre chunks** | 100ms |

#### Fluxo de Criação (Shard)

```
CreateActorsEvent(batchId, actors)
  │
  ├─ Para cada ator no chunk:
  │    ├─ Prepara Initialization (id, data, TMs, reporters, dependencies)
  │    ├─ createShardRegion() — registra shard se não existir
  │    └─ shardRegion ! ShardRegion.StartEntity(entityId)
  │
  ├─ Shard responde: StartEntityAck(entityId)
  │    └─ handleInitialize(): envia InitializeEvent ao ator via shard
  │
  ├─ Ator responde: InitializeEntityAckEvent(entityId)
  │    └─ Remove da lista de pendentes
  │
  └─ Quando todos confirmados:
       └─ Loader ! FinishCreationEvent(batchId, amount)
```

### CreatorPoolLoadData (Pool-Distributed)

| Aspecto | Detalhe |
|---------|---------|
| **Localização** | `core.actor.manager.load.CreatorPoolLoadData` |
| **Distribuição** | ClusterRouterPool |
| **Tipo de ator criado** | `PoolDistributed` (via local actor pool) |
| **Chunk size** | 50 atores por chunk |
| **Delay entre chunks** | 500ms |

#### Fluxo de Criação (Pool)

```
CreateActorsEvent(batchId, actors)
  │
  ├─ Para cada ator no chunk:
  │    ├─ createPoolActor() — cria ator diretamente via Props
  │    └─ Estado injetado via Properties.data (não precisa de InitializeEvent)
  │
  ├─ Ator responde: StartEntityAckEvent(entityId)
  │    └─ Remove da lista de pendentes
  │
  └─ Quando todos confirmados:
       └─ Loader ! FinishCreationEvent(batchId, amount)
```

### Retry (Watchdog do Creator)

O `CreatorLoadData` executa um `RetryPendingAcks` a cada 5s, reenviando `ShardRegion.StartEntity` para atores que não responderam `StartEntityAck`.

---

## 18. Estratégias de Carga (Load Strategies)

### Hierarquia

```
LoadDataStrategy (abstract)
  ├── JsonLoadData           — carga eager (streaming JSON)
  └── ProgressiveJsonLoadData — carga progressiva (tick-indexed)
```

### JsonLoadData (Eager)

| Aspecto | Detalhe |
|---------|---------|
| **I/O** | BufferedInputStream + Jackson streaming parser |
| **Dispatcher** | `pekko.actor.io-dispatcher` (virtual threads) |
| **Chunk size** | 100 atores |
| **Back-pressure** | Espera `FinishCreationEvent` antes de ler próximo chunk |
| **Separação** | Atores `PoolDistributed` → `CreatorPoolLoadData`, restante → `CreatorLoadData` |

### ProgressiveJsonLoadData (Progressive)

| Aspecto | Detalhe |
|---------|---------|
| **Fase 1** | `buildLightIndex()` — streaming scan, Map[Tick, Int] apenas |
| **Fase 2** | `readMatchingChunk()` — reopen file, filter by tick range |
| **Chunk size** | 500 atores |
| **Back-pressure** | Espera `FinishCreationEvent` antes de ler próximo chunk |
| **Memória** | Máximo ~500 ActorSimulation em memória por loader |
| **Distribuição** | Loaders distribuídos via `RemoteScope` round-robin nos nós do cluster |

---

## 19. Subsistema de Relatórios (Reporting)

### Arquitetura

```
SimulationBaseActor.report(data, label)
  │
  ├─ Cria ReportEvent(entityId, tick, lamportTick, data, label)
  ├─ Seleciona reporter por state.getReporterType ou default-strategy
  └─ reporters(reportType) ! event
       │
       ▼
  ┌─────────────────────┐
  │  ReportData Pool    │  (ClusterRouterPool, round-robin)
  │  ┌────────────────┐ │
  │  │ JsonReportData │ │  — JSONL files
  │  │ CsvReportData  │ │  — CSV files
  │  └────────────────┘ │
  └─────────────────────┘
```

### ReportManager

| Aspecto | Detalhe |
|---------|---------|
| **Deploy** | ClusterSingleton |
| **Startup** | Lê `htc.report-manager.enabled-strategies`, cria pools |
| **Registro** | Envia `RegisterReportersEvent(reporters)` ao SM |
| **Shutdown** | Envia `DestructEvent` a cada reporter → flush buffers |

### ReportData (Base Abstrata)

```scala
abstract class ReportData extends BaseActor[DefaultState] {
  override def handleEvent: Receive = {
    case event: ReportEvent => onReport(event)  // subclasses implementam
  }
}
```

### JsonReportData

| Aspecto | Detalhe |
|---------|---------|
| **Formato** | JSONL (um JSON por linha) |
| **Buffer** | 100 eventos (configurável: `htc.report-manager.json.batch-size`) |
| **Diretório** | `{baseDirectory}/{simulationId}/` |
| **Arquivo** | `{prefix}{timestamp}_{uuid}_events.jsonl` |
| **Pool** | Até 256 instâncias (configurável) |
| **Flush** | Quando buffer atinge batch-size OU `postStop()` |

### CsvReportData

| Aspecto | Detalhe |
|---------|---------|
| **Formato** | CSV com header |
| **Colunas** | `entity_id,tick,real_time,lamport_tick,event_type,simulation_id,data` |
| **Buffer** | 1000 eventos (configurável) |
| **Pool** | 2 instâncias (configurável) |
| **Flush** | Quando buffer atinge batch-size OU `postStop()` |

### Determinação do simulationId

Prioridade:
1. `simulation.id` do JSON de configuração
2. `HTC_SIMULATION_ID` (variável de ambiente)
3. `htc.simulation.id` (application.conf)
4. `RandomSeedManager.deterministicSimulationId(name)` (fallback)

---

## 20. RandomSeedManager — Reprodutibilidade

### Localização

`core.actor.manager.RandomSeedManager` (object singleton)

### Responsabilidades

- Garantir reprodutibilidade determinística da simulação
- Fornecer geradores de random com seed controlado
- Gerar UUIDs determinísticos

### API

| Método | Descrição |
|--------|-----------|
| `initialize(simulation)` | Configura seed a partir de `simulation.randomSeed` |
| `getJavaRandom()` | Instância `java.util.Random` com seed controlado |
| `getScalaRandom()` | Instância `scala.util.Random` com seed controlado |
| `deterministicUUID()` | UUID baseado em seed + contador autoincremental |
| `deterministicSimulationId(name)` | ID de simulação baseado em seed |
| `reset()` | Reseta estado (útil para testes) |

### Inicialização

```scala
val seed = simulation.randomSeed.getOrElse(System.currentTimeMillis())
ScalaRandom.setSeed(seed)            // Global
javaRandom = Some(new JavaRandom(seed))
scalaRandom = Some(new ScalaRandom(new JavaRandom(seed)))
```

### UUID Determinístico

```scala
def deterministicUUID(): String = {
  uuidCounter += 1
  val uuidValue = seedPart + uuidCounter
  f"htc-$uuidValue%016x-$uuidCounter%08x"
}
```

Usado em `BaseActor` quando `properties.entityId` é null — garante que mesmo sem ID explícito, a criação é determinística.

---

## 21. LamportClock — Ordenação Causal

### Localização

`core.entity.control.LamportClock`

### Implementação

```scala
class LamportClock {
  private var clock: Tick = 0L

  def increment(): Unit = synchronized { clock += 1 }
  def getClock: Long = synchronized { clock }
  def update(otherClock: Long): Unit = synchronized {
    clock = math.max(clock, otherClock) + 1
  }
}
```

### Uso no SimulationBaseActor

- **Envio** (`sendMessageTo`): `lamportClock.increment()`
- **Recepção** (`handleInteractWith`): `lamportClock.update(event.lamportTick)`
- **Reporting**: `report.lamportTick = getLamportClock`

### Propósito

Garante ordenação parcial de eventos causalmente relacionados em um sistema distribuído sem relógio global.

---

## 22. MetricsServer — Observabilidade (Prometheus)

### Localização

`core.metrics.MetricsServer` (object singleton)

### Endpoint

HTTP server Prometheus em porta configurável (default: 9001), endpoint `/metrics`.

### Métricas Registradas

#### Progresso da Simulação

| Métrica | Tipo | Descrição |
|---------|------|-----------|
| `htc_simulation_ticks_total` | Counter | Total de ticks globais processados |
| `htc_simulation_current_tick` | Gauge | Tick global atual |
| `htc_simulation_progress_ratio` | Gauge | Progresso [0, 1] |
| `htc_tick_duration_seconds` | Histogram | Duração de cada ciclo de tick (wall-clock) |

#### Atores

| Métrica | Tipo | Labels | Descrição |
|---------|------|--------|-----------|
| `htc_actors_registered_total` | Counter | `actor_type` | Atores registrados nos TMs |
| `htc_actors_active` | Gauge | `actor_type` | Atores ativos por tipo |
| `htc_events_processed_total` | Counter | `event_type` | Eventos processados |

#### Time Manager

| Métrica | Tipo | Descrição |
|---------|------|-----------|
| `htc_tm_scheduled_actors` | Gauge | Atores agendados no tick atual |
| `htc_tm_running_events` | Gauge | Eventos em voo no TM local |
| `htc_tm_waiting_for_progressive` | Gauge | 1 se GTM bloqueado aguardando PLM |

#### Carga Progressiva

| Métrica | Tipo | Descrição |
|---------|------|-----------|
| `htc_progressive_actors_created_total` | Counter | Atores criados via progressive loading |
| `htc_progressive_loaded_up_to_tick` | Gauge | Último tick completamente carregado |
| `htc_progressive_windows_loaded_total` | Counter | Janelas de tick completadas |

#### Journeys

| Métrica | Tipo | Labels | Descrição |
|---------|------|--------|-----------|
| `htc_journeys_started_total` | Counter | `vehicle_type` | Viagens iniciadas |
| `htc_journeys_completed_total` | Counter | `vehicle_type` | Viagens completadas |

---

## 23. Properties — Configuração de Atores

### Properties (Atores de Simulação)

```scala
case class Properties(
  entityId: String,
  resourceId: String,
  timeManagers: Map[String, ActorRef],           // tipo → TM ref
  creatorManager: ActorRef,
  reporters: Map[ReportTypeEnum, ActorRef],
  data: Any,                                      // estado inicial serializado
  dependencies: Map[String, Dependency],
  actorType: CreationTypeEnum = LoadBalancedDistributed,
  defaultTimeManagerType: String = "discrete-event"
)
```

### CreatorProperties (Creators)

```scala
case class CreatorProperties(
  entityId: String,
  shardId: String,
  loadDataManager: ActorRef,
  timeManagers: Map[String, ActorRef],
  creatorManager: ActorRef,
  reporters: Map[ReportTypeEnum, ActorRef],
  data: Any,
  actorType: CreationTypeEnum = Simple
)
```

### Initialization (Dados para InitializeEvent)

```scala
case class Initialization(
  id: String,
  resourceId: String,
  classType: String,
  data: Any,
  timeManagers: Map[String, ActorRef],
  creatorManager: ActorRef,
  reporters: Map[ReportTypeEnum, ActorRef],
  dependencies: Map[String, Dependency]
)
```

---

## 24. Fluxo de Inicialização Completo

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          FLUXO DE INICIALIZAÇÃO                             │
│                                                                             │
│  1. JVM Start                                                               │
│     └─ Pekko ActorSystem inicia                                            │
│     └─ SimulationManager criado como ClusterSingleton                       │
│                                                                             │
│  2. SM.onStart()                                                            │
│     └─ self ! PrepareSimulationEvent                                        │
│                                                                             │
│  3. SM.prepareSimulation()                                                  │
│     ├─ Carrega Simulation config (io-dispatcher, async)                     │
│     ├─ Verifica cluster quorum (retry 5s se insuficiente)                   │
│     ├─ Inicializa RandomSeedManager(config.randomSeed)                      │
│     ├─ Cria GlobalTimeManager (singleton)                                   │
│     └─ Cria ReportManager (singleton)                                       │
│                                                                             │
│  4. GTM.onStart()                                                           │
│     └─ Cria ClusterRouterPool de LocalDiscreteEventTM                       │
│     └─ Cada LocalTM se registra via TimeManagerRegisterEvent → GTM          │
│     └─ SM ! TimeManagerRegisterEvent(poolRef)                               │
│                                                                             │
│  5. RM.onStart()                                                            │
│     ├─ Lê enabled-strategies (csv, json)                                    │
│     ├─ Cria ClusterRouterPool de ReportData por tipo                        │
│     └─ SM ! RegisterReportersEvent(reporters)                               │
│                                                                             │
│  6. SM recebe ambos                                                         │
│     └─ startLoadData()                                                      │
│     └─ Cria LoadDataManager (singleton)                                     │
│     └─ LDM ! LoadDataEvent(actorsDataSources)                              │
│                                                                             │
│  7. LDM.loadData()                                                          │
│     ├─ Separa EAGER vs PROGRESSIVE                                          │
│     ├─ Cria Creator pools (shard + pool)                                    │
│     ├─ Para cada fonte EAGER:                                               │
│     │    ├─ Cria JsonLoadData actor (io-dispatcher)                         │
│     │    ├─ Streaming JSON → CreateActorsEvent → Creator                    │
│     │    ├─ Creator → ShardRegion.StartEntity → InitializeEvent             │
│     │    └─ Ator registrado no TM local                                     │
│     │                                                                       │
│     └─ Quando todas EAGER completam:                                        │
│          └─ SM ! FinishLoadDataEvent(progressiveSources)                    │
│                                                                             │
│  8. SM.startSimulation()                                                    │
│     ├─ Se PROGRESSIVE sources existem:                                      │
│     │    ├─ Cria ProgressiveLoadDataManager (singleton)                     │
│     │    ├─ GTM ! RegisterProgressiveLoadManagerEvent                       │
│     │    └─ PLM ! StartProgressiveLoadingEvent                              │
│     │         └─ PLM constrói light indexes (async)                         │
│     │                                                                       │
│     └─ GTM ! StartSimulationTimeEvent(startTick)                            │
│          ├─ Se progressive: espera janela inicial → notifyLocalManagers      │
│          └─ Se não: notifyLocalManagers imediatamente                        │
│                                                                             │
│  9. LocalTMs recebem StartSimulationTimeEvent                               │
│     └─ self ! UpdateGlobalTimeEvent(initialTick)                            │
│     └─ processTick(initialTick) — simulação começa!                         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 25. Fluxo de Execução da Simulação

### Ciclo Principal (por tick global)

```
┌──────────────────────────────────────────────────────────────────────┐
│  CICLO DE UM TICK GLOBAL                                             │
│                                                                      │
│  1. GTM → Broadcast: UpdateGlobalTimeEvent(tick=T)                   │
│     └─ Todos LocalTMs recebem, setam localTickOffset = T            │
│                                                                      │
│  2. Cada LocalTM:                                                    │
│     ├─ triggerMicroLinks(T)  — Links MICRO executam sub-ticks        │
│     ├─ processTick(T):                                               │
│     │    ├─ scheduledActors.get(T) → Set[Identify]                  │
│     │    ├─ Para cada ator agendado:                                 │
│     │    │    ├─ sendSpontaneousEvent(T, identity) → ator            │
│     │    │    └─ runningEvents.add(identity)                         │
│     │    └─ Espera todos FinishEvent                                 │
│     │                                                                │
│     └─ advanceToNextTick():                                          │
│          ├─ Calcula nextTick local                                   │
│          └─ reportGlobalTimeManager(nextTick, hasScheduled)          │
│                                                                      │
│  3. GTM recebe LocalTimeReportEvent de cada TM:                      │
│     ├─ Atualiza localTimeManagers[TM] = {tick, hasScheduled}        │
│     ├─ Se TODOS reportaram:                                         │
│     │    ├─ nextTick = min(reported ticks where hasScheduled=true)   │
│     │    ├─ Se nenhum has scheduled: terminateSimulation()           │
│     │    ├─ Se nextTick > progressiveLoadedUpToTick:                │
│     │    │    waitForProgressiveLoad()                               │
│     │    ├─ Se remainingBuffer < prefetchThreshold:                  │
│     │    │    requestProgressiveLoad() (proativo)                    │
│     │    ├─ Atualiza métricas Prometheus                             │
│     │    └─ Broadcast: UpdateGlobalTimeEvent(tick=nextTick)          │
│     └─ Repete                                                        │
│                                                                      │
│  4. Ator durante SpontaneousEvent:                                   │
│     ├─ actSpontaneous(event):                                        │
│     │    ├─ Lógica de negócio (ex: Car calcula travessia)           │
│     │    ├─ sendMessageTo() → outro ator (ActorInteractionEvent)    │
│     │    ├─ report(data, label)                                      │
│     │    └─ onFinishSpontaneous(Some(nextTick))                     │
│     │         ├─ TM ! FinishEvent (libera running)                  │
│     │         └─ TM ! ScheduleEvent(nextTick) (reagenda)            │
│     │                                                                │
│     └─ actInteractWith(event):                                       │
│          ├─ Recebe mensagem de outro ator                            │
│          ├─ Atualiza Lamport clock                                   │
│          └─ Processa (ex: Link processa EnterLinkData do Car)       │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

### Fluxo de Interação Ator-TM (Detalhe)

```
Car (ator)                          LocalTM
    │                                  │
    │◀──── SpontaneousEvent(tick=100) ─┤
    │                                  │  runningEvents += Car
    │  actSpontaneous():               │
    │    calcular travessia...         │
    │    sendMessageTo(link, ...)      │
    │                                  │
    │──── FinishEvent(tick=100) ──────▶│
    │     scheduleTick=Some(150)       │  runningEvents -= Car
    │                                  │  scheduledActors(150) += Car
    │──── ScheduleEvent(tick=150) ────▶│
    │                                  │
    │                                  │  (quando runningEvents vazia)
    │                                  │  reportGlobalTimeManager(150, true)
```

---

## 26. Fluxo de Carga Progressiva

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CARGA PROGRESSIVA — FLUXO COMPLETO                                      │
│                                                                          │
│  ═══ FASE 1: INDEXAÇÃO ═══                                               │
│                                                                          │
│  PLM cria N ProgressiveJsonLoadData (distribuídos no cluster)            │
│  Para cada batch de 30 fontes:                                           │
│    LoadDataSourceEvent → Loader                                          │
│    Loader.buildTickIndexAsync():                                         │
│      ├─ Streaming JSON → extrai apenas startTick                        │
│      ├─ Constrói Map[Tick, Int] (contagem)                              │
│      └─ TickIndexBuiltEvent → PLM                                       │
│                                                                          │
│  PLM agrega em aggregatedTickCounts (TreeMap[Tick, Int])                 │
│  Quando todos indexados: fullyIndexed = true                             │
│                                                                          │
│  ═══ FASE 2: JANELAS ADAPTATIVAS ═══                                     │
│                                                                          │
│  GTM → TickWindowRequest(currentTick=1000, horizonTick=11000)            │
│                                                                          │
│  PLM.calculateAdaptiveHorizon(1001, 11000):                              │
│    ├─ Percorre aggregatedTickCounts de 1001 a 11000                     │
│    ├─ Acumula contagens até atingir 50,000 (TARGET)                     │
│    ├─ Se atinge em tick 3500: retorna 3500                              │
│    └─ Se não atinge: retorna 11000 (todo o range)                       │
│                                                                          │
│  PLM despacha para loaders relevantes (max 10 simultâneos):              │
│    LoadActorsForTickRange(1001, 3500) → Loader                           │
│    Loader:                                                               │
│      ├─ Abre arquivo, streaming filter [1001, 3500]                     │
│      ├─ Chunks de 500 → CreateActorsEvent → Creator                    │
│      ├─ Espera FinishCreationEvent (back-pressure)                      │
│      └─ TickRangeLoadedEvent(actorsLoaded=12500) → PLM                  │
│                                                                          │
│  PLM quando todos loaders completam:                                     │
│    loadedUpToTick = 3500                                                 │
│    GTM ! TickWindowReady(3500, totalActors)                              │
│                                                                          │
│  ═══ PREFETCH PROATIVO ═══                                               │
│                                                                          │
│  GTM detecta: remainingBuffer = 3500 - 2500 = 1000                      │
│  prefetchThreshold = max(100, 2500 × 0.4) = 1000                        │
│  → Dispara requestProgressiveLoad(2500) antecipadamente                  │
│                                                                          │
│  ═══ CONCLUSÃO ═══                                                       │
│                                                                          │
│  Quando loadedUpToTick >= max(sourceMaxTicks):                           │
│    allSourcesFullyLoaded = true                                          │
│    SM ! ProgressiveLoadingCompleteEvent(totalActorsCreated)              │
│    GTM.progressiveLoadingComplete = true                                 │
│    → Sem mais verificações de janela                                     │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 27. Decisões Arquiteturais

### DA-1: Cluster Sharding para Atores de Simulação

**Decisão**: Usar Pekko Cluster Sharding (`LoadBalancedDistributed`) como mecanismo padrão de distribuição de atores.

**Justificativa**:
- Balanceamento automático de atores entre nós
- Resolução transparente de mensagens entre nós
- Suporte a passivação (atores inativos liberados da memória)

**Trade-off**: Overhead de coordenação do ShardCoordinator. Para atores leves (BusStop), `PoolDistributed` pode ser mais eficiente.

### DA-2: Persistência In-Memory (Journal Inmem)

**Decisão**: Usar `pekko.persistence.journal.inmem` em vez de Cassandra/JDBC.

**Justificativa**:
- Performance superior (sem I/O de disco no hot path)
- Simulações são "fire-and-forget" — não precisa de durabilidade

**Trade-off**: Se um nó morre, os shards nesse nó **perdem estado permanentemente**. Mitigado por:
- `rebalance-threshold = 100000` (praticamente nunca rebalanceia)
- SBR conservador (`stable-after = 60s`)
- Heartbeat generoso (`acceptable-heartbeat-pause = 120s`)

### DA-3: Barreira Global para Sincronização de Tempo

**Decisão**: GTM espera **todos** TMs locais reportarem antes de avançar tick.

**Justificativa**:
- Garante consistência: nenhum TM processa tick T+1 enquanto outro ainda está em T
- Evita violações de causalidade entre atores em nós diferentes

**Trade-off**: O TM mais lento limita todos. Mitigado por:
- Watchdog (300s force-clear)
- `context.watch()` para TMs terminados

### DA-4: Carga Progressiva com Janelas Adaptativas

**Decisão**: Usar janelas de tick com tamanho baseado na densidade de atores (50K alvo por janela).

**Justificativa**:
- Cenários densos (muitos atores por tick) → janelas menores → menos memória
- Cenários esparsos (poucos atores) → janelas maiores → menos overhead
- Prefetch proativo evita pausas na simulação

**Trade-off**: Re-leitura do arquivo JSON a cada janela. Mitigado por:
- Light index (Fase 1) evita re-leitura quando não há atores no range
- Sliding window do PLM limita I/O concorrente

### DA-5: Separação Eager vs Progressive

**Decisão**: Cada fonte de dados pode ser `EAGER` (carregada antes) ou `PROGRESSIVE` (durante simulação).

**Justificativa**:
- Infraestrutura (Links, Nodes) precisa existir antes da simulação → EAGER
- Veículos com startTick futuro podem ser criados sob demanda → PROGRESSIVE
- Permite simulações com milhões de atores sem memória inicial elevada

### DA-6: LamportClock para Ordenação

**Decisão**: Cada `SimulationBaseActor` mantém um `LamportClock` local.

**Justificativa**:
- Ordenação causal de eventos em sistema distribuído
- Permite reconstruir sequência de eventos para debugging/reporting
- Levemente sincronizado via clock update em `actInteractWith`

### DA-7: io-dispatcher para I/O

**Decisão**: Loaders de dados e operações de arquivo usam `pekko.actor.io-dispatcher`.

**Justificativa**:
- Evita bloquear dispatcher principal (que processa eventos de simulação)
- Usa virtual threads (Loom) quando disponível em JDK 21+

### DA-8: Buffer de Shard Region Amplo

**Decisão**: `shard.buffer-size = 100000` (vs default 1000).

**Justificativa**:
- O progressive loader pode enviar ~1500+ CreateActor messages por janela
- Buffer insuficiente causa silent drops → atores nunca inicializados → simulação trava

### DA-9: SBR Conservador

**Decisão**: Split-Brain Resolver com `stable-after = 60s`, heartbeat tolerante.

**Justificativa**:
- Com persistência in-memory, nó derrubado = perda permanente
- Melhor esperar mais do que derrubar nó ocupado (GC pause, CPU saturation)

### DA-10: Prometheus para Observabilidade

**Decisão**: Métricas Prometheus nativas (sem middleware de terceiros).

**Justificativa**:
- JVM metrics padrão (heap, GC, threads)
- Custom metrics para simulação (ticks, actors, progressive loading)
- Integração com Grafana para dashboards em tempo real

---

## 28. Configuração (application.conf)

### Time Manager

```hocon
htc.time-manager {
  total-instances = 50          # Total de LocalTMs no cluster
  max-instances-per-node = 1    # Máximo por nó (distribuição uniforme)
  batch-size = 100000
  actor-timeout-ms = 180000     # Timeout para watchdog
}
```

### Report Manager

```hocon
htc.report-manager {
  default-strategy = "json"
  enabled-strategies = ["json"]

  json {
    prefix = "htc_simulation_"
    directory = "/app/hyperbolic-time-chamber/output/reports/json"
    number-of-instances = 256
    number-of-instances-per-node = 256
    batch-size = 100
  }

  csv {
    prefix = "htc_simulation_"
    directory = "/app/hyperbolic-time-chamber/output/reports/csv"
    number-of-instances = 2
    number-of-instances-per-node = 1
    batch-size = 1000
  }
}
```

### Cluster

```hocon
pekko.cluster {
  min-nr-of-members = 1              # Mínimo de nós antes de iniciar
  
  split-brain-resolver {
    stable-after = 60s                # Conservador
    active-strategy = keep-majority
    down-all-when-unstable = off
  }
  
  failure-detector {
    acceptable-heartbeat-pause = 120s  # Tolerante a CPU spikes
    threshold = 30.0
    heartbeat-interval = 5s
  }
  
  sharding {
    buffer-size = 100000               # Amplo para progressive loading
    rebalance-threshold = 100000       # Quase nunca rebalanceia
    passivation.default-idle-strategy {
      idle-entity.timeout = 1200.hours # Atores nunca passivados
    }
  }
}
```

### Serialização

Todos os eventos core usam **Jackson CBOR** para serialização cross-nó:
- `SpontaneousEvent`, `FinishEvent`
- `EntityEnvelopeEvent`, `ActorInteractionEvent`
- `ReportEvent`, `RegisterReportersEvent`
- `CreateActorsEvent`, `FinishCreationEvent`
- Eventos de load (`TickIndexBuiltEvent`, `TickRangeLoadedEvent`, etc.)
- `Properties`, `CreatorProperties`, `BaseState`

---

## 29. Apêndice: Tabela de Eventos Core

### Eventos de Controle de Tempo

| Evento | Direção | Descrição |
|--------|---------|-----------|
| `StartSimulationTimeEvent` | SM → GTM → LocalTMs | Inicia a simulação |
| `UpdateGlobalTimeEvent(tick)` | GTM → LocalTMs (broadcast) | Sincroniza tick global |
| `LocalTimeReportEvent(tick, hasScheduled)` | LocalTM → GTM | Reporta progresso do tick |
| `SpontaneousEvent(tick, tmRef)` | LocalTM → Ator | Dispara ação no tick |
| `FinishEvent(end, identify, scheduleTick, destruct)` | Ator → LocalTM | Finaliza processamento do tick |
| `ScheduleEvent(tick, identify)` | Ator → LocalTM | Agenda próximo tick |
| `RegisterActorEvent(startTick, identify)` | Ator → LocalTM | Registra ator no TimeManager |
| `PauseSimulationEvent` | Externo → GTM → LocalTMs | Pausa simulação |
| `ResumeSimulationEvent` | Externo → GTM → LocalTMs | Retoma simulação |
| `StopSimulationEvent` | GTM → LocalTMs / SM → todos | Encerra simulação |

### Eventos de Carga

| Evento | Direção | Descrição |
|--------|---------|-----------|
| `LoadDataEvent(actorsDataSources)` | SM → LDM | Inicia carga de dados |
| `LoadDataSourceEvent(source)` | LDM → JsonLoadData | Carrega uma fonte de dados |
| `CreateActorsEvent(batchId, actors)` | Loader → Creator | Cria batch de atores |
| `FinishCreationEvent(batchId, amount)` | Creator → Loader | Batch criado com sucesso |
| `FinishLoadDataEvent(amount, progressiveSources)` | LDM → SM | Carga EAGER completa |
| `StartProgressiveLoadingEvent(sources)` | SM → PLM | Inicia carga progressiva |
| `TickIndexBuiltEvent(tickCounts)` | Loader → PLM | Index leve construído |
| `LoadActorsForTickRange(from, to)` | PLM → Loader | Carrega atores no range de ticks |
| `TickRangeLoadedEvent(actorsLoaded)` | Loader → PLM | Range carregado |
| `TickWindowRequest(current, horizon)` | GTM → PLM | Solicita janela de atores |
| `TickWindowReady(readyUpToTick)` | PLM → GTM | Janela pronta |
| `ProgressiveLoadingCompleteEvent(total)` | PLM → SM | Toda carga progressiva terminada |

### Eventos de Criação de Atores

| Evento | Direção | Descrição |
|--------|---------|-----------|
| `ShardRegion.StartEntity(entityId)` | Creator → ShardRegion | Inicia entidade no shard |
| `ShardRegion.StartEntityAck(entityId)` | ShardRegion → Creator | Entidade iniciada |
| `InitializeEvent(id, data)` | Creator → Ator (via shard) | Inicializa estado do ator |
| `InitializeEntityAckEvent(entityId)` | Ator → Creator | Ator inicializado |
| `StartEntityAckEvent(entityId)` | Ator → Creator | Ack para pool actors |
| `DestructEvent(actorRef)` | TM/Manager → Ator | Destrói ator |

### Eventos de Reporting

| Evento | Direção | Descrição |
|--------|---------|-----------|
| `ReportEvent(entityId, tick, data, label)` | Ator → ReportData | Reporta dado para persistência |
| `RegisterReportersEvent(reporters)` | RM → SM | Registra reporters no SimulationManager |

### Eventos de Infraestrutura

| Evento | Direção | Descrição |
|--------|---------|-----------|
| `PrepareSimulationEvent` | SM → SM | Trigger para iniciar preparação |
| `TimeManagerRegisterEvent(actorRef)` | GTM/LocalTM → SM/GTM | Registra TM reference |
| `RegisterProgressiveLoadManagerEvent(plmRef)` | SM → GTM | Registra PLM no GTM |
| `GlobalTickEvent(tick)` | LocalTM → Link MICRO | Dispara sub-ticks micro |

---

*Documento gerado a partir do código-fonte em `src/main/scala/core/`. Última atualização: Abril 2026.*
