# Protocolo: sincronização do sinal com as trajetórias (E02), escrito antes de calcular

Data: 19/11/2026. Pergunta: os arquivos de sinal estão deslocados no tempo em relação às trajetórias, ou os escorregões de ciclo vêm do comportamento (amarelo tardio, partida lenta)?
**Não ajusto deslocamento para melhorar o erro do modelo**: uso dois indicadores independentes do modelo e só aplico um deslocamento se eles concordarem. **Divulgação:** na PM já vi que, por exemplo,
alguns veículos cruzam a linha da 10th logo depois do início do vermelho, e uma mediana de atraso de partida de 5,5 s numa auditoria anterior (`MICRO_RECONSTRUCTION_AND_SNAPSHOT_2026_11_18.md`).

## Indicadores (regra fixa, para cada aproximação NB do reto: 10th 38,774 m, 11th 196,17, 12th 359,74, 14th 600,6; sul fora desta rodada)

- **I1, atraso de partida após o verde:** para cada início de verde do reto `g` (quadro `BG_Thru`), o líder da fila é o veículo com a frente em `[b − 8, b]`, `v ≤ 0,5 m/s` em `g` e nenhum outro NB na mesma
  faixa (ou faixa 0) em `(x, x + 40]` m. O atraso é `(f − g) × 0,1 s`, com `f` o primeiro quadro ≥ `g` com `v > 1 m/s`, desde que `f ≤ g + 150`. Relato n, mediana, p10 e p90 por aproximação e gravação.
- **I2, cruzamentos depois do início do vermelho:** para cada início de vermelho `r` (`BR_Thru`), os veículos NB cuja frente cruza `b` (primeiro quadro com `y ≥ b`) em `[r − 100, r + 150]` quadros: relato quantos cruzam
  depois de `r`, e `(t − r) × 0,1 s` (mediana, p90, máximo). Só veículos que estavam a menos de 60 m antes da linha em `r − 100`.

## Decisão (fixa)

1. Fonte de desenvolvimento: meio-dia com os arquivos `1245-0100`; PM com `0400-0415` só verifica.
2. **Há indício de deslocamento** se, no meio-dia, em **pelo menos 3 das 4 aproximações**, o p90 de I2 (cruzamentos depois do vermelho) é > 2 s **e** a mediana de I1 é > 4 s. Caso contrário, nada é deslocado
   e o resíduo é declarado comportamento (amarelo tardio, partida) ou não identificável.
3. Se houver indício, o deslocamento candidato é `δ = −mediana de I2 positivo` do meio-dia, uma mudança dos aspectos do arquivo, aplicada **uniformemente às quatro aproximações**, e o cenário de duas interseções
   (PM) é refeito uma vez, sem reajuste: relato o erro mediano na linha da 11th e os escorregões de ciclo, com e sem deslocamento. Sem indício, não se roda o deslocamento.

## Limitações declaradas

Os arquivos NB e SB da 10th são idênticos byte a byte e o sul fica fora desta rodada; o meio-dia tem 8 min e poucos ciclos por aproximação; cruzar depois do vermelho pode ser comportamento real e não deslocamento;
sem vídeo não há verdade independente de sincronização, só indícios.
