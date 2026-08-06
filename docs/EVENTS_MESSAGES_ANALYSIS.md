# Análise de Eventos/Mensagens entre Atores — Serialização, Tamanho e Oportunidades de Otimização

Auditoria de todas as mensagens trocadas entre atores Pekko no HTC: catálogo, arquitetura de
serialização efetiva (o que o código realmente faz, não o que o `CLAUDE.md` descreve), payload
útil vs. redundante, e recomendações priorizadas. `model/mobility/entity/event/*` foi excluído do
escopo de otimização — é código morto (ver `docs/KNOWN_GAPS.md`, pacote `actor` já removido; só os
tipos de dados sobrevivem, consumidos por `JsonUtil`/`ClickHouseReportData`).

## 1. Descoberta central: a stack de serialização real diverge do documentado

O `CLAUDE.md` descreve "Kryo (default), Protobuf (control plane), Jackson (JSON state)". Na
prática:

- **Kryo não é usado para nenhuma mensagem de ator.** A dependência `pekko-kryo-serialization`
  existe no `build.sbt`, mas **não há entrada em `pekko.actor.serializers` nem em
  `serialization-bindings` que a registre**. O único uso de Kryo no projeto é
  `model/mobility/util/RouteCacheSerializer.scala`, que serializa uma **cache local em disco**
  (fora do Pekko), não mensagens entre atores.
- **Todo payload de evento de simulação (`BaseEventData`) cai em Jackson-CBOR** via um binding de
  superclasse:
  ```
  "org.interscity.htc.core.entity.event.data.BaseEventData" = jackson-cbor
  ```
  (`application.conf:61`). Isso inclui **toda a família de mensagens do modelo hybrid** —
  `EnterLinkData`, `LeaveLinkData`, `LinkInfoData`, `RequestRouteData`, `ReceiveRouteData`,
  `ForwardRouteData`, `LinkAccessData`, `TrafficSignalChangeStatusData`, todos os dados de
  bus/subway/vehicle/link — **nenhuma delas tem binding explícito**, só as ~15 classes MICRO e
  `person` que foram adicionadas depois (redundantes, já que herdam o mesmo binding de
  `BaseEventData`; o Pekko resolve por MRO de qualquer forma).
- Ou seja: **as mensagens de maior frequência do simulador (entrada/saída de link, toda
  atualização MICRO, toda requisição de rota) usam o serializador reflexivo mais caro
  configurado no sistema**, enquanto o serializador mais eficiente disponível na classpath
  (Kryo) fica sem uso algum.

## 2. Arquitetura de envelope: três camadas aninhadas por mensagem remota

Toda mensagem que cruza um shard (`sendMessageTo` → `sendMessageToShard`,
`SimulationBaseActor.scala:449`) passa por três serializações encadeadas:

```
EntityEnvelopeEvent(entityId, ActorInteractionEvent(...))
  └─ EntityEnvelopeSerializer          → protobuf EntityEnvelope
       { entityId, payload: bytes, payload_serializer_id, payload_manifest }
       payload =
  └─ ActorInteractionSerializer        → protobuf ActorInteraction
       { tick, lamportTick, actorRefId, shardRefId, actorRef, actorClassType,
         eventType, data: bytes, payload_serializer_id, payload_manifest,
         actorType, resourceId }
       data =
  └─ Jackson-CBOR (via BaseEventData binding) → payload real (EnterLinkData, LinkInfoData, ...)
```

Cada camada grava seu próprio `payload_manifest` — no caso do CBOR, o **nome de classe totalmente
qualificado** (`org.interscity.htc.model.hybrid.entity.event.data.link.LinkInfoData`) é escrito
**em toda mensagem**, além dos nomes de campo (CBOR usa mapas com chaves string, não é
binário posicional como Kryo/Protobuf).

