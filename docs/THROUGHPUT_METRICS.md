# Métricas de Throughput do TimeManager

## Visão Geral

O TimeManager agora rastreia e loga automaticamente o **throughput de processamento de ticks** (ticks por segundo), permitindo monitorar a performance da simulação em tempo real.

## Formato do Log

```
[INFO] Throughput: 1250.45 ticks/s (instant), 980.32 ticks/s (avg) | Tick: 5000 | Total ticks: 5000 | Elapsed: 5.1 s
```

### Campos

| Campo | Descrição | Uso |
|-------|-----------|-----|
| `ticks/s (instant)` | Throughput no último intervalo | Detectar variações de performance |
| `ticks/s (avg)` | Throughput médio desde o início | Performance global da simulação |
| `Tick` | Tick atual da simulação | Progresso |
| `Total ticks` | Ticks processados desde o início | Volume total |
| `Elapsed` | Tempo decorrido (segundos) | Duração |

## Configuração

### Intervalo de Log

```bash
# application.conf
htc.time-manager {
  metrics-log-interval = 500  # Logar a cada 500 ticks
}

# Variável de ambiente
export HTC_TIME_MANAGER_METRICS_INTERVAL=500
```

### Valores Recomendados

| Duração da Simulação | Intervalo Recomendado |
|----------------------|----------------------|
| < 10k ticks | 100 |
| 10k - 100k ticks | 500 (padrão) |
| 100k - 1M ticks | 1000 |
| > 1M ticks | 5000 |

### Desabilitar Métricas

```bash
# Para desabilitar completamente
export HTC_TIME_MANAGER_METRICS_INTERVAL=0
```

## Interpretando os Resultados

### Throughput Saudável

```
[INFO] Throughput: 1200.50 ticks/s (instant), 1150.20 ticks/s (avg)
                      ▲                         ▲
                      └─ Estável ───────────────┘
```

**Indica:** Sistema rodando consistentemente, sem gargalos.

### Throughput Degradando

```
[INFO] Throughput: 850.30 ticks/s (instant), 1150.20 ticks/s (avg)
                     ▲                        ▲
                     └─ Caindo ──────────────┘ Média ainda alta
```

**Indica:** Performance caindo ao longo do tempo (possível memory leak, fragmentação, etc.)

### Throughput Variável

```
[INFO] Throughput: 1500.00 ticks/s (instant), 1000.00 ticks/s (avg)
[INFO] Throughput: 600.00 ticks/s (instant), 950.00 ticks/s (avg)
[INFO] Throughput: 1400.00 ticks/s (instant), 980.00 ticks/s (avg)
```

**Indica:** Carga de trabalho variável (normal em simulações de tráfego com picos)

## Analisando Impacto do Lookahead

### Baseline (window=1)

```bash
export HTC_TIME_MANAGER_LOOKAHEAD=1
export HTC_TIME_MANAGER_METRICS_INTERVAL=500
./run.sh
```

**Exemplo de saída:**
```
[INFO] Throughput: 850.00 ticks/s (instant), 850.00 ticks/s (avg)
[INFO] Throughput: 840.00 ticks/s (instant), 845.00 ticks/s (avg)
[INFO] Throughput: 860.00 ticks/s (instant), 850.00 ticks/s (avg)
```

### Com Lookahead (window=10)

```bash
export HTC_TIME_MANAGER_LOOKAHEAD=10
export HTC_TIME_MANAGER_METRICS_INTERVAL=500
./run.sh
```

**Exemplo de saída:**
```
[INFO] Throughput: 1700.00 ticks/s (instant), 1700.00 ticks/s (avg)  ◄── 2x melhoria!
[INFO] Throughput: 1650.00 ticks/s (instant), 1675.00 ticks/s (avg)
[INFO] Throughput: 1720.00 ticks/s (instant), 1690.00 ticks/s (avg)
```

**Speedup:** 1690 / 850 = **1.99x** (aproximadamente 2x)

## Cálculo das Métricas

### Throughput Instantâneo

```scala
ticksPerSecond = (ticksProcessados / tempoDecorrido) * 1000
```

Mede a taxa de processamento no **último intervalo**.

### Throughput Médio

```scala
avgTicksPerSecond = (totalTicks / tempoTotal) * 1000
```

Mede a taxa de processamento **desde o início** da simulação.

## Exemplo de Análise Completa

### Cenário: Simulação de 100k ticks

**Configuração:**
```bash
export HTC_TIME_MANAGER_LOOKAHEAD=1
export HTC_TIME_MANAGER_METRICS_INTERVAL=5000
```

**Saída (baseline):**
```
[INFO] Throughput: 800.00 ticks/s (instant), 800.00 ticks/s (avg) | Tick: 5000 | Total ticks: 5000 | Elapsed: 6.2 s
[INFO] Throughput: 810.00 ticks/s (instant), 805.00 ticks/s (avg) | Tick: 10000 | Total ticks: 10000 | Elapsed: 12.4 s
...
[INFO] Throughput: 795.00 ticks/s (instant), 800.00 ticks/s (avg) | Tick: 100000 | Total ticks: 100000 | Elapsed: 125.0 s
```

**Tempo total:** 125 segundos

---

**Configuração (com lookahead):**
```bash
export HTC_TIME_MANAGER_LOOKAHEAD=10
export HTC_TIME_MANAGER_METRICS_INTERVAL=5000
```

