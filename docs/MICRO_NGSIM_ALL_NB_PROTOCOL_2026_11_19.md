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
