# Protocolo: incluir todos os veículos do sentido norte (proxy de fronteira observada), escrito antes de calcular

Data: 19/11/2026. Pergunta: o cruzeiro lento e a fila longa observados entre a 10th e a 11th passam a ser reproduzidos quando o modelo recebe **todos** os
veículos do sentido norte e não só os de movimento reto? (Fundamento: `MICRO_NGSIM_SPEED_PROFILE_PROTOCOL_2026_11_19.md`, incluindo a correção do
mesmo dia: o líder excluído não explica diretamente, mas a densidade do modelo é ≈ 40% da real.) Cenário: PM, interseções 1 e 2, mesmos parâmetros da condição B
do protocolo de velocidade desejada (aceleração 1,72 m/s², população do meio-dia, sementes 1 a 5). **Divulgação:** a PM já foi vista em agregado.

## O que entra (regra fixa; baseada na geometria do trajeto, não nos rótulos de movimento)

Todo veículo NB com faixas em `{0, 1, 2}` em todas as linhas do domínio (≤ fim da via de saída da 11th). Excluídos e contados: veículos com faixa de bolso ou
desconhecida. Categoria (a primeira que valer):

1. `through_only`: movimento 1 em todas as linhas (como antes);
2. `side_entry`: primeira linha dentro de uma caixa ou na faixa 0: injetado no **início da via de saída dessa caixa**, posição 0, com a velocidade e a faixa (1 ou 2,
   a primeira observada nessa faixa) do primeiro estado observado; a velocidade e o tempo são os observados (fronteira observada, não demanda modelada);
3. `turn_off_proxy`: última linha dentro da caixa k (k < última interseção modelada): rota até a via de saída dessa caixa e chegada a **3,0 m** dela (o
   veículo atravessa o conector como os demais e sai logo depois; a conversão em si, o conflito com o sentido sul e a rua transversal **não são modelados**);
4. `ends_in_road`: última linha numa via: chegada à última posição observada (a trajetória termina onde o rastreamento termina).

Estado inicial exato e demais parâmetros iguais ao protocolo anterior. A população de velocidade desejada vem do meio-dia, nunca do próprio veículo.

## Comparação (fixa)

As medidas continuam sendo calculadas **apenas nos veículos `through_only`** (mesmas linhas, mesmos critérios dos protocolos anteriores), para comparar
com os resultados já relatados: erro na linha da 11th (mediana, p10, p90, ±5 s, escorregões), tempo de via (observado contra modelo) e o perfil de velocidade
por caixa de 10 m (estrato em movimento). Condições: B (só movimento reto, resultado já existente) contra C (todos, cinco sementes). Sem limiar de aprovação;
relato os cinco resultados. Se alguma execução abortar, a causa é registrada e **nenhuma** semente é descartada sem registro.

## Limitações declaradas

`side_entry` e `turn_off_proxy` usam o comportamento observado dos veículos na fronteira, o que torna o experimento parcialmente forçado (não preditivo)
para eles; os veículos `through_only` continuam sendo avaliados sem uso das suas observações além do estado inicial. Conversões à esquerda não ceditam ao sentido
sul no modelo; o tráfego transversal não ocupa a caixa; pedestres não existem.

## Resultados

Cenário C: 136 veículos incluídos (80 `through_only`, 49 `side_entry`, 7 `other_road_entry`), 74 excluídos por faixa de bolso ou desconhecida; **a exclusão de bolso (conversões à esquerda
que esperam fora das faixas 1 e 2) permanece**. Jar `experiments/claude-g06/engine.jar` (sha256 `33519b84…`), que inclui a correção do motor abaixo.

**Dois incidentes antes de qualquer resultado válido (registrados, nenhuma semente descartada em silêncio):**

1. As cinco execuções iniciais abortaram com `MICRO spillback entry has no physical room`. Causa no **motor**: a restauração de instantâneo medido passava pela checagem de espaço
   de entrada comum, que exige o gap desejado de 2 m e rejeita um instantâneo mais apertado, embora a sobreposição física já seja validada por `snapshotReason`.
   Corrigi (`LinkVehicleFlowHandler`: a checagem não vale para a restauração de instantâneo) e acrescentei teste (`MicroMultilaneHandoffSpec`).
2. Com o motor corrigido, as cinco abortaram de novo com `initial_snapshot_overlap`: os veículos `side_entry` do primeiro quadro eram injetados na posição 0 da via,
   sobrepondo-se ao instantâneo real. A injeção é um proxy meu, não uma posição medida, então passou a entrar como entrada comum na fila (tick ≥ 1).
   Saídas inválidas movidas para `experiments/claude-g01/invalid-snapshot-spillback-defect/` e `invalid-side-entry-snapshot-overlap/`.

