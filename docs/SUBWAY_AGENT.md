# Agente Trem de Metrô (`Subway`) — Documentação Técnica

> Pacote: `model.hybrid.actor.Subway`  
> Arquivos principais:
> - [src/main/scala/model/hybrid/actor/Subway.scala](../src/main/scala/model/hybrid/actor/Subway.scala)
> - [src/main/scala/model/hybrid/entity/state/SubwayState.scala](../src/main/scala/model/hybrid/entity/state/SubwayState.scala)
> - [src/main/scala/model/hybrid/util/SubwayUtil.scala](../src/main/scala/model/hybrid/util/SubwayUtil.scala)

---

## 1. Visão Geral

O `Subway` é o trem de metrô — um ator móvel que percorre **rotas ferroviárias fixas** (RailLinks),
parando em estações para embarcar e desembarcar passageiros. Suas características fundamentais:

- **Rota pré-fixada**: não executa Dijkstra. A rota (sequência de RailLink IDs) é fornecida pela
  `SubwayStation` no momento da criação.
- **Infraestrutura exclusiva**: usa `RailLink`, não links viários. Somente o tipo `Subway` pode
  entrar num RailLink.
- **Velocidade constante**: sem congestionamento, sem SpeedUtil. O tempo entre estações depende
  apenas do comprimento do trecho e da velocidade configurada.
- **Rota circular**: ao atingir o fim da rota, `currentPathPosition` volta a 0 (reinicia).
- **Criado dinamicamente**: instanciado pela `SubwayStation` em tempo de simulação.

---

## 2. Hierarquia de Classes

```
SimulationBaseActor[T]
    └── Movable[T]           ← lógica de enterLink/leavingLink/route
            └── Subway       ← lógica de estação (embarque/desembarque)
```

`Movable[SubwayState]` fornece:
- `enterLink()` — envia `EnterLinkData` ao RailLink atual.
- `leavingLink()` — envia `LeaveLinkData` ao RailLink atual.
- `actHandleReceiveEnterLinkInfo` / `actHandleReceiveLeaveLinkInfo` — hooks sobrescritos pelo `Subway`.
- `getNextPath` — abstract; implementado pelo `Subway` com lógica circular.

---

## 3. Estado (`SubwayState`)

> Arquivo: [src/main/scala/model/hybrid/entity/state/SubwayState.scala](../src/main/scala/model/hybrid/entity/state/SubwayState.scala)

```scala
case class SubwayState(
  startTick: Tick,
  capacity: Int,            // Capacidade máxima de passageiros
  numberOfPorts: Int,       // Número de portas (influencia fluxo de embarque)
  velocity: Double,         // Velocidade (verificar unidade: km/h vs m/s — ver Seção 7)
  stopTime: Tick,           // Dwell time fixo em cada estação (ticks)
  boardingTimeByPassenger: Double = 1.5,  // Ticks por passageiro no embarque

  subwayStations: mutable.Map[String, String],  // stationId → nodeId (mapa de paradas da linha)
  passengers: mutable.Map[String, Identify],    // passageiros a bordo: personId → Identify

  nodeState: SubwayNodeState,    // flags de sincronização de parada (isLoaded, isUnloaded)
  var currentPathPosition: Int,  // índice atual na rota (incrementado a cada trecho)

  var distance: Double,          // distância total percorrida (m)
  var countUnloadPassenger: Int, // passageiros que desembarcaram nesta parada
  var countUnloadReceived: Int,  // respostas de desembarque recebidas nesta parada

  line: String,                  // rótulo da linha (ex: "Linha 1")
  origin: String,                // nó de origem (ponto de criação)
  destination: String            // nó final da rota
)
```

### `SubwayNodeState` — sincronização de parada

```scala
case class SubwayNodeState(
  var isLoaded: Boolean = false,    // embarque concluído?
  var isUnloaded: Boolean = false   // desembarque concluído?
)
```

Este par de flags controla a **sincronização dual** entre embarque e desembarque que ocorrem em paralelo.

---

## 4. Ciclo de Vida e FSM

