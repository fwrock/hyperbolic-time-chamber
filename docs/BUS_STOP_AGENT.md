# Agente Estação de Ônibus (`BusStop`) — Documentação Técnica

> Pacote: `model.hybrid.actor.BusStop`  
> Arquivos principais:
> - [src/main/scala/model/hybrid/actor/BusStop.scala](../src/main/scala/model/hybrid/actor/BusStop.scala)
> - [src/main/scala/model/hybrid/entity/state/BusStopState.scala](../src/main/scala/model/hybrid/entity/state/BusStopState.scala)

---

## 1. Visão Geral

O `BusStop` é um ator de infraestrutura **puramente reativo** — não possui TimeManager, não age
espontaneamente e não toma iniciativa de comunicação (exceto no registro inicial). Sua única
responsabilidade é **gerenciar a fila de passageiros** por linha de ônibus:

- Enfileira `Person` que chegam aguardando embarque.
- Entrega passageiros ao `Bus` quando este solicita ao parar na parada.

### Comparação rápida com `SubwayStation`

| Característica | `BusStop` | `SubwayStation` |
|---|---|---|
| Cria veículos? | **Não** | Sim (`garage=true`) |
| TimeManager | **Não** | Sim (headway) |
| Complexidade | **Simples** (hub de fila) | Alta (fábrica + hub) |
| Linhas suportadas | Múltiplas (por `label`) | Múltiplas (por `line`) |
| Registro no Node | `RegisterBusStopData` | `RegisterSubwayStationData` |

---

## 2. Estado (`BusStopState`)

> Arquivo: [src/main/scala/model/hybrid/entity/state/BusStopState.scala](../src/main/scala/model/hybrid/entity/state/BusStopState.scala)

```scala
case class BusStopState(
  nodeId: String,                                        // Nó do mapa onde a parada está
  label: String,                                         // Rótulo da parada (ex: "Bus Stop Centro")
  people: mutable.Map[String, mutable.Seq[Identify]]    // label-da-linha → lista de passageiros
    = mutable.Map.empty
) extends BaseState
```

### Campos

| Campo | Tipo | Descrição |
|---|---|---|
| `nodeId` | `String` | ID do `Node` ao qual esta parada pertence |
| `label` | `String` | Nome/identificador da parada (usado no registro com o Node) |
| `people` | `Map[String, Seq[Identify]]` | Fila de passageiros indexada por linha de ônibus |

A chave do mapa `people` é o **label da linha de ônibus** (ex: `"Bus Line 1"`), o mesmo valor
presente em `TransitLeg.line` (`model.hybrid.entity.state.plan`, ver
[PERSON_AGENT.md](PERSON_AGENT.md)) e em `RegisterPassengerData.label`.

---

## 3. Ciclo de Vida

O `BusStop` **não possui ciclo de vida próprio** baseado em ticks. É inicializado uma única vez e
depois age apenas em resposta a mensagens:

```
[Inicialização]
      │
      └── handlePostLoadRegistration()
              └── sendMessageTo(nodeId, RegisterBusStopData(label))
                       └── Node registra esta parada em seu mapa interno

[Em operação — apenas reativo]
      ├── RegisterPassengerData  ──► enfileira Person na linha
      └── BusRequestPassengerData ──► entrega passageiros ao Bus
```

### Registro no Node

No carregamento (`handlePostLoadRegistration`), a parada envia `RegisterBusStopData` ao Node.
A busca pelo Node segue esta ordem de prioridade:

1. Lookup por `IdUtil.format(state.nodeId)` nas dependências configuradas.
2. Lookup por `IdUtil.format(state.nodeId)` nos `relationships`.
3. Scan de `relationships` procurando qualquer entrada com `classType == "hybrid.actor.Node"`.
4. Fallback: envio direto via `state.nodeId` com classType hardcoded (com aviso de log).

---

## 4. Protocolo de Passageiros

### 4.1 Chegada de passageiro (Person → BusStop)

```
Person ──[RegisterPassengerData(label)]──► BusStop
```

Handler `handleRegisterPassenger`:
- Cria `Identify(id, classType, pathRef)` a partir do evento.
- Se já existe fila para `label`: `people(label) = people(label) :+ person`.
- Se não existe: `people(label) = Seq(person)`.
- Emite relatório `bus_stop_passenger_arrived`.

```scala
case class RegisterPassengerData(label: String)  // label = nome da linha
```

### 4.2 Pedido de embarque (Bus → BusStop)

```
Bus ──[BusRequestPassengerData(label, availableSpace)]──► BusStop
BusStop ──[BusLoadPassengerData(people)]──► Bus
```

Handler `handleBusRequestPassenger`:
- Busca a fila `people(label)`.
- Retira até `availableSpace` passageiros com `.take(availableSpace)`.
- Atualiza a fila com `.drop(availableSpace)`.
- Envia `BusLoadPassengerData(people = peopleToLoad)` de volta ao Bus.
- Se não há fila para o label: envia lista vazia.
- Emite relatório `bus_stop_passengers_loaded`.

```scala
case class BusRequestPassengerData(label: String, availableSpace: Int)
```

> **Nota:** o `availableSpace` é calculado pelo `Bus` considerando tanto a capacidade física
> disponível quanto o fluxo máximo permitido pelo dwell time e número de portas (ver
> [BUS_AGENT.md](BUS_AGENT.md)).

---

## 5. Comunicação entre Atores

### Mensagens enviadas pelo `BusStop`

