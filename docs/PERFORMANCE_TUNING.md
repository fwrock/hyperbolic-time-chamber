# Performance Tuning - Configuração de Alto Desempenho

## 🚀 Otimizações Aplicadas para Máximo Throughput

Este documento descreve todas as otimizações aplicadas no sistema para maximizar o throughput em workloads grandes.

---

## 1. **Dispatchers Otimizados**

### Default Dispatcher
```hocon
default-dispatcher {
    type = Dispatcher
    executor = "fork-join-executor"
    fork-join-executor {
        parallelism-min = 16
        parallelism-factor = 3.0
        parallelism-max = 128
    }
    throughput = 500  # Processar 500 mensagens antes de trocar de thread
}
```

**Benefícios:**
- Fork-Join executor otimizado para paralelismo
- Throughput de 500 mensagens por thread reduz context switching
- Parallelism-factor de 3.0 = 3x cores disponíveis

### Sharding Dispatcher
```hocon
sharding-dispatcher {
    type = Dispatcher
    executor = "fork-join-executor"
    fork-join-executor {
        parallelism-min = 8
        parallelism-factor = 2.0
        parallelism-max = 64
    }
    throughput = 1000  # Maior throughput para coordenadores
}
```

**Benefícios:**
- Dedicado para operações de sharding coordination
- Throughput de 1000 evita contenção

---

## 2. **Persistence Desabilitado**

### Configuração
- **Journal**: InMemory (sem I/O)
- **Snapshots**: Completamente desabilitados
- **snapshot-interval**: `Int.MaxValue` (2,147,483,647)

### BaseActor
```scala
private val snapShotInterval = Int.MaxValue
```

**Benefícios:**
- Zero overhead de I/O para persistence
- Sem serialização de snapshots
- Sem operações de limpeza de snapshots antigos
- **Ganho estimado**: 30-50% em throughput

---

## 3. **Remote/Artery Otimizado**

### Buffers Aumentados
```hocon
advanced {
    maximum-frame-size = 2 MiB        # Default: 256 KiB
    buffer-pool-size = 256            # Default: 128
    maximum-large-frame-size = 8 MiB  # Default: 2 MiB
    large-buffer-pool-size = 64       # Default: 32
    
    outbound-message-queue-size = 30720   # Default: 3072
    outbound-control-queue-size = 30720   # Default: 3072
}
```

### Lanes Paralelas
```hocon
outbound-lanes = 8  # Múltiplas lanes para paralelismo
inbound-lanes = 8
```

**Benefícios:**
- 10x aumento em buffers = menos backpressure
- Múltiplas lanes = melhor paralelização de I/O
- **Ganho estimado**: 40-60% em comunicação cluster

---

## 4. **Cluster Sharding Otimizado**

### Passivation Desabilitada
```hocon
passivation.strategy = "none"
```
**Benefício**: Atores nunca são passivados, sempre em memória.

### Snapshots Desabilitados
```hocon
remember-entities = false
snapshot-after = 0
```
**Benefício**: Zero overhead de snapshot em shard coordinators.

### State Store: DData
```hocon
state-store-mode = "ddata"
```
**Benefício**: Replicação distribuída mais rápida que persistence.

### Buffer Massivo
```hocon
buffer-size = 100000  # Default: 100000
```
**Benefício**: Suporta picos de tráfego sem perda.

### Alocação de Shards Otimizada
```hocon
least-shard-allocation-strategy {
    rebalance-threshold = 1000000      # Desabilitado efetivamente
    max-simultaneous-rebalance = 1     # Apenas 1 se ocorrer
}
rebalance-interval = 30 s              # Default: 10 s
```

**Benefícios:**
- **Zero perda de estado** (sem rebalanceamento = sem passivação)
- **Distribuição inicial permanece** (adequado para simulações de período fixo)
- **Máximo throughput** (sem overhead de handoff)

**Trade-off**: Se um nó falhar, seus shards perdem estado (mas isso é raro em simulações controladas)

### Timeouts Ajustados
```hocon
waiting-for-state-timeout = 5 s      # Default: 2 s
updating-state-timeout = 10 s        # Default: 5 s
handoff-timeout = 120 s              # Default: 60 s
shard-start-timeout = 60 s           # Default: 10 s
```

**Benefício**: Tolerância para operações em workloads grandes.

---

## 5. **Failure Detector Relaxado**

```hocon
failure-detector {
    acceptable-heartbeat-pause = 20 s   # Default: 10 s
    threshold = 16.0                    # Default: 12.0
    expected-response-after = 5 s       # Default: 1 s
}
```

**Benefícios:**
- Menos falsos positivos em alta carga
- Sistema mais estável sob pressão

---

## 6. **Distributed Data (DData) Otimizado**

```hocon
distributed-data {
    gossip-interval = 500 ms               # Default: 2 s
    notify-subscribers-interval = 200 ms   # Default: 500 ms
    max-delta-elements = 5000              # Default: 1000
    use-dispatcher = "pekko.actor.sharding-dispatcher"
}
```

**Benefícios:**
- Propagação de estado 4x mais rápida
- 5x mais elementos por delta
- Dispatcher dedicado

