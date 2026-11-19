# Protocolo: esquerdas do norte contra o sul reto na 10th, 11th e 12th, com e sem frenagem antecipada, escrito antes de calcular

Data: 19/11/2026. Estende `MICRO_NGSIM_LEFT_TURN_PROTOCOL_2026_11_19.md` (10th) para as interseções que têm linha de retenção medida nos dois sentidos: **10th, 11th e 12th**. A 13th não tem sinal e a 14th não tem aproximação sul observada
(0 líderes parados), então ficam fora. **Divulgação:** nada das esquerdas da 11th e da 12th foi calculado antes; a PM já foi vista em agregado.

## Cenário (fixo; mesmo gerador, `--site`)

PM. Domínio de cada interseção: do fim da caixa da interseção anterior (ou 0) até a linha de retenção do norte da seguinte; coordenadas deslocadas para o início do domínio. Linhas de retenção do sul: 10th 72,6 m, 11th 236,9 m, 12th 380,4 m (p10 dos líderes
parados no vermelho; ±≈ 3 m). Quadros iniciais: **10th 140, 11th 342, 12th 931** (primeiro aspecto conhecido dos sinais envolvidos). Sinais medidos NB reto, SB reto e esquerda permissiva do NB (verde quando o reto ou a esquerda do NB estão verdes; a 11th e a 12th
não têm coluna de esquerda no arquivo, então a esquerda é igual ao reto). Aceleração 1,72 m/s²; sem velocidade desejada por veículo; brecha crítica **3,0 s** (a identificação com dados não é possível: ver o protocolo da 10th); comprimento do conector de esquerda 30 m (suposição);
todos os veículos NB e SB com faixas em {0, 1, 2, 11} entram como no protocolo da 10th (esquerdas com aproximação usam o bolso; esquerdas vistas primeiro dentro da caixa são injetadas na saída da esquerda).

## Condições (fixas)

(i) **sem regra de conflito** (controle); (ii) **com regra** (linha de base); (iii) **com regra e frenagem antecipada** (`--publish-permission`). Uma execução por condição e interseção (nada aleatório).

## Medidas (fixas)

Por interseção e condição, nas esquerdas NB com aproximação observada: violações de exclusão; brecha aceita até a próxima chegada do sul reto (mediana e mínima) e as brechas menores que a crítica; erro do cruzamento da linha (mediana, mínimo, máximo); número de esquerdas
retidas na linha (amostras `micro_exit_wait` em Links de aproximação, só nas esquerdas) como medida de parada abrupta. Comparar (iii) com (ii) relata se a frenagem antecipada muda a retenção e o erro. **Sem limiar de aprovação; todos os resultados são relatados**, e as amostras são pequenas
(poucas esquerdas por interseção): o resultado é descritivo.

## Limitações declaradas

Poder baixo (poucas esquerdas observadas antes da linha); brecha crítica sem identificação; a 11th e a 12th usam a esquerda igual ao reto por falta de coluna; sul reto como proxy, esquerdas do sul e tráfego transversal não modelados; uma execução por condição.
