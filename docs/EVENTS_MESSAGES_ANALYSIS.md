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
  (`application.conf:61`, no momento da auditoria original). Isso inclui **toda a família de
  mensagens do modelo hybrid** — `EnterLinkData`, `LeaveLinkData`, `LinkInfoData`,
  `LinkAccessData`, `TrafficSignalChangeStatusData`, todos os dados de bus/subway/vehicle/link —
  **nenhuma delas tinha binding explícito**, só as ~15 classes MICRO e `person` que foram
  adicionadas depois (redundantes, já que herdam o mesmo binding de `BaseEventData`; o Pekko
  resolve por MRO de qualquer forma). *(Status: ver recomendação 3 — já implementado, o binding
  agora é Kryo.)*
- Ou seja: **as mensagens de maior frequência do simulador (entrada/saída de link, toda
  atualização MICRO) usavam o serializador reflexivo mais caro configurado no sistema**, enquanto
  o serializador mais eficiente disponível na classpath (Kryo) ficava sem uso algum. *(Status:
  corrigido — recomendação 3.)*

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

## 3. `Identify` (protobuf) aninhado em payload — e uma descoberta maior: código morto

**Atualização (recomendação 4): `RequestRouteData`, `ForwardRouteData`, `ReceiveRouteData`,
`RequestRoute` e `ReceiveRoute` (model.hybrid) eram código morto e foram removidos.** A análise
original desta seção assumia que essas classes eram tráfego real (forwarding distribuído de rota
salto-a-salto entre Node/Link). Investigação para implementar a recomendação 4 mostrou que
**nenhuma delas era construída em lugar nenhum de `src/main`** — o roteamento no modelo hybrid é
inteiramente local e síncrono via `GPSUtil.calcRouteCompact` (A*), chamado direto por
Car/Bicycle/Motorcycle/Movable/PersonWalkingTripHandler/BusStationRouteCalculator/etc. Essas 5
classes (e os 3 casos correspondentes `RequestRoute`/`ForwardRoute`/`ReceiveRoute` em
`EventTypeEnum`) eram resquício de um design anterior de roteamento distribuído por
troca-de-mensagens, substituído pelo A* local sem que o código de mensagens fosse removido junto.
Deletadas nesta sessão — ver §7 recomendação 4 e a entrada correspondente em
`docs/KNOWN_GAPS.md`.

O problema de fundo que essas classes ilustravam continua real para quem ainda existe: `Identify`
é uma classe **gerada pelo scalapb** (`scalapb.GeneratedMessage`, com binding próprio `= proto` em
`application.conf`). Esse binding só se aplica quando o Pekko despacha `Identify` como mensagem de
nível superior — quando `Identify` aparece **aninhado dentro de um campo** de um payload
`BaseEventData` (como em `BusLoadPassengerData.people: mutable.Seq[Identify]` e o equivalente em
`SubwayLoadPassengerData`, ambas *vivas*), o serializador externo (antes Jackson, agora Kryo — ver
recomendação 3) não delega para o protobuf; reflete sobre os campos internos do `Identify` gerado.
Com Kryo isso é bem mais barato que era com Jackson (binário posicional, sem nomes de campo), mas
ainda não é o ideal — o payload poderia expor só os `String`s que `BusLoadPassengerData`
realmente usa em vez da mensagem protobuf inteira. Como as únicas classes vivas com esse padrão
carregam uma lista pequena e limitada (passageiros embarcando, não uma rota que cresce a cada
hop), o ganho de reescrever isso é bem menor do que parecia quando `RequestRouteData`/
`ForwardRouteData` (cuja fila crescia O(hops) a cada salto) ainda pareciam estar em uso — não
priorizado nesta rodada.

## 4. Catálogo de mensagens (modelo hybrid — o único vivo em produção)