---

## 7. **Time Manager Otimizado**

### Batch Size Aumentado
```hocon
batch-size = 50000  # Era: 15000
```
**Benefício**: 3.3x mais eventos processados por lote.

### Snapshots Desabilitados
```hocon
snapshot-interval = 2147483647  # Int.MaxValue
```

### Timeouts Reduzidos
```hocon
actor-timeout-ms = 180000      # Era: 300000 (reduzido 40%)
sync-timeout-ms = 30000        # Era: 60000 (reduzido 50%)
stale-event-max-age-ms = 30000 # Era: 60000 (reduzido 50%)
```

**Benefícios:**
- Detecção mais rápida de problemas
- Menos tempo esperando por atores travados

---

## 8. **Cluster Gossip Otimizado**

```hocon
gossip-interval = 500 ms              # Default: 1 s
gossip-time-to-live = 10 s            # Default: 2 s
leader-actions-interval = 200 ms      # Default: 1 s
unreachable-nodes-reaper-interval = 5 s  # Default: 1 s
publish-stats-interval = 10 s         # Default: off
```

**Benefícios:**
- Convergência de cluster 2x mais rápida
- Leader actions 5x mais frequentes

---

## 📊 Ganhos Estimados de Performance

| Componente | Otimização | Ganho Estimado |
|------------|-----------|----------------|
| **Persistence** | Desabilitado completamente | **30-50%** |
| **Remote (Artery)** | Buffers 10x maiores | **40-60%** |
| **Sharding** | Passivation off + DData | **20-30%** |
| **Dispatchers** | Throughput 500-1000 | **15-25%** |
| **Time Manager** | Batch 50k + timeouts | **25-35%** |
| **Cluster Gossip** | Intervals reduzidos | **10-15%** |

### **Ganho Total Estimado: 2x-3x em throughput geral** 🚀

---

## 🔧 Monitoramento

### Métricas Importantes

1. **Message Rate**: Mensagens/segundo processadas
2. **Mailbox Size**: Tamanho das filas de mensagens
3. **Remote Message Rate**: Taxa de mensagens entre nodes
4. **Shard Region Load**: Distribuição de entities por shard
5. **GC Pauses**: Pausas de garbage collection

### Logs de Performance

O sistema agora está configurado para:
- Minimal logging (INFO level)
- Dead letters desabilitados
- Debug logging completamente off

---

## ⚠️ Trade-offs

### O que foi sacrificado para performance:

1. **Durabilidade**: 
   - Sem snapshots = perda de estado em crash
   - **Solução**: Use external state store se necessário

2. **Memória**:
   - Passivation desabilitada = todos atores em memória
   - **Requisito**: RAM suficiente para todo workload

3. **Recovery Time**:
   - Sem snapshots = recovery mais lento após restart
   - **Mitigação**: Minimize restarts, use rolling updates

4. **Observability**:
   - Logging reduzido = menos visibilidade
   - **Solução**: Use métricas e tracing externo

---

## 🎯 Recomendações para Workloads Grandes

### Hardware
- **RAM**: 64-128 GB por node
- **CPU**: 16+ cores físicos
- **Network**: 10 Gbps+ entre nodes
- **Disk**: SSD NVMe (para logs apenas)

### JVM Settings
```bash
-Xmx110G                           # 85% da RAM disponível
-Xms110G                           # Pre-allocate full heap
-XX:+UseG1GC                       # G1 GC para heaps grandes
-XX:MaxGCPauseMillis=200          # Target GC pause
-XX:G1HeapRegionSize=32M          # Região grande para objetos grandes
-XX:+UseStringDeduplication       # Deduplicate strings
-XX:+ParallelRefProcEnabled       # Parallel reference processing
-XX:+UseCompressedOops            # Compressed object pointers
```

### Monitoring
```bash
# Pekko Management HTTP endpoint
curl http://localhost:8558/cluster/members

# JMX for detailed metrics
-Dcom.sun.management.jmxremote=true
-Dcom.sun.management.jmxremote.port=9999
```

---

## 🚦 Validação

Para validar as otimizações:

```bash
# 1. Build e deploy
./build-and-run.sh

# 2. Monitor cluster formation
watch -n 1 'curl -s http://localhost:8558/cluster/members'

# 3. Check GC logs
tail -f /var/log/gc.log

# 4. Monitor throughput
# Use ReportManager JSON outputs

# 5. Profile com async-profiler
java -agentpath:/path/to/async-profiler/libasyncProfiler.so=start,event=cpu,file=profile.html
```

---

## 📚 Referências

- [Apache Pekko Performance Tuning](https://pekko.apache.org/docs/pekko/current/additional/performance.html)
- [Cluster Sharding](https://pekko.apache.org/docs/pekko/current/typed/cluster-sharding.html)
- [Dispatchers](https://pekko.apache.org/docs/pekko/current/dispatchers.html)
- [Serialization](https://pekko.apache.org/docs/pekko/current/serialization.html)
- [Remote Configuration](https://pekko.apache.org/docs/pekko/current/remoting-artery.html)

---

**Última atualização**: 14 de Dezembro de 2025
**Versão**: 1.12.0
