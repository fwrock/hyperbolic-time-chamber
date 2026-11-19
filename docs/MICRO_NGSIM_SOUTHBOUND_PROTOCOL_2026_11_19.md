# Protocolo: sentido sul (direção 4) com o mesmo tratamento do norte, escrito antes de calcular

Data: 19/11/2026. Objetivo: ter o sentido sul modelado e medido da mesma forma que o norte, pré-requisito para as conversões à esquerda do norte (que cedem ao sul reto).
**Divulgação:** nada do sul foi calculado antes deste protocolo, exceto as estimativas de linha de retenção abaixo e a inspeção dos arquivos de sinal.

## Cenário (fixo)

Coordenadas espelhadas `s' = 359,74 − local_y` (o sul se desloca para `s'` crescente). Interseções na ordem de encontro: **11th, depois 10th**. A caixa física é a mesma do norte.
Linha de retenção do sul = estimativa pelo percentil 10 das frentes dos líderes parados no vermelho observado (leaders: nenhum veículo à frente na mesma faixa em 15 m):
**236,9 m (11th) e 72,6 m (10th)** em `local_y` (±≈ 3 m, mesma incerteza que a do norte; p2 foi 236,9 e 71,4), fim da caixa = linha de retenção do norte (196,17 e 38,774 m),
fim da via de saída = `local_y = 0`. Sinais SB medidos de `Peachtree_11th_SB` e `Peachtree_10th_SB` (reto); **os arquivos de 10th e 11th SB são idênticos aos NB byte a byte**
(ver observação anterior); janela de quadros 342 a 9000 da PM (o primeiro aspecto observado do reto da 11th é 342). Regra de inclusão **a mesma do protocolo `ALL_NB`**
(`--include-all`): todos os veículos SB com faixas em {0, 1, 2}, categorias `through_only`, `side_entry`, `turn_off_proxy`, `ends_in_road`; bolsos excluídos e contados.
Aceleração máxima 1,72 m/s² (estimada no norte, **suposta igual no sul**). Velocidade desejada: população estimada no **meio-dia, direção 4**, pela mesma regra do protocolo de
velocidade desejada (percentil 90 das linhas sem líder, n ≥ 50 linhas por veículo, `|população| ≥ 30`; a mediana deve estar entre 6 e 13,5 m/s), sorteio com reposição, sementes 1 a 5.

## Medidas e decisão (fixas)

Nos veículos `through_only` do sul: erro de cruzamento da linha de cada interseção (mediana, p10, p90), dentro de ±5 s, escorregões de ciclo e o tempo de via entre as duas
interseções (observado contra modelo). **Sem limiar de aprovação**; as cinco sementes são relatadas. Falha de execução: causa registrada, nenhuma semente descartada sem registro.
Não se calibra nada no sul com a PM; se a população do meio-dia não cumprir as condições de validade, o sul fica com o teto de via e o resultado é declarado inconclusivo.

## Limitações declaradas

Sul assumido com a mesma aceleração do norte; linhas de retenção estimadas (não levantadas); sem conversões à esquerda do sul contra o norte (o sul também tem esquerdas ignoradas); 
a PM já foi vista em agregado; o desempenho do perfil de velocidade do sul não está incluído nesta rodada.

## Resultados

**Velocidade desejada do sul: população inválida pelo protocolo.** A estimação do meio-dia (direção 4) deu **n = 26 (< 30)**, mediana 12,93 m/s, p10 11,15, p90 15,57.
Conforme a regra congelada, o sul **ficou com o teto de via** (sem sorteio de velocidade desejada) e o resultado de velocidade é declarado **inconclusivo**; não há sementes (nada aleatório).

**Três tentativas, duas com defeito do meu gerador, registradas e preservadas** (saídas movidas para `experiments/claude-g01/invalid-sb-*`):

1. O filtro de domínio aceitava linhas com `s' < 0` (ao norte do domínio): só 6 veículos avaliáveis e 220 "inlocalizáveis". Corrigido (`0 ≤ s' ≤ fim`).
2. Ainda 9 veículos avaliáveis e 120 inlocalizáveis: o primeiro quadro desses veículos cai a 0,4 m do início do domínio com `lane_id = 0` ("dentro da interseção 3"), embora a via já comece ali; o
   gerador o tratou como inlocalizável. Corrigido: linhas iniciais de faixa 0 que já estão sobre uma via são descartadas e a primeira linha na faixa 1 ou 2 é o estado inicial.
3. Execução válida: 181 veículos incluídos (102 `through_only`, 52 `side_entry`, 27 `other_road_entry`), 63 excluídos por bolso/faixa desconhecida, 3 nunca em faixa 1 ou 2; `engineCompleted`, sem erros.

**Medidas nos 102 veículos `through_only` do sul (PM, 11th e 10th, aceleração 1,72 m/s², sem velocidade desejada por veículo):**

| Medida | 11th | 10th |
| --- | --- | --- |
| Veículos comparáveis | 96 | 83 |
| Erro de cruzamento da linha (modelo menos observado): mediana, p10, p90 | −2,7 s, −6,2, +1,1 | −2,5 s, −13,4, +61,8 |
| Dentro de ±5 s | 71 | 30 |
| Modelo um ciclo depois (> 20 s) | 3 | **18** |

Tempo de via da 11th até a linha da 10th (inclui fila): observado mediana **70,7 s** (p10 9,3, p90 77,7), modelo mediana **71,3 s** (p10 9,2, p90 75,0). RMSE de posição 31,7 m.

**Leitura (limitada):** na 11th o sul é reproduzido com pouco viés (mediana −2,7 s, 74% dentro de ±5 s), melhor que o norte. Na 10th há **18 de 83 escorregões de ciclo (22%)**, mais do que os 14% do norte;
o arquivo de sinal do SB da 10th é idêntico, byte a byte, ao do NB, o que pode ser um erro de origem da fonte e é a primeira suspeita para os escorregões do sul (não testada). Tempos de via longos (≈ 70 s) mostram
que o sul passa grande parte do tempo em fila entre as duas interseções, e o modelo reproduz a mediana. Limites: sem velocidade desejada (população inválida), aceleração do norte suposta igual no sul, linhas de retenção ±3 m,
a PM já vista em agregado e o conflito das esquerdas não modelado.
