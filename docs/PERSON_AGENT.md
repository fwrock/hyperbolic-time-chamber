# Agente Pessoa (`Person`) — Documentação Técnica

> Pacote: `model.hybrid.actor.Person`  
> Arquivo principal: [src/main/scala/model/hybrid/actor/Person.scala](../src/main/scala/model/hybrid/actor/Person.scala)

---

## 1. Visão Geral

O `Person` é um ator de simulação baseado em agentes que representa um indivíduo ao longo de um
dia de simulação. Diferentemente dos veículos, que são ativados sob demanda, **o agente Person
persiste durante todo o ciclo simulado**, gerencia um cronograma diário de atividades, toma
decisões de modo de transporte e coordena a comunicação com veículos e transporte público.

### Princípio fundamental

O modelo é **centrado na pessoa**: cada Person é a entidade decisora, e os veículos (Car,
Bicycle, Motorcycle) são **ativos passivos** que a Person ativa conforme necessário. Essa
separação garante que a lógica de mobilidade (rotas, escolha modal, histórico de viagens) fique
encapsulada no agente Person, enquanto a física do movimento fica nos veículos.

---

## 2. Hierarquia de Classes

```
SimulationBaseActor[PersonState]
    └── Person
```

`Person` herda de `SimulationBaseActor` com parâmetro de tipo `PersonState`, recebendo:

- `actSpontaneous(event)` — chamado pelo TimeManager no tick agendado.
- `actInteractWith(event)` — chamado quando outro ator envia uma mensagem.
- `onFinishSpontaneous(nextTick)` — agenda o próximo tick (`Some(tick)`) ou desregistra (`None`).

---

## 3. Estado (`PersonState`)

> Arquivo: [src/main/scala/model/hybrid/entity/state/PersonState.scala](../src/main/scala/model/hybrid/entity/state/PersonState.scala)

```scala
case class PersonState(
  startTick: Tick = 0L,
  scheduleOnTimeManager: Boolean = true,

  // Cronograma diário
  dailySchedule: List[Activity] = List.empty,
  currentActivityIndex: Int = 0,

  // Veículos privados de posse da pessoa
  ownedVehicles: Map[String, Identify] = Map.empty,  // modo -> Identify(id, classType)

  // Viagem em andamento
  currentTripVehicleId: Option[String] = None,
  currentTripStartTick: Option[Tick] = None,

  // Estatísticas acumuladas do dia
  totalDistanceTraveled: Double = 0.0,
  completedTrips: Int = 0,

  // Transporte público — campos ativos durante viagem PT
  ptAlightingNodeId: Option[String] = None,
  ptLine: Option[String] = None,

  // Escolha modal dinâmica
  enableDynamicModeChoice: Boolean = false,
  modeChoiceWeights: ModeChoiceWeights = ModeChoiceWeights()
)
```

### Campos principais

| Campo | Tipo | Descrição |
|---|---|---|
| `dailySchedule` | `List[Activity]` | Lista ordenada de atividades do dia |
| `currentActivityIndex` | `Int` | Índice (0-based) da atividade atual |
| `ownedVehicles` | `Map[String, Identify]` | Mapa modo→referência do veículo (`id` + `classType`) |
| `currentTripVehicleId` | `Option[String]` | ID do veículo em uso; `"walking"` para a pé; `"pt:bus:Line1"` para PT |
| `ptAlightingNodeId` | `Option[String]` | Nó onde Person deve desembarcar do PT |
| `ptLine` | `Option[String]` | Linha de PT em uso |
| `enableDynamicModeChoice` | `Boolean` | Ativa escolha modal baseada em utilidade (default `false`) |
| `modeChoiceWeights` | `ModeChoiceWeights` | Pesos da função de utilidade usada na escolha dinâmica |

### Métodos de estado

| Método | Comportamento |
|---|---|
| `currentActivity` | Retorna `Some(Activity)` ou `None` se fora do índice |
| `nextActivity` | Retorna a próxima atividade na lista |
| `advanceActivity()` | Incrementa `currentActivityIndex` e zera campos de viagem |
| `isScheduleComplete` | `true` quando índice excede o tamanho da lista |
| `completeTrip(dist)` | Limpa campos de viagem, acumula estatísticas, zera PT |

