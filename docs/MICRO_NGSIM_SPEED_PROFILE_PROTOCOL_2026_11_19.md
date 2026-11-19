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

## Resultados (descritivos; `profile_ngsim_road.py`, saída local `experiments/claude-g01/speed-profile.json`)

Veículos que cobrem a via inteira: PM 116 em movimento e 9 lentos; meio-dia 74 e 2; modelo 73 em movimento e 2 lentos (por execução). Estrato **em movimento**
(mediana da velocidade em m/s por caixa de 10 m; modelo = mediana das medianas das cinco sementes):

| Posição (m) | PM obs. | Meio-dia obs. | Modelo | Modelo menos PM |
| --- | --- | --- | --- | --- |
| 72–82 | 9,2 | 9,8 | 12,0 | +2,8 |
| 92–102 | 9,2 | 11,0 | 12,5 | +3,3 |
| 112–122 | 10,2 | 11,5 | 12,4 | +2,2 |
| 132–142 | 8,2 | 10,2 | 12,4 | +4,2 |
| 152–162 | 6,0 | 4,0 | 12,2 | +6,2 |
| 172–182 | 3,1 | 0,1 | 9,3 | +6,2 |
| 192–196 | 0,1 | 0,0 | 2,8 | +2,7 |

(As demais caixas seguem o mesmo padrão; a tabela completa está no JSON.) O estrato lento tem n = 9 (PM) e 2 (modelo): sem informação.

**Leitura (descritiva, sem causa identificada pelo protocolo):**

- Há **dois efeitos separados**. (1) **Cruzeiro:** na primeira metade da via os observados andam a 9 a 10 m/s e o modelo a 12,4 m/s (diferença de +2,2 a +3,3 m/s),
  mesmo com a velocidade desejada sorteada; (2) **aproximação da linha:** os observados começam a desacelerar por volta de 130 m (cerca de 65 m antes da
  linha) e chegam a 2 m/s a 182 m; o modelo mantém ≈ 12,3 m/s até ≈ 160 m e só então freia, ou seja, **a fila observada é bem mais longa que a simulada**.
- O padrão é parecido nas duas gravações (cruzeiro a ≈ 10 m/s e queda perto da linha), com a queda do meio-dia começando ~20 m mais perto da linha:
  o formato é robusto, os valores não são idênticos.
- Hipótese mais simples, **não testada aqui**: os veículos retirados do cenário (somente movimento reto entra: 89 + 21 + 10 de 210 ficam de fora) ocupam as
  mesmas faixas, seguem na frente e formam parte da fila observada; sem eles o modelo tem uma fila mais curta e velocidade de cruzeiro maior. Isso é
  coerente com os dois efeitos, mas outras causas (pedestres, estacionamento, comportamento de amarelo) não foram excluídas.
- Consequência: **calibrar parâmetros do Car não fecha a diferença restante**; a próxima mudança relevante é modelar os veículos hoje excluídos.

## Correção posterior (mesmo dia): verificação da hipótese dos veículos excluídos

Medi na PM, para as 89 linhas de veículos só em movimento reto na via entre a 10th e a 11th (14.648 linhas), quem é o líder (`preceding`):
**74,0% seguem outro veículo só em movimento reto, 25,0% seguem um veículo que o cenário exclui e 1,0% não têm líder; a velocidade mediana é 7,9 m/s nos
dois primeiros casos.** Portanto o líder excluído **não explica diretamente** o cruzeiro lento: os observados andam a 7,9 m/s também atrás de veículos
que estão no modelo. A frase "hipótese mais simples" acima fica rebaixada: os veículos excluídos podem ainda agir de forma indireta (densidade e
comprimento da fila: o modelo tem só cerca de 40% dos veículos do sentido norte), mas isso exige teste direto, que é o próximo passo (incluir
todos os veículos do sentido norte no modelo e repetir o perfil).
