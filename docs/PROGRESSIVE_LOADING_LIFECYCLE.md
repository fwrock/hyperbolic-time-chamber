# Progressive Loading — Ciclo de Vida Completo

## Visão Geral

O carregamento progressivo permite que atores com `loadingStrategy = PROGRESSIVE` sejam criados **durante** a simulação, em janelas de tick adaptativas. Isso evita carregar milhões de atores na memória antes de iniciar.

Atores `EAGER` (nodes, links, signals) são carregados antes da simulação. Atores `PROGRESSIVE` (persons, cars) são carregados sob demanda, em lotes baseados no `startTick` de cada ator.

---

## Arquitetura de Componentes

```
┌─────────────────────┐
│  SimulationManager   │  Orquestra setup e start
└────────┬────────────┘
         │
    ┌────▼────────────────────┐     ┌──────────────────────────────┐
    │  GlobalTimeManager (GTM)│◄───►│  ProgressiveLoadDataManager  │
    │  (singleton)            │     │  (PLM, singleton)            │
    └────────┬────────────────┘     └──────────┬───────────────────┘
             │                                 │
    ┌────────▼────────────┐         ┌──────────▼───────────────────┐
    │  LocalTimeManagers   │         │  ProgressiveJsonLoadData     │
    │  (pool, N instâncias)│         │  (1 por arquivo JSON)        │
    └─────────────────────┘         └──────────┬───────────────────┘
                                               │
                                    ┌──────────▼───────────────────┐
                                    │  CreatorLoadData (pool)       │
                                    │  CreatorPoolLoadData (pool)   │
                                    │  (filhos do PLM)             │
                                    └──────────────────────────────┘
```

> **Ponto crítico:** Os creator pools são filhos do `ProgressiveLoadDataManager`, não do `LoadDataManager`. O `LoadDataManager` é destruído após o eager loading, e seus filhos morrem junto. O PLM cria pools independentes que vivem durante toda a simulação.

---

## Fluxo Detalhado

### Fase 1 — Setup (antes da simulação)

```
SimulationManager
  │
  ├─ ① loadManager ! DestructEvent
  │     └─ Destrói LoadDataManager + seus filhos (creator pools do eager loading)
  │
  ├─ ② Cria ProgressiveLoadDataManager (singleton)
  │
  ├─ ③ GTM ! RegisterProgressiveLoadManagerEvent(plmRef, lookAheadTicks)
  │     └─ GTM salva referência do PLM
  │     └─ GTM seta progressiveLoadingEnabled = true
  │
  ├─ ④ PLM ! StartProgressiveLoadingEvent(sources, timeManagerRef)
  │     └─ PLM cria seus PRÓPRIOS creator pools (filhos do PLM)
  │     └─ PLM cria loaders distribuídos (1 por arquivo JSON, round-robin nos pods)
  │     └─ PLM envia LoadDataSourceEvent → loaders iniciam light indexing
  │
  └─ ⑤ GTM ! StartSimulationTimeEvent
        └─ GTM vê progressiveLoadingEnabled = true
        └─ GTM SEGURA o start: pendingStartEvent = Some(event)
        └─ GTM ! PLM: TickWindowRequest(initialTick, initialTick + lookAhead)
```

### Fase 2 — Indexação + Primeira Janela

```
Loaders (paralelo, batches de INDEX_BUILD_BATCH_SIZE=30)
  │
  └─ buildLightIndex(file)
  │     Streaming do JSON, extrai apenas startTick de cada ator
  │     Resultado: Map[Tick, Count] (sem reter ActorSimulation em memória)
  │
  └─ PLM ! TickIndexBuiltEvent(sourceId, tickCounts, maxTick)
       │
       └─ PLM agrega tickCounts de todos os loaders
       └─ Quando TODOS indexados: fullyIndexed = true
       └─ Se há pendingWindowRequest → processTickWindowRequest()
```

```
PLM.processTickWindowRequest(TickWindowRequest)
  │
  ├─ Calcula horizonte adaptativo:
  │     Percorre ticks em ordem, acumula contadora de atores
  │     Para quando acumula TARGET_ACTORS_PER_WINDOW (50K) atores
  │     Mínimo: MIN_LOOK_AHEAD_TICKS (100 ticks)
  │
  ├─ Filtra loaders relevantes (maxTick >= fromTick)
  │
  ├─ Envia LoadActorsForTickRange para cada loader
  │   (sliding-window de LOAD_BATCH_SIZE=10 loaders concorrentes)
  │     │
  │     └─ Loader abre JSON, filtra atores com startTick ∈ [from, to]
  │     └─ Streaming em chunks de CHUNK_SIZE=500 atores (back-pressure)
  │     └─ Para cada chunk:
  │           CreatorLoadData ! CreateActorsEvent(batchId, actors)
  │           │
  │           ├─ Creator: ShardRegion.StartEntity(entityId)
  │           │     └─ Pekko cria o ator no shard (state = null neste ponto)
  │           │
  │           ├─ Shard responde: StartEntityAck
  │           │     └─ Creator envia InitializeEvent com JSON data
  │           │     └─ Ator.onInitialize:
  │           │           state = JsonUtil.convertValue(data)  ← STATE SETADO
  │           │           registerOnTimeManager()              ← REGISTRA NO TM
  │           │
  │           ├─ Ator confirma: Creator ! InitializeEntityAckEvent
  │           │
  │           └─ Creator confirma: PLM ! FinishCreationEvent
  │
  └─ Quando TODOS loaders confirmam:
       PLM ! TickRangeLoadedEvent(sourceId, fromTick, toTick, actorsLoaded)
       │
       └─ PLM atualiza loadedUpToTick = toTick
       └─ PLM ! GTM: TickWindowReady(readyUpToTick, actorsCreated)
```