**Saída (otimizado):**
```
[INFO] Throughput: 1600.00 ticks/s (instant), 1600.00 ticks/s (avg) | Tick: 5000 | Total ticks: 5000 | Elapsed: 3.1 s
[INFO] Throughput: 1620.00 ticks/s (instant), 1610.00 ticks/s (avg) | Tick: 10000 | Total ticks: 10000 | Elapsed: 6.2 s
...
[INFO] Throughput: 1590.00 ticks/s (instant), 1600.00 ticks/s (avg) | Tick: 100000 | Total ticks: 100000 | Elapsed: 62.5 s
```

**Tempo total:** 62.5 segundos

**Speedup:** 125 / 62.5 = **2.0x**

## Correlacionando com Outras Métricas

### CPU Utilization

```bash
# Terminal 1: Rodar simulação
./run.sh

# Terminal 2: Monitorar CPU
watch -n 1 "ps aux | grep java | grep -v grep"
```

**Correlação esperada:**
- Throughput alto → CPU alto (sistema CPU-bound, bom!)
- Throughput baixo + CPU baixo → Gargalo I/O ou sincronização
- Throughput baixo + CPU alto → Ineficiência algorítmica

### Lookahead Effectiveness

```bash
grep -E "(Throughput|lookahead:)" logs/simulation.log
```

**Exemplo:**
```
[INFO] Send spontaneous at tick 500 to 1000 actors (lookahead: +8 ticks)
[INFO] Throughput: 1600.00 ticks/s (instant), 1600.00 ticks/s (avg)
[INFO] Send spontaneous at tick 1000 to 1000 actors (lookahead: +9 ticks)
[INFO] Throughput: 1650.00 ticks/s (instant), 1625.00 ticks/s (avg)
```

**Análise:** Lookahead efetivo (+8-9 ticks) correlaciona com throughput alto.

## Troubleshooting

### Throughput muito baixo (< 100 ticks/s)

**Possíveis causas:**
1. Muitos atores com dependências complexas
2. I/O excessivo (banco de dados, arquivos)
3. Sincronização muito frequente (lookahead=1 com workload denso)

**Soluções:**
- Aumentar `lookahead-window`
- Desabilitar logging desnecessário
- Usar JSON pré-configurado ao invés de banco de dados

### Throughput instável (variação > 50%)

**Possíveis causas:**
1. Carga de trabalho variável (normal)
2. Garbage collection
3. CPU throttling

**Soluções:**
- Ajustar heap JVM (`-Xms` e `-Xmx`)
- Verificar temperatura/throttling do sistema
- Aumentar `metrics-log-interval` para suavizar variações

### Métricas não aparecem

**Checklist:**
1. ✓ `metrics-log-interval > 0`?
2. ✓ Simulação rodou por tempo suficiente?
3. ✓ Logs sendo capturados? (`tee logs/output.log`)

## Scripts Úteis

### Extrair Métricas de Throughput

```bash
#!/bin/bash
# extract-metrics.sh
grep "Throughput:" logs/simulation.log | \
  awk '{print $3, $9}' | \
  sed 's/,//g' > throughput_data.csv
```

Gera CSV: `instant_tps,avg_tps`

### Plot com gnuplot

```bash
#!/bin/bash
gnuplot <<EOF
set terminal png size 800,600
set output 'throughput.png'
set title 'Throughput over Time'
set xlabel 'Sample'
set ylabel 'Ticks/Second'
plot 'throughput_data.csv' using 1 with lines title 'Instant', \
     '' using 2 with lines title 'Average'
EOF
```

### Comparação Automática

```bash
#!/bin/bash
# compare-lookahead.sh

echo "Running baseline (window=1)..."
export HTC_TIME_MANAGER_LOOKAHEAD=1
export HTC_TIME_MANAGER_METRICS_INTERVAL=1000
./run.sh > baseline.log 2>&1

baseline_tps=$(grep "Throughput:" baseline.log | tail -1 | awk '{print $9}')

echo "Running optimized (window=10)..."
export HTC_TIME_MANAGER_LOOKAHEAD=10
./run.sh > optimized.log 2>&1

optimized_tps=$(grep "Throughput:" optimized.log | tail -1 | awk '{print $9}')

speedup=$(echo "scale=2; $optimized_tps / $baseline_tps" | bc)
echo "Baseline: $baseline_tps ticks/s"
echo "Optimized: $optimized_tps ticks/s"
echo "Speedup: ${speedup}x"
```

## Integração com Monitoramento

### Prometheus/Grafana (Futuro)

As métricas já estão estruturadas para fácil exportação:

```scala
// Futuro: exportar para Prometheus
prometheusRegistry.register(
  Gauge.build()
    .name("htc_ticks_per_second")
    .help("Simulation throughput in ticks per second")
    .labelNames("type")
    .create()
)
```

### Elastic Stack (Futuro)

Logs já estão no formato apropriado para parsing:

```json
{
  "timestamp": "2025-12-14T18:54:00Z",
  "level": "INFO",
  "message": "Throughput",
  "instant_tps": 1600.00,
  "avg_tps": 1600.00,
  "current_tick": 5000,
  "total_ticks": 5000,
  "elapsed_s": 3.1
}
```

## Referências

- [LOOKAHEAD_OPTIMIZATION.md](LOOKAHEAD_OPTIMIZATION.md) - Otimização principal
- [PERFORMANCE_TUNING.md](PERFORMANCE_TUNING.md) - Guia geral de performance
- [CONFIGURATION.md](CONFIGURATION.md) - Todas as opções de configuração

---

**Adicionado em:** 2025-12-14  
**Versão:** 1.0.0  
**Status:** ✅ Produção