| Categoria | Mensagens | Frequência | Binding efetivo |
|---|---|---|---|
| Link ↔ Veículo (MESO) | `EnterLinkData`, `LeaveLinkData`, `LinkInfoData` | Altíssima (todo hop de link) | `BaseEventData` → jackson-cbor |
| Link ↔ Veículo (MICRO) | `MicroEnterLinkData`, `MicroLeaveLinkData`, `MicroUpdateData`, `MicroStepData`, `LaneChangeData`, `FollowingUpdateData`, `IntersectionMicroData`, `MicroTicksCompleted`, `GlobalTickEvent` | Muito alta — por sub-tick, por veículo em modo micro | `BaseEventData` → Kryo |
| ~~Roteamento~~ | ~~`RequestRoute`, `RequestRouteData`, `ForwardRouteData`, `ReceiveRoute`, `ReceiveRouteData`~~ | — | **Removidas (recomendação 4) — código morto, nunca construídas.** Roteamento é local via `GPSUtil.calcRouteCompact`. |
| Node ↔ Link (capacidade/sinal) | `LinkAccessData`, `RequestLinkAccessData`, `CancelLinkAccessRequestData`, `LinkCapacityFreedData`, `RegisterLinkCapacityData`, `LinkSignalStateData`, `TrafficSignalChangeStatusData` | Alta — por veículo por interseção | `BaseEventData` → Kryo |
| Bus/Subway (embarque) | `BusLoadPassengerData`, `BusRequestPassengerData`, `BusRequestUnloadPassengerData`, `BusUnloadPassengerData`, `RegisterPassengerData`, `LineNotOperationalData`, `PTLineNotOperationalData`, `RegisterBusStopData`, subway equivalentes | Média — por parada, por linha | `BaseEventData` → Kryo |
| Person ↔ Vehicle | `StartTripData`, `TripCompletedData`, `ParkVehicleData`, `PersonScheduleCompleteData`, `PassengerBoardedVehicleData`, `ModeChoiceDecision` | Média — por trip | `BaseEventData` → Kryo |
| Ciclo de tick (TimeManager) | `SpontaneousEvent`, `FinishEvent`, `ActorInteractionEvent` (envelope) | **Máxima** — 2 mensagens por ator por tick, para toda a população simulada | binário dedicado (protobuf, ver §5) |
| Controle/carga/migração/warm-up | `CreateActorsEvent`, `LoadDataEvent`, `InitializeEvent`, eventos de `control.load`/`control.migration`/`control.loadbalance`/`control.warmup` | Baixa — fases de setup/rebalance, não no hot loop de simulação | jackson-cbor (bindings explícitos individuais) |

`LinkConnectionsData` (categoria Node↔Link) também parece não ter nenhum call-site em `src/main` —
descoberta ao lado do trabalho da recomendação 4, mas fora do escopo aprovado para remoção nesta
rodada (o usuário autorizou especificamente as classes de roteamento). Fica registrada aqui como
candidata a uma futura limpeza de código morto.

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
| `EnterLinkData` | `actorType, actorCreationType, actorSize` | `maxAcceleration`/`maxDeceleration` **removidos (recomendação 6)** — eram função determinística só de `actorType` (já presente na mensagem), não do evento; agora derivados no Link via `actorType.microMaxAcceleration`/`.microMaxDeceleration`. `shardId`+`actorId` **removidos (recomendação 11)** — eram sempre idênticos a `actorRefId`/`shardRefId` do envelope `ActorInteractionEvent` que os contém (mesma origem: `getEntityId`/`getShardId` do remetente); consumidores agora leem `event.actorRefId`/`event.shardRefId` diretamente. |
| ~~`RequestRouteData`~~/~~`ForwardRouteData`~~ | — | **Removidas (recomendação 4) — código morto**, nunca construídas; carregavam `requester: ActorRef` duplicando `requesterId: String`, e um `path: Queue[(Identify,Identify)]` que crescia a cada hop de forwarding — motivo original das recomendações 4/5 abaixo, que perderam o alvo junto com a remoção. |
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
   `ActorRef` (presente em `BusRequestUnloadPassengerData`, `SubwayRequestUnloadPassengerData`,
   etc.) funciona sem trabalho extra: a
   `io.altoo.serialization.kryo.pekko.DefaultKryoInitializer` da própria biblioteca já registra
   um `ActorRefSerializer` para isso. `Identify` (protobuf, aninhado dentro de
   `mutable.Seq[Identify]` em `BusLoadPassengerData`/`SubwayLoadPassengerData` — o problema
   descrito em §3) também é coberto automaticamente pelo serializador de campo genérico do Kryo —
   continua funcionando (Kryo o serializa de forma binária/posicional, mais compacto que a
   reflexão do Jackson sobre os internals do scalapb). Cobertura:
   `KryoEventDataSerializationSpec` — confirma que o binding resolve para
   `PekkoKryoSerializer` (não jackson-cbor) e faz round-trip de `EnterLinkData` (enums Scala),
   `MicroUpdateData` (`Option[String]`, `Some`/`None`), `BusLoadPassengerData` (`Identify`
   protobuf aninhado) e `BusRequestUnloadPassengerData` (campo `ActorRef` cru). Suíte completa:
   187 testes verdes (depois ajustada para 188 pela recomendação 4, ver abaixo).
