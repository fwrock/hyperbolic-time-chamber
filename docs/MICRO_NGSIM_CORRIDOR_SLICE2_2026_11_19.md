# NGSIM Peachtree NB, interseções 1 e 2 (10th e 11th), movimento reto

Data: 19/11/2026. **Evidência de desenvolvimento sobre a gravação PM já exposta**, não validação. Scripts: `ngsim_corridor_scenario.py`
(cadeia de interseções, generaliza a fatia 1) e `analyze_corridor_scenario.py`. Saídas locais em `experiments/claude-g01/cor2-*`.

## O que mudou em relação à fatia 1

Duas interseções encadeadas: via de saída da 10th = via de aproximação da 11th, com conector próprio e sinal NB medido da 11th (quadros
342 a 9000, 10,2 min, porque o primeiro aspecto observado do reto da 11th é o quadro 342). Mesmo recorte do que entra: sentido norte,
movimento 1 em todas as linhas, faixas 0/1/2, primeira observação numa via (não dentro de uma caixa). **80 veículos elegíveis**;
exclusões: 99 não só-reto, 21 com bolso/faixa desconhecida, 10 primeiro observados dentro de uma caixa. Fontes e incertezas das
estações: 10th, linha de retenção do ortofoto manual (38,774 m, ±0,76 m); 11th, 196,17 m pela mediana dos líderes parados no vermelho
observado (±≈ 2 m: o mesmo método sobrestima o valor do ortofoto na 10th em 0,6 a 2,5 m). Fim da via de saída da 11th: início da
caixa da 12th (235,8 m é o fim da caixa; a via termina em 359,74 m, estimativa da 12th).

## Resultado (conservativo, 1 JVM)

| Medida | 10th | 11th |
| --- | --- | --- |
| Cruzamento da linha: veículos comparáveis | 75 | 77 |
| Erro (modelo menos observado): mediana / p10 / p90 | −0,4 s / −5,5 / +45,9 | **−7,1 s / −13,7 / −3,3** |
| Dentro de ±5 s | 53 | 20 |
| Modelo um ciclo depois (> 20 s) | 11 | 3 |

Tempo na via entre as duas interseções (do fim da caixa da 10th à linha da 11th, com espera de fila incluída): observado mediana 15,4 s
(p10 11,6, p90 23,0); modelo mediana 9,5 s (p10 9,3, p90 15,4). RMSE de posição 77,0 m (não interpretar como acurácia do seguimento: é
dominado pelos efeitos acima). 79 de 80 veículos concluem no modelo. **Os quatro runtimes (conservativo e Time Warp, 1 e 2 JVMs)
produzem eventos físicos comprometidos idênticos.**

## Leitura (hipóteses, sem conclusão causal)

- Na 10th o padrão se repete (14% de escorregões de ciclo, resíduo de sincronização/amarelo, já descrito).
- Na 11th o modelo chega à linha **mais cedo** que o observado (mediana −7,1 s) e só 3 veículos escorregam um ciclo. Como o erro é
  negativo para quase todos, o modelo é rápido demais entre as duas linhas, mesmo com a fila da 11th modelada. A fila a jusante,
  portanto, não explica sozinha a via de saída livre demais da fatia 1.
- **Velocidade livre observada** (PM, linhas sem líder ou com espaço > 40 m, sentido norte, movimento reto): p95 de 13,3 a 14,0 m/s
  (48 a 50 km/h) nas seções 2 a 4, coerente com o teto de 48,28 km/h, mas **medianas de 8,2 a 10,5 m/s**. Ou seja, o teto parece
  adequado, e o que difere é a velocidade típica (aceleração/saída da fila, motoristas, amostra de "sem líder" que inclui veículos
  ainda acelerando). Não atribuí a causa; uma medida limpa da aceleração de saída dos líderes de fila não saiu (n = 2 com meu filtro).
- A diferença de ≈ 6 s entre modelo e observado numa via de 126 m é compatível com aceleração/velocidade de cruzeiro menores que as do
  Car nativo (2,6 m/s², 48,28 km/h), mas também com outras causas (conversões que freiam o fluxo, pedestres, estacionamento): **não
  testado**.

## Limitações e próximos passos

Só movimento reto, dados já vistos, parâmetros de estação com ±0,76 m a ±2 m, sinal da 10th idêntico ao SB, sem calibração, sem
efeito de conversões/pedestres/estacionamento. Próximos passos: (1) medir a aceleração e a velocidade de saída observadas com uma regra
de seleção declarada antes (líderes de fila, partida de repouso) e comparar com os parâmetros do Car, **desenvolvendo no meio-dia e
verificando na PM**; (2) estudar o deslocamento do sinal com a mesma separação; (3) acrescentar conversões à esquerda contra o sul reto
(G04/G05) e, só depois, as interseções 3 e 5.
