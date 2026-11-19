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
