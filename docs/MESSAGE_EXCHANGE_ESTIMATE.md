# Estimativa de Troca de Mensagens (`MessageExchange`)

> Escopo: `hyperbolic-time-chamber` (engine) + geradores de cenário `tools/sao-paulo` e `tools/toulouse`.
> Fontes primárias: [src/main/scala/core/actor/SimulationBaseActor.scala](../src/main/scala/core/actor/SimulationBaseActor.scala),
> [src/main/scala/core/actor/manager/time/](../src/main/scala/core/actor/manager/time/),
> [src/main/scala/model/hybrid/](../src/main/scala/model/hybrid/),
> [src/main/resources/application.conf](../src/main/resources/application.conf),
> `../../tools/sao-paulo/output/sp_1pct/` e `../../tools/toulouse/`.

> **Natureza do documento:** esta é uma **estimativa de ordem de magnitude** (não uma medição
> instrumentada). Os números de escala de cenário foram **medidos nos arquivos gerados**; os
> multiplicadores por mensagem vêm de **leitura direta do código** (não de execução com tracing).
> Toda premissa (divisão modal, tamanho de rota, nº de paradas) está explicitada na §6.

---

## 1. O que determina a troca de mensagens

Pontos confirmados diretamente na fonte:

- **1 tick = 1 segundo**; duração padrão de simulação = 1 dia = **86.400 ticks**
  (`VehicleSimulationConfig.simulationEndTick` default `86400L`).
- **Protocolo de tempo (plano de controle):** o `TimeManager` envia `SpontaneousEvent` (TM→ator);
  o ator responde com `FinishEvent` (que já carrega o `scheduleTick` embutido) e, quando se
  (re)registra, um `ScheduleEvent` separado. Ver `LocalTimeManagerBase.scheduleEvent`/`finishEvent`
  e `SimulationBaseActor.onFinishSpontaneous`/`scheduleEvent`. O próprio `application.conf`
  (linha ~347) documenta: *"Each actor sends FinishEvent + ScheduleEvent back to its TM every tick"*.
- **Pessoas são orientadas a eventos:** `Person.actSpontaneous` agenda o próximo wake no fim da
  atividade (`onFinishSpontaneous(Some(wakeTick))`), **não** a cada tick.
- **Veículos MESO avançam por link:** `EnterLinkData`→`LinkInfoData` (entrada), depois
  `LeaveLinkData`→`LinkInfoData` (saída), com 2 wakes por link (entrada/saída) — ver `Movable.scala`
  e `CarLinkHandler.scala`.
- **Caminhada é um "salto" mesoscópico:** calcula `arrivalTick = now + distância / 1.4 m/s` e
  agenda 1 único wake, sem mensagem por link — `PersonWalkingTripHandler.scala`.
- **MICRO usa 10 sub-ticks por tick global** (`Link.scala`, `application.conf` linha ~510), com
  `MicroUpdateData` por veículo por sub-tick — amplificador restrito a links em modo MICRO.
- **Infraestrutura (nodes/links) é passiva:** só gera mensagens de ciclo de vida
  (carga/init/registro/destruição), **não** gera mensagens por tick.

---

## 2. Categorização das mensagens

| # | Categoria | Mensagens representativas |
|---|---|---|
| **A** | **Controle de tempo (barreira)** | `SpontaneousEvent`, `FinishEvent`, `ScheduleEvent`, `LocalTimeReportEvent`, `UpdateGlobalTimeEvent`, `QueryNextTickEvent`, `RegisterPassiveActorEvent`/`TimeManagerRegisterEvent` |
| **B** | **Interação MESO (veículo↔link↔nó)** | `EnterLinkData`, `LeaveLinkData`, `LinkInfoData`, `LinkAccessData` (semáforo), `StartTripData`, `TripCompletedData` |
| **C** | **Transporte público (PT)** | `RegisterPassengerData`, `RegisterBusStopData`, `BusRequestUnloadPassengerData`, `BusUnloadPassengerData`, `LineNotOperationalData` |
| **D** | **Micro-simulação (MICRO)** | `GlobalTickEvent`, `MicroEnterLinkData`, `MicroUpdateData`, `MicroLeaveLinkData`, `MicroStepData`, `MicroTicksCompleted` |
| **E** | **Observabilidade (report)** | `ReportEvent` → CSV/JSON/ClickHouse/Kafka/Parquet/Prometheus (`enter_link`, `leave_link`, `vehicle_link_flow`, `person_walking_*`, …) |
| **F** | **Ciclo de vida / carga / migração** | `InitializeEvent`, `CreateActorsEvent`, `PostLoadRegistration*`, `DestructEvent`/`DestructAckEvent`, `SaveMigrationSnapshotEvent`, `MigrationWindowOpen/Close/Ack`, `GvtUpdateEvent`/`LvtReportEvent` (Time Warp) |

