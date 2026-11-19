# NGSIM Peachtree NB, interseção 1 (10th), fatia 1: só movimento reto

Data: 19/11/2026. **Evidência de desenvolvimento sobre a gravação PM já exposta.** Nada aqui é validação independente nem aceitação.
Scripts: `ngsim_intersection_scenario.py` (gerador), `analyze_intersection_scenario.py` (comparador). Saídas locais em
`experiments/claude-g01/` (cenário, execuções e análises, com hashes das páginas de origem no metadata).

## O que foi construído

Aproximação (Link 11, até a linha de retenção em 38,774 m), conector (Link 12, 32,9 m, a caixa da interseção) e via de saída (Link 13,
de 71,7 a 197,7 m), duas faixas, sinal do movimento reto da 10th NB importado do arquivo medido (quadros 140 a 9000, 15 min). A
coordenada do corredor é o `local_y` do NGSIM em metros. Entram só veículos sentido norte com movimento `1` em todas as linhas da janela
e faixas 0/1/2; o estado inicial (posição, velocidade, faixa e tick) é o primeiro observado, com instantâneo inicial exato no primeiro
quadro. **Fontes e hipóteses:** linha de retenção do ortofoto manual (±0,76 m, não é levantamento topográfico); limites das seções do
particionamento do próprio NGSIM; limite de velocidade 30 mph (48,28 km/h) como **hipótese**, sem fonte verificada; sinal NB da 10th
usado como está (o arquivo SB é idêntico byte a byte).

Exclusões, contadas e preservadas no metadata: **89 com algum movimento diferente de reto** e **18 com faixa de bolso ou desconhecida**;
91 veículos elegíveis. O que não está no modelo: tráfego transversal, sentido sul, conversões, bolsos e a fila do cruzamento seguinte.

## Resultado (conservativo, 1 JVM)

| Medida | Valor |
| --- | --- |
| Veículos elegíveis / concluíram a via de saída no modelo | 91 / 90 |
| Amostras observadas (31.930) sem previsão | 895 |
| RMSE de posição (m), fronteira absorvente na saída | 46,9 |
| Erro de posição (modelo menos observado): mediana, p10, p90 (m) | 15,6, −6,2, 70,3 |
| Cruzamento da linha de retenção (88 veículos que a aproximação observa): mediana do erro | −0,4 s |
| Dentro de ±5 s / modelo um ciclo depois (> 20 s) / um ciclo antes | 61 / 12 / 0 |
| Travessia da via de saída: observado mediana 16,2 s (p10 12,0, p90 24,9) contra modelo mediana 9,4 s (p10 9,4, p90 9,7) | n = 60 |

Os quatro runtimes concordam: **conservativo e Time Warp, 1 e 2 JVMs, produzem eventos físicos comprometidos idênticos** (91 veículos,
15 min de tempo simulado), o que é uma verificação de invariantes em escala real de dados, não de acurácia.

## Diagnóstico (hipóteses, não conclusões)

- **Escorregões de ciclo (12 de 88, 14%).** Os piores casos (≈ 60 s de erro) são veículos observados cruzando a linha logo depois do
  início do vermelho (por exemplo 0,7 s após o vermelho no arquivo): no modelo eles conseguem frear e esperam o ciclo seguinte. Isso
  é o resíduo de partida/sincronização já conhecido (E02): sincronização do sinal com o vídeo não verificada, comportamento de
  amarelo tardio e fim do amarelo não modelados. Não ajustei nenhum deslocamento de sinal para reduzir esses erros.
- **Via de saída livre demais.** O modelo atravessa a via de saída em 9,4 s (velocidade limite, sem variação), os observados em 16,2 s
  (mediana). Causas candidatas, sem distinguir ainda: fila vinda da interseção 2, velocidade desejada menor que 48,28 km/h, aceleração
  de saída e heterogeneidade de motoristas. A parte do erro vinda do cruzamento seguinte exige modelá-lo.
- O **RMSE de 46,9 m não é erro do modelo de seguimento**: é dominado por essas duas causas. Não interpretar como acurácia do MICRO.

## Próximos passos

1. Encadear a interseção 2 (e a via entre elas) para capturar a fila a jusante, com o sinal da 11th NB.
2. Estudar o deslocamento do sinal com a gravação do meio-dia (desenvolvimento) e verificar na PM sem reajustar; declarar a regra antes.
3. Medir a velocidade livre observada na via de saída para fixar a velocidade desejada com fonte, em vez da hipótese de 30 mph.
4. Conversões à esquerda do norte contra o sul reto (usa G04/G05), depois as demais interseções.

## Limitações

Dados já vistos; um único recorte; fatia só de movimento reto; sem calibração; parâmetros de geometria com incerteza de ±0,76 m (linha de
retenção) e ≈ ±3 m (início das regiões, ver auditoria do corredor); o instantâneo inicial vem das mesmas observações comparadas.
