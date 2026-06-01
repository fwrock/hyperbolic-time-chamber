# Agente Terminal de Ônibus (`BusStation`) — Documentação Técnica

> Pacote: `model.hybrid.actor.BusStation`  
> Arquivos principais:
> - [src/main/scala/model/hybrid/actor/BusStation.scala](../src/main/scala/model/hybrid/actor/BusStation.scala)
> - [src/main/scala/model/hybrid/entity/state/BusStationState.scala](../src/main/scala/model/hybrid/entity/state/BusStationState.scala)

---

## 1. Visão Geral

O `BusStation` é o **análogo rodoviário da `SubwayStation`**: uma fábrica de veículos que cria
dinamicamente atores `Bus` em intervalos regulares. Diferentemente do `BusStop` (que apenas
gerencia filas de passageiros), o `BusStation`:

1. **Calcula rotas** automaticamente usando o mapa em memória (`GPSUtil.calcRoute`).
2. **Cria atores `Bus`** a cada `interval` ticks, já equipados com rota completa (ida + volta).
3. **Não gerencia passageiros** — essa responsabilidade é dos `BusStop` ao longo da linha.

### Posição no ecossistema PT

```
BusStation (fábrica)
    │
    ├── calcula rota: BusStop[0]→BusStop[1]→...→BusStop[n]→BusStop[n-1]→...→BusStop[0]
    │
    └── cria dinamicamente ──► Bus (trem em movimento)
                                    │
                                    └── para em BusStop, embarca/desembarca Person
```

### Comparação com `SubwayStation`

| Característica | `BusStation` | `SubwayStation` |
|---|---|---|
| Calcula rotas? | **Sim** (Dijkstra em tempo de execução) | Não (rota pré-fixada no JSON) |
| Rota | **Ida + volta** (circular no grafo viário) | Sequência linear de RailLinks |
| Paradas da rota | `busStops` (mapa ordenado por ID) | `linesRoute` (sequência configurada) |
| Headway | `interval` (único para todos os ônibus) | `interval` por linha |
| Suporte a múltiplas linhas | **Não** (uma linha por `BusStation`) | Sim (múltiplas linhas) |
| Passageiros | **Não gerencia** (delega ao `BusStop`) | Gerencia diretamente |

---

## 2. Estado (`BusStationState`)

> Arquivo: [src/main/scala/model/hybrid/entity/state/BusStationState.scala](../src/main/scala/model/hybrid/entity/state/BusStationState.scala)

```scala
case class BusStationState(
  startTick: Long,
  name: String,
  origin: String,              // ID do nó de origem (ponto de partida dos ônibus)
  destination: String = null,  // ID do nó de destino (último nó da rota)

  // Paradas da linha, preservando ordem de inserção (LinkedHashMap)
  busStops: mutable.LinkedHashMap[String, String],  // busStopId -> nodeId

  interval: Tick,              // Headway: ticks entre criações de ônibus consecutivos

  buses: mutable.Queue[BusInformation],  // Fila de ônibus pré-configurados para criar

  // Rota calculada: subtrecho entre paradas consecutivas
  goingRoute:     Option[mutable.Map[SubRoutePair, mutable.Queue[(Identify, Identify)]]],
  returningRoute: Option[mutable.Map[SubRoutePair, mutable.Queue[(Identify, Identify)]]],

  goingBestCost:     Double = Double.MaxValue,
  returningBestCost: Double = Double.MaxValue,

  var status: BusStationStateEnum = Start
)
```

### Estruturas de dados aninhadas

#### `BusInformation` (especificação do ônibus)
```scala
case class BusInformation(
  actorId: String,      // ID futuro do ator Bus
  label: String,        // Rótulo da linha (ex: "Bus Line 1")
  capacity: Int,        // Capacidade de passageiros
  numberOfPorts: Int,   // Número de portas (afeta dwell time)
  size: Double,         // Comprimento do veículo em metros
  speedFactor: Double = 1.0  // Fator de velocidade desejada no modo MICRO [0.5, 1.5]
)
```

> **Atenção (compatibilidade):** O campo `speedFactor` possui valor default `1.0`. Arquivos JSON
> de configuração existentes **não precisam ser alterados** — o campo é opcional. Quando omitido,
> o ônibus opera com a `desiredVelocity` padrão (40 km/h no modo MICRO).
> Para variar o comportamento, inclua `"speedFactor": <valor>` em cada entrada de `buses`.
> O valor é **limitado internamente** ao intervalo `[0.5, 1.5]`; valores fora desse range são
> descartados silenciosamente com log de aviso.