---

## 4. Modelo de Atividades

### `Activity`

```scala
case class Activity(
  sequence: Int,          // Posição no cronograma (0-based)
  activityType: String,   // "Home", "Work", "School", "Shopping", etc.
  nodeId: String,         // Nó do mapa onde a atividade ocorre
  endTime: String,        // Tick de saída (string numérica) ou "HH:MM"
  arrivalLogistics: Option[ArrivalLogistics] = None  // Como chegar (None na primeira atividade)
)
```

A **primeira atividade** normalmente não tem `arrivalLogistics` (Person já está lá). Todas as
demais descrevem como a Person chega até aquele local.

### `ArrivalLogistics`

```scala
case class ArrivalLogistics(
  mode: String,                          // "car" | "bicycle" | "motorcycle" | "walk" | "bus" | "subway" | "transit"
  vehicle: Option[Identify] = None,      // Referência ao veículo privado (id + classType)
  instant: Boolean = false,             // true → sem roteamento (mesmo nó ou snap PT)
  driverAttributes: DriverAttributes = DriverAttributes(),
  line: Option[String] = None,          // Rótulo da linha PT, ex: "Bus Line 1"
  boardingStopId: Option[String] = None,         // ID do BusStop/SubwayStation
  boardingStopClassType: Option[String] = None,  // ClassType do BusStop/SubwayStation
  alightingNodeId: Option[String] = None,        // Nó onde Person desembarca
  fixedMode: Boolean = false             // true → ignora escolha dinâmica mesmo com a flag ativa
)
```

> **`fixedMode`**: permite marcar legs individuais do cronograma como imutáveis. Útil quando o
> designer de cenário quer que uma viagem de carro específica sempre aconteça de carro,
> independente do estado da rede de transporte público.

### `DriverAttributes`

Parâmetros de comportamento de condução que sobrepõem os defaults do veículo:

| Campo | Padrão | Intervalo | Efeito |
|---|---|---|---|
| `aggressiveness` | 0.5 | [0.0, 1.0] | Agressividade no trânsito |
| `maxSpeedFactor` | 1.0 | [0.5, 1.5] | Multiplicador do limite de velocidade |
| `reactionTime` | 1.0 s | [0.5, 2.0] | Tempo de reação (modelo Krauss) |
| `minGapFactor` | 1.0 | [0.5, 2.0] | Multiplicador do gap mínimo de segurança |

---

## 5. Ciclo de Vida do Agente

```
Início do tick startTick
        │
        ▼
[actSpontaneous] ──── isScheduleComplete? ──── YES ──► onFinishSpontaneous(None)
        │
        │ NO
        ▼
 currentTripVehicleId?
   ├── "walking" ──► advanceToNextActivity()
   ├── Some(id)  ──► onFinishSpontaneous(None)  [guarda defensivo]
   └── None
        │
        ▼
  currentActivity?
   ├── isActivityEndTime? ──── YES ──► startNextTrip()
   └── NO ──► onFinishSpontaneous(Some(endTick))
```

### Fases

1. **Atividade em andamento**: Person dorme até o `endTime` da atividade atual.
2. **Decisão de viagem**: ao acordar, lê `nextActivity.arrivalLogistics` e escolhe o modo.
3. **Execução da viagem**: delega ao veículo ou gerencia internamente (caminhada/PT).
4. **Conclusão da viagem**: recebe confirmação e avança para a próxima atividade.
5. **Fim do cronograma**: desregistra do TimeManager.

---

## 6. Modos de Transporte

### 6.1 Veículo Privado (`car`, `bicycle`, `motorcycle`)

```
Person ──[StartTripData]──► Vehicle
                               │ (veículo executa rota nos links)
Vehicle ──[TripCompletedData]──► Person
Person desregistra do TM ao iniciar; re-registra ao receber TripCompleted
```