```
[Start]
   │  status = Ready
   └── enterLink()

[Ready]
   └── enterLink()  ──► envia EnterLinkData ao RailLink
                            │
                       RailLink responde com LinkInfoData
                            │
                       actHandleReceiveEnterLinkInfo()
                            │  t = ceil((length / velocity) × 3600)
                            │  status = Moving
                       onFinishSpontaneous(Some(currentTick + t))

[Moving]  (acorda no tick de chegada)
   │
   ├── há estação no nó atual?
   │      │
   │      ├── SIM ──► status = Stopped
   │      │           requestUnloadPeopleData()  ──► todos os passageiros
   │      │           requestLoadPassenger()     ──► SubwayStation
   │      │           onFinishSpontaneous(None)  ← cede TM
   │      │
   │      └── NÃO ──► leavingLink()  ──► envia LeaveLinkData ao RailLink
   │                       │
   │                  RailLink responde com LinkInfoData
   │                       │
   │                  actHandleReceiveLeaveLinkInfo()
   │                       │  status = Ready
   │                  onFinishSpontaneous(Some(currentTick + 1))

[Stopped]  (acorda após scheduleEvent(currentTick + stopTime))
   └── leavingLink()  ──► avança para o próximo RailLink
```

### Transição de parada

O trem passa para `Stopped` e **cede o TM** imediatamente. Duas operações assíncronas correm em
paralelo: desembarque (n mensagens para n passageiros) e embarque (1 mensagem para a estação).
Só quando **ambas completam** (`isLoaded && isUnloaded`) o trem agenda o próximo tick:

```scala
private def onFinishNodeState(): Unit =
  if (isEndNodeState) {
    state.nodeState.isLoaded = false
    state.nodeState.isUnloaded = false
    scheduleEvent(currentTick + state.stopTime)  // dwell time fixo
  }
```

---

## 5. Algoritmo de Tempo de Viagem

### Fórmula

$$t_{trecho} = \left\lceil \frac{d}{v} \times 3600 \right\rceil$$

onde:
- $d$ = comprimento do RailLink (metros), recebido em `LinkInfoData.linkLength`
- $v$ = `state.velocity` — **atenção à unidade** (ver Seção 7)
- $3600$ = fator de conversão de horas para segundos
- 1 tick = 1 segundo

```scala
// SubwayUtil.scala
def timeToNextStation(distance: Double, velocity: Double): Tick =
  Math.ceil((distance / velocity) * 3600).toLong
```

---

## 6. Protocolo de Parada (Embarque + Desembarque)

### 6.1 Detecção de estação

O trem identifica se está em uma estação comparando o nó atual com o mapa `subwayStations`:

```scala
private def retrieveSubwayStationFromNodeId(value: String): Option[String] =
  state.subwayStations.find { case (_, v) => v == value }.map(_._1)
// Retorna Some(stationId) se o nó atual é uma estação, None caso contrário
```

### 6.2 Desembarque

```
Subway ──[SubwayRequestUnloadPassengerData(nodeId, nodeRef)]──► cada Person a bordo
Person ──[SubwayUnloadPassengerData(isArrival)]──► Subway
```

- Enviado a **todos** os passageiros simultaneamente.
- `isArrival = true` se `nodeId == alightingNodeId` do `PTWaitState` da Person (ver
  [PERSON_AGENT.md](PERSON_AGENT.md) — antigo `ptAlightingNodeId`, hoje carregado em
  `TripExecutionState.Traveling.ptWait.alightingNodeId`).
- Trem remove passageiros que alightam de `state.passengers`.
- Contagem: `countUnloadReceived` acumula respostas; quando `countUnloadReceived >= countUnloadPassenger + passengers.remaining`, o desembarque está completo.

**Caso especial:** sem passageiros → `isUnloaded = true` imediatamente (sem enviar mensagens).

### 6.3 Embarque

```
Subway ──[SubwayRequestPassengerData(line, availableSpace)]──► SubwayStation
SubwayStation ──[SubwayLoadPassengerData(people)]──► Subway
```

Vagas disponíveis = mínimo entre vagas físicas e vagas de fluxo:

$$\text{availableSpace} = \min\!\left(\text{capacity} - |\text{passengers}|,\ \left\lceil n_{portas} \times C_{porta} \times \frac{t_{stop}}{t_{boarding}} \right\rceil\right)$$

```scala
// SubwayUtil.scala
def numberOfPassengerToBoarding(
  numberOfPorts: Int,
  portsCapacity: Int,
  stopTime: Tick,
  boardingTimeByPassenger: Double
): Int = Math.ceil(numberOfPorts * portsCapacity * (stopTime / boardingTimeByPassenger)).toInt
```

**Caso especial:** sem estação no nó atual → `isLoaded = true` imediatamente.

### 6.4 Diagrama de sincronização

