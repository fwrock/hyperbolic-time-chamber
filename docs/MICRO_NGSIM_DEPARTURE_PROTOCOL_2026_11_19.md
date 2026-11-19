# Protocolo: aceleração de saída de líderes de fila (Peachtree NB), escrito antes de calcular

Data: 19/11/2026. Objetivo: decidir, com regra declarada antes, se a diferença de ≈ 6 s entre modelo e observado entre a 10th e a 11th
(ver `MICRO_NGSIM_CORRIDOR_SLICE2_2026_11_19.md`) está na aceleração de saída. **Divulgação:** antes deste protocolo olhei, na gravação
PM, um recorte minúsculo com filtro diferente (n = 2 líderes de fila: mediana 5,2 s para cobrir 30 m, aceleração equivalente 2,37 m/s²). Por isso a PM
já é considerada vista para esta pergunta; a gravação do meio-dia (origem 1163018600, quadros 5 a 4800, 183 veículos NB) é a fonte de desenvolvimento.

## Regra de seleção (fixa)

Episódio de saída: veículo NB, `movement == '1'` e `lane_id ∈ {1, 2}` em todas as linhas do episódio; para uma linha de retenção
`b ∈ {38,774; 196,17; 359,74; 600,6}` m, início `f0` = último quadro com `v ≤ 0,5 m/s`, posição da frente em `[b − 8, b]` m e `preceding == '0'`,
seguido de quadros com `v` ultrapassando 3 m/s; o episódio é válido se a frente alcança `x0 + 30` m em até 20 s. O mesmo
operador é aplicado às trajetórias observadas e às simuladas (posição do corredor = `offset + posição` no Link).

## Medidas

(m1) tempo para cobrir 30 m a partir de `x0`; (m2) velocidade em `x0 + 30` m; (m3) aceleração constante equivalente `2·30/t²`.
Relato mediana, p10, p90 e n, por gravação, e a comparação com o modelo (analítico: 4,8 s, 12,5 m/s e 2,6 m/s² para o Car nativo; e o
operador aplicado à saída simulada da PM).

## Decisão (fixa, antes de ver os números)

1. Se as medianas do meio-dia de m1 e m3 diferirem do Car nativo por mais que a metade da largura p10 a p90 observada, a aceleração máxima
   do Car no cenário passa a ser a mediana de m3 do meio-dia (parâmetro do cenário, fonte e n registrados); senão, o parâmetro não muda e a
   causa da diferença fica sem explicação aceita.
2. Verificação na PM, sem reajustar: refazer o cenário de duas interseções e relatar o erro mediano na linha da 11th e o tempo de via
   observado contra o do modelo. Sem limiares de aprovação; relato de todos os resultados, também os desfavoráveis.
3. Não alterar a desaceleração, o tempo de reação nem o teto de velocidade neste protocolo.

## Limitações declaradas

Dados do meio-dia cobrem 8 min (poucos ciclos); estações com ±0,76 a ±2 m; a velocidade vem do campo `v_vel` do NGSIM (derivada
das posições); líderes de fila podem ter partida influenciada por fatores não modelados (pedestres, conversões na frente).