**Fluxo detalhado:**

1. Person verifica que possui o veículo em `ownedVehicles` com o mesmo ID de `logistics.vehicle`.
2. Envia `StartTripData` ao veículo via `sendMessageTo`.
3. Atualiza `currentTripVehicleId` com o ID do veículo.
4. Chama `onFinishSpontaneous(None)` — **cede o TimeManager ao veículo**.
5. Ao receber `TripCompletedData`, chama `advanceToNextActivity()`, que re-agenda no TM.

### 6.2 Caminhada (`walk`)

```
Person ──[GPSUtil.calcRoute (Dijkstra)]──► rota calculada
         soma EdgeGraph.length por link
         t = ceil(d / 1.4 m/s)
Person ──[onFinishSpontaneous(Some(arrivalTick))]──► TimeManager
TimeManager ──[actSpontaneous no arrivalTick]──► Person
Person ──[advanceToNextActivity]──► próxima atividade
```

#### Algoritmo de roteamento

A caminhada usa o **mesmo grafo de rede viária** dos veículos motorizados (sem rede pedonal
dedicada). A rota é calculada pelo algoritmo de **Dijkstra com pesos dinâmicos**
(`GPSUtil.calcRoute` → `Graph.dijkstraEdgeTargetsOptimized`), que reflete o estado de tráfego
em tempo real via `DynamicWeightCache`.

```
Entrada:  originId, destinationId
          grafo G = (V, E), pesos w(e) ∈ DynamicWeightCache ou pesos estáticos
Saída:    (custo, Queue[(linkId, nodeId)])

1. Inicializa dist[origin] = 0, dist[v] = ∞ para todo v ≠ origin
2. Fila de prioridade Q ← {(0, origin)}
3. Enquanto Q ≠ ∅:
     (d, u) ← Q.dequeue_min()
     se d > dist[u]: ignorar (entrada obsoleta)
     se u == destination: reconstruir caminho e retornar
     para cada (v, aresta) ∈ vizinhos(u):
       novaDist ← dist[u] + w(aresta)   // w pode ser dinâmico
       se novaDist < dist[v]:
         dist[v] ← novaDist
         Q.enqueue((novaDist, v))
4. Reconstruir caminho via mapa cameFrom
```

Complexidade: $O((|E| + |V|) \log |V|)$ com binary heap.

#### Modelo de tempo de caminhada

Modelo de **velocidade de fluxo livre constante** (Free-Flow Walking Speed):

$$t_{walk} = \left\lceil \frac{\sum_{e \in \text{rota}} \text{length}(e)}{v_{walk}} \right\rceil$$

onde:
- $\sum \text{length}(e)$ = distância total acumulando `EdgeGraph.length` por link da rota (metros)
- $v_{walk} = 1{,}4\ \text{m/s}$ = velocidade de caminhada em fluxo livre
- $\lceil \cdot \rceil$ = teto (arredondamento para cima), pois 1 tick = 1 segundo

```scala
val walkingSpeed  = 1.4                                    // m/s
val walkingTimeTicks = math.ceil(totalDistance / walkingSpeed).toLong
val arrivalTick      = currentTick + walkingTimeTicks
```

#### Parâmetros

| Parâmetro | Valor | Descrição |
|---|---|---|
| `walkingSpeed` | `1.4 m/s` (5,04 km/h) | Velocidade livre do pedestre — hardcoded |
| Peso da aresta | dinâmico via `DynamicWeightCache` | Reflete congestionamento dos links |
| Resolução temporal | 1 tick = 1 segundo | Tick simulado inteiro |

#### Referências bibliográficas

O valor `1.4 m/s` é o valor de referência canônico para velocidade de caminhada de adultos em
fluxo livre:

- **Weidmann, U. (1993).** *Transporttechnik der Fussgänger.* Schriftenreihe IVT-Berichte 90,
  ETH Zürich. — Referência seminal: $\bar{v}_{0} = 1{,}34\ \text{m/s}$ como velocidade média
  livre para pedestres em ambiente urbano.