#### `SubRoutePair` (chave do mapa de rotas)
```scala
case class SubRoutePair(origin: String, destination: String)
// Representa o trecho entre duas paradas consecutivas
// Ex: SubRoutePair("htcaid:busstop;01", "htcaid:busstop;02")
```

A rota é armazenada como mapa de subtrechos — cada par `(busStop[i], busStop[i+1])` tem sua
própria fila de links calculada pelo Dijkstra. Isso permite reuso eficiente caso múltiplos ônibus
precisem da mesma rota.

---

## 3. Ciclo de Vida e FSM

```
[Start]
   │
   └── calculateRoutesFromMap()
            │
            ├── calcula goingRoute: [0→1, 1→2, ..., n-1→n]
            ├── calcula returningRoute: [n→n-1, ..., 1→0]
            └── isCalculateRoutingComplete?
                    │
                    ├── SIM ──► cria 1º Bus, status=Working
                    │           onFinishSpontaneous(currentTick + interval)
                    └── NÃO ──► status=WorkingWithOutBus
                                onFinishSpontaneous(None)

[Working]  (a cada interval ticks)
   │
   ├── buses.nonEmpty? ──► cria próximo Bus
   │                        onFinishSpontaneous(currentTick + interval)
   └── buses.vazio? ──► status=WorkingWithOutBus
                         onFinishSpontaneous(currentTick + interval)

[WorkingWithOutBus]  (continua agendado mas sem criar ônibus)

[Finish]  onDestruct → para permanentemente
```

### Estados do FSM

| Estado | Descrição |
|---|---|
| `Start` | Estado inicial; dispara cálculo de rotas |
| `RouteWaiting` | Aguardando rotas (legado; hoje o cálculo é síncrono) |
| `Ready` | Rotas calculadas; pronto para criar ônibus |
| `Working` | Criando ônibus periodicamente |
| `WorkingWithOutBus` | Sem ônibus na fila; continua agendado — recebe ticks do TM mas não cria ônibus. Emite log de diagnóstico (sem `logWarn`) a cada ciclo. |
| `Finish` | Destruído ao fim da simulação |

---

## 4. Cálculo de Rotas (`calculateRoutesFromMap`)

O cálculo é **síncrono** e feito em memória com `GPSUtil.calcRoute` (Dijkstra com pesos dinâmicos).

### Ordem das paradas

As paradas em `busStops` são ordenadas pelo sufixo numérico do ID:

```scala
def orderedBusStopIds: List[String] =
  state.busStops.keys.toList.sortBy { id =>
    val digits = id.reverse.takeWhile(_.isDigit).reverse
    if (digits.nonEmpty) (0L, digits.toLong, id)
    else (1L, Long.MaxValue, id)
  }
```

Exemplo: `["busstop;3", "busstop;1", "busstop;2"]` → ordenado como `["busstop;1", "busstop;2", "busstop;3"]`.

### Rota de ida

Percorre as paradas em ordem crescente, calculando Dijkstra entre cada par consecutivo:

```
goingRoute[SubRoutePair(stop[0], stop[1])] = Queue[(link, node), ...]
goingRoute[SubRoutePair(stop[1], stop[2])] = Queue[(link, node), ...]
...
goingRoute[SubRoutePair(stop[n-1], stop[n])] = Queue[(link, node), ...]
```

### Rota de volta

Idem, mas em ordem reversa:

```
returningRoute[SubRoutePair(stop[n], stop[n-1])] = ...
...
returningRoute[SubRoutePair(stop[1], stop[0])] = ...
```

### Validação

O cálculo é considerado completo quando o número de entradas no mapa == `busStops.size - 1`:

```scala
val expectedSize = state.busStops.size - 1
actualSize == expectedSize
```

Se qualquer subtrecho falhar, a rota fica incompleta e o `BusStation` entra em `WorkingWithOutBus`.

---

## 5. Montagem da Rota Completa do Ônibus (`calcBusBestRoute`)

Ao criar cada ônibus, a rota completa é montada concatenando todos os subtrechos:

