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