- **Bohannon, R. W., & Andrews, A. W. (2011).** "Normal walking speed: a descriptive
  meta-analysis." *Physiotherapy*, 97(3), 182–189. — Meta-análise com 23.111 participantes;
  média de $1{,}40\ \text{m/s}$ para adultos de 20–60 anos.

- **Transportation Research Board (TRB). (2010).** *Highway Capacity Manual (HCM 2010)*, 6th ed.
  National Academies Press. — Capítulo 24 (Pedestrians): velocidade padrão de projeto de
  $1{,}2\ \text{m/s}$ (planejamento conservador) a $1{,}5\ \text{m/s}$ (fluxo livre).

- **Dijkstra, E. W. (1959).** "A note on two problems in connexion with graphs." *Numerische
  Mathematik*, 1(1), 269–271. — Algoritmo de roteamento utilizado.

#### Limitações do modelo atual

| Limitação | Descrição |
|---|---|
| Rede viária como proxy | Pedestres usam o grafo de links de veículos, sem calçadas ou caminhos exclusivos |
| Velocidade constante | Não modela fadiga, subidas/descidas, clima, congestionamento pedonal |
| Sem interação com outros modos | Pedestres não interagem com semáforos nem aguardam travessias |
| Granularidade mesoscópica | Não há simulação microscópica de caminhada (posição, fluxo, densidade) |

**Fluxo detalhado:**

1. `GPSUtil.calcRoute` executa Dijkstra e retorna custo e fila de (linkId, nodeId).
2. Distância total = $\sum$ `EdgeGraph.length` de cada link da rota.
3. Tempo de chegada = `currentTick + ceil(distância / 1.4)`.
4. `currentTripVehicleId = Some("walking")` — Person **mantém o TM**.
5. Ao acordar no `arrivalTick`, `actSpontaneous` detecta `"walking"` e chama `advanceToNextActivity`.

### 6.3 Transporte Público — Ônibus (`bus`, `transit`)

```
Person ──[RegisterPassengerData(label)]──► BusStop
                                              │
           (ônibus chega ao stop)             │
Bus ──[BusRequestUnloadPassengerData(nodeId)]──► Person
Person ──[BusUnloadPassengerData(isArrival)]──► Bus
                (se isArrival=true) ──► Person avança atividade
```

**Fluxo detalhado:**

1. Person envia `RegisterPassengerData(label=line)` ao `BusStop` identificado por `boardingStopId`.
2. Atualiza `currentTripVehicleId = Some("pt:bus:<line>")` e `ptAlightingNodeId`.
3. Chama `onFinishSpontaneous(None)` — **cede o TM ao ônibus**.
4. A cada parada, o ônibus envia `BusRequestUnloadPassengerData(nodeId)`.
5. Person responde com `BusUnloadPassengerData(isArrival = nodeId == ptAlightingNodeId)`.
6. Se `isArrival=true`, Person chama `state.completeTrip(0.0)` e `advanceToNextActivity()`.

### 6.4 Transporte Público — Metrô (`subway`)

Idêntico ao ônibus, mas com os equivalentes de metrô:

| Bus | Subway |
|---|---|
| `RegisterPassengerData(label)` | `RegisterSubwayPassengerData(line)` |
| `BusRequestUnloadPassengerData` | `SubwayRequestUnloadPassengerData` |
| `BusUnloadPassengerData` | `SubwayUnloadPassengerData` |

### 6.5 Modos não implementados

Modos como `car_passenger`, `carpool`, etc. fazem Person avançar diretamente à próxima atividade
usando o tempo pré-definido no cronograma (`advanceToNextActivity()`).

### 6.6 Escolha Modal Dinâmica

> Arquivos:
> - [src/main/scala/model/hybrid/util/ModeChoiceUtil.scala](../src/main/scala/model/hybrid/util/ModeChoiceUtil.scala)
> - [src/main/scala/model/hybrid/util/TransitMapUtil.scala](../src/main/scala/model/hybrid/util/TransitMapUtil.scala)
> - [src/main/scala/model/hybrid/entity/state/model/TransitStop.scala](../src/main/scala/model/hybrid/entity/state/model/TransitStop.scala)