| Mensagem | Destino | Condição |
|---|---|---|
| `RegisterBusStopData(label)` | `Node` | Inicialização — registro da parada no nó |
| `BusLoadPassengerData(people)` | `Bus` | Resposta ao pedido de embarque |

### Mensagens recebidas pelo `BusStop`

| Mensagem | Remetente | Handler |
|---|---|---|
| `RegisterPassengerData(label)` | `Person` | `handleRegisterPassenger` — enfileira passageiro |
| `BusRequestPassengerData(label, space)` | `Bus` | `handleBusRequestPassenger` — entrega passageiros |

---

## 6. Gerenciamento do TimeManager

O `BusStop` **não registra no TimeManager**. Não há `actSpontaneous`, não há ticks, não há
agendamento. O ator existe passivamente durante toda a simulação.

---

## 7. Relatórios emitidos

| Label | Quando emitido | Campos principais |
|---|---|---|
| `bus_stop_passenger_arrived` | Cada `RegisterPassengerData` recebido | `bus_stop_id`, `person_id`, `route_label`, `passengers_waiting`, `tick` |
| `bus_stop_passengers_loaded` | Cada `BusRequestPassengerData` respondido | `bus_stop_id`, `bus_id`, `route_label`, `passengers_loaded`, `available_space`, `passengers_waiting`, `tick` |

---

## 8. Configuração JSON

```json
{
  "id": "htcaid:busstop;busstop_42",
  "typeActor": "hybrid.actor.BusStop",
  "data": {
    "dataType": "model.hybrid.entity.state.BusStopState",
    "content": {
      "nodeId": "htcaid:node;60609822",
      "label": "Parada Centro"
    }
  },
  "dependencies": {
    "htcaid:node;60609822": {
      "id": "htcaid:node;60609822",
      "classType": "hybrid.actor.Node"
    }
  }
}
```

### Referência no plano da Person

Na Person, essa viagem é um `TransitLeg` no plano (ver [PERSON_AGENT.md](PERSON_AGENT.md)):

```json
{
  "kind": "TransitLeg",
  "mode": "Bus",
  "line": "Bus Line 1",
  "boardingStop": {
    "actorId": "htcaid:busstop;busstop_42",
    "actorClassType": "hybrid.actor.BusStop",
    "nodeId": "htcaid:node;300"
  },
  "alightingStop": {
    "actorId": "htcaid:busstop;busstop_87",
    "actorClassType": "hybrid.actor.BusStop",
    "nodeId": "htcaid:node;60609822"
  }
}
```

> O `line` deve coincidir exatamente com o `label` configurado no ônibus (`Bus.state.label`) — é a
> chave que liga Person → BusStop → Bus.

---

## 9. Diagrama de Sequência Completo

```
Person        BusStop          Bus          Node
  │               │               │            │
  │ (inicialização)               │            │
  │               │─RegisterBusStop────────────►│
  │               │               │            │
  │ (Person decide viajar de ônibus)            │
  │─RegisterPassengerData(label)──►│            │
  │               │ enfileira      │            │
  │               │               │            │
  │          (ônibus chega à parada)            │
  │               │◄─BusRequestPassengerData────│
  │               │   (label, availableSpace)   │
  │               │─BusLoadPassengerData(people)►│
  │               │               │            │
  │◄──BusRequestUnloadPassengerData(nodeId)─────│
  │─BusUnloadPassengerData(isArrival=false)─────►│
  │         (nas paradas seguintes...)           │
  │◄──BusRequestUnloadPassengerData(alightNode)──│
  │─BusUnloadPassengerData(isArrival=true)──────►│
  │ advanceToNextActivity()        │            │
```

---

## 10. Relação com os outros atores PT

```
Person
  └──► BusStop (RegisterPassengerData)
              │
              │ fila de espera por linha
              │
Bus ──────────┤ (BusRequestPassengerData)
              │
              └──► Bus (BusLoadPassengerData)
                      │
                      └──► Person (BusRequestUnloadPassengerData)
```

O `Node` sabe quais paradas existem em seus limites e repassa a referência ao `Bus` quando este
entra no nó. Assim, o `Bus` sabe qual `BusStop` consultar sem precisar da referência direta no
JSON de entrada.

---

## 11. Limitações e Pontos de Extensão

| Limitação | Descrição |
|---|---|
| Fila simples FIFO | Sem prioridade (idosos, cadeirantes, etc.) |
| Sem timeout de espera | Passageiros aguardam indefinidamente |
| Sem capacidade máxima da plataforma | Não há limite de pessoas em espera |
| Sem informação de tempo de espera | Passageiro não tem feedback sobre quando o próximo ônibus chega |
| Múltiplas linhas numa parada | Suportado (mapa por label), mas todas compartilham o mesmo `BusStop` |

---

## 12. Referências

- [BUS_AGENT.md](BUS_AGENT.md) — Ator Bus: como solicita passageiros e calcula `availableSpace`
- [PERSON_AGENT.md](PERSON_AGENT.md) — Como Person escolhe a parada e registra embarque
- [SUBWAY_STATION_AGENT.md](SUBWAY_STATION_AGENT.md) — Contrapartida para metrô
- [src/main/scala/model/hybrid/actor/BusStop.scala](../src/main/scala/model/hybrid/actor/BusStop.scala)
- [src/main/scala/model/hybrid/entity/state/BusStopState.scala](../src/main/scala/model/hybrid/entity/state/BusStopState.scala)
