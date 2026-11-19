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