Quando `PersonState.enableDynamicModeChoice = true`, a escolha de modo deixa de ser lida
estaticamente de `ArrivalLogistics.mode` e passa a ser calculada em runtime por `ModeChoiceUtil`,
que avalia todas as paradas de TP acessíveis e a caminhada direta, retornando a opção com maior
utilidade.

#### Função de Utilidade

$$U(m, b, a) = \beta_{\text{mode}} \cdot \text{pref}(m)
             - \beta_{\text{access}} \cdot d_{\text{access}}(b)
             - \beta_{\text{egress}} \cdot d_{\text{egress}}(a)$$

onde:
- $m$ = modo (`bus`, `subway`, `walk`)
- $b$ = parada de embarque mais próxima da origem
- $a$ = parada de desembarque mais próxima do destino
- $d_{\text{access}}$ = distância haversine entre a origem e $b$ (metros)
- $d_{\text{egress}}$ = distância haversine entre $a$ e o destino (metros)
- $\text{pref}(m)$ = preferência modal escalar (padrão: metrô > ônibus > caminhada)

Para caminhada direta (sem PT), $d_{\text{access}}$ é a distância em linha reta origem→destino:

$$U(\text{walk}) = \beta_{\text{mode}} \cdot \text{pref}(\text{walk}) - \beta_{\text{access}} \cdot d_{\text{O} \to \text{D}}$$

#### `ModeChoiceWeights`

```scala
case class ModeChoiceWeights(
  betaMode: Double           = 1.0,    // escala da preferência modal
  betaAccess: Double         = 0.001,  // penalidade por metro no acesso
  betaEgress: Double         = 0.001,  // penalidade por metro no egresso
  modePrefSubway: Double     = 2.0,    // preferência de metrô
  modePrefBus: Double        = 1.0,    // preferência de ônibus
  modePrefWalk: Double       = 0.0,    // referência base
  maxAccessDistanceM: Double = 1500.0, // raio máximo de busca de paradas (m)
  maxWalkDistanceM: Double   = 2000.0  // distância máxima para oferecer caminhada (m)
)
```

#### Garantias de compatibilidade

| Condição | Comportamento |
|---|---|
| `enableDynamicModeChoice = false` (padrão) | Usa `logistics.mode` estático — comportamento idêntico ao anterior |
| `logistics.vehicle.isDefined` | Sempre usa o veículo definido, ignora flag |
| `logistics.fixedMode = true` | Leg nunca é reavaliado, mesmo com a flag ativa |
| `HTC_MOBILITY_TRANSIT_MAP_FILE` não configurado | `ModeChoiceUtil` retorna `currentLogistics` sem modificação |

#### Fluxo de decisão

```
startNextTrip()
  │
  ├── enableDynamicModeChoice = false ──► usa logistics.mode original
  │
  └── enableDynamicModeChoice = true
        │
        ├── vehicle.isDefined? ──► usa veículo fixo
        ├── fixedMode = true?  ──► usa logistics original
        ├── TransitMapUtil indisponível? ──► usa logistics original
        └── avalia candidatos:
              ├── bus:  nearestStops(origin, "bus") × linhas × alightingStop
              ├── subway: nearestStops(origin, "subway") × linhas × alightingStop
              └── walk: se d(O→D) ≤ maxWalkDistanceM
             ──► retorna ArrivalLogistics do candidato com maior U
```

#### Arquivo de paradas (`transit_map.json`)

Formato: **array JSON direto** (sem objeto wrapper).

```json
[
  {
    "id": "htcaid:stop;bus_stop_123",
    "actorId": "htcaid:busstop;123",
    "actorClassType": "hybrid.actor.BusStop",
    "nodeId": "htcaid:node;456",
    "latitude": -23.5505,
    "longitude": -46.6333,
    "stopType": "bus",
    "lines": ["line_1", "line_2"]
  },
  {
    "id": "htcaid:stop;metro_A",
    "actorId": "htcaid:subwaystation;A",
    "actorClassType": "hybrid.actor.SubwayStation",
    "nodeId": "htcaid:node;789",
    "latitude": -23.5461,
    "longitude": -46.6388,
    "stopType": "subway",
    "lines": ["blue_line"]
  }
]
```