### Fase 3 — Simulação Inicia

```
GTM.handleTickWindowReady (caso: waitingForInitialWindow = true)
  │
  └─ waitingForInitialWindow = false
  └─ progressiveLoadedUpToTick = readyUpToTick (ex: tick 10000)
  └─ lastWindowTickRange = readyUpToTick - initialTick
  └─ notifyLocalManagers(pendingStartEvent)
       └─ Broadcast StartSimulationTimeEvent para todos os LocalTimeManagers
       └─ ══════ SIMULAÇÃO COMEÇA ══════
```

### Fase 4 — Simulação Rodando + Prefetch

```
LocalTimeManagers processam ticks...
  │
  └─ Cada tick completo → GTM ! LocalTimeReportEvent(tick)
       │
       └─ Quando todos LTMs reportaram:
            GTM.calculateAndBroadcastNextGlobalTick()
            │
            ├─ CASO A: nextTick > progressiveLoadedUpToTick
            │     ⚠ BLOQUEIA simulação
            │     waitingForProgressiveLoad = true
            │     pendingNextTick = Some(nextTick)
            │     PLM ! TickWindowRequest(nextTick, nextTick + lookAhead)
            │     └─ PLM carrega janela → GTM ! TickWindowReady
            │           └─ GTM desbloqueia: notifyLocalManagers(UpdateGlobalTimeEvent)
            │
            ├─ CASO B: buffer < prefetchThreshold (40% da última janela)
            │     📡 Prefetch proativo (NÃO bloqueia)
            │     PLM ! TickWindowRequest(nextTick, nextTick + lookAhead)
            │     └─ Carrega próxima janela em background
            │
            └─ CASO C: buffer suficiente
                 ✅ Avança normalmente
                 notifyLocalManagers(UpdateGlobalTimeEvent(nextTick))
```

### Fase 5 — Conclusão

```
Quando loadedUpToTick >= maxTick de TODOS os sources:
  │
  PLM seta allSourcesFullyLoaded = true
  PLM ! SimulationManager: ProgressiveLoadingCompleteEvent(totalActorsCreated)
  │
  SimulationManager ! GTM: TickWindowReady(Long.MaxValue, 0)
  │
  GTM seta progressiveLoadingComplete = true
  └─ Não faz mais verificações de janela progressiva
```

---

## Janela Adaptativa

A janela não é fixa. O `PLM` usa a densidade de atores por tick para determinar o tamanho:

| Cenário | Ticks na janela | Atores |
|---------|----------------|--------|
| Denso (rush hour) | ~200 ticks | ~50K atores |
| Esparso (madrugada) | ~5000 ticks | ~2K atores |
| Vazio (sem atores) | maxLookAhead | 0 atores |

Parâmetros:
- `TARGET_ACTORS_PER_WINDOW = 50,000`
- `MIN_LOOK_AHEAD_TICKS = 100`
- `PREFETCH_RATIO = 0.4` (prefetch quando buffer < 40% da última janela)
- `MIN_PREFETCH_BUFFER = 100` (mínimo absoluto)

---

## Memória e Back-Pressure

| Mecanismo | Controle |
|-----------|---------|
| **Light index** | Apenas `Map[Tick, Count]` — sem reter objetos `ActorSimulation` |
| **Streaming chunks** | Máx 500 atores em memória por loader por vez |
| **Sliding-window loaders** | Máx 10 loaders lendo arquivos simultaneamente |
| **Index build batching** | Máx 30 indexações paralelas |
| **Creator back-pressure** | Próximo chunk só lido após `FinishCreationEvent` do atual |

---

## Configuração

No JSON de simulação, marcar data sources como progressivos:

```json
{
  "id": "htcrid:person;1",
  "classType": "hybrid.actor.Person",
  "creationType": "LoadBalancedDistributed",
  "loadingStrategy": "PROGRESSIVE",
  "dataSource": {
    "sourceType": "json",
    "info": {
      "path": "/data/persons_1.json"
    }
  }
}
```

Fontes sem `loadingStrategy` usam o default `EAGER` (carregadas antes da simulação iniciar).

**Recomendação:** Infraestrutura (nodes, links, signals) como `EAGER`. Entidades móveis (persons, cars) como `PROGRESSIVE`.

---

## Arquivos Relevantes

| Arquivo | Responsabilidade |
|---------|-----------------|
| `core/actor/manager/ProgressiveLoadDataManager.scala` | Coordena janelas, cria creator pools, calcula horizonte adaptativo |
| `core/actor/manager/load/strategy/ProgressiveJsonLoadData.scala` | Light indexing + streaming de chunks por arquivo |
| `core/actor/manager/GlobalTimeManager.scala` | Sincroniza ticks globais, bloqueia/prefetch janelas |
| `core/actor/manager/LoadDataManager.scala` | Eager loading (pré-simulação) |
| `core/actor/manager/load/CreatorLoadData.scala` | Cria atores sharded + envia InitializeEvent |
| `core/actor/manager/SimulationManager.scala` | Orquestra setup do PLM e GTM |
| `core/entity/event/control/load/StartProgressiveLoadingEvent.scala` | Evento de setup do PLM |
| `core/entity/event/control/load/TickWindowRequest.scala` | GTM → PLM: "carregue atores até tick X" |
| `core/entity/event/control/load/TickWindowReady.scala` | PLM → GTM: "atores prontos até tick X" |
| `core/util/TickIndexUtil.scala` | Utilitários de light indexing e chunk reading |