```
actSpontaneous (Moving, chegou em estação)
    │
    ├── requestUnloadPeopleData()  ──► Person1, Person2, ...  (paralelo)
    └── requestLoadPassenger()     ──► SubwayStation          (paralelo)
    └── onFinishSpontaneous(None)  ← TM cedido

    ... respostas chegam em ordem arbitrária ...

    handleUnloadPassenger() ──► countUnloadReceived++
                                se completo: isUnloaded = true
                                onFinishNodeState()

    handleBusLoadPeople()   ──► isLoaded = true
                                onFinishNodeState()

    onFinishNodeState() (chamado 2x, avança quando isLoaded && isUnloaded)
        └── scheduleEvent(currentTick + stopTime)  ← re-registra no TM
```

---

## 7. Rota Circular (`getNextPath`)

```scala
override def getNextPath: Option[(String, String)] =
  state.bestRoute match
    case Some(routePath) =>
      if state.currentPathPosition < routePath.size then
        val nextPath = routePath(state.currentPathPosition)
        state.currentPathPosition += 1
        Some(nextPath)
      else
        state.currentPathPosition = 0          // ← reinício circular
        Some(routePath(state.currentPathPosition))
    case None => None
```

A rota nunca se esgota: ao atingir o último RailLink, `currentPathPosition` volta a 0 e o trem
repete o ciclo indefinidamente. **Não há destruição** do ator Subway no modelo atual.

---

## 8. Unidade de Velocidade — Ponto de Atenção

O campo `velocity` em `SubwayState` e a fórmula em `SubwayUtil` assumem implicitamente que:

$$t = \left\lceil \frac{d[\text{m}]}{v} \times 3600 \right\rceil$$

Se $v$ está em **km/h**, a fórmula produz:

$$\frac{d[\text{m}]}{v[\text{km/h}]} \times 3600 = \frac{d}{v} \times 3600\ \text{s/h} = d \times \frac{3600}{v}\ \text{s}$$

Exemplo correto: $d = 1000\ \text{m}$, $v = 80\ \text{km/h}$:
$$t = \lceil 1000 / 80 \times 3600 \rceil = \lceil 45000 \rceil = 45000\ \text{ticks} \approx 12.5\ \text{horas}$$

Isso é claramente errado para 1 km. A fórmula só faz sentido se $v$ estiver em **m/s**:
$$v = 80\ \text{km/h} = 22.2\ \text{m/s} \Rightarrow t = \lceil 1000 / 22.2 \times 3600 \rceil$$

Isso também fica enorme. O correto para m/s seria simplesmente $t = \lceil d/v \rceil$ (sem o ×3600).

> **Conclusão:** a fórmula `(distance / velocity) * 3600` é consistente se — e somente se —
> `velocity` estiver em **km/h** e a unidade de `distance` for **km** (não metros). Verificar
> se `LinkInfoData.linkLength` é fornecido em km ou metros, e ajustar o campo `velocity` no JSON
> de configuração de acordo.

---

## 9. Comunicação entre Atores

### Mensagens enviadas pelo `Subway`

| Mensagem | Destino | Condição |
|---|---|---|
| `EnterLinkData` | `RailLink` | Ao iniciar travessia de um trecho |
| `LeaveLinkData` | `RailLink` | Ao concluir travessia |
| `SubwayRequestUnloadPassengerData(nodeId)` | cada `Person` a bordo | Ao chegar em estação |
| `SubwayRequestPassengerData(line, space)` | `SubwayStation` | Ao chegar em estação |

### Mensagens recebidas pelo `Subway`

| Mensagem | Remetente | Handler |
|---|---|---|
| `LinkInfoData` (enter) | `RailLink` | `actHandleReceiveEnterLinkInfo` — calcula tempo de viagem |
| `LinkInfoData` (leave) | `RailLink` | `actHandleReceiveLeaveLinkInfo` — acumula distância |
| `SubwayLoadPassengerData(people)` | `SubwayStation` | `handleBusLoadPeople` — embarca passageiros |
| `SubwayUnloadPassengerData(isArrival)` | `Person` | `handleUnloadPassenger` — processa desembarque |

---

## 10. Gerenciamento do TimeManager

| Situação | Ação |
|---|---|
| Após `enterLink()` | `onFinishSpontaneous(Some(currentTick + 1))` — aguarda LinkInfoData |
| Recebeu LinkInfoData (enter) | `onFinishSpontaneous(Some(currentTick + t))` — dorme durante a viagem |
| Após `leavingLink()` | `onFinishSpontaneous(Some(currentTick + 1))` — aguarda próximo enterLink |
| Aguardando load + unload | `onFinishSpontaneous(None)` — cede TM |
| Ambos completos | `scheduleEvent(currentTick + stopTime)` — re-registra após dwell time |

