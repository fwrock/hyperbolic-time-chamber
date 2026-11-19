# Protocolo: esquerda do norte contra o sul reto na 10th (G04/G05 com dados), escrito antes de calcular

Data: 19/11/2026. Pergunta: as regras de exclusão, prioridade e brecha (G04/G05), com uma brecha crítica estimada no **meio-dia**, reproduzem na **PM** o comportamento das
conversões à esquerda do sentido norte que cedem ao sentido sul reto na interseção 1 (10th)? **Divulgação:** nada das esquerdas foi calculado antes. A PM já foi vista em agregado.

## Estimação da brecha crítica (meio-dia, regra fixa)

Esquerda observada: veículo NB cuja **última linha está na caixa 1** (`int_id == '1'`) com `movement == '2'`. Para cada uma, `t_L` = primeiro quadro em que a frente atinge
`y_c = (38,774 + 72,6)/2 = 55,69 m` (meio da caixa; se a trajetória termina antes de `y_c`, o último quadro). Chegada do sul reto ao ponto de conflito: primeiro quadro com
`y ≤ y_c` de um veículo SB com `movement == '1'` nas linhas da caixa 1. **Brecha aceita** = `t_next − t_L`, com `t_next` a próxima chegada do sul reto depois de `t_L` (em s). Só entram
esquerdas que ocorrem com o **sinal do reto do SB na 10th verde ou amarelo em `t_L`** (permissivas). **Brecha crítica = percentil 15 das brechas aceitas** (proxy conservador de limite inferior; método declarado
como aproximação, não é máxima verossimilhança); relato n, mediana, p15. Se n < 8, o resultado é declarado inconclusivo e o cenário usa 3,0 s do fixture.
`conflictOffsetM = 72,6 − 55,69 = 16,91 m` (linha de retenção do sul menos `y_c`, geométrico).

## Cenário (fixo; só a interseção 1)

PM, quadros 140 a 9000. NB: via de aproximação (0 a 38,774 m) com **3 faixas (índice 2 = bolso de esquerda)**, conector reto (2 faixas) e conector de esquerda; saída reta (71,7 a 196,17 m) e uma via
de saída da esquerda (60 m) como sumidouro. SB: aproximação (196,17 a 72,6 m, medida em coordenada espelhada), conector reto (33,8 m) e saída (38,8 m). Sinais medidos do arquivo da 10th: NB reto, SB reto e **esquerda NB
permissiva = verde quando o reto NB ou a esquerda NB estão verdes** (amarelo se algum amarelo). Conflito: conector da esquerda NB cede ao conector reto do SB, com brecha crítica e deslocamento acima, e o NB esquerda
em vermelho do SB não é ameaça (ajuste do G05). Veículos: todos os NB e SB com faixas em {0, 1, 2, 11}, entradas por estado inicial (instantâneo no primeiro quadro, resto por fila), mesmos parâmetros de aceleração
(1,72 m/s²) e **sem velocidade desejada por veículo** (norte tem população válida mas o sul não; para não misturar efeitos, nenhum dos dois neste protocolo). Conversões à esquerda do NB: veículo com última linha na caixa 1
e rótulo 2 usa o bolso (índice 2), qualquer que seja a faixa observada (simplificação declarada); faixa 11 com rótulo diferente de 2 é excluída e contada. Comprimento do conector de esquerda **30 m**, sem fonte (suposição).

## Medidas e decisão (fixas)

Nas esquerdas NB da PM: erro do cruzamento da linha de retenção (modelo menos observado: mediana, p10, p90), espera na linha, brecha aceita no modelo (próxima chegada do SB no ponto de conflito depois da
entrada da esquerda no conector) contra a observada (mediana, p15), fração que cruza em fase protegida (SB reto vermelho) contra permissiva, e violações de exclusão (esquerda e SB reto no conflito ao mesmo tempo no modelo: deve ser 0 por construção).
Controle: o mesmo cenário **sem a regra de conflito**; relato quantas esquerdas cruzam com brecha menor que a crítica. **Sem limiar de aprovação**; relato todos os resultados.

## Limitações declaradas

Cenário de uma interseção; sul reto como proxy com a mesma aceleração do norte; comprimento do conector de esquerda e a via de saída da esquerda sem fonte; esquerdas em faixa compartilhada tratadas como de bolso; sem tráfego transversal
nem pedestres; brecha crítica do meio-dia com amostra pequena (8 minutos).
