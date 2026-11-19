# MICRO G03 (parcial) — handoff físico e spillback multifaixa

Data: 19/11/2026. Estado: **primeiro incremento de G03 implementado; a avaliação multifaixa de ponta a ponta continua pendente**.
Depende de [G02](MICRO_LANE_PATH_BOUNDARIES_2026_11_18.md).

## Achado que muda o desenho

Antes deste incremento, o Car liberava a pegada física no Link de origem (`LeaveLinkData`) **no momento em que o Node concedia a
passagem**, e só depois pedia admissão ao Link seguinte. Logo a concessão de capacidade (uma contagem nominal por Link)
decidia a liberação, e a admissão física no destino podia falhar com `require(availablePosition >= -1e-9)` ou, com fila de
admissão, deixar o veículo fisicamente em lugar nenhum. Isso é o que `MICRO_EMPIRICAL_GAPS_AND_ACCEPTANCE.pt.md` (G03) chama de
"grant de capacidade não equivale a entrada efetiva".

## O que mudou

1. **Liberação adiada até o ACK de admissão** (`Movable.holdPreviousLinkUntilNextAdmitted`, sobrescrito em `Car`): se o Car tem
   uma pegada retida (`retainedMicroExitLinkId`) e a rota continua, o `LeaveLinkData` ao Link de origem sai quando chega o
   `MicroEnterLinkData` do Link seguinte, não na concessão. O comportamento padrão de `Movable` (outras entidades) não muda.
   No fim da rota nada é adiado.
2. **Guards removidos só onde a admissão física agora arbitra**: `microEntry` com `downstreamSpillback` e spillback com
   `lanes > 1`. Permanecem: lookahead exige `laneConnections` em multifaixa (G02), spillback exige lookahead, spillback só aceita
   Car. Um snapshot multifaixa sem ocupação por faixa continua recusado.
3. A fila de admissão já existente (`pendingMicroEntries`) segura o pedido numa faixa fisicamente cheia sem bloquear a outra;
   ao liberar, o ACK sai uma vez, com velocidade zero (espera real limpa a velocidade/remainder). Nada novo foi criado.

## Arquitetura e custo

Nenhuma mensagem nova, nenhum uso do Time Manager: a única mudança de protocolo é **mover** uma mensagem que já existia (o
`LeaveLinkData`) do instante da concessão para o instante do ACK, ambos no fluxo normal `sendMessageTo`. Estado novo: nenhum
(reaproveita `retainedMicroExitLinkId`, que já está em `CarJourneyState` e no checkpoint). Custo: nenhum caminho quente novo;
a fila só trabalha com pedidos pendentes. **Desempenho multifaixa saturado não foi medido.**

## Fontes

- Distinção entre capacidade nominal e espaço físico: Daganzo (1994), "The cell transmission model", *Transportation Research
  Part B* 28(4). Usado como motivação conceitual; não implementei o modelo de células.
- Contrato de causalidade, remainder e retenção: `MICRO_SPILLBACK.md`, `MICRO_CROSS_LINK_FOLLOWING.md`.
- **Não verifiquei o código do SUMO** para junção bloqueada; nenhuma equivalência com ele é afirmada.

## Evidência executada

- Suíte completa: 1.114 testes Scala passam e 1 falha (`SettingsRoutesHttpSpec`, "apply nested bodies atomically": a resposta
  traz 4 configurações em vez de 2, vazamento de overrides entre testes da API de settings). Não toquei nessa área e não confirmei
  em árvore limpa se ela já falhava antes; trate como pendência separada.
- Novos testes (`MicroMultilaneHandoffSpec`): construção multifaixa com spillback + fila; pedido numa faixa cheia espera sem
  falhar e sem bloquear a outra faixa; admissão atrasada ocorre exatamente uma vez com velocidade zero.
- Regressão de ponta a ponta com retenção real (`micro_boundary_publication_fixture.py --spillback --handoff`, dois carros
  cruzam a fronteira 11→12): **838 eventos físicos comprometidos idênticos** entre o jar anterior (`claude-g02`) e o novo
  (`claude-g03`, sha256 `d309fca5…`), conservativo e Time Warp, 1 e 2 JVMs. O jar inclui as alterações de settings/MESO do workspace.
- **Tentativa que falhou e foi abandonada:** os cenários congelados de `validate_micro_spillback.py` (2026-10-26) não rodam mais
  neste motor (relógio fracionário recusado para as fontes do cenário; o gerador passa `tickDuration` que o escritor atual
  ignora) e o inspetor confere hashes das entradas. Não são evidência nem a favor nem contra.

- **Fusão de duas faixas com admissão atrasada** (`micro_multilane_merge_fixture.py` + `check_micro_multilane_merge.py`; Link 11 com
  2 faixas e spillback, Link 12 com 1 faixa, fila de admissão e semáforo no fim): 6 carros, as faixas 0 e 1 fundem na faixa 0 do
  Link 12. Dois carros (103 e 105) ficaram retidos a montante por **96 ticks** (9,6 s) até a admissão; as 192 amostras
  `micro_exit_wait` batem com esses atrasos e não há amostra depois da admissão (a pegada saiu no ACK). Todos entram e saem de cada
  Link uma vez, as 6 viagens terminam, os carros retidos não se sobrepõem, e os 3.114 eventos físicos comprometidos são **idênticos**
  em conservativo e Time Warp, 1 e 2 JVMs. O atraso igual de 96 ticks vem do ciclo do semáforo do fixture, não é medida de
  comportamento. Não há jar anterior para comparar (ele recusa essa configuração), então a evidência é de invariantes e
  concordância entre runtimes, não de equivalência com a versão antiga.

## Não verificado / pendente

- A liberação adiada **não tem teste de unidade no nível do Car**; é coberta pelos dois cenários de ponta a ponta acima.
- Cancelamento e replay com handoff pendente (antes/depois do ACK) e rollback com a pegada retida e o ACK em voo.
- Capacidade por faixa no Node continua nominal por Link (decisão: só mudar se a medição mostrar bloqueio indevido).
- Saturação além do fixture (muitos carros, filas longas, mais de duas faixas) e medição de desempenho.
- Conflito/prioridade entre as duas faixas que fundem: aqui a ordem vem do pedido mais antigo, sem regra de prioridade (G04/G05).