---

## 3. Escala dos cenários (medida nos dados reais)

Medido diretamente nos arquivos gerados (`grep -o '"typeActor"'` sobre `data/*.json` para SP;
`wc -l`/contagem de tags XML para Toulouse).

| Métrica | SP 1% (`sp_1pct` gerado) | SP 100% (extrapolado) | Toulouse 100% (full) |
|---|---|---|---|
| Pessoas | **141.500** | ~14,15 M | **3.585.342** |
| Viagens/dia | ~425 mil | ~42,5 M | **10.949.602** |
| Nós (rede) | **2.191.102** | 2.191.102 | **488.523** |
| Links (rede) | **4.292.756** | 4.292.756 | **1.081.827** |
| Carros | **29.879** | ~2,99 M | ~500 mil (est.) |
| Ônibus (frota) | **10.234** | 10.234 (frota fixa) | ~1 mil (est.) |
| Paradas de ônibus | **71.584** | 71.584 | ~2 mil (est.) |
| Estações de ônibus | **2.176** | 2.176 | ~50 (est.) |

> **Insight central:** o gerador materializa **rede + frota de ônibus em escala de cidade cheia
> mesmo a 1% de amostra** (SP 1% já carrega 6,48 M de atores de infraestrutura + 10.234 ônibus).
> Apenas a **demanda** (pessoas/carros/viagens) escala com a taxa de amostragem. Por isso o custo de
> transporte público (categoria C) é quase idêntico em 1% e 100%, enquanto pessoas/carros escalam ×100.

---

## 4. Orçamento de mensagens por entidade

| Ação | Mensagens trocadas | Composição |
|---|---|---|
| Ativação (wake) de ator | **2–3** | 1 `SpontaneousEvent` + 1 `FinishEvent` (+1 `ScheduleEvent` se registra) |
| Pessoa, 1 dia (~7 wakes) | **~15–20** | 7 × 2–3 (acorda só no fim de cada atividade) |
| Carro MESO, 1 link | **~6–8** | 2 wakes (2×3) + `EnterLinkData` + `LeaveLinkData` + 2× `LinkInfoData` |
| Carro, viagem (≈30 links) | **~200–250** | + `StartTripData` + `TripCompletedData` |
| Caminhada (perna) | **~3–5** | 1 wake + 2 reports (salto mesoscópico) |
| Ônibus, viagem de passageiro (10 paradas) | **~21** | 1 `RegisterPassengerData` + 2×10 (solicita/confirma desembarque) |
| MICRO, veículo × sub-tick | **+1** `MicroUpdateData` | ×10 sub-ticks/tick global (amplificador) |

---

## 5. Estimativa de troca total (início ao fim da simulação)

Ordens de magnitude por categoria (mensagens por dia simulado):

| Categoria | SP 1% | SP 100% | Toulouse 100% |
|---|---|---|---|
| **A. Controle de tempo** | ~60 M | ~2,2 Bi | ~1,3 Bi |
| **B. Interação MESO** | ~40 M | ~1,6 Bi | ~700 M |
| **C. Transporte público** | ~45 M | ~200 M | ~40 M |
| **D. MICRO** (se habilitado) | +opcional | +opcional | +opcional |
| **E. Observabilidade** | ~30–50 M | ~2 Bi | ~1 Bi |
| **F. Ciclo de vida (uma vez)** | ~26 M | ~26 M | ~6 M |
| **Total estimado** | **≈ 150–200 M** | **≈ 4–6 Bi** | **≈ 2–3 Bi** |