Custo fixo por mensagem, antes de qualquer byte de dado útil:
- 2 frames de protobuf (tags + comprimentos)
- `entityId`, `actorRefId`, `shardRefId`, `actorRef` (nome do path do ator remoto, ex.
  `pekko://hyperbolic-time-chamber@10.0.1.4:1600/system/sharding/...`), `actorClassType`,
  `actorType`, `resourceId`, `eventType` — todos como string, **nenhum interning ajuda aqui**:
  `StringPool.intern` deduplica objetos na heap da JVM após deserializar, mas os bytes na rede/disco
  são escritos por extenso a cada mensagem, sempre.
- manifest de classe completo do payload (CBOR) — dezenas de bytes por mensagem, repetido
  milhões de vezes ao longo de uma simulação.

## 3. Risco de correção, não só de tamanho: `Identify` (protobuf) aninhado em payload Jackson

`RequestRouteData`, `ForwardRouteData` e `ReceiveRouteData` carregam
`path: mutable.Queue[(Identify, Identify)]`, onde `Identify` é uma classe **gerada pelo scalapb**
(`scalapb.GeneratedMessage`, com binding próprio `= proto` em `application.conf:50`). Esse binding
só se aplica quando o Pekko despacha `Identify` como mensagem de nível superior. Quando `Identify`
aparece **aninhado dentro de um campo** de uma classe serializada via Jackson (como aqui), o
Jackson não delega para o protobuf — ele reflete sobre os campos internos do `Identify` gerado
(incluindo estruturas internas do scalapb como `unknownFields`, índices de memoização, etc.),
o que é mais caro e potencialmente frágil a mudanças na geração de código do scalapb. Isso é um
mistura de dois mundos de serialização que não deveria acontecer — **route data deveria expor um
tipo de payload puro (par de strings `linkId`/`nodeId`), não a mensagem protobuf `Identify`
diretamente**, tanto por tamanho quanto por robustez.

Ademais, o `path` cresce a cada hop do algoritmo de forwarding de rota (`ForwardRouteData`,
`RequestRouteData`) — a fila inteira é **retransmitida e reserializada em cada salto do grafo**,
não só o incremento. Para rotas longas (muitos nós), isso é O(hops²) em bytes trafegados no
cálculo de uma única rota.

## 4. Catálogo de mensagens (modelo hybrid — o único vivo em produção)

| Categoria | Mensagens | Frequência | Binding efetivo |
|---|---|---|---|
| Link ↔ Veículo (MESO) | `EnterLinkData`, `LeaveLinkData`, `LinkInfoData` | Altíssima (todo hop de link) | `BaseEventData` → jackson-cbor |
| Link ↔ Veículo (MICRO) | `MicroEnterLinkData`, `MicroLeaveLinkData`, `MicroUpdateData`, `MicroStepData`, `LaneChangeData`, `FollowingUpdateData`, `IntersectionMicroData`, `MicroTicksCompleted`, `GlobalTickEvent` | Muito alta — por sub-tick, por veículo em modo micro | jackson-cbor (binding explícito, redundante) |
| Roteamento | `RequestRoute`, `RequestRouteData`, `ForwardRouteData`, `ReceiveRoute`, `ReceiveRouteData` | Alta — 1 por trip + N por hop de forwarding | `BaseEventData` → jackson-cbor (contém `Identify` protobuf aninhado, §3) |
| Node ↔ Link (capacidade/sinal) | `LinkAccessData`, `RequestLinkAccessData`, `CancelLinkAccessRequestData`, `LinkCapacityFreedData`, `RegisterLinkCapacityData`, `LinkSignalStateData`, `LinkConnectionsData`, `TrafficSignalChangeStatusData` | Alta — por veículo por interseção | `BaseEventData` → jackson-cbor |
| Bus/Subway (embarque) | `BusLoadPassengerData`, `BusRequestPassengerData`, `BusRequestUnloadPassengerData`, `BusUnloadPassengerData`, `RegisterPassengerData`, `LineNotOperationalData`, `PTLineNotOperationalData`, `RegisterBusStopData`, subway equivalentes | Média — por parada, por linha | `BaseEventData` → jackson-cbor |
| Person ↔ Vehicle | `StartTripData`, `TripCompletedData`, `ParkVehicleData`, `PersonScheduleCompleteData`, `PassengerBoardedVehicleData`, `ModeChoiceDecision` | Média — por trip | jackson-cbor (algumas com binding explícito redundante) |
| Ciclo de tick (TimeManager) | `SpontaneousEvent`, `FinishEvent`, `ActorInteractionEvent` (envelope) | **Máxima** — 2 mensagens por ator por tick, para toda a população simulada | binário dedicado (protobuf, ver §5) |
| Controle/carga/migração/warm-up | `CreateActorsEvent`, `LoadDataEvent`, `InitializeEvent`, eventos de `control.load`/`control.migration`/`control.loadbalance`/`control.warmup` | Baixa — fases de setup/rebalance, não no hot loop de simulação | jackson-cbor (bindings explícitos individuais) |