Configurar via variável de ambiente ou `application.conf`:
```bash
HTC_MOBILITY_TRANSIT_MAP_FILE=/app/simulations/input/my_scenario/transit_map.json
```
```hocon
htc.mobility.transit-map-file = "/app/simulations/input/my_scenario/transit_map.json"
```

### 6.7 Viagem instantânea (`instant=true`)

Quando `ArrivalLogistics.instant = true`, Person pula o roteamento e avança imediatamente.
Usado após snap de paradas PT que colapsam dois nós consecutivos no mesmo nó da rede viária.

---

## 7. Comunicação entre Atores

### Mensagens enviadas por Person

| Mensagem | Destino | Condição |
|---|---|---|
| `StartTripData` | Vehicle (`Car`/`Bicycle`/`Motorcycle`) | Início de viagem de veículo privado |
| `RegisterPassengerData` | `BusStop` | Embarque em ônibus |
| `RegisterSubwayPassengerData` | `SubwayStation` | Embarque em metrô |
| `BusUnloadPassengerData` | `Bus` | Resposta ao pedido de desembarque |
| `SubwayUnloadPassengerData` | `Subway` | Resposta ao pedido de desembarque |

### Mensagens recebidas por Person

| Mensagem | Remetente | Handler |
|---|---|---|
| `TripCompletedData` | Vehicle | `handleTripCompleted` |
| `BusRequestUnloadPassengerData` | `Bus` | `handlePTUnloadRequest(..., "bus")` |
| `SubwayRequestUnloadPassengerData` | `Subway` | `handlePTUnloadRequest(..., "subway")` |

---

## 8. Gerenciamento do TimeManager (TM)

O **TimeManager** controla quando cada ator recebe eventos espontâneos. Person alterna o controle
do TM dependendo do modo de transporte:

| Situação | Dono do TM | Como Person re-registra |
|---|---|---|
| Atividade em andamento | Person | Dorme até `endTime` |
| Caminhada | Person | Dorme até `arrivalTick` |
| Veículo privado | Veículo | Recebe `TripCompletedData` → `advanceToNextActivity` |
| Transporte público | Veículo PT | Recebe `BusRequestUnloadPassengerData` com `isArrival=true` |
| Cronograma completo | — | `onFinishSpontaneous(None)` — desregistra permanentemente |

### Migração de ator

Após migração entre shards, Person só re-registra no TM se `scheduleOnTimeManager=true` **e**
estava em caminhada (`currentTripVehicleId == Some("walking")`) ou sem viagem ativa. Viagens de
veículo e PT **não** re-registram, pois o veículo ainda possui o TM.

---

## 9. Roteamento

### `GPSUtil`

> Arquivo: [src/main/scala/model/hybrid/util/GPSUtil.scala](../src/main/scala/model/hybrid/util/GPSUtil.scala)

Fornece três algoritmos de roteamento:

| Método | Algoritmo | Uso recomendado |
|---|---|---|
| `calcRoute(origin, destination)` | Dijkstra + pesos dinâmicos | Padrão; reflete congestionamento em tempo real |
| `calcRouteCHAStar(origin, destination)` | CH + A* estático | Alta performance, pesos estáticos |
| `calcRouteCHAStarAdaptive(origin, destination, tick)` | CH + A* adaptativo | Balance entre performance e atualização de pesos |

Todos retornam `Option[(Double, mutable.Queue[(String, String)])]`:
- `Double` = custo total da rota
- `Queue[(linkId, nodeId)]` = sequência de (ID do link, ID do nó destino de cada hop)

**Short-circuit:** se `origin == destination`, retorna `Some((0.0, Queue.empty))` sem cálculo.

### `CityMapUtil`

