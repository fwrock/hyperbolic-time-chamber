# MESO: tempo artificial em nós e arredondamento por link (medição e ajuste opcional)

Data: 19/11/2026. Origem: a hipótese de que o HTC acrescenta 1 a 2 s a cada interação com um nó e que isso acumula ao longo da viagem MESO mesmo sem congestionamento.

## O que o código fazia

1. **Um tick fixo por nó.** `CarLinkHandler.handleLeaveLink` agenda o próximo passo em `tick + 1` (a origem MESO usa `tick`). Com tick de 1 s, cada transferência link-nó-link custa 1 s, mesmo com sinal verde (a resposta do Node e a travessia do nó já eram instantâneas). A estratégia de agendamento (`node-local-tick`, com transporte conservativo `direct`) **não** elimina esse tick.
2. **Arredondamento para cima de cada link.** `exitTick = tick + ceil(tempo)` acrescenta em média cerca de meio tick por link (de 0 a quase 1).

## Medição (cenário sintético de engenharia: 30 links de 60 a 350 m, 20 carros, fluxo livre, sem sinal; `meso_line_fixture.py`, `analyze_meso_quantisation.py`)

Tempo contínuo da viagem (soma dos `travel_time` reportados na entrada de cada link): 375,6 s.

| Configuração | Tempo de viagem | Extra por viagem | Extra relativo |
| --- | --- | --- | --- |
| Como estava | 419,0 s | +43,4 s | **11,5%** |
| `htc.meso.immediate-node-transfer = true` | 390,0 s | +14,4 s | 3,8% |
| `htc.meso.carry-rounding = true` sozinho | 419,0 s | +43,4 s (sem efeito: o tick do nó absorve o crédito) | 11,5% |
| as duas | 376,0 s | **+0,4 s** | **0,1%** |

Cerca de 30 s dos 43 s vêm do tick por nó e cerca de 14 s do arredondamento por link. O efeito relativo cresce quando os links são mais curtos.

## O que foi implementado (opcional; **ligado por padrão por decisão do autor**, 19/11/2026)

- `htc.meso.immediate-node-transfer` (ou `HTC_MESO_IMMEDIATE_NODE_TRANSFER=true`): a transferência para o próximo link ocorre no mesmo tick (`t' = t`).
- `htc.meso.carry-rounding` (ou `HTC_MESO_CARRY_ROUNDING=true`): o resto do arredondamento (crédito) passa ao link seguinte; uma espera em nó o absorve; mínimo de um tick por link. Lógica pura em `MesoLinkTiming` com testes de unidade.
- **Padrão: as duas ligadas.** Para reproduzir resultados MESO anteriores a esta mudança, definir `htc.meso.immediate-node-transfer = false` e `htc.meso.carry-rounding = false` (ou `HTC_MESO_IMMEDIATE_NODE_TRANSFER=false` e `HTC_MESO_CARRY_ROUNDING=false`). Os resultados MESO e as comparações com o MATSim feitos antes de 19/11/2026 usaram o comportamento antigo.

## Limitações

Medido em um cenário sintético de fluxo livre e uma única rede linear; o efeito em uma rede real depende da distribuição dos comprimentos de link e de congestionamento/sinais, e **não foi medido**. A espera de 2 s por posição de fila no vermelho (`CarSignalHandler`) é outro tempo fixo e **não foi alterada**. `Subway` e `Person` (caminhada) **não** foram alterados.

## Extensão a bicicleta, moto e ônibus (mesmo dia)

As mesmas duas opções (padrão **ligadas**) passaram a valer para `Bicycle`, `Motorcycle` e `Bus`, com a lógica comum em `MesoLinkTiming` (trait `MesoTimingState` com o crédito de arredondamento e o último tick de saída, no estado e portanto no checkpoint).
- Bicicleta e moto: o `ceil` por link e o `+1` na saída do link tinham o mesmo padrão do Car.
- Ônibus: o tempo do link era **truncado** (`time.toLong`, viés para menos) e a saída também usava `+1`; com a opção ligada passa a usar o mesmo cálculo contínuo.
- Dois testes existentes tiveram a expectativa atualizada (`CarMesoTraversalSpec` e `BicycleMesoTraversalSpec`: o próximo link começa no tick da resposta de saída, `exitTick + 3`, antes `exitTick + 4`). Suíte completa na árvore de trabalho: **1.148 passam e 1 falha** (`SettingsRoutesHttpSpec`, que vem das alterações de settings sem commit, já presente antes).
- **Medição só do Car** (cenário sintético); bicicleta, moto e ônibus **não** foram medidos em execução, só cobertos por testes de unidade e pela mesma função.
