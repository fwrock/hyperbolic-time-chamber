# Protocolo: distribuição da velocidade desejada (Peachtree NB), escrito antes de calcular

Data: 19/11/2026. Pergunta: a diferença restante entre modelo e observado na via entre a 10th e a 11th (tempo de via 9,8 s contra 15,4 s,
depois de corrigir a aceleração de saída, ver `MICRO_NGSIM_DEPARTURE_PROTOCOL_2026_11_19.md`) vem de a velocidade desejada dos motoristas
ser menor e variável?

**Divulgação:** na PM, em agregado, já vi velocidades sem líder por seção (medianas de 8,2 a 10,5 m/s; p95 de 13,3 a 14,0 m/s; ver
`MICRO_NGSIM_CORRIDOR_SLICE2_2026_11_19.md`). A fonte de estimação aqui é o meio-dia (origem 1163018600, quadros 5 a 4800). A PM só verifica.

## Estimação (meio-dia, regra fixa)

Para cada veículo NB com `movement == '1'` e `lane_id ∈ {0, 1, 2}`: conjunto Q de linhas com `v > 1 m/s` e **nenhum outro veículo NB na mesma
faixa (ou na faixa 0) com a frente em (x, x + 40] m** no mesmo quadro. O veículo entra na população se `|Q| ≥ 50` (5 s). Sua velocidade
livre é o **percentil 90 de v em Q**. A população é o conjunto dessas velocidades livres, com n, mediana, p10 e p90 relatados.

## Atribuição no cenário (população, não o próprio veículo)

No cenário da PM de duas interseções (80 veículos), cada veículo recebe `microDesiredMaxSpeed` sorteado com reposição da população do meio-dia
com semente fixa. **Nunca se usa a velocidade observada do próprio veículo na PM.** Aceleração máxima 1,72 m/s² (protocolo anterior) nas duas
condições comparadas; teto de via 48,28 km/h inalterado. Sementes: 1 a 5 (a atribuição é aleatória; reporto a mediana entre sementes e
todas as cinco).

## Medidas e decisão

Condições: (A) aceleração 1,72 m/s², sem velocidade desejada por veículo (resultado já existente); (B) com a atribuição acima. Medidas:
erro de cruzamento da linha da 11th (mediana, p10, p90), dentro de ±5 s, escorregões de ciclo, tempo de via observado contra modelo
(mediana, p10, p90). **Sem limiar de aprovação**: relato os cinco resultados, também os desfavoráveis. Se a população do meio-dia tiver
menos de 30 veículos ou mediana fora de 6 a 13,5 m/s, o resultado é declarado inconclusivo e nada é adotado. Não alterar desaceleração,
reação, `sigma` nem o teto de via neste protocolo.

## Limitações declaradas

Meio-dia cobre 8 min e poucos veículos; p90 sobre linhas sem líder mistura trechos ainda acelerando; população sem relação com classe de
veículo; conversões, pedestres e estacionamento ficam fora; a PM já foi vista em agregado.

## Resultados

**População do meio-dia (estimação como congelada):** n = 43 veículos, velocidade livre mediana 12,71 m/s (45,8 km/h), p10 10,17, p90 15,49.
Cumpre as condições de validade do protocolo (n ≥ 30, mediana entre 6 e 13,5 m/s). Parte da população (acima de 13,4 m/s) fica limitada pelo teto
de via de 48,28 km/h.

**Incidente de execução, antes de ler resultados válidos:** a primeira rodada das cinco sementes teve **3 de 5 execuções abortadas** (sementes 3, 4 e 5:
`MICRO spillback entry has no physical room; origin injection or handoff requires admission` no Link do conector da 11th). A causa estava no meu
gerador: conectores sem fila de admissão (`microEntry`), que o contrato do G03 exige quando a faixa pode ficar fisicamente cheia. Corrigi o gerador
(`microEntry` habilitado nos conectores), movi as saídas inválidas para `experiments/claude-g01/invalid-no-connector-admission/` e refiz as **cinco**
sementes. As sementes 1 e 2 deram os mesmos números antes e depois; os números abaixo vêm só das execuções corrigidas (todas com `engineCompleted`).

**Verificação na PM (80 veículos, interseções 1 e 2; aceleração 1,72 m/s² nas duas condições):**

| Medida | A: sem velocidade desejada por veículo | B: sementes 1, 2, 3, 4, 5 | Mediana de B (faixa) | Observado |
| --- | --- | --- | --- | --- |
| Erro mediano na linha da 11th (s) | −6,8 | −6,1, −6,5, −5,9, −5,0, −5,3 | −5,9 (−6,5 a −5,0) | 0 |
| Tempo de via, mediana (s) | 9,8 | 11,6, 11,3, 11,9, 12,4, 12,9 | 11,9 (11,3 a 12,9) | 15,4 |
| Dentro de ±5 s na 11th (de 77) | 24 | 29, 26, 30, 36, 33 | 30 | |
| Escorregões de ciclo na 11th | 3 | 4, 3, 4, 3, 3 | 3 | |
| Execuções concluídas (de 80 veículos) | 79 | 79 em todas | | |

**Conclusão (limitada):** sortear a velocidade desejada da população do meio-dia **reduz a diferença de tempo de via de 5,6 s para ≈ 3,5 s**
(mediana entre sementes), e o erro na linha da 11th de −6,8 para −5,9 s. É uma melhora real e consistente nas cinco sementes, mas
**explica pouco mais da metade do que faltava**. A população tem mediana de 12,7 m/s, perto do teto de 13,4 m/s, então a velocidade de cruzeiro
sozinha não pode produzir a velocidade média observada de ≈ 8,2 m/s na via: o restante (≈ 3,5 s) vem de outro efeito, ainda não localizado
(perda de velocidade logo após a caixa, aproximação da linha seguinte, conversões que freiam o fluxo, pedestres ou estacionamento). Nenhuma
dessas causas foi testada. Limites: 43 veículos e 8 minutos na estimação; sem limiar de aprovação; a PM já foi vista em agregado.