**Medidas nos 80 veículos `through_only` (PM, interseções 1 e 2, cinco sementes; as cinco execuções concluíram):**

| Medida | A: 1,72 m/s², sem v. desejada | B: só reto + v. desejada (mediana, faixa) | **C: todos NB + v. desejada (mediana, faixa)** | Observado |
| --- | --- | --- | --- | --- |
| Erro mediano na linha da 11th (s) | −6,8 | −5,9 (−6,5 a −5,0) | **−3,3 (−3,9 a −2,2)** | 0 |
| Dentro de ±5 s na 11th (de 77) | 24 | 30 | **40 (35 a 45)** | |
| Escorregões de ciclo na 11th | 3 | 3 | 4 (4 a 5) | |
| Tempo de via, mediana (s) | 9,8 | 11,9 (11,3 a 12,9) | **13,1 (12,8 a 14,3)** | 15,4 |

Perfil de velocidade (estrato em movimento, mediana em m/s; modelo = mediana das cinco sementes): a diferença modelo menos PM cai em todas as caixas, por exemplo
72–82 m: +2,8 → +1,5; 92–102 m: +3,3 → +2,3; 152–162 m: +6,2 → +4,9; 172–182 m: +6,2 → +4,2. A desaceleração da aproximação começa mais cedo no modelo (≈ 140 m) mas ainda
mais tarde que o observado (≈ 130 m) e a velocidade permanece 1,3 a 4,9 m/s acima da observada em todo o trecho.

**Conclusão (limitada):** incluir todos os veículos do sentido norte **fecha cerca de dois terços da diferença restante de tempo de via** (de 3,5 s para 2,3 s) e leva o erro
na linha da 11th de −5,9 para −3,3 s. A hipótese de que a densidade (e não o líder direto) importa recebe **suporte**, não prova. A diferença que sobra (≈ 2,3 s de tempo de via; velocidade ainda alta
em todo o trecho) permanece sem causa identificada: ficam de fora os veículos de faixa de bolso, o tráfego transversal, os pedestres, o conflito das conversões à esquerda
com o sentido sul e a sincronização do sinal. Limites: `side_entry` e `turn_off_proxy` usam o comportamento observado na fronteira (parcialmente forçado); somente 80 veículos são avaliados;
o conjunto de 5 sementes mede variabilidade do sorteio da velocidade, não incerteza do dado; a PM já foi vista em agregado.

## Intervalos de confiança (adendo; `aggregate_corridor_results.py`)

Reamostragem de veículos (2.000 amostras, semente fixa, intervalo percentil de 95%), com os valores por veículo médios entre as cinco sementes. **Isso corrige a leitura das tabelas de medianas por semente acima**, que não tinham incerteza.
Medidas na 11th (77 veículos) e na 10th (75):

| Medida | B: só reto + v. desejada | C: todos NB + v. desejada |
| --- | --- | --- |
| 11th, erro mediano na linha (s) | −5,4 (−6,6 a −3,9) | −2,3 (−4,1 a −1,3) |
| 11th, dentro de ±5 s | 40% (30 a 52) | 57% (46 a 68) |
| 11th, erro mediano do tempo de via (s) | −3,1 (−4,4 a −2,0) | −2,1 (−3,1 a −0,5) |
| 10th, erro mediano na linha (s) | −0,18 (−0,48 a +0,02) | −0,1 (−0,3 a +0,14) |

**Comparação pareada nos mesmos veículos (C menos B, erro absoluto na linha):** na 11th, a **mediana** da diferença é **−1,26 s (−2,02 a −0,12)**, ou seja, C melhora e o intervalo exclui zero;
a **média** da diferença é −0,72 s (−1,65 a +0,27), que inclui zero. Na 10th a diferença é zero na mediana (esperado: a primeira interseção não depende do que vem antes). Portanto o ganho de incluir todos os veículos na 11th é **sustentado
para a mediana, mas não para a média** do erro absoluto; a frase anterior "fecha cerca de dois terços da diferença" descreve estimativas pontuais com incerteza, não uma garantia.
Limite: os intervalos reamostram veículos e tratam as sementes como réplicas do mesmo veículo; não capturam a incerteza dos parâmetros, da geometria nem a dependência entre veículos do mesmo ciclo.
