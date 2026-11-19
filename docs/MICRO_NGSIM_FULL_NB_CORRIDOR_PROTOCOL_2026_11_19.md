# Protocolo: corredor norte completo (cinco interseções), escrito antes de calcular

Data: 19/11/2026. Extensão do cenário `ALL_NB` para as cinco interseções do sentido norte (10th, 11th, 12th, 13th sem sinal, 14th). **Divulgação:** nada do corredor completo foi calculado antes; a PM já foi vista em agregado
e a 10th e a 11th já foram avaliadas com este mesmo gerador.

## Cenário (fixo)

PM, sentido norte, regra de inclusão `--include-all` (as quatro categorias e as exclusões já contadas), aceleração 1,72 m/s², velocidade desejada sorteada da população NB do meio-dia (sementes 1 a 5), instantâneo inicial exato.
Interseções e estações (m em `local_y`): 10th (parada 38,774; fim da caixa 71,7; ortofoto), 11th (196,17; 235,8), 12th (359,74; 377,2), **13th sem sinal** (483,8; 503,4; início e fim da região `int_id = 4`; sem linha de retenção medida:
o veículo não é obrigado a parar), 14th (600,6; 635,3). Fim do corredor 638,2 m. Paradas da 12th e da 14th pela mediana dos líderes parados no vermelho observado (±≈ 2 m). Sinais medidos NB reto para 10th, 11th, 12th e 14th.
Janela de quadros **630 a 9000**: o primeiro aspecto conhecido do reto da 14th é 630 e o da 12th é 559. Limitações herdadas: veículos de bolso excluídos, esquerdas e direitas só como proxy de saída, tráfego transversal e sentido sul fora, pedestres ausentes.

## Medidas (fixas) e comparação

Nos veículos `through_only`: por interseção, erro do cruzamento da linha (mediana, intervalo de 95% por reamostragem de veículos), dentro de ±5 s, escorregões de ciclo (> 20 s), e o tempo de via entre
interseções consecutivas (observado contra modelo). **Comparação com o resultado de duas interseções:** na 10th e na 11th, a mudança de janela (342 para 630) e de domínio é relatada, não escondida. Sem limiar de aprovação.
A 13th não tem sinal: seu "cruzamento da entrada da caixa" é só um marcador de tempo, não uma linha de retenção. Se alguma execução abortar, causa registrada e nenhuma semente descartada sem registro.

## Limitações declaradas

O erro se acumula ao longo do corredor (a fila de uma interseção afeta a anterior e a posterior); a parada da 13th não existe nos dados; a 14th é a última interseção modelada e não tem saída a jusante medida; tamanho de amostra por interseção diminui
nas interseções mais ao norte (alguns veículos nascem mais perto do fim).

## Resultados (jar reprodutível `experiments/claude-release/engine.jar`, commit `8b7568d`; `aggregate_corridor_results.py`)

**Incidente (defeito do meu gerador, antes de qualquer resultado válido):** as cinco execuções iniciais abortaram com `invalid MICRO arrival position` no Link 20: a última via do corredor tem 2,9 m (635,3 a 638,2 m) e o proxy
de saída chegava a 3,0 m. Passei a limitar a chegada ao comprimento da via. Saídas inválidas em `experiments/claude-g01/invalid-cor5-arrival-position/`.

**Inclusão:** 206 veículos (77 `through_only`, 116 `side_entry`, 13 `other_road_entry`); **107 excluídos** por faixa de bolso ou faixa desconhecida (um terço do sentido norte). Cinco sementes, todas com `engineCompleted`; 173 veículos de todas as categorias
concluíram a rota.

**Medidas nos veículos `through_only` (média das sementes por veículo; intervalo de 95% por reamostragem de veículos):**

| Interseção | Veículos | Erro mediano na linha (s) | Dentro de ±5 s | Um ciclo depois (> 20 s) | Erro mediano do tempo de via anterior (s) |
| --- | --- | --- | --- | --- | --- |
| 10th | 58 | −0,18 (−0,44 a +0,03) | 71% (59 a 81) | 17% (9 a 28) | |
| 11th | 60 | −3,36 (−4,66 a −2,00) | 60% (48 a 73) | 3% (0 a 8) | −2,36 (−3,99 a −1,31) |
| 12th | 65 | −3,24 (−5,28 a −0,32) | 31% (20 a 42) | 19% (9 a 28) | +0,80 (−1,74 a +3,60) |
| 13th (sem sinal) | 67 | −1,70 (−4,14 a −0,48) | 43% (31 a 55) | 13% (6 a 22) | +0,42 (−0,16 a +0,84) |
| 14th | 73 | −2,02 (−5,52 a +0,02) | 33% (22 a 44) | 16% (8 a 25) | −0,78 (−1,84 a +0,88) |

(O número de veículos por interseção muda porque cada veículo só entra nas interseções que atravessa com observação da linha anterior. Na 13th "um ciclo depois" não tem significado de sinal; é só um indicador de atraso grande.)

**Leitura (limitada):** o modelo chega às linhas **2 a 3 s antes do observado** (mediana) em todas as interseções depois da 10th, e o desvio **não cresce ao longo do corredor**: o erro do tempo de via entre interseções é pequeno nos trechos de 12th a 14th (intervalos incluem zero), então o viés
parece se concentrar no primeiro trecho (10th a 11th) e não se acumular. A fração dentro de ±5 s cai de 71% (10th) para 31 a 43% nas interseções seguintes, o que combina com o viés de 2 a 3 s. Escorregões de ciclo: 13% a 19% nas interseções com
sinal da 10th, 12th e 14th (3% na 11th). Esses números **são desenvolvimento em dados já vistos**, com as limitações do protocolo (um terço dos veículos de bolso excluído, tráfego transversal e pedestres ausentes, sul fora). Não há limiar de aprovação.
A comparação com o resultado de duas interseções muda janela (342 para 630) e domínio, então números da 10th e da 11th não são diretamente comparáveis com os anteriores.
