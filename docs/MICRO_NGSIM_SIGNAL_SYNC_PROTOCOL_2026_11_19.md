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

## Resultados (`measure_signal_sync.py`; saídas locais `experiments/claude-g01/signal-sync-midday.json` e `-pm.json`)

**Regra congelada, meio-dia (`1245-0100`), reto NB:**

| Aproximação | I1 atraso de partida: n, mediana (p10 a p90) | I2: veículos que cruzam, depois do vermelho |
| --- | --- | --- |
| 10th | 7, 1,0 s (0,4 a 2,2) | 11, 0 |
| 11th | 5, 1,8 s (0,8 a 2,9) | 11, 0 |
| 12th | 4, 0,4 s (0,2 a 1,4) | 9, 0 |
| 14th | 3, 1,0 s (0,8 a 1,8) | 13, 0 |

**Decisão pela regra: não há indício de deslocamento** (a condição exigia p90 de I2 > 2 s e mediana de I1 > 4 s em ≥ 3 aproximações; o meio-dia tem I1 de 0,4 a 1,8 s e **nenhum** cruzamento depois do início do vermelho).
Nada foi deslocado e o deslocamento não foi rodado no cenário. **No meio-dia o sinal parece bem sincronizado com as trajetórias** (atraso de partida de ≈ 1 s).
A PM (`0400-0415`), apenas verificação, tem n muito pequeno em I1 (1 a 4 líderes por aproximação: medianas 5,0, 3,0, 0,3 e 4,3 s) e I2 também sem cruzamentos pela regra.

**Falha do meu operador I2, declarada antes de usar o resultado:** ele exigia que o veículo estivesse rastreado no quadro `r − 100`, o que exclui veículos vistos pela primeira vez perto da linha (como os que motivaram a pergunta na 10th da PM). Por isso I2 subestima.
Fiz uma verificação **pós-hoc, fora do protocolo e sem poder de decisão**, sem essa exigência (veículos observados antes da linha que a cruzam entre `r − 100` e `r + 150`):

| Aproximação | Meio-dia: cruzam / depois do vermelho (mediana) | PM: cruzam / depois do vermelho (mediana, máximo) |
| --- | --- | --- |
| 10th | 92 / 2 (11,5 s) | 159 / 8 (6,1 s, 12,2 s) |
| 11th | 87 / 1 (2,3 s) | 197 / 7 (3,9 s, 8,7 s) |
| 12th | 57 / 1 (0,1 s) | 132 / 16 (3,0 s, 14,2 s) |
| 14th | 20 / 0 | 27 / 1 (11,5 s) |

**Leitura (limitada):** no meio-dia quase não há cruzamentos depois do vermelho, na PM há 3 a 12% por aproximação, com mediana de 3 a 6 s. Isso é **compatível com uma diferença de sincronização específica da PM
(arquivo adiantado em alguns segundos em relação ao vídeo) ou com comportamento diferente na PM (por exemplo, mais fluxo e mais tentativas de passar no vermelho)**, e **não é identificável sem vídeo**. Como o desenvolvimento (meio-dia)
não mostra indício, **não se desloca nada na PM**: fazê-lo seria calibrar na gravação que deveria verificar. Os escorregões de ciclo da PM ficam como resíduo declarado de sincronização possível ou
comportamento, e os 14 a 22% de escorregões relatados antes não devem ser atribuídos ao modelo físico.