**Leitura dos números:**

- **SP 1% → ~150–200 M mensagens.** Dominado pelo plano de controle (A) e pela frota de ônibus (C),
  que já está em escala cheia mesmo a 1% — por isso o custo de PT (~45 M) não é desprezível frente à
  demanda reduzida.
- **SP 100% → ~4–6 Bi mensagens.** Pessoas (×100) e carros (×100) dominam: só o tráfego de tempo de
  carros MESO passa de 1,5 Bi. A infraestrutura permanece fixa, então o total **não** cresce 100×
  (cresce ~30–40×).
- **Toulouse 100% → ~2–3 Bi mensagens.** Menor que SP 100% (população ~4× menor e rede ~4× menor),
  mas bem maior que SP 1% por ter ~25× mais demanda.

---

## 6. Premissas e ressalvas

- **Gargalo dominante = protocolo de tempo.** Cada wake custa 2–3 mensagens
  (Spontaneous + Finish + Schedule); multiplicado por milhões de atores × dezenas de wakes/dia, é
  isso que leva os totais à casa dos bilhões. É exatamente o gargalo que `application.conf`
  (linhas ~337–352) tenta mitigar com 128 LocalTMs e 4 TMs/nó.
- **Veículos MESO são os maiores emissores individuais** (centenas de mensagens por viagem),
  seguidos de **pessoas** (dezenas/dia).
- **MICRO muda a ordem de grandeza por link ativo:** 10 sub-ticks × N veículos × `MicroUpdateData`
  por sub-tick. Com uma fração relevante de links em MICRO, o acréscimo pode chegar a centenas de
  milhões/bilhões a mais — mantido como item opcional (D) por isso.
- **Ciclo de vida (F) é uma vez só**, mas relevante em SP (6,5 M atores de infra × ~4 mensagens ≈
  26 M) e quase irrelevante em Toulouse (~6 M).
- **Premissas de demanda não medidas:** divisão modal e comprimento de rota (30 links/carro em SP,
  25 em Toulouse, ~60% carro em Toulouse) são **estimativas razoáveis**, não medidas. O `mode` real
  de Toulouse vive em `toulouse_population.xml.gz` (não no CSV — ver `tools/toulouse/README.md`); se
  a divisão modal exata for extraída, as categorias B/C/E se ajustam proporcionalmente.
- **Não inclui** o custo de rede do próprio Pekko (heartbeats, cluster, sharding, gossip), nem o
  tráfego Kafka/Redpanda de publicação de custo de roteamento (`htc.routing.link-cost.*`).

---

## 7. Referências

- `core/actor/SimulationBaseActor.scala` — `onFinishSpontaneous` (`FinishEvent`), `scheduleEvent`
  (`ScheduleEvent`), `sendMessageTo`, `report`.
- `core/actor/manager/time/LocalTimeManagerBase.scala` — `scheduleEvent`/`finishEvent`/
  `sendSpontaneousEvent`; `ConservativeLocalTimeManager.scala` — protocolo de barreira.
- `model/hybrid/actor/Movable.scala` — `enterLink`/`leavingLink` (fluxo MESO por link).
- `model/hybrid/support/car/CarLinkHandler.scala` — cálculo de `exitTick` e wake de saída do link.
- `model/hybrid/actor/Person.scala` + `support/person/*` — plano orientado a eventos, handlers por modo.
- `model/hybrid/support/person/PersonWalkingTripHandler.scala` — salto mesoscópico de caminhada.
- `model/hybrid/actor/Link.scala` — MICRO (10 sub-ticks/tick global).
- `model/hybrid/util/VehicleSimulationConfig.scala` — `simulationEndTick` default 86400.
- `src/main/resources/application.conf` — seção `time-manager` (instâncias, lookahead, window-size).
- Geradores: `tools/sao-paulo/generate_hybrid_input.py`, `tools/toulouse/generate_hybrid_input.py`;
  dados medidos em `tools/sao-paulo/output/sp_1pct/data/` e `tools/toulouse/*.csv|*.xml.gz`.