> Arquivo: [src/main/scala/model/hybrid/util/CityMapUtil.scala](../src/main/scala/model/hybrid/util/CityMapUtil.scala)

Mantém o grafo da cidade em memória com:

- `nodesById: Map[String, NodeGraph]` — lookup O(1) de nós
- `edgeLabelsById: Map[String, EdgeGraph]` — lookup O(1) de links
- `cityMap: Graph[NodeGraph, Double, EdgeGraph]` — grafo completo
- `chIndex` — índice de Contraction Hierarchies pré-computado

### `TransitMapUtil`

> Arquivo: [src/main/scala/model/hybrid/util/TransitMapUtil.scala](../src/main/scala/model/hybrid/util/TransitMapUtil.scala)

Índice leve das paradas de transporte público, separado do grafo viário. Contém apenas nós que
possuem uma parada (ponto de ônibus, estação de metrô), permitindo buscas por proximidade sem
executar Dijkstra na rede completa.

- `stopsById: Map[String, TransitStop]` — lookup O(1) por ID de parada
- `stopsByLine: Map[String, List[TransitStop]]` — todas as paradas de uma linha
- `nearestStops(lat, lon, type, maxDist)` — K paradas mais próximas por tipo
- `haversineM(lat1, lon1, lat2, lon2)` — distância geodésica em metros (fórmula de Haversine)
- `isAvailable: Boolean` — `false` se o arquivo não estiver configurado (degrada graciosamente)

---

## 10. Relatórios emitidos

Person emite eventos de telemetria via `report(data, label)` durante o ciclo de vida:

| Label | Quando emitido |
|---|---|
| `person_walking_start` | Início de caminhada |
| `person_walking_completed` | Chegada após caminhada |
| `person_pt_trip_start` | Embarque em PT |
| `person_pt_trip_completed` | Desembarque do PT |
| `person_trip_completed` | Recepção de `TripCompletedData` (veículo privado) |
| `person_activity_start` | Chegada em nova atividade |
| `person_schedule_complete` | Fim do cronograma diário |

---

## 11. Configuração JSON do Agente

```json
{
  "id": "htcaid:person;person_001",
  "typeActor": "hybrid.actor.Person",
  "data": {
    "dataType": "model.hybrid.entity.state.PersonState",
    "content": {
      "startTick": 0,
      "scheduleOnTimeManager": true,
      "enableDynamicModeChoice": false,
      "modeChoiceWeights": {
        "betaMode": 1.0,
        "betaAccess": 0.001,
        "betaEgress": 0.001,
        "modePrefSubway": 2.0,
        "modePrefBus": 1.0,
        "modePrefWalk": 0.0,
        "maxAccessDistanceM": 1500.0,
        "maxWalkDistanceM": 2000.0
      },
      "ownedVehicles": {
        "car":      { "id": "htcaid:car;car_001",         "classType": "hybrid.actor.Car" },
        "bicycle":  { "id": "htcaid:bicycle;bike_001",    "classType": "hybrid.actor.Bicycle" },
        "motorcycle":{ "id": "htcaid:motorcycle;moto_001","classType": "hybrid.actor.Motorcycle" }
      },
      "dailySchedule": [
        {
          "sequence": 0,
          "activityType": "Home",
          "nodeId": "htcaid:node;60609822",
          "endTime": "28800"
        },
        {
          "sequence": 1,
          "activityType": "Work",
          "nodeId": "htcaid:node;4922987596",
          "endTime": "61200",
          "arrivalLogistics": {
            "mode": "car",
            "vehicle": { "id": "htcaid:car;car_001", "classType": "hybrid.actor.Car" },
            "driverAttributes": {
              "aggressiveness": 0.5,
              "maxSpeedFactor": 1.0,
              "reactionTime": 1.0,
              "minGapFactor": 1.0
            }
          }
        },
        {
          "sequence": 2,
          "activityType": "Home",
          "nodeId": "htcaid:node;60609822",
          "endTime": "86400",
          "arrivalLogistics": {
            "mode": "bus",
            "line": "Bus Line 1",
            "boardingStopId": "htcaid:busstop;busstop_42",
            "boardingStopClassType": "hybrid.actor.BusStop",
            "alightingNodeId": "htcaid:node;60609822"
          }
        }
      ]
    }
  }
}
```