4. **✅ Implementado, mas não como planejado — as classes-alvo eram código morto e foram
   removidas em vez de reescritas.** Investigação para trocar `Identify` por um par de `String`
   simples em `RequestRouteData`/`ForwardRouteData`/`ReceiveRouteData`/`RequestRoute`/
   `ReceiveRoute` (model.hybrid) achou que **nenhuma dessas 5 classes é construída em lugar
   nenhum de `src/main`** — roteamento é feito localmente via `GPSUtil.calcRouteCompact` (A*), não
   por forwarding distribuído de mensagens. São resquício de um design de roteamento anterior. Ver
   §3 para os detalhes da investigação. Ação tomada (aprovada explicitamente pelo usuário antes de
   remover):
   - As 5 classes deletadas de `model/hybrid/entity/event/data/`.
   - Os 3 casos correspondentes (`RequestRoute`, `ForwardRoute`, `ReceiveRoute`) removidos de
     `EventTypeEnum` (model.hybrid) — únicos casos do enum ligados só a essas classes.
   - As 5 entradas correspondentes removidas da lista `pekko-kryo-serialization.classes` em
     `application.conf` (adicionadas erroneamente pela recomendação 3, antes desta descoberta).
   - `KryoEventDataSerializationSpec` deixou de testar `RequestRouteData`; a mesma cobertura
     (`Identify` aninhado + `ActorRef` cru) passou a usar `BusLoadPassengerData`/
     `BusRequestUnloadPassengerData` — classes reais e vivas com os mesmos formatos de campo.
   - Entrada correspondente adicionada em `docs/KNOWN_GAPS.md`, no mesmo padrão usado para a
     remoção do pacote `model/mobility/actor/`.
   - `model/mobility/entity/event/data/{RequestRoute,ForwardRoute,ReceiveRoute}Data` **não**
     foram tocadas — já fora do escopo de otimização (pacote morto, ver topo do documento) e fora
     do escopo aprovado pelo usuário para esta remoção.
   Suíte completa: 188 testes verdes.
5. **Obsoleto — dependia da recomendação 4.** "Parar de retransmitir o `path` inteiro a cada hop
   de forwarding de rota" não se aplica mais: o forwarding que essa recomendação mirava não
   existe (nunca existiu em execução — código morto removido pela recomendação 4). Roteamento já
   é local via A*, sem esse custo.

### Impacto direto, baixo esforço
6. **✅ Implementado — `maxAcceleration`/`maxDeceleration` removidos de `EnterLinkData`.**
   Eram constantes por *tipo* de veículo (não por instância nem por evento): cada subclasse de
   `Movable` (`Bicycle`, `Bus`, `Motorcycle`; `Car`/demais ficavam no default de `Movable`)
   sobrescrevia `microMaxAcceleration`/`microMaxDeceleration` só para preencher esses dois campos
   ao montar `EnterLinkData` — e `EnterLinkData` **já carrega `actorType: ActorTypeEnum`**, então
   o valor era 100% derivável no lado do Link sem precisar viajar na mensagem. Os dois métodos
   viraram extension methods em `ActorTypeEnum` (mesmo arquivo do enum,
   `model/hybrid/entity/state/enumeration/ActorTypeEnum.scala`) com os mesmos valores por tipo
   (Car/default 2.6↑/4.5↓, Bicycle 1.0/3.0, Bus 1.2/3.5, Motorcycle 3.5/5.0);
   `LinkVehicleFlowHandler.handleEnterLinkMicro` agora lê `data.actorType.microMaxAcceleration`/
   `.microMaxDeceleration` em vez do campo removido. Os métodos protegidos equivalentes em
   `Movable`/`Bicycle`/`Bus`/`Motorcycle` (cuja única finalidade era alimentar esses dois campos)
   foram removidos junto — sem essa mensagem, não tinham mais nenhum uso. Zero mudança de
   comportamento: é a mesma função determinística de `actorType`, só computada no receptor em vez
   de transmitida. Cobertura: novo teste em `LinkVehicleFlowHandlerSpec` — para cada `ActorTypeEnum`
   que tinha override próprio, confirma que o `VehicleInLane` semeado no lado do Link tem
   exatamente os mesmos `maxAcceleration`/`maxDeceleration` que o vetor de override antigo
   produzia. Suíte completa: 189 testes verdes.
