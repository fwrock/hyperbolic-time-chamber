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

## Emenda 1 (antes de qualquer distribuição de resultados)

A primeira execução do extrator devolveu **0 episódios**, sem nenhuma medida, por dois defeitos da regra original, ambos de definição e
verificados por contagem simples (sem olhar m1, m2 ou m3):

- A janela de 30 m cruza a caixa da interseção, onde `lane_id` vale 0 (3.275 linhas de 3.464 entre a linha e o fim da caixa); a regra
  exigia faixa 1 ou 2. **Nova regra: `lane_id ∈ {0, 1, 2}` (0 = dentro da interseção).**
- O campo `preceding` está preenchido em quase todos os veículos parados na linha (4.110 contra 114 sem líder na 10th; 1.709 contra 5 na
  11th), então `preceding == '0'` não identifica o líder da fila. **Novo critério de líder, o mesmo para dados observados e simulados:
  nenhum outro veículo NB na mesma faixa, ou na faixa 0, com a frente em `(x, x + 40]` m no quadro `f0`.**

O restante (medidas, decisão e verificação) não muda. A emenda é anterior à leitura de qualquer valor de m1, m2 ou m3.

## Resultados (protocolo aplicado como emendado; extrator `measure_ngsim_departures.py`)

**Desenvolvimento, meio-dia (13 episódios: 3 na 10th, 3 na 11th, 4 na 12th, 3 na 14th):**

| Medida | Observado: mediana (p10, p90) | Car nativo (analítico) | Car nativo (mesmo operador na saída simulada, n = 4) |
| --- | --- | --- | --- |
| m1 tempo para 30 m (s) | 5,9 (5,3 a 7,1) | 4,8 | 4,7 |
| m2 velocidade a 30 m (m/s) | 8,74 (7,21 a 9,67) | 12,5 | 12,5 |
| m3 aceleração equivalente (m/s²) | 1,72 (1,19 a 2,14) | 2,6 | 2,72 |

O mesmo operador sobre o modelo reproduz o valor analítico (4,7 s e 2,72 m/s²), então a diferença não vem do extrator.
**Regra de decisão 1:** m1 difere do nativo em 1,1 s (metade da largura p10 a p90 observada: 0,9 s) e m3 em 0,88 m/s² (metade: 0,48), os dois
acima do limite declarado; portanto a aceleração máxima do Car no cenário passou a **1,72 m/s²** (mediana de m3 do meio-dia, n = 13).

**PM (vista; n = 4, não informativo sozinha):** m1 mediana 7,65 s (p10 6,7, p90 13,5), m3 mediana 1,07 m/s².

**Verificação na PM, sem reajuste (interseções 1 e 2, 80 veículos, cenário `--max-acceleration 1,72`):**

| Medida | Antes (2,6 m/s²) | Depois (1,72 m/s²) | Observado |
| --- | --- | --- | --- |
| Erro mediano na linha da 11th (s) | −7,1 | **−6,8** | 0 |
| Tempo de via, mediana (s) | 9,5 | **9,8** | 15,4 |
| Dentro de ±5 s na 11th | 20 | 24 | |
| Escorregões de ciclo (10th / 11th) | 11 / 3 | 11 / 3 | |

**Conclusão (limitada):** a aceleração de saída observada é menor que a do Car, mas **corrigi-la explica só cerca de 0,3 s dos ≈ 6 s** de
diferença na via entre as interseções. O modelo continua a atravessar a via em ≈ 9,8 s, próximo do tempo à velocidade limite
(126 m a 13,4 m/s = 9,4 s), enquanto o observado leva 15,4 s (≈ 8,2 m/s de média). Logo, **a maior parte da diferença não está na saída da
fila**: os veículos observados cruzam a via a uma velocidade típica bem menor que o teto, o que aponta para a velocidade desejada dos
motoristas (distribuição, não valor único) ou para fatores não modelados. Isso não foi testado; é a hipótese para o próximo protocolo.
Limites: n = 13 no desenvolvimento (intervalos largos), 8 min de meio-dia, e nenhum limiar de aprovação foi usado.