## 5. `SpontaneousEvent` / `FinishEvent` — via `ActorInteractionEvent`? Não.

Diferente das mensagens de simulação, `SpontaneousEvent` e `FinishEvent` **não** carregam
`ActorRef` como payload de `BaseEventData` gigante — elas são a estrutura Scala serializada
diretamente por `jackson-cbor` (bindings próprios em `application.conf:98-99`), **contendo
`ActorRef` como campo** (`actorRef: ActorRef`, `timeManager: ActorRef` em `FinishEvent`).
`ActorRef` serializado via Jackson usa o módulo de referência de ator do Pekko, que grava o
**path completo do ator remoto** (protocolo, sistema, host, porta, path hierárquico) como string
— isso é reescrito em **toda mensagem `FinishEvent`/`SpontaneousEvent`**, ou seja, duas vezes por
ator por tick, para toda a população. Com `time-manager.total-instances = 128` e potencialmente
milhões de atores (ver comentários em `application.conf` sobre 5M atores / 64 TMs), esse é o
tráfego de maior volume absoluto do sistema, mesmo sendo mensagens "pequenas" campo-a-campo.

## 6. Payload útil vs. redundante, mensagem a mensagem (amostra representativa)

| Mensagem | Campos | Observação |
|---|---|---|
| `LinkInfoData` | `linkLength, linkCapacity, linkNumberOfCars, linkFreeSpeed, linkLanes` — 5 `Double`/`Int` | Denso, sem redundância. Bom caso de referência para os demais. |
| `EnterLinkData` | `shardId, actorId, actorType, actorCreationType, actorSize, maxAcceleration=2.6, maxDeceleration=4.5` | `maxAcceleration`/`maxDeceleration` são quase sempre os defaults (constantes de veículo, não do evento) — **deveriam vir do estado do Link/Veículo já conhecido no destinatário**, não retransmitidos em toda entrada de link. `shardId`+`actorId` já são redundantes com `actorRefId`/`shardRefId` do envelope `ActorInteractionEvent` que os contém — a mesma identidade é escrita duas vezes por mensagem (uma no envelope, outra no payload). |
| `RequestRouteData` | `requester: ActorRef, requesterId, requesterClassType, targetNodeId, currentCost, originNodeId, path: Queue[(Identify,Identify)], label` | `requester: ActorRef` **e** `requesterId: String` carregam a mesma identidade por dois caminhos diferentes (ActorRef via Jackson + string). `path` cresce a cada hop (§3). `label="default"` quase sempre não usado. |
| `ForwardRouteData` | `requester: ActorRef, requesterId, updatedCost, targetNodeId, path: Queue[(Identify,Identify)]` | Mesmo padrão de `path` crescente + `Identify` protobuf aninhado. |
| `LinkAccessData` | `phase, nextTick, queuePosition=0, capacityState=Available` | Enxuto; nenhum campo obviamente descartável. |
| `FinishEvent` | `actorRef, identify: Identify, end, scheduleTick: Option[String], scheduleEvent: Option[ScheduleEvent], timeManager: ActorRef, destruct, eventsAmount, generation` | `actorRef` e `identify.actorRef` (dentro do protobuf `Identify`) frequentemente carregam a mesma referência por dois caminhos — um via Jackson (`ActorRef` nativo), outro via string dentro do protobuf `Identify`. `timeManager: ActorRef` é conhecido estaticamente pelo remetente (é sempre o TM que disparou o `SpontaneousEvent` correspondente) — candidato a eliminar do payload e inferir no receptor por correlação de generation/tick. |
| `ActorInteractionEvent`/envelope | `tick, lamportTick, actorRefId, shardRefId, actorRef(path), actorClassType, eventType, actorType, resourceId` + `EntityEnvelope.entityId` | `entityId` do `EntityEnvelope` e `actorRefId` do payload interno frequentemente coincidem (mesmo ator origem/destino) — dependendo do fluxo, uma das duas strings é redundante. `actorClassType` e `actorType` são baixíssima cardinalidade (dezenas de valores possíveis) mas viajam como string completa em toda mensagem — candidatos ideais a `byte`/enum protobuf. |