---

## 11. Parâmetros de Configuração

| Parâmetro | Tipo | Descrição |
|---|---|---|
| `capacity` | `Int` | Capacidade máxima (passageiros) |
| `numberOfPorts` | `Int` | Número de portas bilaterais |
| `velocity` | `Double` | Velocidade de cruzeiro — verificar unidade (km/h ou m/s) |
| `stopTime` | `Tick` | Dwell time fixo em cada estação (ticks = segundos) |
| `boardingTimeByPassenger` | `Double` | Ticks por passageiro ao embarcar (padrão: 1.5) |
| `line` | `String` | Rótulo da linha — deve coincidir com o da `SubwayStation` |

---

## 12. Comparação com `Bus`

| Característica | `Bus` | `Subway` |
|---|---|---|
| Roteamento | Dijkstra (rota dinâmica) | **Rota pré-fixada** (RailLinks) |
| Infraestrutura | Links viários | **RailLinks** exclusivos |
| Congestionamento | Afetado (SpeedUtil BPR) | **Não** — velocidade constante |
| Criação | JSON de entrada | **Criado dinamicamente** por `SubwayStation` |
| Passageiros | Gerenciado pelo `BusStop` | Gerenciado pela **`SubwayStation`** |
| Circular | Sim (índice circular) | Sim (índice circular) |
| Destruição | Ao atingir destino | **Nunca** (rota circular infinita) |
| Velocidade | m/s (SpeedUtil) | km/h (verificar) |

---

## 13. Diagrama de Sequência — Trecho Completo (Estação A → Trecho → Estação B)

```
TM         Subway       RailLink     Person1    SubwayStation(B)
  │              │            │           │              │
  │─actSp(Ready)─►│            │           │              │
  │              │─EnterLink──►│           │              │
  │              │◄─LinkInfoData(enter)────│              │
  │              │  t = ceil(d/v × 3600)  │              │
  │─actSp(Moving)─(tick+t)                │              │
  │              │  ← chegou em B         │              │
  │              │─UnloadReq──────────────►│              │
  │              │─RequestPassenger(space)─────────────►│
  │              │◄UnloadPassengerData(true)────────────│
  │              │◄LoadPassengerData(people)────────────│
  │              │  isLoaded && isUnloaded               │
  │              │  scheduleEvent(+stopTime)             │
  │─actSp(Stopped)►│           │           │              │
  │              │─LeaveLink──►│           │              │
  │              │◄─LinkInfoData(leave)                  │
  │              │  distance += length                   │
  │              │  status = Ready                       │
  │─actSp(Ready)─►│  (próximo RailLink)   │              │
```

---

## 14. Limitações

| Limitação | Descrição |
|---|---|
| Sem destruição | Trem circula infinitamente; sem modelagem de fim de serviço ou retorno à garagem |
| `stopTime` fixo | Dwell time igual em todas as estações, independente da demanda |
| Velocidade constante | Sem aceleração, frenagem, curvas ou gradientes |
| Unidade de velocidade ambígua | Ver Seção 8 — risco de erro de configuração |
| Sem falhas operacionais | Sem modelagem de atraso, pane, manutenção |

---

## 15. Referências

- [SUBWAY_STATION_AGENT.md](SUBWAY_STATION_AGENT.md) — Fábrica que cria o `Subway`
- [PERSON_AGENT.md](PERSON_AGENT.md) — Perspectiva do passageiro (modo `subway`)
- [BUS_AGENT.md](BUS_AGENT.md) — Comparação com ônibus
- [src/main/scala/model/hybrid/actor/Subway.scala](../src/main/scala/model/hybrid/actor/Subway.scala)
- [src/main/scala/model/hybrid/entity/state/SubwayState.scala](../src/main/scala/model/hybrid/entity/state/SubwayState.scala)
- [src/main/scala/model/hybrid/util/SubwayUtil.scala](../src/main/scala/model/hybrid/util/SubwayUtil.scala)
- **Vuchic, V. R. (2005).** *Urban Transit: Operations, Planning and Economics.* Wiley. — Modelagem de metrô: capacidade, headway, dwell time.
- **Daganzo, C. F. (1997).** *Fundamentals of Transportation and Traffic Operations.* Pergamon. — Movimento de veículos em infraestrutura guiada.
