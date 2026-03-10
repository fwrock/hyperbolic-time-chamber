# SUMO ↔ HTC de/para de métricas (tripinfo.xml e summary.xml)

Este documento descreve o mapeamento entre as métricas do SUMO e os novos eventos emitidos pelo simulador HTC híbrido.

## Eventos adicionados

### 1) `sumo_tripinfo`
- **Onde é emitido:** atores `Car`, `Bus`, `Bicycle`, `Motorcycle`
- **Quando:** ao finalizar jornada (`journey_completed`)
- **Objetivo:** equivalente por veículo ao `tripinfo.xml`

### 2) `sumo_summary_step`
- **Onde é emitido:** ator `Link`
- **Quando:** em cada `handleGlobalTick` (escopo link)
- **Objetivo:** equivalente por passo de tempo ao `summary.xml` (escopo local do link)

---

## De/para: `tripinfo.xml` → evento `sumo_tripinfo`

| SUMO (`tripinfo.xml`) | Campo no evento `sumo_tripinfo` | Observação |
|---|---|---|
| `id` | `vehicle_id` | ID do ator do veículo |
| `depart` | `depart` | Tick de partida (start trip / primeira entrada em link) |
| `arrival` | `arrival` | Tick atual no encerramento |
| `duration` | `duration` | `arrival - depart` |
| `routeLength` | `routeLength` | Distância acumulada do estado do veículo |
| `waitingTime` | `waitingTime` | Soma de tempo em espera por sinal vermelho |
| `waitingCount` | `waitingCount` | Número de episódios de espera em sinal |
| `stopTime` | `stopTime` | Acumulado de parada (mesmo acumulador de espera) |
| `timeLoss` | `timeLoss` | `duration - tempo_esperado` (clamped em 0) |
| `departDelay` | `departDelay` | Atualmente `0` |
| `rerouteNo` | `rerouteNo` | Atualmente `0` |
| `arrivalSpeed` | `arrivalSpeed` | Velocidade final conhecida |
| `departSpeed` | `departSpeed` | Velocidade inicial na primeira entrada em link |
| `vType` | `vType` | `car`, `bus`, `bicycle`, `motorcycle` |
| `speedFactor` | `speedFactor` | Driver attrs nos privados; `1.0` para `bus` |
| (extra HTC) | `vehicle_type` | Tipo textual do veículo |
| (extra HTC) | `origin`, `destination`, `final_node`, `completion_reason`, `tick` | Metadados adicionais |

---

## De/para: `summary.xml` → evento `sumo_summary_step`

> **Importante:** no HTC atual este evento é emitido em **escopo de link** (`scope = link`, `link_id`), não agregado global da simulação inteira.

| SUMO (`summary.xml`) | Campo no evento `sumo_summary_step` | Observação |
|---|---|---|
| `time` | `time` | Tick do passo |
| `loaded` | `loaded` | Cumulativo de veículos carregados no link |
| `inserted` | `inserted` | Inseridos no passo (tick) |
| `running` | `running` | Veículos atualmente no link |
| `waiting` | `waiting` | Aproximação por `halting` |
| `ended` | `ended` | Cumulativo de saídas do link |
| `arrived` | `arrived` | Saídas no passo (tick) |
| `collisions` | `collisions` | Atualmente `0` |
| `teleports` | `teleports` | Atualmente `0` |
| `halting` | `halting` | Veículos com velocidade ≤ 0.1 m/s (micro) |
| `stopped` | `stopped` | Mesmo valor de `halting` |
| `meanWaitingTime` | `meanWaitingTime` | Atualmente `0.0` |
| `meanTravelTime` | `meanTravelTime` | Média de tempos de saída observados no passo |
| `meanSpeed` | `meanSpeed` | Média de velocidade no link |
| `meanSpeedRelative` | `meanSpeedRelative` | `meanSpeed / freeSpeed` |
| `discarded` | `discarded` | Atualmente `0` |
| (extra HTC) | `scope`, `link_id`, `mode`, `tick` | Metadados de escopo/diagnóstico |

---

## Sugestão de agregação para comparação com SUMO

Para comparar com o `summary.xml` global do SUMO, agregue todos os eventos `sumo_summary_step` por `time`:
- soma: `loaded`, `inserted`, `running`, `waiting`, `ended`, `arrived`, `collisions`, `teleports`, `halting`, `stopped`, `discarded`
- média ponderada (ou média simples, conforme sua análise): `meanTravelTime`, `meanSpeed`, `meanSpeedRelative`, `meanWaitingTime`

Assim você obtém um dataset no mesmo formato lógico do SUMO para de/para e validação.
