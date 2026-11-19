# Protocolo: corredor sul completo (13th a 10th), escrito antes de calcular

Data: 19/11/2026. Extensão do cenário do sul (`MICRO_NGSIM_SOUTHBOUND_PROTOCOL_2026_11_19.md`) para as interseções 13th (sem sinal), 12th, 11th e 10th. **Divulgação:** nada do corredor sul completo foi calculado antes; o sul de 11th e 10th
já foi avaliado com o mesmo gerador, e a PM já foi vista em agregado.

## Cenário (fixo)

PM, direção 4, coordenadas espelhadas `s' = 638,2 − local_y`. Ordem de encontro: **13th, 12th, 11th, 10th**. Linha de retenção do sul = percentil 10 das frentes dos líderes parados no vermelho observado (nenhum veículo à frente na mesma faixa em 15 m), caixa física = região entre a
linha de retenção do norte e a do sul: **12th 380,4 m** (caixa de 359,74 a 380,4), **11th 236,9 m** (196,17 a 236,9), **10th 72,6 m** (38,774 a 72,6), erros de ±≈ 3 m; **13th sem sinal** (caixa de 483,8 a 503,4, sem linha de retenção). **14th:** nenhum líder parado a norte da caixa foi observado
(0 amostras), então não há aproximação sul medida e a 14th **não é uma interseção do sul neste cenário**: os veículos vistos pela primeira vez na região da 14th entram como estado inicial na primeira via. Sinais medidos `Peachtree_<rua>_SB` do reto; janela de quadros **931 a 9000** (primeiro aspecto conhecido do reto da 12th SB).
Inclusão `--include-all`, aceleração 1,72 m/s², **sem velocidade desejada por veículo** (a população do sul no meio-dia é inválida pelo protocolo anterior, n = 26), instantâneo inicial exato. Limitações herdadas: bolsos excluídos, esquerdas e direitas como proxy de saída, tráfego transversal e sentido norte fora.

## Medidas (fixas)

Veículos `through_only`: por interseção (12th, 11th, 10th e o marcador na 13th), erro do cruzamento da linha (mediana e intervalo de 95% por reamostragem de veículos), dentro de ±5 s, escorregões de ciclo (> 20 s) e erro do tempo de via
entre interseções. Sem sementes (nada aleatório). Sem limiar de aprovação. Falha de execução: causa registrada.

## Limitações declaradas

Sem velocidade desejada por veículo; aceleração do sul suposta igual à do norte; linhas de retenção estimadas; o arquivo de sinal SB da 10th e da 11th é idêntico ao NB (possível erro da fonte); sem a 14th como interseção do sul.

## Resultados (jar reprodutível `experiments/claude-release/engine.jar`, commit `8b7568d`; uma execução, sem aleatoriedade)

271 veículos incluídos: 92 `through_only`, 55 `side_entry`, 124 `other_road_entry`; **146 excluídos** (142 de bolso ou faixa desconhecida, 4 nunca nas faixas 1 ou 2). A execução concluiu sem erro (`engineCompleted`); 154 veículos de todas as categorias terminaram a rota.

| Interseção (ordem de encontro) | Veículos | Erro mediano na linha (s) | Dentro de ±5 s | Um ciclo depois (> 20 s) | Erro mediano do tempo de via anterior (s) |
| --- | --- | --- | --- | --- | --- |
| 13th (sem sinal) | 68 | −2,1 (−2,7 a −1,6) | 93% (87 a 99) | 0% | |
| 12th | 72 | −4,7 (−6,0 a −4,3) | 49% (36 a 60) | 3% (0 a 7) | −2,65 (−3,5 a −1,85) |
| 11th | 71 | −2,8 (−4,1 a −1,6) | 65% (54 a 76) | 1% (0 a 4) | +1,9 (+0,3 a +2,8) |
| 10th | 67 | −4,2 (−7,1 a −1,4) | 33% (22 a 45) | **21%** (12 a 31) | −1,5 (−4,7 a +0,1) |

(Intervalos de 95% por reamostragem de veículos; erro = modelo menos observado, negativo = o modelo chega antes.)

**Leitura (limitada):** o sul, como o norte, chega às linhas **2 a 5 s antes** do observado em todas as interseções, e o viés **não cresce de forma monótona** ao longo do corredor. O sinal do SB da 10th concentra os escorregões de ciclo
(21%), como na versão de duas interseções (22%); como o arquivo SB da 10th é idêntico ao NB byte a byte, a primeira suspeita continua sendo a fonte do sinal e não o modelo, **sem teste que a confirme**. Sem velocidade desejada por veículo (população
inválida) e com a aceleração do norte suposta no sul. Desenvolvimento em dados já vistos; sem limiar de aprovação. O sentido norte e o sul rodam em cenários **separados**: as esquerdas contra o sul reto só foram combinadas na 10th (protocolo `LEFT_TURN`).
