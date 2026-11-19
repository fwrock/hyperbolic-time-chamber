# Protocolo: sentido sul (direção 4) com o mesmo tratamento do norte, escrito antes de calcular

Data: 19/11/2026. Objetivo: ter o sentido sul modelado e medido da mesma forma que o norte, pré-requisito para as conversões à esquerda do norte (que cedem ao sul reto).
**Divulgação:** nada do sul foi calculado antes deste protocolo, exceto as estimativas de linha de retenção abaixo e a inspeção dos arquivos de sinal.

## Cenário (fixo)

Coordenadas espelhadas `s' = 359,74 − local_y` (o sul se desloca para `s'` crescente). Interseções na ordem de encontro: **11th, depois 10th**. A caixa física é a mesma do norte.
Linha de retenção do sul = estimativa pelo percentil 10 das frentes dos líderes parados no vermelho observado (leaders: nenhum veículo à frente na mesma faixa em 15 m):
**236,9 m (11th) e 72,6 m (10th)** em `local_y` (±≈ 3 m, mesma incerteza que a do norte; p2 foi 236,9 e 71,4), fim da caixa = linha de retenção do norte (196,17 e 38,774 m),
fim da via de saída = `local_y = 0`. Sinais SB medidos de `Peachtree_11th_SB` e `Peachtree_10th_SB` (reto); **os arquivos de 10th e 11th SB são idênticos aos NB byte a byte**
(ver observação anterior); janela de quadros 342 a 9000 da PM (o primeiro aspecto observado do reto da 11th é 342). Regra de inclusão **a mesma do protocolo `ALL_NB`**
(`--include-all`): todos os veículos SB com faixas em {0, 1, 2}, categorias `through_only`, `side_entry`, `turn_off_proxy`, `ends_in_road`; bolsos excluídos e contados.
Aceleração máxima 1,72 m/s² (estimada no norte, **suposta igual no sul**). Velocidade desejada: população estimada no **meio-dia, direção 4**, pela mesma regra do protocolo de
velocidade desejada (percentil 90 das linhas sem líder, n ≥ 50 linhas por veículo, `|população| ≥ 30`; a mediana deve estar entre 6 e 13,5 m/s), sorteio com reposição, sementes 1 a 5.

## Medidas e decisão (fixas)

Nos veículos `through_only` do sul: erro de cruzamento da linha de cada interseção (mediana, p10, p90), dentro de ±5 s, escorregões de ciclo e o tempo de via entre as duas
interseções (observado contra modelo). **Sem limiar de aprovação**; as cinco sementes são relatadas. Falha de execução: causa registrada, nenhuma semente descartada sem registro.
Não se calibra nada no sul com a PM; se a população do meio-dia não cumprir as condições de validade, o sul fica com o teto de via e o resultado é declarado inconclusivo.

## Limitações declaradas

Sul assumido com a mesma aceleração do norte; linhas de retenção estimadas (não levantadas); sem conversões à esquerda do sul contra o norte (o sul também tem esquerdas ignoradas); 
a PM já foi vista em agregado; o desempenho do perfil de velocidade do sul não está incluído nesta rodada.
