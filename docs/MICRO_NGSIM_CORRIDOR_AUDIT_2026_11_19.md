# NGSIM Peachtree NB: auditoria do corredor para o G01

Data: 19/11/2026. Evidência de **desenvolvimento** sobre a gravação PM já exposta (`experiments/micro-empirical-v3-20261118/heldout`,
10 páginas, 457.412 linhas); nada aqui é aceitação independente. Script: `scripts/scenario/audit_ngsim_corridor.py`
(saída local: `experiments/claude-g01/corridor-audit-pm.json`, com hashes das páginas de origem).

## O que os dados mostram (direção 2, sentido norte)

- **324 veículos** no sentido norte na gravação PM. Por movimento rotulado: **85 só em movimento "through"**, **155 com algum movimento
  de conversão à esquerda**, 64 com conversão à direita, 15 usando a faixa de bolso, 5 outros. Ou seja, só cerca de **26%** dos
  veículos seguem direto pelo corredor; **48%** convertem à esquerda em algum ponto.
- A faixa `0` nos dados marca "dentro da interseção". O rótulo de movimento muda por seção (um veículo pode ser `1` e depois `2`).
  Conversões à esquerda também partem das faixas 1 e 2, não só do bolso (faixa 11).
- O corredor tem 5 interseções (10th a 14th) em estações de ≈42, 197, 359, 484 e 599 m (início das regiões `int_id`), com seções
  entre elas de 126 a 100 m. Há arquivos de mudança de sinal para 4 delas (10th, 11th, 12th, 14th); a interseção 4 (13th) não tem
  arquivo e não foi tratada como sinalizada. Os arquivos NB e SB da 10th são idênticos byte a byte (limitação herdada).
- **Entradas dos veículos só em direção reta (85)**: 5 nascem na fronteira sul, 63 nascem dentro da seção 1 e os demais dentro de
  seções posteriores. Nascer dentro do domínio não é demanda de fronteira verificada (já registrado em G01).
- **Linha de retenção**: estimativa pelo primeiro parado durante vermelho observado, por faixa. Funcionou na 12th (359,7 m) e na 14th
  (600,6 m), próximos do início da região `int_id` (359,3 m e 598,6 m). Na 10th e na 11th houve menos de 3 amostras por faixa
  e o estimador não decide. O ortofoto manual da 10th dá 38,8 m, enquanto o início da região é 41,9 m: a diferença entre
  "início da região" e a linha medida varia de −3,1 a +2,0 m entre interseções. **Não use o início da região como linha de
  retenção sem carregar essa incerteza de ≈ ±3 m.**

## Consequência para o escopo (decisão pendente com o autor)

Um corredor autônomo completo exige conversões (inclusive à esquerda a partir de faixas compartilhadas), tráfego oposto (sentido
sul, **direção 4**; correção: a versão anterior deste texto dizia direção 3) para brechas permissivas, ruas transversais e bolsos. Isso depende de G04/G05 e de modelar o sentido sul. Excluir os
74% de veículos que convertem altera a dinâmica dos que seguem direto (bloqueio por esquerdistas em faixa compartilhada), então
um corredor "só through" não é uma validação do corredor. Opções:

- **A. Episódios** (seguimento e filas em faixas de passagem, como em Lankershim/Peachtree prospectivo): barato; a afirmação fica
  limitada a esses episódios, condicional.
- **B. Aproximações com pouco conflito de conversão**: escolher janelas/aproximações em que a faixa de interesse tem poucas
  conversões, com sinais medidos; exige varredura de janelas e declarar a regra de seleção antes de olhar o erro.
- **C. Corredor completo** com conversões e tráfego oposto: longo; depende de G04/G05.

## Códigos de direção (verificados pelo sinal das trajetórias, PM)

1 = leste (EB, dx > 0), 2 = norte (NB, dy > 0), 3 = oeste (WB, dx < 0), 4 = sul (SB, dy < 0). Linhas por direção: EB 13.534, NB 196.955,
WB 10.479, SB 236.444; veículos com ≥ 50 linhas: EB 20, NB 212, WB 14, SB 287. O sentido sul tem **mais** veículos que o norte; é o
tráfego oposto relevante para conversões à esquerda do norte. O erro de rótulo (direção 3 como sul) estava só neste texto; os
scripts do auditor usam apenas a direção 2.

## Limitações

Estimativas de linha de retenção não são levantamento topográfico; somente a gravação PM foi lida; sem calibração; o sentido sul e
as ruas transversais não foram examinados.
