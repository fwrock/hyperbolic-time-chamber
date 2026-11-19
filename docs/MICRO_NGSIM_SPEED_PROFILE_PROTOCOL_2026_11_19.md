# Protocolo: perfil de velocidade na via entre a 10th e a 11th, escrito antes de calcular

Data: 19/11/2026. Pergunta (descritiva, sem calibrar): em que trecho da via entre o fim da caixa da 10th (71,7 m) e a linha de retenção da 11th
(196,17 m) o modelo e o observado diferem em velocidade? Contexto: depois das calibrações de aceleração (1,72 m/s²) e de velocidade desejada
(população do meio-dia), ainda faltam ≈ 3,5 s de tempo de via (`MICRO_NGSIM_DESIRED_SPEED_PROTOCOL_2026_11_19.md`).

**Divulgação:** a PM já foi vista em agregado (tempos de via, velocidades sem líder por seção). Este protocolo não estima parâmetros; só descreve.

## Dados e seleção (fixos)

- **Observado:** PM (origem 1163030300) e meio-dia (origem 1163018600), cada um separado. Veículos NB com `movement == '1'` e `lane_id ∈ {0, 1, 2}`
  em todas as linhas com posição em [71,7; 196,17] m, que cobrem a via inteira (primeira linha ≤ 80 m e última ≥ 190 m).
- **Modelo:** as 5 execuções com a distribuição de velocidade desejada (sementes 1 a 5, `cor2-ds*`) e a execução sem distribuição (`cor2-acc`), todas
  da PM; todos os veículos que concluem a via, posições `offset + posição` do Link.
- Caixas de 10 m de posição, de 71,7 m a 196,17 m (13 caixas, a última parcial); em cada caixa a mediana, o p25 e o p75 da velocidade das linhas
  amostradas (todas as linhas de todos os veículos, sem pesar por veículo), e n.
- Dois estratos por estado na entrada da via (velocidade na primeira linha da via): **em movimento** (≥ 5 m/s) e **lento** (< 5 m/s).

## Relato (sem limiares)

Para cada estrato e cada caixa: mediana observada (PM e meio-dia), mediana do modelo (agregada nas cinco sementes e a execução sem distribuição) e a
diferença modelo menos observado da PM. Descrevo onde a diferença é maior e se o padrão observado é parecido nas duas gravações (se não for, o
padrão é declarado não robusto). Também relato o tempo médio de via por estrato. **Nenhum parâmetro é alterado com base nisto.** Qualquer
mudança futura exigirá um novo protocolo congelado antes.

## Limitações declaradas

Amostragem por linha mistura veículos de comprimento de permanência diferente; estratos pequenos podem ter poucas amostras (relato n); a causa de
qualquer diferença localizada não é identificada por este protocolo.