7. **Obsoleto — dependia da recomendação 4.** "Remover duplicidade `requesterId: String` vs
   `requester: ActorRef`" mirava `RequestRouteData`/`ForwardRouteData`, removidas como código
   morto pela recomendação 4.
8. **✅ Implementado como parte da recomendação 3** — os 14 bindings explícitos redundantes para
   classes MICRO/`person` (que só reafirmavam o binding herdado de `BaseEventData`) foram
   removidos de `application.conf` quando o binding-pai passou de `jackson-cbor` para `kryo`.
9. **✅ Implementado — `ActorInteraction.shardRefId` eliminado do wire, 100% redundante com
   `actorClassType`.** Investigação de uma proposta maior (converter `entityId`/`shardId` de
   `String` para `Long` para compactar o wire) esbarrou em dois fatos: a API de sharding do
   próprio Pekko (`ShardRegion.ExtractEntityId`/`ExtractShardId`, ver `ActorCreatorUtil.scala`) é
   tipada em `String`, e os IDs reais do dataset (`htcaid:car;42_v_car`,
   `htcaid:subwaystation;station_A` — ver `docs/MATSIM_CONVERSION_GUIDE.md`) não são uniformemente
   numéricos; uma migração para `Long` exigiria uma camada de ID surrogate tocando dezenas de
   state classes, fora de escopo. Só que essa investigação achou uma redundância concreta e
   isolada: `SimulationBaseActor.sendMessageToShard`/`sendMessageToPool` sempre montam
   `shardRefId = IdUtil.format(getShardId)` (= `getClass.getName` do remetente) **e**
   `actorClassType = StringUtil.getModelClassNameWithoutPackage(getClass.getName)` — o mesmo
   `getClass.getName`, duas vezes, uma delas (`actorClassType`) já passando pelo enum protobuf
   fechado da recomendação 2. Como `StringUtil.getModelClassName` é exatamente a função inversa da
   que produz `actorClassType`, o receptor sempre consegue reconstruir `shardRefId` a partir do
   `actorClassType` já decodificado — inclusive no caminho `OTHER`/override. O campo `shardRefId`
   continua declarado no proto (estabilidade de forma do wire) mas nunca mais é escrito;
   `EntityEnvelopeSerializer`/`ActorInteractionSerializer` passam a computar
   `shardRefId = StringUtil.getModelClassName(actorClassType decodificado)` no `fromBinary`. Nenhum
   consumidor de `event.shardRefId` (`LinkVehicleFlowHandler`, `NodeEventHandler`,
   `PendingLinkAccessRequest` em `NodeState`, `RailLink`) precisou mudar — a correção acontece
   inteiramente na construção do `ActorInteractionEvent` pelo deserializer, não nos call sites.
