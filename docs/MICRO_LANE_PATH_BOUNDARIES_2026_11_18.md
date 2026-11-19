# MICRO G02 — líderes a jusante por caminho de faixa

Data: 18/11/2026. Estado: **implementado e testado em unidade; não executado em cenário integral nem medido em desempenho**.
Escopo: Car/Link, MICRO, 1 passo físico por tick. Não cobre G03 (handoff/spillback multifaixa), G04 (conflitos) nem G05 (brechas).

## Problema

Após `2b6fab6`, cada Link publicava ocupação por faixa (`MicroLaneBoundary`), mas ninguém a consumia: o consumo
(`LinkFollowingHandler.leaders`) usava um único veículo traseiro agregado entre todas as faixas e
`LinkState` exigia `lanes == 1` para `downstreamLookahead`. Consequências em multifaixa: um veículo numa faixa
seria bloqueado por ocupação de outra, e MOBIL/LC2013 avaliavam a faixa candidata sem enxergar o líder real
a jusante dela.

## O que foi implementado

1. **Caminho tipado** (`LaneConnection`): faixa de origem → `toLane` (faixa do primeiro Link a jusante: o conector se
   `downstreamConnectors` o declara, senão a via de saída) → `exitLane` (faixa da via de saída atrás do conector;
   ausente = mesmo índice). Campo opcional com default: JSON legado continua válido.
2. **`LinkFollowingHandler.laneLeader(tick, road, lane, exclude)`**: líder físico do caminho (road, faixa), em coordenadas do
   Link atual. Consulta **somente a faixa que o caminho realmente entra** (`rearmost` daquela faixa), então ocupação em outra
   faixa não bloqueia. Conector vazio → olha a faixa mapeada da via seguinte (mesma semântica anterior, agora por faixa).
   Sem conexão, sem observação ou sem detalhe de faixa → **sem líder = desconhecido, não via livre** (mesmo princípio de G02).
   `leaders(tick)` (um líder por veículo, na faixa atual) passou a derivar dele.
3. **Estratégias de mudança de faixa** (MOBIL e LC2013): segurança frontal, incentivo (MOBIL) e ganho previsto (LC2013)
   usam o líder da faixa candidata: o veículo à frente no mesmo Link, ou, se não houver, o líder a jusante do caminho
   dessa faixa (veículos do mesmo Link estão sempre mais próximos). A lógica de cooperação/yield **não** foi alterada
   (continua só com veículos do mesmo Link).
4. **Guards mantidos/ajustados** em `LinkState`: lookahead multifaixa exige `laneConnections` não vazio;
   `downstreamSpillback` continua exigindo `lanes == 1` (G03). `receive` recusa snapshot multifaixa sem ocupação por faixa e
   spillback retido sobre jusante multifaixa. `MicroBoundarySnapshot.supported` agora vale para qualquer link MICRO de 1
   passo/tick; a segurança vem do detalhe por faixa exigido no `receive`. (Teste anterior que afirmava `supported=false`
   para multifaixa foi atualizado: refletia o contrato pré-G02.)

## Arquitetura e custo (restrições do autor)

- **Nenhuma mensagem nova, nenhuma rodada do Time Manager, nenhum bloqueio**: reutiliza a publicação existente
  (`sendMessageTo`, fire-and-forget) e as duas coortes de ticks de `MicroBoundaryHistory` (física em t usa observação
  estritamente anterior a t).
- Custo adicional por tick: por veículo, uma busca em lista pequena (conexões) e em até duas observações; sem alocação de
  estruturas por tick além do `Option` resultante. **Não medido**: não foi feito benchmark; o custo é argumentado por
  inspeção, não demonstrado. Medir antes de afirmar ausência de regressão (cenário integral, 1 e 2 JVMs).
- Caminho single-lane sem `laneConnections` mantém o comportamento anterior (conexão ausente cai na faixa 0 só nesse caso).