---

## 12. Diagrama de Sequência — Viagem de Carro

```
TM          Person          Car           Link(s)       TM
 │               │              │              │          │
 │─actSpontaneous►│              │              │          │
 │               │─StartTripData►│              │          │
 │               │◄onFinish(None)│              │          │
 │               │              │─registerTM──────────────►│
 │                              │◄─actSpontaneous──────────│
 │                              │─EnterLink───►│           │
 │                              │◄─LeaveLink───│           │
 │                              │─TripCompletedData►       │
 │               │◄TripCompleted─│              │          │
 │               │─advanceActivity              │          │
 │               │─onFinish(Some(endTick))───────────────►│
```

---

## 13. Diagrama de Sequência — Viagem de Ônibus

```
TM          Person        BusStop         Bus           TM(Bus)
 │               │              │              │              │
 │─actSpontaneous►│              │              │              │
 │               │─RegisterPassengerData►│      │              │
 │               │◄onFinish(None)│              │              │
 │                              │◄─arrive───────│              │
 │                              │─loadPassengers►│              │
 │               │◄BusRequestUnload──────────────│              │
 │               │─BusUnloadData(false)──────────►│              │
 │                              │  (nas paradas seguintes...)   │
 │               │◄BusRequestUnload(alightingNode)│              │
 │               │─BusUnloadData(true)───────────►│              │
 │               │─completeTrip / advanceActivity  │              │
 │               │─onFinish(Some(endTick))─────────────────────►│
```

---

## 14. Pontos de Extensão

### Modelo de escolha modal

A escolha modal dinâmica está implementada em `ModeChoiceUtil` usando função de utilidade aditiva
(ver seção 6.6). Para substituir por um modelo mais elaborado (e.g. Nested Logit, Logit
Multinomial completo com variáveis sociodemográficas), implementar a interface em
`ModeChoiceUtil.chooseBestLogistics` — o ponto de integração no `Person` já está preparado.

### Formato de `endTime`

`endTime` atualmente aceita apenas strings numéricas (número de ticks). Para suporte a `"HH:MM"`,
implementar conversão em `isActivityEndTime` e `getTickUntilActivityEnd`.

### Modos não suportados

Modos como `car_passenger`, `carpool`, `e-scooter` podem ser adicionados em `initiateTrip` com
novos `case` na correspondência de padrão.

---

## 15. Referências

- [ARCHITECTURE.md](ARCHITECTURE.md) — Visão geral do sistema
- [API_REFERENCE.md](API.md) — APIs de atores e eventos
- [src/main/scala/model/hybrid/entity/state/PersonState.scala](../src/main/scala/model/hybrid/entity/state/PersonState.scala) — Modelo de estado
- [src/main/scala/model/hybrid/entity/event/data/person/PersonEventData.scala](../src/main/scala/model/hybrid/entity/event/data/person/PersonEventData.scala) — Eventos de Person
- [src/main/scala/model/hybrid/util/GPSUtil.scala](../src/main/scala/model/hybrid/util/GPSUtil.scala) — Roteamento
- [src/main/scala/model/hybrid/util/CityMapUtil.scala](../src/main/scala/model/hybrid/util/CityMapUtil.scala) — Mapa da cidade
- [src/main/scala/model/hybrid/util/TransitMapUtil.scala](../src/main/scala/model/hybrid/util/TransitMapUtil.scala) — Índice de paradas de TP
- [src/main/scala/model/hybrid/util/ModeChoiceUtil.scala](../src/main/scala/model/hybrid/util/ModeChoiceUtil.scala) — Escolha modal por utilidade
- [src/main/scala/model/hybrid/entity/state/model/TransitStop.scala](../src/main/scala/model/hybrid/entity/state/model/TransitStop.scala) — Modelo de parada de TP