## 7. Recomendações, priorizadas por impacto/esforço

### Alto impacto, esforço moderado
1. **✅ Implementado — envelope duplo achatado.** `EntityEnvelope.entityId` foi movido para dentro
   da mensagem `ActorInteraction` (campo 13, `communication.proto`). Quando
   `EntityEnvelopeEvent` embrulha um `ActorInteractionEvent` — o caso de todo tráfego de
   simulação via `sendMessageToShard` — `EntityEnvelopeSerializer` agora escreve **um único**
   frame `ActorInteraction` (com `entityId` preenchido) em vez de aninhar um frame `EntityEnvelope`
   em volta de um `ActorInteraction` já serializado; elimina o `payload_manifest`/`payload`
   redundantes do frame externo para esse caminho. O `manifest()` do serializer diferencia os dois
   formatos (`...EntityEnvelopeEvent$ActorInteraction` vs. o genérico) para que `fromBinary`
   escolha o parser correto. Payloads que não são `ActorInteractionEvent` (setup/controle/
   migração — baixa frequência) continuam no formato genérico de duas camadas, já que não têm uma
   forma fixa para achatar. A lógica de serializar/desserializar o payload aninhado foi extraída
   para `NestedPayloadCodec`, compartilhada com `ActorInteractionSerializer` (usado no envio direto
   a pools, `sendMessageToPool`) para não haver duas implementações divergentes do mesmo glue code.
   Cobertura: `src/test/scala/core/serializer/EntityEnvelopeSerializerSpec.scala` — round-trip do
   caminho achatado, do fallback genérico e do `ActorInteractionEvent` standalone. Suíte completa
   (175 testes) verde após a mudança.
2. **✅ Implementado — `eventType`/`actorClassType`/`actorType` viram enums protobuf no wire.**
   Auditoria exaustiva (grep de todo call-site que constrói `ActorInteractionEvent`, não
   suposição) encontrou um conjunto realmente fechado: 13 valores de `eventType`
   (`"default"`, `"enter"`, `"leave"`, `"LineNotOperational"`, `"PTLineNotOperational"`,
   `"TripCompleted"`, os três `EventTypeEnum.Receive*`/`RegisterLinkCapacity`, e os quatro
   `DT_*` do `DigitalTwinManager`), 14 classes de ator que constroem a mensagem
   (`actorClassType`), e o já-fechado `CreationTypeEnum` (4 valores, `actorType`). Descoberta
   importante no processo: **`EventTypeEnum` (model.hybrid) não é o vocabulário completo** —
   metade dos `eventType` reais em uso (`"enter"`, `"leave"`, `"TripCompleted"`,
   `"LineNotOperational"`, `"PTLineNotOperational"`, os 4 `DT_*`) nunca esteve nele; a
   verificação de tipo que ele dava no call-site cobria só uma fração do tráfego real.
   `communication.proto` ganhou três enums (`ActorEventType`, `ActorClassType`,
   `ActorCreationType`) com o valor mais comum mapeado para `= 0` (o default de
   `sendMessageTo`/`CreationTypeEnum`), para que o proto3 sequer escreva o campo na maioria das
   mensagens. Cada enum tem um caso `OTHER`/`UNSPECIFIED` com campo `*Override: String`
   companheiro — só populado quando um valor não está no mapa conhecido — como rede de segurança
   de compatibilidade futura (uma classe/eventType nova ainda funciona, só não ganha a
   compactação até o enum ser estendido). A conversão string↔enum vive inteiramente em
   `core/serializer/ActorInteractionCodec.scala`, usada só pelos dois serializadores — nenhum dos
   ~30 call-sites de `sendMessageTo` nem o case class `ActorInteractionEvent` foi alterado (continuam
   com `String` puro). Cobertura: `ActorInteractionCodecSpec` (round-trip de todo valor
   conhecido + fallback OTHER para cada um dos três campos) e mais dois casos em
   `EntityEnvelopeSerializerSpec` (caminho com valores conhecidos e caminho OTHER/override de
   ponta a ponta). Suíte completa: 183 testes verdes.
