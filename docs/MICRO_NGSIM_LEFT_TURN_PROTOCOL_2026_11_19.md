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

## Resultados

**Estimação da brecha crítica (meio-dia): inconclusiva.** Meu primeiro cálculo usou por engano o arquivo de sinal da tarde (`0400-0415`) para filtrar as esquerdas permissivas do meio-dia e deu n = 8,
mediana 9,45 s e p15 = 6,5 s; **esse resultado era inválido** (o meio-dia corresponde a `1245-0100`). Com o arquivo correto: **n = 2** esquerdas permissivas (brechas 2,3 s e 73,2 s), abaixo do mínimo
declarado (8). Pela regra congelada o cenário usa **3,0 s** (valor do fixture, sem fonte empírica). `conflictOffsetM` = 16,91 m (geométrico).

**Poder da amostra da PM, informação importante antes dos números:** das 27 esquerdas do 10th, **12 são observadas antes da linha de retenção** (as outras 15 só aparecem dentro da caixa, então a decisão de entrar
não é observada) e, entre as que o gerador classificou com aproximação (14), **5 ocorrem com o sul reto verde ou amarelo**. Os dados do Peachtree **não permitem validar a aceitação de brechas** com
significância; o que segue verifica o mecanismo e descreve a concordância.

**Incidentes (defeitos do meu gerador, registrados; saídas inválidas em `experiments/claude-g01/invalid-left-arrival-position/`):** (1) linhas com faixa artefato (9999, 3, 1061) excluíam o veículo inteiro; agora só
a linha é descartada; (2) esquerdas vistas pela primeira vez dentro da caixa passam a ser injetadas na saída da esquerda (6 veículos, sem decisão modelada); (3) veículos que terminam dentro da caixa sem rótulo de
caixa geravam `invalid MICRO arrival position` e abortaram as duas execuções; corrigido (proxy de saída com 3 m).

**Execuções válidas** (jar `claude-g06`; 355 veículos incluídos: 173 NB e 182 SB, esquerdas 14 + 6 injetadas, 136 retos, 17 saídas proxy, 182 que terminam na via; excluídos: 16 faixa de bolso sem esquerda, 9 nunca em faixa,
49 esquerdas do sul não modeladas):

| Medida (14 esquerdas NB com aproximação) | Com regra de conflito | Controle sem regra | Observado |
| --- | --- | --- | --- |
| Violações de exclusão (esquerda e sul reto no conflito ao mesmo tempo) | **0** | **34** | |
| Brecha até a próxima chegada do sul reto, todas: mediana / mínima | 10,55 s / **6,2 s** | 7,5 s / **0,2 s** | 9,35 s / 4,6 s |
| Brecha, só fase permissiva (n): mediana / mínima | 6,6 s / 6,2 s (5) | 0,6 s / 0,2 s (9) | 9,5 s / 4,6 s (5) |
| Brechas menores que a crítica de 3,0 s | **0** | **6** | |
| Erro do cruzamento da linha (modelo menos observado): mediana, p15, máximo | +2,1 s, −0,4, +68,8 | −0,15, −1,6, +5,1 | 0 |

Os quatro runtimes (conservativo e Time Warp, 1 e 2 JVMs) dão **129.606 eventos físicos comprometidos idênticos** com a regra de conflito.

**Conclusão (limitada):** (a) a exclusão e a brecha **mudam o resultado do jeito esperado**: sem a regra há 34 sobreposições e brechas de até 0,2 s, com a regra há 0 e a mínima é 6,2 s;
(b) as brechas aceitas pelo modelo (mínima 6,2 s) são **maiores** que as observadas (mínima 4,6 s), ou seja, o modelo está conservador com a brecha crítica de 3,0 s, mais que o necessário para reproduzir o
mínimo observado, mas **a amostra (5 permissivas) não permite estimar nem validar o valor**; (c) a mediana do atraso na linha é +2,1 s com uma esquerda esperando um ciclo a mais (+68,8 s), coerente com o rigor
do modelo; (d) o resíduo de brecha crítica fica **não identificável com os dados do Peachtree 10th**. Limites: n = 14 (5 permissivas), esquerda do sul não modelada, comprimento do conector de esquerda e da via de saída
sem fonte, esquerdas de faixa compartilhada tratadas como de bolso, PM já vista em agregado.