```
bestRoute = goingRoute[stop0→stop1] ++ goingRoute[stop1→stop2] ++ ...
         ++ returningRoute[stopN→stopN-1] ++ ... ++ returningRoute[stop1→stop0]
```

Resultado: `Queue[(linkId, nodeId)]` — exatamente o formato esperado por `BusState.bestRoute`.
O ônibus percorre a rota **circular** inteiramente via `getNextPath` com índice circular.

---

## 6. Criação Dinâmica de Ônibus (`createBus`)

Para cada ônibus criado:

1. Chama `calcBusBestRoute()` — monta rota completa de ida + volta.
2. Instancia `BusState` com:
   - `busStops` (mapa de paradas da linha — ônibus sabe onde parar)
   - `bestRoute` (rota completa pré-calculada)
   - `storedBestRoute` (cópia de segurança para serialização JSON)
   - `startTick = currentTick + 1`
3. Cria o ator via `createShardedActorSeveralArgs`.
4. Registra o ônibus em `dependencies`.
5. Agenda próximo despacho: `onFinishSpontaneous(Some(currentTick + interval))`.

Se a criação falhar (rota vazia), o ônibus é **descartado permanentemente** (não reenfileirado —
diferente da `SubwayStation` que reenfileira). O próximo ônibus da fila será tentado no próximo
tick.

---

## 7. Comunicação entre Atores

### Mensagens enviadas pelo `BusStation`

| Mensagem | Destino | Condição |
|---|---|---|
| *(cria ator)* | `Bus` | A cada `interval` ticks, enquanto `buses.nonEmpty` |

O `BusStation` **não envia mensagens de texto** a outros atores em operação normal — apenas cria
ônibus via sistema de sharding.

### Mensagens recebidas pelo `BusStation`

O `BusStation` **não trata nenhuma mensagem** de interação (`actInteractWith` cai no wildcard de
`logWarn`). Toda comunicação de passageiros é gerenciada pelos `BusStop`.

---

## 8. Gerenciamento do TimeManager

| Situação | Ação |
|---|---|
| Após criar ônibus | `onFinishSpontaneous(Some(currentTick + interval))` |
| Sem ônibus na fila | `onFinishSpontaneous(Some(currentTick + interval))` (continua agendado) |
| Rota incompleta | `onFinishSpontaneous(None)` — desregistra permanentemente |
| Fim da simulação | `onFinishSpontaneous(None)` |

---

## 9. Relatórios emitidos

| Label | Quando emitido | Campos principais |
|---|---|---|
| `bus_created` | Cada criação de ônibus | `station_id`, `bus_id`, `capacity`, `route_length`, `number_of_ports`, `label`, `start_tick`, `tick` |

---

## 10. Configuração JSON

```json
{
  "id": "htcaid:busstation;terminal_norte",
  "typeActor": "hybrid.actor.BusStation",
  "data": {
    "dataType": "model.hybrid.entity.state.BusStationState",
    "content": {
      "startTick": 0,
      "name": "Terminal Norte",
      "origin": "htcaid:node;node_terminal_norte",
      "destination": "htcaid:node;node_terminal_sul",
      "interval": 600,
      "busStops": {
        "htcaid:busstop;stop_01": "htcaid:node;node_stop_01",
        "htcaid:busstop;stop_02": "htcaid:node;node_stop_02",
        "htcaid:busstop;stop_03": "htcaid:node;node_stop_03"
      },
      "buses": [
        {
          "actorId": "htcaid:bus;bus_L1_001",
          "label": "Bus Line 1",
          "capacity": 80,
          "numberOfPorts": 2,
          "size": 12.0,
          "speedFactor": 1.0
        },
        {
          "actorId": "htcaid:bus;bus_L1_002",
          "label": "Bus Line 1",
          "capacity": 80,
          "numberOfPorts": 2,
          "size": 12.0,
          "speedFactor": 0.9
        }
      ]
    }
  }
}
```

> **Importante:** as chaves do mapa `busStops` devem ter sufixos numéricos sequenciais para garantir
> a ordem correta das paradas na rota (ex: `stop_01`, `stop_02`, `stop_03`).

---

## 11. Diagrama de Sequência — Inicialização e Despacho