3. **✅ Implementado — `BaseEventData` agora serializa via Kryo, não jackson-cbor.**
   `application.conf` ganhou `kryo = "io.altoo.serialization.kryo.pekko.PekkoKryoSerializer"` em
   `pekko.actor.serializers` e o binding `"...BaseEventData" = kryo` (era `jackson-cbor`); as 14
   sobrescritas explícitas redundantes para as classes MICRO/`person` (que só reafirmavam o mesmo
   binding herdado) foram removidas — voltariam a ficar em jackson-cbor por engano se não fossem
   apagadas junto com a troca do binding-pai. Um bloco `pekko-kryo-serialization { id-strategy =
   "incremental", classes = [...] }` pré-registra **toda** subclasse concreta de `BaseEventData`
   encontrada por grep exaustivo de `extends BaseEventData` (68 classes — inclui `model.mobility`,
   pacote morto mas mantido por completude) — pré-registro é obrigatório com `incremental` num
   cluster multi-nó: sem ele, cada nó atribuiria IDs numéricos dinamicamente na ordem em que
   primeiro encontra cada classe, e nós diferentes veriam ordens diferentes, fazendo Kryo
   desserializar bytes como a classe errada silenciosamente. A lista está comentada como
   *append-only* (nunca reordenar/remover uma entrada já implantada).
   `ActorRef` (presente em `RequestRouteData`, `ForwardRouteData`,
   `BusRequestUnloadPassengerData`, etc.) funciona sem trabalho extra: a
   `io.altoo.serialization.kryo.pekko.DefaultKryoInitializer` da própria biblioteca já registra
   um `ActorRefSerializer` para isso. `Identify` (protobuf, aninhado dentro de
   `mutable.Queue[(Identify,Identify)]` — o problema descrito em §3) também é coberto
   automaticamente pelo serializador de campo genérico do Kryo — continua funcionando (não é o
   ideal — ver recomendação 4 abaixo — mas Kryo o serializa de forma binária/posicional, mais
   compacto que a reflexão do Jackson sobre os internals do scalapb). Cobertura:
   `KryoEventDataSerializationSpec` — confirma que o binding resolve para
   `PekkoKryoSerializer` (não jackson-cbor) e faz round-trip de `EnterLinkData` (enums Scala),
   `MicroUpdateData` (`Option[String]`, `Some`/`None`) e `RequestRouteData` (a combinação mais
   arriscada: `Identify` protobuf aninhado + campo `ActorRef` cru). Suíte completa: 187 testes
   verdes.

### Alto impacto, esforço maior (mudança de contrato)
4. **Trocar `Identify` (protobuf `scalapb.GeneratedMessage`) por um par de `String` simples
   (`linkId`, `nodeId`) dentro de `RequestRouteData`/`ForwardRouteData`/`ReceiveRouteData`.**
   Elimina o aninhamento protobuf-dentro-de-Jackson descrito em §3 e reduz o tamanho de cada
   hop da fila de rota.