10. **✅ Implementado — prefixo `"htcaid_<tipo>_"` de `entityId`/`actorRefId` retirado do wire.**
    No momento em que chegam ao serializer, `entityId`/`getEntityId()` já passaram por
    `IdUtil.format` (`:`/`;` → `_`), então o formato real é sempre
    `"htcaid_" + tipoEmMinúsculo + "_" + idLocal` (confirmado contra IDs reais do
    `MATSIM_CONVERSION_GUIDE.md` e do dataset de teste). Esse prefixo é 100% redundante com o tipo
    do ator já presente na mensagem: para `actorRefId` (sempre o próprio remetente),
    `actorClassType` já basta — sem campo novo, só um `bool actorRefIdPrefixStripped` (proto field
    17) sinalizando se o corte aconteceu, já que (diferente de `entityId`) `actorRefId` não tem um
    enum sobrando pra também carregar esse sinal. Para `entityId` (o destino, de tipo
    potencialmente diferente do remetente — ex. Car enviando para Link), o remetente já conhece o
    tipo-alvo via o parâmetro `shardId` de `sendMessageToShard`, só nunca o tinha colocado no wire
    de forma compacta; um novo campo `ActorClassType entityClassType` (proto field 16, reaproveita
    o enum `ActorClassType` da recomendação 2) carrega esse tipo — `UNSPECIFIED` (valor zero, custo
    de wire nulo) sinaliza "não bateu com o formato esperado, id intocado" (ids de controle como
    `"creator-load-data"`). Corte/reconstrução é *best-effort*: nunca obrigatório, nunca quebra um
    id que não segue a convenção. Helpers compartilhados em `ActorInteractionCodec`
    (`stripIdPrefix`/`rebuildIdPrefix`/`encodeEntityIdPrefix`/`decodeEntityIdPrefix`), usados pelos
    dois serializers. Cobertura: `ActorInteractionCodecSpec` (corte/reconstrução para todo tipo
    conhecido, id com underscore no meio, id não-correspondente) e `EntityEnvelopeSerializerSpec`
    (round-trip completo, derivação de `shardRefId` ignorando valor stale do remetente, id de
    controle intocado, e um teste comparando `.toByteArray.length` antes/depois confirmando que o
    payload compactado é estritamente menor). Suíte completa: 198 testes verdes.

    **Medição real (recomendações 9+10 juntas):** instrumentação temporária em
    `sendMessageToShard`/`sendMessageToPool` capturou todo `ActorInteractionEvent` real construído
    ao rodar o cenário `simulations/input/sqlite_validation_test` (dia inteiro, 86400 ticks) e
    comparou o tamanho do payload compactado com uma reconstrução fiel do formato antigo para a
    mesma mensagem. Resultado: **21.637 mensagens, 4.195.672 bytes compactados vs. 5.369.224 bytes
    no formato antigo — 1.173.552 bytes economizados (21,86%)**, consistente entre quase todas as
    categorias de `eventType` (`EnterLink`/`LeaveLink` ~22%, `ReceiveEnterLinkInfo`/
    `ReceiveLeaveLinkInfo` ~22%, `RequestLinkAccess`/`ReceiveLinkAccess` ~22%). Instrumentação
    removida após a medição (não faz parte do código de produção).
11. **✅ Implementado — `EnterLinkData.shardId`/`.actorId` removidos, redundantes com o envelope.**
    `Movable.enterLink`/`leavingLink` (`Movable.scala`) preenchiam `EnterLinkData.actorId =
    getEntityId` e `.shardId = getShardId` — exatamente os mesmos valores que
    `SimulationBaseActor.sendMessageToShard` já escreve em `ActorInteractionEvent.actorRefId`/
    `.shardRefId` para a mesma mensagem (mesma origem: identidade do remetente). Os consumidores
    (`Link.handleEnterLink`/`handleLeaveLink`, `LinkVehicleFlowHandler` — que já recebiam
    `event: ActorInteractionEvent` como parâmetro ao lado de `data`, mas liam a identidade errada
    — e `RailLink.handleEnterLink`) passaram a ler `event.actorRefId`/`event.shardRefId` em vez de
    `data.actorId`/`data.shardId`, inclusive na hora de popular `LinkRegister`/`VehicleInLane` em
    `LinkState` — a obrigação de guardar `shardId` para poder responder ao veículo depois (ver
    disciplina de sincronização do `CLAUDE.md`, "must live in state") continua satisfeita, só a
    fonte do valor muda. `LeaveLinkData` tinha o mesmo par de campos, removidos junto. Zero mudança
    de comportamento — mesmo valor, uma fonte a menos. `model/mobility` (pacote morto) não foi
    tocado. Cobertura: `KryoEventDataSerializationSpec`/`LinkVehicleFlowHandlerSpec` atualizados
    para o novo formato dos case classes. Suíte completa: 198 testes verdes.

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
