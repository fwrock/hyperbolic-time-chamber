# Hyperbolic Time Chamber — Documentação Arquitetural Detalhada

> Documento técnico para fundamentação da metodologia e proposta de qualificação de doutorado.
> Última atualização: Abril 2026.

---

## Sumário

1. [Visão Geral do Sistema](#1-visão-geral-do-sistema)
2. [Decisões Arquiteturais Fundamentais](#2-decisões-arquiteturais-fundamentais)
3. [Hierarquia de Atores e Modelo de Objetos](#3-hierarquia-de-atores-e-modelo-de-objetos)
4. [Ciclo de Vida da Simulação](#4-ciclo-de-vida-da-simulação)
5. [Sistema de Gerenciamento de Tempo](#5-sistema-de-gerenciamento-de-tempo)
6. [Sistema de Eventos e Comunicação](#6-sistema-de-eventos-e-comunicação)
7. [Pipeline de Carregamento de Dados](#7-pipeline-de-carregamento-de-dados)
8. [Modelo de Mobilidade Mesoscópica](#8-modelo-de-mobilidade-mesoscópica)
9. [Modelo Híbrido Micro-Meso](#9-modelo-híbrido-micro-meso)
10. [Distribuição e Escalabilidade Horizontal](#10-distribuição-e-escalabilidade-horizontal)
11. [Observabilidade e Monitoramento](#11-observabilidade-e-monitoramento)
12. [Sistema de Relatórios](#12-sistema-de-relatórios)
13. [Roteamento Dinâmico](#13-roteamento-dinâmico)
14. [Decisões de Engenharia de Software](#14-decisões-de-engenharia-de-software)
15. [Stack Tecnológica](#15-stack-tecnológica)
16. [Limitações e Trabalhos Futuros](#16-limitações-e-trabalhos-futuros)

---

## 1. Visão Geral do Sistema

O **Hyperbolic Time Chamber (HTC)** é um simulador de tráfego urbano distribuído, orientado a eventos e baseado em multi-agentes, implementado em Scala 3.3.5 sobre o framework Apache Pekko (fork open-source do Akka). O sistema foi projetado para simular mobilidade urbana em escala metropolitana, suportando escalabilidade horizontal, persistência de eventos e uma arquitetura de plugins extensível.

### 1.1 Propósito e Motivação

O simulador foi concebido para atender a necessidades de pesquisa em cidades inteligentes (*smart cities*), abordando limitações de simuladores tradicionais:

- **Escalabilidade**: Simuladores monolíticos (SUMO, MATSim) enfrentam limitações de memória e CPU ao modelar cidades inteiras. O HTC distribui a carga computacional horizontalmente através de um cluster.
- **Flexibilidade de escala**: A abordagem híbrida micro-meso permite detalhe microscópico onde necessário (e.g., corredores de BRT) sem o custo computacional de modelar toda a cidade microscopicamente.
- **Extensibilidade**: A arquitetura baseada em atores permite adicionar novos tipos de entidades (veículos, infraestrutura) sem modificar o núcleo do sistema.
- **Reprodutibilidade**: Seeds determinísticos garantem que simulações com a mesma configuração produzem resultados idênticos.

### 1.2 Modelo de Simulação

O HTC utiliza um modelo **mesoscópico** como base, onde o tráfego é representado por entidades individuais (carros, ônibus, bicicletas, etc.) que se movem através de uma rede de *links* (segmentos de via) e *nodes* (interseções), mas com dinâmica de fluxo agregada por link. A extensão **híbrida** adiciona capacidade microscópica seletiva por link, incorporando modelos de *car-following* (Krauss) e gestão de faixas (*lane management*).

### 1.3 Organização do Código

```
src/main/scala/
├── core/                          # Núcleo genérico do simulador
│   ├── HyperbolicTimeChamber.scala  # Ponto de entrada
│   ├── actor/                       # Framework de atores
│   │   ├── BaseActor.scala            # Ator genérico base
│   │   ├── SimulationBaseActor.scala  # Ator com capacidades de simulação
│   │   └── manager/                   # Gerenciadores do sistema
│   ├── entity/                      # Entidades de domínio
│   │   ├── event/                     # Eventos do sistema
│   │   ├── state/                     # Estados base
│   │   ├── configuration/             # Configuração de simulação
│   │   └── control/                   # Relógios lógicos
│   ├── enumeration/                 # Enumerações do sistema
│   ├── metrics/                     # Prometheus / Monitoramento
│   ├── serializer/                  # Serialização (Kryo, Protobuf, Jackson)
│   ├── kafka/                       # Integração com Kafka
│   ├── types/                       # Type aliases (Tick, EventId)
│   └── util/                        # Utilitários gerais
├── model/                         # Modelos de domínio
│   ├── mobility/                    # Modelo mesoscópico original
│   │   ├── actor/                     # Car, Bus, Link, Node, etc.
│   │   ├── entity/state/             # CarState, LinkState, etc.
│   │   └── util/                      # SpeedUtil, CityMapUtil, GPSUtil
│   ├── hybrid/                      # Modelo híbrido micro-meso
│   │   ├── actor/                     # Car, Bus, Link, Node + veículos novos
│   │   ├── entity/state/             # CarState, LinkState + estados micro
│   │   ├── micro/                     # Modelos microscópicos
│   │   │   ├── model/                  # CarFollowingModel, KraussModel
│   │   │   ├── lane/                   # LaneManager, MOBIL
│   │   │   └── strategy/              # MicroSimulationStrategy
│   │   └── util/                      # SpeedUtil, GPSUtil + cache dinâmico
│   └── supermarket/                 # Modelo de exemplo (extensibilidade)
└── system/                        # Integrações externas
```

---

## 2. Decisões Arquiteturais Fundamentais

### 2.1 Actor Model como paradigma central

**Decisão**: Toda lógica de negócio é encapsulada em atores independentes que se comunicam exclusivamente via troca de mensagens assíncronas.

**Justificativa**:
- **Isolamento de estado**: Cada entidade (carro, link, nó) é representada por um ator com estado interno privado. Não há memória compartilhada, eliminando categorias inteiras de bugs de concorrência.
- **Localidade de referência**: O modelo de atores mapeia naturalmente para entidades de simulação de tráfego — cada veículo é um agente autônomo com comportamento próprio.
- **Escalabilidade transparente**: A comunicação por mensagens é agnóstica à localização — atores podem residir no mesmo JVM ou em nós diferentes do cluster sem alterar o código de aplicação.
- **Tolerância a falhas**: A supervisão hierárquica do Pekko permite recuperação automática de falhas individuais sem afetar o restante da simulação.

**Implicações**:
- Todo estado é imutável (case classes Scala) ou controlado pelo ator proprietário.
- Transições de estado são funções puras, facilitando testes e raciocínio sobre comportamento.
- Comunicação cross-componente é sempre assíncrona e orientada a eventos.

### 2.2 Apache Pekko como runtime

**Decisão**: Utilizar Apache Pekko (fork do Akka sob licença Apache 2.0) como runtime do modelo de atores.

**Justificativa**:
- O Akka Classic (agora Pekko) é a implementação mais madura e testada em produção do modelo de atores na JVM.
- Pekko oferece primitivas essenciais integradas: Cluster Sharding, Cluster Singleton, Cluster Routing, Persistence, Remote, e Management.
- A licença Apache 2.0 do Pekko garante uso livre em contexto acadêmico, diferentemente do Akka BSL.
- Suporte nativo a serialização (Kryo, Jackson, Protobuf) e descoberta de serviços (Kubernetes).

### 2.3 Separação núcleo/modelo

**Decisão**: O núcleo do simulador (`core/`) é genérico e independente do domínio de tráfego. Modelos específicos (`model/mobility`, `model/hybrid`) implementam comportamento de domínio via extensão das classes base.

**Justificativa**:
- Permite reutilizar a infraestrutura de simulação (gerenciamento de tempo, eventos, carregamento de dados) para domínios diferentes de tráfego (o pacote `model/supermarket` demonstra isso).
- Facilita testes unitários do núcleo isoladamente.
- Habilita evolução independente do core e dos modelos.

### 2.4 Modelo Híbrido Micro-Meso por link

**Decisão**: Cada link da rede viária define individualmente se opera em modo MESO ou MICRO. Todos os veículos que entram em um link adotam o modo de simulação daquele link.

**Justificativa**:
- Permite simular uma cidade inteira em modo mesoscópico (computacionalmente barato) enquanto regiões de interesse (corredores de BRT, cruzamentos complexos) usam simulação microscópica detalhada.
- A seleção por link (e não por veículo) simplifica a implementação: o link é o ponto de decisão natural, já que controla o fluxo.
- Evita inconsistências: dois veículos no mesmo link sempre usam o mesmo modelo de simulação.

### 2.5 Gerenciamento de Tempo Hierárquico

**Decisão**: O sistema de tempo é organizado em três camadas: GlobalTimeManager (singleton) → pool de LocalTimeManagers → Links com micro time management local.

**Justificativa**:
- **GlobalTimeManager singleton**: Coordena a barreira de sincronização global entre todos os nós do cluster. Garante que nenhum LocalTimeManager avance além do tick global mínimo.
- **Pool de LocalTimeManagers**: Distribuídos pelo cluster via ClusterRouterPool, cada LocalTM gerencia um subconjunto de atores de simulação. Essa distribuição evita que o singleton se torne gargalo.
- **Micro time management nos links**: Links em modo MICRO executam sub-ticks localmente sem comunicação com o GlobalTM a cada sub-tick, reduzindo drasticamente o overhead de mensagens.

### 2.6 Carregamento Progressivo

**Decisão**: Atores de simulação com `loadingStrategy: PROGRESSIVE` são criados sob demanda durante a simulação, em janelas de tempo adaptativas, ao invés de todos serem criados antes do início.

**Justificativa**:
- Simulações de grande escala (centenas de milhares a milhões de veículos) não cabem em memória simultaneamente.
- O carregamento progressivo cria atores em janelas de tick (e.g., 50.000 atores por janela), adaptando o tamanho da janela à densidade de atores por tick.
- Infraestrutura (nodes, links, signals) usa `loadingStrategy: EAGER` pois precisa existir desde o início.

---

## 3. Hierarquia de Atores e Modelo de Objetos

### 3.1 Hierarquia de classes de atores

```
PersistentActor (Pekko)
 └── ActorSerializable (trait)
      └── BaseActor[T <: BaseState]
           ├── SimulationBaseActor[T <: BaseState]
           │    ├── model.mobility.actor.Movable[T <: MovableState]
           │    │    ├── model.mobility.actor.Car
           │    │    ├── model.mobility.actor.Bus
           │    │    ├── model.mobility.actor.Person
           │    │    └── model.mobility.actor.Subway
           │    ├── model.mobility.actor.Link
           │    ├── model.mobility.actor.Node
           │    ├── model.mobility.actor.TrafficSignal
           │    ├── model.hybrid.actor.Movable[T <: MovableState]
           │    │    ├── model.hybrid.actor.Car (+ PrivateVehicle trait)
           │    │    ├── model.hybrid.actor.Bus
           │    │    ├── model.hybrid.actor.Bicycle
           │    │    ├── model.hybrid.actor.Motorcycle
           │    │    ├── model.hybrid.actor.Person
           │    │    └── model.hybrid.actor.Subway
           │    ├── model.hybrid.actor.Link (MESO/MICRO dual-mode)
           │    └── model.hybrid.actor.Node
           └── BaseManager[DefaultState]
                ├── TimeManagerBase
                │    ├── GlobalTimeManager
                │    └── LocalTimeManagerBase (+ MicroAwareTimeManager)
                │         ├── LocalDiscreteEventTimeManager
                │         └── LocalTimeSteppedTimeManager
                ├── SimulationManager
                ├── LoadDataManager
                ├── ProgressiveLoadDataManager
                └── ReportManager
```

### 3.2 BaseActor — Funcionalidade genérica

O `BaseActor[T <: BaseState]` fornece:
- **Identidade**: `entityId` derivado das properties ou gerado deterministicamente via `RandomSeedManager`.
- **Estado tipado**: Campo `state: T` parametrizado pelo tipo de estado, deserializado automaticamente via Jackson.
- **Persistência**: Herda de `PersistentActor` (Pekko Persistence), embora a persistência esteja atualmente desabilitada por performance.
- **Ciclo de vida**: `preStart()` → `onStart()` → `receive()` → `onDestruct()`.
- **Sharding**: Suporta Cluster Sharding via `EntityEnvelopeEvent` como message extractor.

### 3.3 SimulationBaseActor — Capacidades de simulação

Adiciona sobre o `BaseActor`:
- **Relógio de Lamport**: Cada ator mantém um `LamportClock` para ordenação causal de eventos entre atores distribuídos.
- **Gerenciamento de tick**: `currentTick`, `startTick`, tratamento de `SpontaneousEvent`.
- **Múltiplos Time Managers**: Suporte a `timeManagers: Map[String, ActorRef]`, permitindo que um ator alterne entre discrete-event e time-stepped dinamicamente via `switchTimeManager()`.
- **Comunicação inter-atores**: `sendMessageTo()` abstrai envio via shard ou pool, carregando automaticamente o Lamport clock.
- **Reporting**: `report(data, label)` para coleta de métricas durante a simulação.
- **Dependências**: Mapa de dependências para resolução de referências entre atores (e.g., um carro depende dos nós de origem e destino).
- **Ciclo SpontaneousEvent**: O ator recebe `SpontaneousEvent` do TimeManager, executa `actSpontaneous()`, e ao final chama `onFinishSpontaneous(nextTick?)` para sinalizar conclusão e opcionalmente reagendar.

### 3.4 Properties — Configuração de atores

A classe `Properties` encapsula toda a configuração necessária para um ator de simulação:

```scala
case class Properties(
  entityId: String,
  resourceId: String,
  timeManagers: Map[String, ActorRef],     // discrete-event, time-stepped
  creatorManager: ActorRef,                 // quem criou este ator
  reporters: Map[ReportTypeEnum, ActorRef], // csv, json reporters
  data: Any,                               // estado inicial serializado
  dependencies: Map[String, Dependency],    // atores dos quais depende
  actorType: CreationTypeEnum,              // LoadBalanced, Pool, Singleton
  defaultTimeManagerType: String            // tipo padrão de TM
)
```

### 3.5 BaseState — Estado base

Todo estado de ator herda de `BaseState`:

```scala
abstract class BaseState(
  startTick: Tick = Long.MinValue,  // quando este ator inicia
  reporterType: ReportTypeEnum,     // tipo de reporter preferido
  scheduleOnTimeManager: Boolean,   // se registra automaticamente no TM
  eventsAmount: Long,               // contagem de eventos
  totalEventsAmount: Long           // contagem total
)
```

---

## 4. Ciclo de Vida da Simulação

O ciclo de vida da simulação segue uma sequência orquestrada de sete fases, coordenadas primariamente pelo `SimulationManager` (Cluster Singleton):

### Fase 1 — Bootstrap do Sistema

```
main.scala
 └→ HyperbolicTimeChamber.start()
     ├─ MetricsServer.start(9001)          // Prometheus na porta 9001
     ├─ ActorSystem("hyperbolic-time-chamber")
     ├─ RandomSeedManager.initialize()      // Seed determinístico
     ├─ PekkoManagement.start()             // Health checks
     ├─ ClusterBootstrap.start()            // Descoberta de nós
     ├─ SimulationUtil.startShards()        // ShardRegions para todos os tipos de ator
     └─ ClusterSingletonManager(SimulationManager)
```

O ponto de entrada cria o `ActorSystem` Pekko, inicializa o gerenciador de seeds para reprodutibilidade, inicia mecanismos de gerenciamento do cluster, registra as ShardRegions para cada tipo de ator definido na configuração, e finalmente instancia o `SimulationManager` como Cluster Singleton.

A chamada `SimulationUtil.startShards()` itera sobre todos os `actorsDataSources` da configuração, extraindo os `classType` distintos e criando uma ShardRegion para cada um. Isso garante que o sistema está pronto para receber atores de qualquer tipo antes do carregamento de dados.

### Fase 2 — Preparação da Simulação

```
SimulationManager.onStart()
 └→ PrepareSimulationEvent
     ├─ loadSimulationConfig()               // Carrega JSON de configuração
     ├─ Log diagnóstico do cluster           // Membros up, roles, líder
     ├─ createSingletonTimeManager()         // GlobalTimeManager (singleton)
     └─ createSingletonReportManager()       // ReportManager (singleton)
```

O `SimulationManager` carrega a configuração da simulação (arquivo JSON), registra o estado do cluster para diagnóstico, e cria os singletons de gerenciamento de tempo e relatórios.

### Fase 3 — Inicialização de Gerenciadores

```
GlobalTimeManager.onStart()
 └→ createTimeManagersPool()
     ├─ ClusterRouterPool(RoundRobinPool)    // Pool de LocalTMs
     │   └─ LocalDiscreteEventTimeManager × N  // N instâncias distribuídas
     └→ SimulationManager ← TimeManagerRegisterEvent(poolRef)

ReportManager.onStart()
 └→ createReporters()
     ├─ ClusterRouterPool(CsvReportData)     // Reporter CSV
     ├─ ClusterRouterPool(JsonReportData)    // Reporter JSON
     └→ SimulationManager ← RegisterReportersEvent(reportersMap)
```

O `GlobalTimeManager` cria um pool distribuído de `LocalDiscreteEventTimeManager` via `ClusterRouterPool`. A configuração define `total-instances` e `max-instances-per-node`, garantindo distribuição equilibrada pelo cluster. Cada instância do LocalTM se registra com o GlobalTM ao iniciar.

O `ReportManager` cria pools de reporters (CSV, JSON) e envia referências ao `SimulationManager`.

### Fase 4 — Carregamento de Dados (Eager)

```
SimulationManager.startLoadData()
 └→ CreateSingleton(LoadDataManager)
     └→ LoadDataEvent(actorsDataSources)

LoadDataManager:
 ├─ Fontes EAGER: Cria CreatorLoadData/CreatorPoolLoadData
 │   ├─ CreateActorsEvent(batch de ActorSimulation)
 │   │   └─ Para cada ator:
 │   │       ├─ createShardActor() ou createPoolActor()
 │   │       ├─ InitializeEvent(data, timeManagers, reporters, dependencies)
 │   │       └─ Ator.preStart() → deserializa estado → RegisterActorEvent ao TM
 │   └─ FinishCreationEvent
 │
 ├─ Fontes PROGRESSIVE: Indexadas mas não carregadas
 │   └─ ProgressiveJsonLoadData.buildLightIndex()
 │       └─ Apenas contagem de atores por tick (sem carregar dados)
 │
 └→ SimulationManager ← FinishLoadDataEvent(progressiveSources)
```

O `LoadDataManager` distingue entre fontes EAGER (infraestrutura: nodes, links, signals) e PROGRESSIVE (veículos). Fontes EAGER são carregadas integralmente antes do início da simulação. Fontes PROGRESSIVE são indexadas apenas por contagem (sem manter objetos em memória) para permitir carregamento adaptive posterior.

Os atores são criados em batches, e cada criação envolve:
1. Instanciação do ator no ShardRegion ou Pool apropriado.
2. Envio de `InitializeEvent` com estado, referências de TimeManagers, reporters e dependências.
3. O ator deserializa seu estado, e se `scheduleOnTimeManager == true`, se registra automaticamente no TimeManager.

### Fase 5 — Início da Simulação com Carregamento Progressivo

```
SimulationManager.startSimulation()
 ├─ Se há fontes PROGRESSIVE:
 │   ├─ CreateSingleton(ProgressiveLoadDataManager)
 │   ├─ RegisterProgressiveLoadManagerEvent → GlobalTM
 │   └─ StartProgressiveLoadingEvent → ProgressiveLDM
 │
 └→ GlobalTM ← StartSimulationTimeEvent(startTick)

GlobalTimeManager.startSimulation():
 ├─ Se progressiveLoading ativado:
 │   ├─ Aguarda janela inicial ser carregada (BLOQUEANTE)
 │   ├─ TickWindowRequest(startTick, startTick + maxLookAhead)
 │   └─ Prossegue apenas após TickWindowReady
 └─ Broadcast(StartSimulationTimeEvent) → LocalTMs
```

Quando há fontes progressivas, o `GlobalTimeManager` aguarda o carregamento da primeira janela de ticks antes de iniciar a simulação, garantindo que os atores necessários existam quando o primeiro tick for processado.

### Fase 6 — Execução da Simulação (Loop Principal)

```
┌──────────────────────────────────────────────────────────┐
│                   LOOP DE SIMULAÇÃO                       │
│                                                          │
│  GlobalTM ──Broadcast(UpdateGlobalTimeEvent(tick))──→    │
│     ├─ LocalTM₁.syncWithGlobalTime(tick)                 │
│     │   ├─ triggerMicroLinks(tick)    // sub-ticks MICRO │
│     │   └─ processTick(tick)          // eventos MESO    │
│     │       ├─ SpontaneousEvent → Ator₁                  │
│     │       ├─ SpontaneousEvent → Ator₂                  │
│     │       └─ ...                                       │
│     ├─ LocalTM₂.syncWithGlobalTime(tick)                 │
│     │   └─ ...                                           │
│     └─ LocalTMₙ                                          │
│                                                          │
│  Atores processam:                                       │
│     Ator.actSpontaneous(event)                           │
│       ├─ Lógica de negócio                               │
│       ├─ sendMessageTo(outroAtor, data)  // interação    │
│       └─ onFinishSpontaneous(nextTick?)  // conclusão    │
│                                                          │
│  Após todos FinishEvents no LocalTM:                     │
│     LocalTM → LocalTimeReportEvent → GlobalTM            │
│                                                          │
│  Quando todos LocalTMs reportam:                         │
│     GlobalTM.calculateAndBroadcastNextGlobalTick()       │
│       ├─ Verifica se simulação acabou                    │
│       ├─ Verifica se precisa carregamento progressivo    │
│       ├─ Calcula nextTick = min(todos os LocalTMs)       │
│       └─ Broadcast(UpdateGlobalTimeEvent(nextTick))      │
│                                                          │
│  Carregamento Progressivo (se ativo):                    │
│     Se nextTick > progressiveLoadedUpToTick:             │
│       ├─ BLOQUEIA (waitingForProgressiveLoad = true)     │
│       ├─ TickWindowRequest → ProgressiveLDM              │
│       └─ Continua após TickWindowReady                   │
│                                                          │
│     Pre-fetch adaptivo:                                  │
│       Se remainingBuffer < PREFETCH_RATIO × lastWindow:  │
│       └─ TickWindowRequest proativo (não bloqueia)       │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

O loop principal utiliza uma **barreira de sincronização conservadora**: o `GlobalTimeManager` só avança para o próximo tick quando todos os `LocalTimeManagers` reportam conclusão do tick atual. Isso garante consistência causal mas limita o paralelismo ao ritmo do LocalTM mais lento.

### Fase 7 — Término da Simulação

```
Condição de término:
 ├─ tickOffset >= simulationDuration (duração configurada)
 ├─ Nenhum LocalTM tem eventos agendados (hasScheduled = false)
 └─ StopSimulationEvent explícito

GlobalTM.terminateSimulation()
 ├─ printSimulationDuration()
 ├─ Broadcast(StopSimulationEvent) → LocalTMs
 └─ Cada LocalTM:
     ├─ forceDestructActiveActors()  // limpa atores em execução
     └─ reportGlobalTimeManager(hasScheduled = false)

SimulationManager.handleStopSimulation()
 ├─ StopSimulationEvent → LoadManager
 ├─ StopSimulationEvent → ProgressiveLoadManager
 ├─ StopSimulationEvent → ReportManager
 ├─ StopSimulationEvent → GlobalTM
 └─ selfDestruct()
```

A opção `extendSimulationIfPendingEventsAfterEnd` permite que a simulação continue além da duração configurada enquanto houver veículos que ainda não completaram suas viagens, útil para garantir dados completos de trip info.

---

## 5. Sistema de Gerenciamento de Tempo

### 5.1 Arquitetura de três camadas

O sistema de gerenciamento de tempo é hierárquico, com cada camada desempenhando um papel específico:

#### Camada 1 — GlobalTimeManager (Cluster Singleton)

- **Responsabilidade**: Coordenação global de tempo entre todos os LocalTMs distribuídos.
- **Mecanismo**: Barreira de sincronização. Coleta `LocalTimeReportEvent` de cada LocalTM e calcula o próximo tick global como `min(todos os reportedTicks)`.
- **Singleton**: Executa em exatamente um nó do cluster, com failover automático.
- **Progressive Loading**: Coordena com o `ProgressiveLoadDataManager` para garantir que atores existam antes que seus ticks sejam processados.
- **Métricas**: Publica tick corrente, progresso, e duração de cada ciclo de tick via Prometheus.

#### Camada 2 — LocalTimeManagers (Pool Distribuído)

- **Responsabilidade**: Execução efetiva de eventos de simulação para um subconjunto de atores.
- **Distribuição**: `ClusterRouterPool` com `RoundRobinPool`, distribuindo instâncias pelo cluster. Configurável via `htc.time-manager.total-instances` e `max-instances-per-node`.
- **Duas implementações**:
  - `LocalDiscreteEventTimeManager`: Processa eventos em ordem cronológica. Tempo só avança quando todos os eventos do tick atual terminam. **Batching** para ticks com mais de 5.000 atores para evitar sobrecarga (e.g., tick 0 com 750K+ atores).
  - `LocalTimeSteppedTimeManager`: Avança tempo em passos fixos, processando todos os atores registrados a cada passo.
- **Watchdog**: Timer periódico (60s) que detecta `runningEvents` stuck (e.g., por shard rebalancing, crashes) e force-avança após 300s de inatividade.
- **MicroAwareTimeManager**: Trait que adiciona capacidade de triggar micro links antes de processar eventos regulares a cada tick.

#### Camada 3 — LinkMicroTimeManager (dentro de Links MICRO)

- **Responsabilidade**: Execução de sub-ticks microscópicos (e.g., 10 sub-ticks de 0.1s por tick global de 1s).
- **Localidade**: Cada Link MICRO gerencia seus próprios sub-ticks internamente, sem comunicação com o GlobalTM para cada sub-tick.
- **Motivação**: Um link MICRO pode conter dezenas de veículos; executar car-following para todos eles a cada sub-tick geraria milões de mensagens por tick global se centralizado.

### 5.2 Fluxo de sincronização

```
              GlobalTM                L-TM₁          L-TM₂          L-TMₙ
                │                      │              │              │
 tick=T    ─────┼──Broadcast───────→   │     ─────→   │     ─────→   │
                │   (UpdateGlobal)     │              │              │
                │                      │              │              │
                │               processTick(T)  processTick(T) processTick(T)
                │               Spontaneous→A₁  Spontaneous→A₃ Spontaneous→A₅
                │               Spontaneous→A₂  Spontaneous→A₄     │
                │                      │              │              │
                │                A₁ FinishEvent       │              │
                │                A₂ FinishEvent  A₃ FinishEvent      │
                │                      │         A₄ FinishEvent A₅ FinishEvent
                │                      │              │              │
                │  ←─ LocalTimeReport ─┤              │              │
                │     (tick=T+3,has)    │              │              │
                │  ←──────────────────────── LocalTimeReport ──┤     │
                │                             (tick=T+1,has)         │
                │  ←───────────────────────────────────── LocalTimeReport
                │                                        (tick=T+5,has)
                │                      │              │              │
 tick=T+1  ─────┼──Broadcast──( min(T+3,T+1,T+5)=T+1 )──→ ...
```

### 5.3 Relógio de Lamport

Cada `SimulationBaseActor` mantém um `LamportClock` que é:
- **Incrementado** antes de cada envio de mensagem (`sendMessageTo`).
- **Atualizado** ao receber uma mensagem: `clock = max(local, received) + 1`.

Isso garante **ordenação causal**: se um evento A causa um evento B, o Lamport clock de B sempre é maior que o de A, mesmo em execução distribuída. Combinado com o tick de simulação, permite reconstrução determinística da sequência de eventos.

---

## 6. Sistema de Eventos e Comunicação

### 6.1 Taxonomia de eventos

O sistema utiliza quatro categorias fundamentais de eventos:

| Categoria | Classe | Direção | Propósito |
|-----------|--------|---------|-----------|
| **Espontâneo** | `SpontaneousEvent` | TM → Ator | Trigger temporal: "é a sua vez de agir neste tick" |
| **Interação** | `ActorInteractionEvent` | Ator → Ator | Comunicação de domínio entre entidades |
| **Finalização** | `FinishEvent` | Ator → TM | Sinaliza conclusão de processamento + reagendamento |
| **Agendamento** | `ScheduleEvent` | Ator → TM | Solicita ativação em tick futuro |

#### SpontaneousEvent

```scala
case class SpontaneousEvent(
  tick: Tick,           // Tick atual da simulação
  actorRef: ActorRef,   // Referência ao TimeManager que enviou
  safeHorizon: Tick     // Horizonte seguro para lookahead (-1 = conservador)
)
```

O `safeHorizon` é uma preparação para otimização futura de *lookahead*: se o ator sabe que não receberá mensagens externas até o tick H, pode processar múltiplos ticks internamente sem sincronização.

#### ActorInteractionEvent

```scala
case class ActorInteractionEvent(
  tick: Tick,               // Tick em que o evento foi criado
  lamportTick: Tick,        // Clock de Lamport do remetente
  actorRefId: String,       // ID do ator remetente
  shardRefId: String,       // ID do shard do remetente
  actorPathRef: String,     // Caminho do ator remetente
  actorClassType: String,   // Tipo do ator remetente
  eventType: String,        // Sub-tipo do evento (EnterLink, LeaveLink, etc.)
  data: AnyRef,             // Payload tipado (e.g., EnterLinkData, SignalStateData)
  actorType: String,        // CreationType (Shard ou Pool)
  resourceId: String        // ID do recurso
)
```

O event type é uma string discriminadores que permite ao receptor rotear o tratamento sem type matching no envelope. O `data` é o payload tipado de domínio.

#### FinishEvent

```scala
case class FinishEvent(
  end: Tick,                     // Tick em que o processamento terminou
  actorRef: ActorRef,            // Referência ao ator
  identify: Identify,            // Identidade completa do ator
  scheduleTick: Option[String],  // Tick para reagendamento (None = desregistrar)
  scheduleEvent: Option[...],    // Evento para agendar (não utilizado atualmente)
  timeManager: ActorRef,         // TM que deve processar este finish
  destruct: Boolean              // Se o ator deve ser destruído após finish
)
```

O `FinishEvent` é **crítico** para o protocolo de sincronização: o LocalTM só avança para o próximo tick quando todos os `runningEvents` emitem seu `FinishEvent`. Se `scheduleTick` é `None`, o ator é removido de **todos** os ticks futuros (unregistered).

### 6.2 Envelope e roteamento de mensagens

Mensagens entre atores em ShardRegions são encapsuladas em `EntityEnvelopeEvent`:

```scala
case class EntityEnvelopeEvent(
  entityId: String,   // ID do ator destino (usado pelo ShardRegion como entity extractor)
  event: Any          // Evento encapsulado
)
```

O ShardRegion extrai o `entityId` do envelope para rotear a mensagem ao shard correto, e o ator destino recebe o `event` interno.

### 6.3 Protobuf para eventos de controle

Eventos de controle do sistema (RegisterActorEvent, ScheduleEvent, StartSimulationTimeEvent, etc.) são definidos em Protocol Buffers (`src/main/protobuf/`) e gerados via ScalaPB. Isso garante:
- Serialização compacta e eficiente para comunicação inter-nó.
- Versionamento de protocolo e compatibilidade backward/forward.
- Type-safety na fronteira de serialização.

### 6.4 Serialização

O sistema suporta três mecanismos de serialização, configurados em `application.conf`:
- **Kryo**: Para eventos de domínio genéricos (ActorInteractionEvent, SpontaneousEvent, FinishEvent). Serialização binária compacta e rápida.
- **Protobuf/ScalaPB**: Para eventos de controle (RegisterActorEvent, ScheduleEvent, etc.). Definidos em `.proto` e gerados pelo ScalaPB.
- **Jackson**: Para serialização/deserialização de estado (JSON ↔ case classes Scala) durante carregamento e persistência.

---

## 7. Pipeline de Carregamento de Dados

### 7.1 Configuração de fontes de dados

A configuração da simulação define as fontes de dados como uma lista de `ActorDataSource`:

```scala
case class ActorDataSource(
  id: String,                                   // Identificador único
  classType: String,                            // Classe do ator (e.g., "hybrid.actor.Car")
  creationType: CreationTypeEnum,               // LoadBalanced, Pool, Singleton
  dataSource: DataSource,                       // Tipo e info da fonte (JSON, etc.)
  loadingStrategy: LoadingStrategyEnum,         // EAGER ou PROGRESSIVE
  entityLifecycle: EntityLifecycleEnum           // STATIC ou DYNAMIC
)
```

#### CreationTypeEnum

| Tipo | Descrição | Uso |
|------|-----------|-----|
| `LoadBalancedDistributed` | Cluster Sharding | Maioria dos atores (carros, links, nodes) |
| `PoolDistributed` | ClusterRouterPool | Atores com estado compartilhado |
| `SingletonDistributed` | Cluster Singleton | Gerenciadores (TM, SM, RM) |
| `Simple` | Ator local | Testes e desenvolvimento |

#### LoadingStrategyEnum

| Tipo | Descrição | Uso |
|------|-----------|-----|
| `EAGER` | Criado antes do início da simulação | Infraestrutura (nodes, links, signals) |
| `PROGRESSIVE` | Criado durante a simulação, por janela de tick | Veículos (carros, ônibus, bicicletas) |

### 7.2 Duas fases de carregamento

#### Fase EAGER (LoadDataManager)

1. Lê cada fonte de dados EAGER do arquivo JSON.
2. Cria `CreatorLoadData` ou `CreatorPoolLoadData` (para atores Pool).
3. O Creator processa a lista em batches de `CreateActorsEvent`.
4. Para cada ator no batch:
   a. Cria via `ClusterSharding` ou `createPoolActor`.
   b. Envia `InitializeEvent` com `Properties` completas.
   c. Aguarda `InitializeEntityAckEvent` de confirmação.
5. Ao terminar, reporta `FinishCreationEvent`.

#### Fase PROGRESSIVE (ProgressiveLoadDataManager)

1. **Indexação leve**: Percorre cada arquivo JSON fonte e conta atores por `startTick`, sem manter objetos em memória. Produz um `LightTickIndex` (Map[Tick, Int]).
2. **Janelas adaptativas**: Quando o GlobalTM solicita `TickWindowRequest(currentTick, horizonTick)`, o ProgressiveLDM calcula uma janela adaptativa:
   - Target: ~50.000 atores por janela.
   - Em regiões densas (muitos atores por tick), a janela é menor.
   - Em regiões esparsas, estende até `maxLookAheadTicks`.
3. **Carregamento sob demanda**: Re-lê o arquivo JSON com `readMatchingChunk`, extraindo apenas atores cujo `startTick` está na janela solicitada.
4. Cria atores via Creators dedicados e reporta `TickWindowReady` ao GlobalTM.
5. **Pre-fetch adaptativo**: O GlobalTM solicita proativamente a próxima janela quando `remainingBuffer < PREFETCH_RATIO × lastWindowRange`, evitando stalls.

### 7.3 Formato de dados JSON

Os atores são definidos como arrays JSON:

```json
[
  {
    "id": "htcaid:car;trip_1",
    "typeActor": "hybrid.actor.Car",
    "data": {
      "dataType": "model.hybrid.entity.state.CarState",
      "content": {
        "startTick": 154,
        "origin": "htcaid:node;60609822",
        "destination": "htcaid:node;4922987596",
        "scheduleOnTimeManager": true
      }
    },
    "dependencies": {
      "from_node": { "id": "htcaid:node;60609822", "classType": "hybrid.actor.Node" },
      "to_node": { "id": "htcaid:node;4922987596", "classType": "hybrid.actor.Node" }
    }
  }
]
```

O campo `startTick` é fundamental para o carregamento progressivo: determina em qual tick o ator será criado. Atores de infraestrutura tipicamente têm `startTick` no início da simulação.

---

## 8. Modelo de Mobilidade Mesoscópica

### 8.1 Rede viária como grafo

A rede viária é representada como um grafo direcionado onde:
- **Nodes** (interseções): Vértices com coordenadas geográficas (lat/long), armazenando conexões de links, estados de semáforos, paradas de ônibus e estações de metrô.
- **Links** (segmentos de via): Arestas direcionadas com comprimento, capacidade, velocidade livre (*free-flow speed*), número de faixas e fator de congestionamento.

O grafo é carregado pelo `CityMapUtil` e utilizado pelo `GPSUtil` para cálculo de rotas via Dijkstra otimizado.

### 8.2 Modelo BPR de velocidade por link

No modo MESO, a velocidade de cada link é calculada pela função BPR (Bureau of Public Roads):

```scala
speed = freeSpeed × (1 - (numberOfCars / capacity)^β)^α
```

Onde `α = 1.0`, `β = 1.0` por padrão. Quando `numberOfCars >= capacity`, a velocidade cai para 1.0 m/s (congestionamento total).

O tempo de travessia do link é: `time = (length / speed) + 1`.

### 8.3 Ciclo de vida de um veículo mesoscópico

```
Start → requestRoute() → calcRoute(origin, destination) → bestRoute
  │
  ▼
Ready → enterLink() → sendMessageTo(Link, EnterLinkData)
  │                                    │
  │                     Link recebe → calcula speed → responde LinkInfoData
  │                                    │
  ▼                     ◄──────────────┘
Moving → requestSignalState() → sendMessageTo(Node, RequestSignalStateData)
  │                                    │
  │                     Node consulta SignalState → responde SignalStateData
  │                                    │
  ▼                     ◄──────────────┘
  ├─ Se Green ou sem semáforo:
  │   WaitingSignal → leavingLink() → sendMessageTo(Link, LeaveLinkData)
  │                                    │
  │                     Link remove registro → responde LinkInfoData
  │                                    │
  │   ◄───────────────────────────────┘
  │   Ready → enterLink() [próximo link da rota] → ...
  │
  ├─ Se Red:
  │   Stopped → aguarda nextTick (quando sinal muda)
  │   SpontaneousEvent → requestSignalState() → ...
  │
  └─ Se chegou ao destino:
      Finished → onFinishSpontaneous(None) → desregistra do TM
```

### 8.4 Entidades do modelo mesoscópico

| Ator | Estado | Comportamento |
|------|--------|---------------|
| **Car** | CarState (origin, destination, bestRoute, status) | Rota A*, enter/leave link, signal handling |
| **Bus** | BusState (label, route, stops, passengers, capacity) | Rota fixa, paradas, embarque/desembarque |
| **Subway** | SubwayState (line, stations, passengers) | Trilhos digitais, headway, paradas |
| **Person** | PersonState (origin, destination, mode) | Multimodal: caminhada + transporte público |
| **Link** | LinkState (from, to, length, capacity, registered) | Registro de veículos, cálculo de speed |
| **Node** | NodeState (connections, signals, busStops) | Interseção, roteamento, semáforos |
| **TrafficSignal** | TrafficSignalState (phases, timing) | Ciclos de fases, broadcast de estado |

---

## 9. Modelo Híbrido Micro-Meso

### 9.1 Filosofia de design

O modelo híbrido (`model.hybrid`) preserva o modelo mesoscópico original (`model.mobility`) como referência e implementa um sistema dual onde cada link pode operar independentemente em modo MESO ou MICRO. A decisão de modo é **configurada por link** e é transparente para o veículo.

### 9.2 Modo MICRO — Car-Following

#### Modelo de Krauss (padrão)

O modelo de Krauss calcula a velocidade segura com base no veículo líder:

$$v_{safe} = -\tau \cdot b + \sqrt{(\tau \cdot b)^2 + v_{leader}^2 + 2 \cdot b \cdot gap}$$

Onde:
- $\tau$ = tempo de reação (1.0s padrão)
- $b$ = desaceleração máxima (4.5 m/s² padrão)
- $gap$ = distância efetiva ao líder (gap real - gap mínimo)
- $v_{leader}$ = velocidade do veículo líder

A velocidade final incorpora aleatoriedade do motorista:

$$v_{target} = \min(v_{desired}, v_{safe}, v_{max\_possible}) \times (1 - \sigma \cdot rand)$$

Onde $\sigma$ é o fator de aleatoriedade (0.2 padrão).

#### Interface extensível

```scala
trait CarFollowingModel {
  def calculateSafeVelocity(currentVelocity, desiredVelocity, gap,
                            leaderVelocity, maxAcceleration, maxDeceleration,
                            minGap, reactionTime, deltaT): Double
  def calculateAcceleration(currentVelocity, desiredVelocity, safeVelocity,
                            maxAcceleration, maxDeceleration, deltaT): Double
  def updateState(state: MicroMovableState, deltaT: Double): MicroMovableState
}
```

Preparada para implementações futuras: IDM (Intelligent Driver Model), Gipps.

### 9.3 Strategy Pattern para simulação microscópica

O Link MICRO delega a lógica de simulação a estratégias plugáveis:

```
MicroSimulationStrategy (interface)
 └── DefaultMicroSimulationStrategy (Krauss + gestão de faixas)

LaneChangeStrategy (interface)
 └── NoLaneChangeStrategy (sem mudança de faixa - padrão)
 └── [Futuro: MobilLaneChange (MOBIL)]
```

#### Execução de sub-ticks

Cada tick global, o Link MICRO executa `microTicksPerGlobalTick` sub-ticks (tipicamente 10):

```
Link.handleGlobalTick(tick):
 Para cada subTick em [0, microTicksPerGlobalTick):
   microSimulationStrategy.executeSubTick(vehiclesByLane, ...)
     Para cada lane:
       Para cada veículo (do líder para o seguidor):
         calcSafeVelocity(gap ao líder, velocidade do líder)
         calcAcceleration(currentV, desiredV, safeV)
         newPosition = position + velocity × dt + 0.5 × accel × dt²
         Se newPosition >= linkLength:
           veículo saiu do link → LeaveLinkMicro
         Senão:
           atualiza posição e velocidade
```

### 9.4 Estados microscópicos por tipo de veículo

Cada tipo de veículo possui parâmetros microscópicos específicos:

| Parâmetro | Car | Bus | Bicycle | Motorcycle |
|-----------|-----|-----|---------|------------|
| Comprimento (m) | 4.5 | 12.0 | 2.0 | 2.5 |
| Acel. máx (m/s²) | 2.6 | 1.2 | 1.0 | 3.5 |
| Decel. máx (m/s²) | 4.5 | 3.5 | 3.0 | 5.0 |
| Gap mínimo (m) | 2.0 | 3.0 | 1.5 | 1.5 |
| Vel. desejada (m/s) | 13.89 (50km/h) | 11.11 (40km/h) | 5.56 (20km/h) | 16.67 (60km/h) |
| Tempo reação (s) | 1.0 | 1.5 | 1.2 | 0.9 |

### 9.5 Transição MESO → MICRO → MESO

```
Veículo em link MESO
  │
  ├─ EnterLink → Link MESO responde LinkInfoData (speed agregada)
  │   └─ Veículo calcula tempo de travessia e agenda LeaveLink
  │
  ▼  Veículo entra em link MICRO
  │
  ├─ EnterLink → Link MICRO
  │   ├─ Atribui faixa (least occupied)
  │   ├─ Cria VehicleInLane(position=0, velocity=0, lane=n)
  │   ├─ Agenda micro-tick se não agendado
  │   └─ Responde LinkInfoData com modo MICRO
  │
  │ [Link executa sub-ticks internamente]
  │   ├─ Car-following: posição/velocidade atualizados por sub-tick
  │   └─ Quando position >= linkLength:
  │       ├─ Remove veículo da faixa
  │       ├─ Envia MicroLeaveLinkData ao veículo
  │       └─ Veículo volta ao modo MESO
  │
  ▼  Veículo em próximo link MESO
  │
  └─ Comportamento mesoscópico normal retomado
```

### 9.6 VehicleInLane — Representação microscópica no Link

```scala
case class VehicleInLane(
  actorId: String,        // ID do veículo
  shardId: String,        // Shard do veículo
  position: Double,       // metros desde o início do link
  velocity: Double,       // m/s
  acceleration: Double,   // m/s²
  vehicleLength: Double,  // metros (4.5 para carros, 12 para ônibus)
  entryTick: Tick          // tick de entrada no link
)
```

O Link mantém `vehiclesByLane: Map[Int, Queue[VehicleInLane]]`, onde cada fila é ordenada pela posição. O veículo na frente da fila é o líder.

---

## 10. Distribuição e Escalabilidade Horizontal

### 10.1 Cluster Pekko

O sistema utiliza Pekko Cluster com as seguintes configurações:

- **Descoberta**: Kubernetes API discovery para ambientes cloud, seed nodes para desenvolvimento local.
- **Min members**: Configurável para aguardar um número mínimo de nós antes de iniciar.
- **Split brain resolver**: `keep-majority` para lidar com partições de rede.

### 10.2 Cluster Sharding

Atores de simulação (carros, links, nodes) são distribuídos via **Cluster Sharding**:

- **Entity ID**: Derivado do `entityId` do ator (e.g., `htcaid_car_trip_1`).
- **Shard ID**: Calculado deterministicamente a partir do entity ID.
- **Message Extractor**: `EntityEnvelopeEvent` serve como extractor de entity ID e shard ID.
- **Rebalancing**: O Pekko redistribui automaticamente shards entre nós quando membros entram ou saem do cluster.

### 10.3 Cluster Singletons

Componentes que requerem exatamente uma instância utilizam Cluster Singleton:

| Singleton | Propósito |
|-----------|-----------|
| `SimulationManager` | Orquestra ciclo de vida |
| `GlobalTimeManager` | Barreira de sincronização global |
| `LoadDataManager` | Carregamento EAGER |
| `ProgressiveLoadDataManager` | Carregamento PROGRESSIVE |
| `ReportManager` | Coordena reporters |

Cada singleton tem um proxy correspondente para comunicação location-transparent.

### 10.4 Cluster Router Pools

Componentes que se beneficiam de múltiplas instâncias utilizam ClusterRouterPool:

| Pool | Instâncias | Propósito |
|------|-----------|-----------|
| `LocalTimeManagers` | Configurável (total × per-node) | Execução paralela de eventos |
| `CreatorLoadData` | 10+ por carregamento | Criação paralela de atores |
| `ReportData (CSV/JSON)` | 2-4 | Escrita paralela de relatórios |

### 10.5 Topologia de deploy

```
Kubernetes Cluster / Docker Compose
├── Nó 1 (seed)
│   ├── SimulationManager (singleton)
│   ├── GlobalTimeManager (singleton)
│   ├── LocalTM₁
│   ├── ShardRegion(Car) → Shard₁, Shard₂, ...
│   ├── ShardRegion(Link) → Shard₅, Shard₆, ...
│   └── MetricsServer :9001
├── Nó 2
│   ├── LocalTM₂
│   ├── ShardRegion(Car) → Shard₃, Shard₄, ...
│   ├── ShardRegion(Link) → Shard₇, Shard₈, ...
│   └── MetricsServer :9001
└── Nó N
    ├── LocalTMₙ
    ├── ShardRegion(Car) → ...
    └── MetricsServer :9001
```

---

## 11. Observabilidade e Monitoramento

### 11.1 Prometheus Metrics

O `MetricsServer` expõe métricas via endpoint HTTP (:9001/metrics):

#### Progresso da Simulação
- `htc_simulation_ticks_total`: Contador de ticks globais processados.
- `htc_simulation_current_tick`: Gauge do tick global atual.
- `htc_simulation_progress_ratio`: Progresso [0,1] em relação à duração configurada.
- `htc_tick_duration_seconds`: Histograma do tempo real de processamento de cada tick.

#### Atores
- `htc_actors_registered_total`: Registro cumulativo por tipo de ator.
- `htc_actors_active`: Gauge de atores ativos por tipo.
- `htc_events_processed_total`: Eventos processados por tipo (spontaneous, interaction, finish, destruct).

#### Time Manager
- `htc_tm_scheduled_actors`: Atores agendados no tick atual.
- `htc_tm_running_events`: Eventos espontâneos em execução (in-flight).
- `htc_tm_waiting_for_progressive`: 1 se GTM bloqueado aguardando carregamento.

#### Carregamento Progressivo
- `htc_progressive_actors_created_total`: Atores criados durante simulação.
- `htc_progressive_loaded_up_to_tick`: Tick mais alto completamente carregado.
- `htc_progressive_windows_loaded_total`: Janelas de tick completadas.

#### Infraestrutura
- `htc_journeys_started` / `htc_journeys_completed`: Viagens por tipo de veículo.
- `htc_dead_letters_total`: Dead letters (mensagens não entregues).

### 11.2 Dead Letter Monitoring

Um `DeadLetterListener` subscrito ao event stream do Pekko incrementa o contador Prometheus para cada mensagem não entregue, permitindo detecção de problemas de roteamento e atores ausentes.

---

## 12. Sistema de Relatórios

### 12.1 Arquitetura

```
SimulationBaseActor.report(data, label)
  │
  └→ reporters(reportType) ! ReportEvent(entityId, tick, lamportTick, data, label)
       │
       ├→ CsvReportData (pool distribuído)
       │   └─ Escreve em arquivo CSV por pool worker
       │
       └→ JsonReportData (pool distribuído)
           └─ Escreve em arquivo JSONL por pool worker
```

### 12.2 ReportEvent

```scala
case class ReportEvent(
  entityId: String,     // Quem gerou o report
  tick: Tick,           // Tick da simulação
  lamportTick: Tick,    // Clock de Lamport
  data: Any,            // Dados do relatório (Map, case class, etc.)
  label: String         // Categoria (journey_started, vehicle_entered_link, etc.)
)
```

### 12.3 Formato de saída

- **JSONL**: Uma linha JSON por evento, arquivo nomeado com timestamp e UUID do worker. Organizados em diretório com ID da simulação.
- **CSV**: Estrutura tabular, um arquivo por worker.

O tipo de reporter é configurável por ator (via `reporterType` no estado) ou globalmente via `htc.report-manager.default-strategy`.

---

## 13. Roteamento Dinâmico

### 13.1 Cálculo de rotas

O sistema oferece dois mecanismos de roteamento:

- **GPSUtil (mesoscópico)**: Dijkstra com cache de rotas (Redis) e pesos estáticos do grafo.
- **GPSUtil (híbrido)**: Dijkstra com pesos dinâmicos gerenciados pelo `DynamicWeightCache`.

### 13.2 Pesos dinâmicos

Links publicam custos dinâmicos periodicamente (configurável via `htc.routing.link-cost.publish-interval`):

```
Link.publishDynamicCost()
  └→ DynamicWeightCache.updateWeight(linkId, cost)
       ├─ Cache local (in-memory) com TTL configurável
       └─ [Opcional] Publicação via Kafka topic "dynamic-link-costs"
```

O custo dinâmico considera:
```scala
cost = length × congestionFactor + length / currentSpeed
```

Quando um veículo calcula sua rota, o GPSUtil consulta o cache para obter pesos dinâmicos atualizados, refletindo congestionamento em tempo real.

---

## 14. Decisões de Engenharia de Software

### 14.1 Scala 3 com Apache Pekko Classic

**Decisão**: Utilizar Scala 3.3.5 com Pekko Classic (API untyped) ao invés de Pekko Typed.

**Justificativa**:
- Pekko Classic oferece API mais madura para Cluster Sharding e Persistence.
- A migração para Typed não agrega valor suficiente para justificar o risco.
- Scala 3 é utilizada para benefícios de linguagem (enums, union types, contextual abstractions) sem dependência de APIs Typed.

### 14.2 Persistência desabilitada

**Decisão**: A persistência de eventos (Pekko Persistence) está implementada mas desabilitada em produção.

**Justificativa**:
- O overhead de persistir cada evento em Cassandra/LevelDB reduz significativamente a taxa de ticks por segundo.
- Para o caso de uso atual (simulação batch), a reprodutibilidade é garantida pelo seed determinístico — basta re-executar com a mesma configuração.
- A infraestrutura de persistência permanece no código para habilitação futura em cenários de simulação longa com checkpointing.

### 14.3 Protocol Buffers para eventos de controle

**Decisão**: Eventos de controle do sistema são definidos em `.proto` e serializados via Protobuf/ScalaPB.

**Justificativa**:
- Eventos de controle (RegisterActorEvent, ScheduleEvent, etc.) são extremamente frequentes (milhões por segundo em simulações grandes).
- Protobuf oferece serialização ~10x mais compacta e ~5x mais rápida que Jackson JSON.
- Type-safety na fronteira de serialização previne erros em evolução de protocolo.

### 14.4 Kryo para eventos de domínio

**Decisão**: Eventos de interação entre atores (ActorInteractionEvent) utilizam serialização Kryo.

**Justificativa**:
- ActorInteractionEvent tem payload `data: AnyRef` polimórfico, difícil de definir em Protobuf.
- Kryo serializa objetos Scala nativamente sem necessidade de schema definition.
- Performance satisfatória para eventos de domínio que são menos frequentes que eventos de controle.

### 14.5 Watchdog para recovery de atores stuck

**Decisão**: LocalTMs implementam um watchdog periódico que detecta e limpa `runningEvents` que não emitem `FinishEvent` após 300 segundos.

**Justificativa**:
- Shard rebalancing pode mover um ator enquanto ele está processando um SpontaneousEvent. O ator original morre sem emitir FinishEvent, travando o LocalTM indefinidamente.
- O watchdog verifica a cada 60s se os mesmos eventos estão running, e após 2 checks consecutivos sem progresso (300s), limpa os stale events e avança.
- É uma solução pragmática para um problema inerente a sistemas distribuídos com barreira de sincronização.

### 14.6 Batching de ticks grandes

**Decisão**: Quando um tick tem mais de 5.000 atores agendados (e.g., tick 0 onde todos os veículos iniciam), o LocalTM dispara em batches.

**Justificativa**:
- Enviar 750K+ SpontaneousEvents simultaneamente pode saturar o buffer de mensagens do ShardRegion e causar backpressure.
- Batches de 5.000 mantêm o pipeline fluido sem parar para GC.

### 14.7 Bump de ticks passados

**Decisão**: Quando um `ScheduleEvent` chega ao LocalTM com tick <= localTickOffset (já processado), o evento é automaticamente "bumped" para `localTickOffset + 1`.

**Justificativa**:
- Em sistema distribuído com pool router, um ScheduleEvent pode ser roteado a um LocalTM que já avançou além do tick solicitado.
- Sem esse mecanismo, o evento seria perdido (orphaned), causando o ator a parar de receber SpontaneousEvents.
- O bump garante que o evento será processado no próximo tick disponível.

### 14.8 Determinismo via RandomSeedManager

**Decisão**: Todos os geradores de números aleatórios são centralizados no `RandomSeedManager` com seed configurável.

**Justificativa**:
- Reprodutibilidade é essencial em simulação científica.
- O seed pode ser definido na configuração da simulação ou gerado automaticamente com base no timestamp.
- UUIDs determinísticos (`deterministicUUID()`) garantem que IDs de atores criados dinamicamente são reprodutíveis.

### 14.9 FinishEvent direto vs. via pool

**Decisão**: O FinishEvent é enviado diretamente ao TimeManager que originou o SpontaneousEvent (`currentTimeManager`), não ao pool router.

**Justificativa**:
- Usar `getTimeManager(currentTimeManagerType)` poderia rotear o FinishEvent a um LocalTM diferente daquele que está aguardando, causando inconsistência de `runningEvents` e deadlock.
- O campo `timeManager` no FinishEvent é verificado pelo receptor: se não corresponde a `self`, o FinishEvent é encaminhado ao TM correto.

---

## 15. Stack Tecnológica

### 15.1 Linguagem e Runtime

| Componente | Tecnologia | Versão |
|-----------|------------|--------|
| Linguagem | Scala | 3.3.5 |
| Runtime | JVM (Java) | 21+ |
| Build | SBT | Current |
| Actor Framework | Apache Pekko | 1.4.0 |

### 15.2 Serialização

| Mecanismo | Uso | Biblioteca |
|-----------|-----|-----------|
| Protocol Buffers | Eventos de controle | ScalaPB 0.11.11, Protobuf 4.34.1 |
| Kryo | Eventos de domínio | pekko-kryo-serialization 1.5.0 |
| Jackson | Estados e configuração | jackson-module-scala 2.21.2 |

### 15.3 Distribuição

| Componente | Tecnologia |
|-----------|------------|
| Cluster | Pekko Cluster com Sharding |
| Service Discovery | Pekko Management + Kubernetes API |
| Container | Docker + Docker Compose |
| Orquestração | Kubernetes (k8s/) |

### 15.4 Observabilidade

| Componente | Tecnologia |
|-----------|------------|
| Métricas | Prometheus (simpleclient 0.16.0) |
| Logging | SLF4J + Logback 1.5.32 |
| Health | Pekko Management HTTP |

### 15.5 Dados e Integração

| Componente | Tecnologia |
|-----------|------------|
| Cache | Redis (Jedis 7.4.0) |
| Mensageria | Apache Kafka (pekko-connectors-kafka 1.1.0) |
| Persistência | LevelDB (local), Cassandra (opcional) |
| Configuração | Typesafe Config 1.4.6 |

---

## 16. Limitações e Trabalhos Futuros

### 16.1 Limitações atuais

1. **Barreira conservadora**: O GlobalTM aguarda todos os LocalTMs antes de avançar, limitando o ganho de paralelismo quando há heterogeneidade de carga entre TMs.
2. **Persistência desabilitada**: Sem checkpointing, falhas requerem re-execução completa.
3. **Lane change não implementado**: A interface `LaneChangeStrategy` existe, mas apenas `NoLaneChangeStrategy` está ativa.
4. **Roteamento estático por tipo de ator**: Todos os atores do mesmo ShardRegion seguem a mesma estratégia de sharding; não há otimização de localidade geográfica.

### 16.2 Trabalhos futuros

1. **Sincronização otimista com lookahead**: Utilizar o `safeHorizon` do SpontaneousEvent para permitir que atores sem dependências externas processem múltiplos ticks sem sincronização.
2. **MOBIL lane change**: Implementação do modelo MOBIL para mudança de faixa em links MICRO.
3. **IDM car-following**: Implementação do Intelligent Driver Model como alternativa ao Krauss.
4. **Persistência seletiva**: Habilitar persistência apenas para atores dinâmicos (veículos) em pontos de checkpoint configuráveis.
5. **Geographic-aware sharding**: Distribuir atores por shard com base em proximidade geográfica para reduzir comunicação inter-nó.
6. **Validação contra SUMO**: Pipeline de comparação de resultados (trip info, link statistics) entre HTC e SUMO para mesma rede e demanda.

---

## Glossário

| Termo | Definição |
|-------|-----------|
| **Tick** | Unidade discreta de tempo da simulação (tipo `Long`) |
| **Sub-tick** | Subdivisão de um tick global para simulação microscópica |
| **Link** | Segmento de via representado como aresta do grafo viário |
| **Node** | Interseção representada como vértice do grafo viário |
| **ShardRegion** | Componente Pekko que distribui atores de um tipo entre shards |
| **Cluster Singleton** | Exatamente uma instância em todo o cluster |
| **ClusterRouterPool** | Pool de atores distribuídos por nós do cluster |
| **Spontaneous Event** | Evento temporal que ativa um ator em um tick específico |
| **Finish Event** | Sinalização de conclusão de processamento de um ator |
| **MESO** | Modo mesoscópico: agregação de fluxo por link |
| **MICRO** | Modo microscópico: dinâmica individual por veículo |
| **BPR** | Bureau of Public Roads — função velocidade-densidade |
| **Krauss** | Modelo de car-following baseado em velocidade segura |
| **Lamport Clock** | Relógio lógico para ordenação causal em sistemas distribuídos |
| **Progressive Loading** | Criação de atores sob demanda durante a simulação |
| **Watchdog** | Timer que detecta e limpa eventos stuck |

---

*Documento gerado a partir de análise direta do código-fonte do Hyperbolic Time Chamber v2.0.0.*
