# Desempenho do MICRO com junções, conflitos e atores reais (medição de 19/11/2026)

Jar reprodutível: `experiments/claude-release/engine.jar` (sha256 `f0817b97…`), construído de uma **árvore git limpa** no commit `8b7568d` (1.121 testes Scala passam, nenhum falha).
Máquina: uma única estação com 12 CPUs lógicas e 31 GB de RAM; os "2 JVMs" são dois processos no **mesmo** host, não duas máquinas. Tempo = intervalo entre o fim de
`post_load_registration` e o `StopSimulationEvent` do `report-manager` no log (exclui a subida da JVM). Nada rodou em paralelo durante estas medições (uma rodada anterior foi descartada por
sobreposição com compilação: `experiments/claude-release/contaminated-perf/NOTE.txt`).

## Cena das esquerdas (355 veículos, 15 min simulados, 129.606 eventos físicos, 8 Links, 1 Node de junção), 3 repetições

| Configuração | Tempo do motor (média, desvio) | Repetições (s) |
| --- | --- | --- |
| Conservativo, 1 JVM | 14,5 s (1,9) | 14,7; 12,4; 16,2 |
| Conservativo, 2 JVMs | 30,8 s (3,4) | 30,4; 34,4; 27,7 |
| Time Warp, 1 JVM | 28,9 s (1,5) | 28,2; 30,6; 27,9 |
| Time Warp, 2 JVMs | 79,6 s (10,2) | 86,6; 84,2; 67,9 |

## Cena de escala (40 corredores independentes × 25 carros, 1.000 carros, conservativo), 2 repetições

| Configuração | Tempo do motor (média) | Repetições (s) |
| --- | --- | --- |
| 1 JVM | 16,1 s | 14,7; 17,6 |
| 2 JVMs | 35,8 s | 33,3; 38,2 |

## Leitura (sem extrapolar)

- Todas as 16 execuções concluíram sem erro, e os eventos físicos comprometidos são idênticos entre as quatro configurações na cena das esquerdas (verificado antes).
- **Nenhuma configuração mostrou ganho com 2 JVMs**; ao contrário, 2 JVMs foi 2,1 vezes mais lento (conservativo) e 2,8 vezes (Time Warp) na cena das esquerdas, e 2,2 vezes mais lento na cena de 40 corredores independentes.
  Time Warp custou cerca de 2 vezes o conservativo em 1 JVM. **Isto é uma medição de uma máquina só, com cenas pequenas (≤ 1.000 carros e ≤ 100 atores de Link); não demonstra nem refuta escalabilidade em cluster.** Em cenas assim a
  coordenação entre processos domina, e os atores de um mesmo corredor podem cair em processos diferentes porque a distribuição é por balanceamento, não por afinidade de corredor.
- Esse comportamento (2 JVMs não mais rápidas nestas cenas) **já existia antes deste trabalho**: a matriz inicial do G02 com o jar anterior mostrou tempos totais de 14,6 s (1 JVM) e 17,5 s (2 JVMs) na cena mínima do fixture; o jar atual não piorou esse padrão nas cenas em que os dois rodam,
  mas **não comparei o jar atual com o anterior na cena das esquerdas, porque o anterior não aceita a configuração**.
- Para uma afirmação de escalabilidade é preciso um experimento próprio: cenas com milhares de Links, afinidade de corredor por processo e máquinas separadas. Não existe nesta rodada e o relatório não deve alegar escalabilidade.