5. **Parar de retransmitir o `path` inteiro a cada hop de forwarding de rota.** Hoje
   `ForwardRouteData`/`RequestRouteData` reenviam a fila acumulada completa; considerar apenas
   acumular no lado do nó atual (mantendo estado local do cálculo em andamento) e enviar só o
   incremento, ou (mais simples) despachar o cálculo de rota via A* local sem forwarding
   distribuído incremental quando o grafo permitir.

### Impacto direto, baixo esforço
6. **Remover `maxAcceleration`/`maxDeceleration` de `EnterLinkData`** — são constantes do
   veículo, não do evento de entrada; se o Link precisa delas, deveria buscá-las uma vez do
   estado do veículo/ator, não recebê-las em toda entrada de link.
7. **Remover duplicidade `requesterId: String` vs `requester: ActorRef`** em
   `RequestRouteData`/`ForwardRouteData` — manter apenas o `ActorRef` (ou apenas o `id` +
   `shardId`, resolvendo o `ActorRef` no destino via `getShardRef`, como já é feito em
   `sendMessageToShard`).
8. **Podar os 15 bindings explícitos redundantes** para classes MICRO/`person` em
   `application.conf` que já herdam o mesmo `jackson-cbor` via `BaseEventData` — não afeta
   tamanho de mensagem, mas remove ruído de configuração e é o primeiro lugar a checar quando
   alguém reintroduzir Kryo (esses bindings hoje "escondem" o fato de que essas classes já
   cairiam no default de qualquer forma).

### A não fazer / falso positivo já descartado
- **Não é necessário mexer no `StringPool`** para reduzir tamanho de mensagem — ele já cumpre
  seu papel (dedup de heap pós-deserialização), mas não influencia bytes na rede/disco. Qualquer
  redução de tamanho de mensagem tem que vir da forma de serialização ou dos campos
  transmitidos, não do interning.

## 8. Organização de arquivos: violações corrigidas nesta análise

Convenção do projeto (uma classe por arquivo) estava violada em 4 arquivos de eventos/mensagens.
Todos foram separados nesta sessão, mantendo pacote e imports (`import ...data.*` e imports
nomeados continuam funcionando sem alteração):

- `model/hybrid/entity/event/data/MicroEventData.scala` (9 classes) → `MicroEnterLinkData.scala`,
  `MicroLeaveLinkData.scala`, `MicroUpdateData.scala`, `MicroStepData.scala`,
  `LaneChangeData.scala`, `FollowingUpdateData.scala`, `IntersectionMicroData.scala`,
  `MicroTicksCompleted.scala`, `GlobalTickEvent.scala`.
- `model/hybrid/entity/event/data/person/PersonEventData.scala` (5 classes) →
  `StartTripData.scala`, `TripCompletedData.scala`, `ParkVehicleData.scala`,
  `ModeChoiceDecision.scala`, `PersonScheduleCompleteData.scala`.
- `core/entity/event/control/warmup/WarmUpEvents.scala` (3 classes) →
  `StartWarmUpWorkersEvent.scala`, `WarmUpWorkerDoneEvent.scala`, `WarmUpAllDoneEvent.scala`.
- `core/entity/event/control/loadbalance/MigrationCoordinationEvent.scala` (3 classes) →
  `RequestMigrationPauseEvent.scala`, `MigrationSafeEvent.scala`,
  `MigrationCompleteNotifyEvent.scala`.

`sbt compile` confirmado limpo após a divisão.

## 9. Protobuf: já está organizado como pedido, nenhuma mudança necessária

Os `.proto` em `src/main/protobuf/` já agrupam múltiplas mensagens por arquivo de contexto
(ex.: `core/entity/event/control/execution.proto` com 12 mensagens de controle de tempo,
`core/entity/actor.proto` com os tipos estruturais compartilhados `Identify`/`Property`/
`Relationship`/etc.). Esse padrão está correto e não deve ser fragmentado em um arquivo por
mensagem — mantém-se como está.