## Fontes das decisões

- **MOBIL**: Kesting, Treiber, Helbing (2007), "General Lane-Changing Model MOBIL for Car-Following Models",
  *Transportation Research Record* 1999. O critério de segurança e o incentivo dependem do líder e do seguidor na faixa
  alvo; por isso o líder da faixa candidata deve incluir quem está além do fim do Link.
- **LC2013 / SUMO**: Erdmann (2015), "SUMO's Lane-Changing Model", em *Modeling Mobility with Open Data* (Springer).
  Usado para a motivação de que líderes consecutivos na faixa alvo entram na avaliação. **Não verifiquei o código-fonte do
  SUMO nesta sessão**; a equivalência de detalhe com o SUMO não está estabelecida.
- **Krauss**: Krauss (1998), tese, DLR; velocidade segura reutilizada sem mudança.
- Contrato interno de causalidade/observações: `MICRO_CROSS_LINK_FOLLOWING.md`, `MICRO_RECONSTRUCTION_AND_SNAPSHOT_2026_11_18.md`.
- A escolha "ausência de dado = desconhecido" vem do critério de aceitação de G02 em
  `MICRO_EMPIRICAL_GAPS_AND_ACCEPTANCE.pt.md`.

## Evidência executada

- 78 testes Scala aprovados no conjunto MICRO (inclui `MicroLanePathLeaderSpec`, novo): ocupação em outra faixa não
  bloqueia; conector vazio olha a faixa de saída mapeada; conexão/observação ausente não vira líder; recusa de dado
  multifaixa sem detalhe; MOBIL e LC2013 recusam a troca quando há líder na faixa candidata além do fim do Link
  (controle: sem líder a troca ocorre).
- **Não executado**: cenário integral conservativo/Time Warp, 1 e 2 JVMs, assembly, testes Python, medição de desempenho.

## Lacunas remanescentes

- A seleção de faixa de **entrada** no Link a jusante e a segurança da faixa de destino na admissão seguem em G03.
- Só a faixa-líder traseira (`rearmost`) é usada; prioridade/brechas (frontmost, conflitos) pertencem a G04/G05.
- Uma conexão `toLane` fora do número real de faixas do conector é detectada só ao receber (sem líder), não na carga.

## Medição de desempenho (adendo, 19/11/2026)

Carga sintética de engenharia (não é tráfego empírico): `scripts/scenario/micro_lane_path_benchmark.py`, 40 corredores
independentes × 25 carros (1.000 carros), `lookahead` na via de aproximação, 1 JVM, conservativo, 5 repetições
intercaladas, jars em `experiments/`. Tempo do motor = intervalo entre o fim de `post_load_registration` e o
`StopSimulationEvent` do `report-manager` no `runtime.log` (exclui a subida da JVM).

| Cenário | Jar | n | Média (s) | Desvio (s) |
| --- | --- | --- | --- | --- |
| 1 faixa, lookahead | anterior (`snapshot-engine-final.jar`) | 5 | 26,12 | 1,56 |
| 1 faixa, lookahead | G02 (`claude-g02/engine.jar`, sha256 `4b2e1bf1…`) | 5 | 26,33 | 0,55 |
| 2 faixas, conexões, MOBIL | G02 | 5 | 27,88 | 2,14 |

Leitura: com 1 faixa (único caso que os dois jars executam) a diferença de médias (0,2 s) é menor que o desvio; **não há
evidência de regressão nesta carga, mas também não há poder para detectar uma regressão pequena** (n=5). O cenário de 2 faixas
só roda no jar novo (o antigo o recusa), logo não é comparável; sua média maior mistura cena diferente, troca de faixa e
variância. Limites: densidade baixa (carros espaçados 3 s), um nó, conservativo apenas, sem Time Warp nem 2 JVMs nesta
medição. Todas as 15 execuções terminaram com `engineCompleted=true` e `runtimeFailed=false`.