```
TM           BusStation       GPSUtil          Bus
  │                │               │              │
  │─actSpontaneous─►│               │              │
  │                │─calcRoute(0→1)─►│              │
  │                │◄────────────────│              │
  │                │─calcRoute(1→2)─►│              │
  │                │◄────────────────│              │
  │                │─calcRoute(2→1)─►│  (volta)     │
  │                │◄────────────────│              │
  │                │─calcRoute(1→0)─►│  (volta)     │
  │                │◄────────────────│              │
  │                │─createBus(bus_001)────────────►│ (ator criado)
  │                │─onFinish(+interval)             │
  │                │               │              │
  │─actSpontaneous─►│               │              │
  │                │─createBus(bus_002)────────────►│ (ator criado)
  │                │─onFinish(+interval)             │
```

---

## 12. Diferenças entre `BusStation`, `BusStop` e `SubwayStation`

| | `BusStation` | `BusStop` | `SubwayStation` |
|---|---|---|---|
| **Função** | Fábrica de ônibus | Hub de passageiros | Fábrica de trens + hub |
| **TimeManager** | Sim (headway) | Não | Sim (headway) |
| **Calcula rota?** | Sim (Dijkstra) | Não | Não (rota pré-fixada) |
| **Cria veículos?** | Sim | Não | Sim (se `garage=true`) |
| **Gerencia passageiros?** | Não | Sim | Sim |
| **Múltiplas linhas?** | Não | Sim (por `label`) | Sim (por `line`) |
| **Rota** | Ida + volta circular | — | Sequência linear |

---

## 13. Limitações e Pontos de Extensão

| Limitação | Descrição |
|---|---|
| Uma linha por `BusStation` | Não suporta múltiplas linhas no mesmo terminal |
| Ordem por sufixo numérico | Paradas sem sufixo numérico têm ordem não garantida |
| Falha de ônibus descarta | Ônibus com rota inválida são descartados (não reenfileirados); emite log com segmentos faltantes para diagnóstico |
| Sem passageiros no terminal | Passageiros que partem do terminal devem usar um `BusStop` no mesmo nó |
| Rota calculada uma única vez | Pesos dinâmicos do grafo são capturados apenas na inicialização |

---

## 14. Referências

- [BUS_AGENT.md](BUS_AGENT.md) — O ator `Bus` criado por esta fábrica
- [BUS_STOP_AGENT.md](BUS_STOP_AGENT.md) — Paradas ao longo da linha
- [SUBWAY_STATION_AGENT.md](SUBWAY_STATION_AGENT.md) — Contrapartida ferroviária
- [PERSON_AGENT.md](PERSON_AGENT.md) — Como `Person` usa ônibus
- [src/main/scala/model/hybrid/actor/BusStation.scala](../src/main/scala/model/hybrid/actor/BusStation.scala)
- [src/main/scala/model/hybrid/entity/state/BusStationState.scala](../src/main/scala/model/hybrid/entity/state/BusStationState.scala)
- [src/main/scala/model/hybrid/entity/state/model/BusInformation.scala](../src/main/scala/model/hybrid/entity/state/model/BusInformation.scala)

### Parâmetros de veículo e car-following (MICRO)

> Todos os parâmetros padrão de `MicroBusState` são propagados via `BusInformation.speedFactor`
> → `BusState.speedFactor`. Ver [BUS_AGENT.md](BUS_AGENT.md) Seção 15 para referências completas.

- **Krajzewicz, D., Erdmann, J., Behrisch, M. & Bieker, L. (2012).** "Recent Development and
  Applications of SUMO — Simulation of Urban MObility." *International Journal On Advances in
  Systems and Measurements*, 5(3&4), 128–138. — Defaults do tipo de veículo `bus`: `length=12m`,
  `accel=1.2 m/s²`, `speedFactor` (distribuição).
- **ABNT NBR 15570:2011.** *Transporte — Especificações técnicas para fabricação de veículos de
  características urbanas para transporte coletivo de passageiros.* — Comprimento mínimo de
  ônibus urbano simples: 12,0 m.
- **Bureau of Public Roads (BPR). (1964).** *Traffic Assignment Manual.* US Department of
  Commerce. — Modelo de velocidade utilizado pelo `Bus` nos links viários (MESO).
- **Vuchic, V. R. (2005).** *Urban Transit: Operations, Planning and Economics.* Wiley. —
  Parâmetros operacionais de ônibus urbano (headway, capacidade, dwell time).
