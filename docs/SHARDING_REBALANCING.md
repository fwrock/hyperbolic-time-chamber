# 🔄 Cluster Sharding - Rebalanceamento e Persistência de Estado

## ⚠️ Problema: Rebalanceamento SEM Snapshots

### Como Funciona o Rebalanceamento

```
┌─────────────────────────────────────────────────────┐
│  REBALANCEAMENTO DE SHARD (sem snapshots)           │
└─────────────────────────────────────────────────────┘

Nó A (origem)                      Nó B (destino)
┌─────────────┐                    ┌─────────────┐
│ Shard 123   │                    │             │
│  ├─ Car#1   │ ─────────┐         │             │
│  ├─ Car#2   │          │         │             │
│  └─ Car#3   │          │         │             │
└─────────────┘          │         └─────────────┘
                         │
                    1. Passivate
                       (DESTROY)
                         │
                         ↓
Nó A                              Nó B
┌─────────────┐                 ┌─────────────┐
│ (vazio)     │   2. Transfer   │ Shard 123   │
│             │  ─────────────→ │  ├─ Car#1   │ ← Recriado VAZIO
│             │                 │  ├─ Car#2   │ ← Recriado VAZIO
│             │                 │  └─ Car#3   │ ← Recriado VAZIO
└─────────────┘                 └─────────────┘

❌ Estado perdido!
```

### Com Snapshots (Cenário Tradicional)
```
┌─────────────────────────────────────────────────────┐
│  REBALANCEAMENTO COM SNAPSHOTS                      │
└─────────────────────────────────────────────────────┘

Nó A                              Persistent Store
┌─────────────┐                 ┌─────────────────┐
│ Shard 123   │                 │ Snapshots:      │
│  ├─ Car#1   │ ─── save ────→  │  Car#1 = {...} │
│  ├─ Car#2   │ ─── save ────→  │  Car#2 = {...} │
│  └─ Car#3   │ ─── save ────→  │  Car#3 = {...} │
└─────────────┘                 └─────────────────┘
                                         │
                    Passivate            │
                         ↓               │
Nó B                              ┌──────┘
┌─────────────┐                 │ restore
│ Shard 123   │                 │
│  ├─ Car#1   │ ←────────────────┘
│  ├─ Car#2   │ ← Estado restaurado
│  └─ Car#3   │ ← Estado restaurado
└─────────────┘

✅ Estado preservado!
```

---

## 🎯 Estratégias para Alto Throughput

### Opção 1: **Desabilitar Rebalanceamento** (RECOMENDADO para simulação)

```hocon
least-shard-allocation-strategy {
  rebalance-threshold = 1000000      # Threshold muito alto
  max-simultaneous-rebalance = 1     # Apenas 1 por vez (se ocorrer)
}
```

**Quando usar**:
- ✅ Simulações de período fixo (horas/dias)
- ✅ Cluster estável (nós não falham)
- ✅ Workload conhecido antecipadamente
- ✅ Máximo throughput é prioridade

**Vantagens**:
- 🚀 **Zero overhead** de handoff
- ✅ **Zero perda** de estado
- ⚡ **Máximo throughput**

**Desvantagens**:
- ⚠️ Distribuição inicial permanece (não se adapta)
- ⚠️ Hotspots não são corrigidos automaticamente
- ⚠️ Se um nó falhar, seus shards vão para outros nós, mas estado é perdido

**Configuração Completa**:
```hocon
pekko.cluster.sharding {
  passivation.strategy = "none"
  remember-entities = false
  snapshot-after = 0
  
  least-shard-allocation-strategy {
    rebalance-threshold = 1000000
    max-simultaneous-rebalance = 1
  }
  
  rebalance-interval = 1.hour   # Raramente verifica
}
```

---

### Opção 2: **Atores Stateless** + Rebalanceamento Ativo

```hocon
least-shard-allocation-strategy {
  rebalance-threshold = 10          # Rebalanceia agressivamente
  max-simultaneous-rebalance = 10
}
```

**Quando usar**:
- ✅ Atores podem recarregar estado de fonte externa (Redis, DB)
- ✅ Estado é externalizável
- ✅ Workload varia muito

**Arquitetura**:
```scala
class Car extends BaseActor[CarState] {
  
  override def preStart(): Unit = {
    super.preStart()
    // Carrega estado do Redis ao iniciar
    state = loadStateFromRedis(entityId)
  }
  
  override def onDestruct(event: DestructEvent): Unit = {
    // Salva estado no Redis antes de destruir
    saveStateToRedis(entityId, state)
    super.onDestruct(event)
  }
}
```

**Vantagens**:
- 🔄 **Rebalanceamento** funciona normalmente
- 📊 **Distribuição adaptativa** de carga
- ✅ **Sem perda** de estado (externalizado)

**Desvantagens**:
- 🐌 **Latência** extra (leitura/escrita Redis)
- 💾 **I/O overhead** (~20-30% throughput)
- 🔌 **Dependência externa** (Redis)

---

### Opção 3: **Híbrido - Estado em Memória + Checkpoint Periódico**

```scala
class Car extends BaseActor[CarState] {
  
  private var checkpointTick: Tick = 0
  
  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    // Lógica normal
    processSimulationTick(event)
    
    // Checkpoint periódico (ex: a cada 1000 ticks)
    if (currentTick - checkpointTick >= 1000) {
      saveStateToRedis(entityId, state)  // Async
      checkpointTick = currentTick
    }
  }
  
  override def preStart(): Unit = {
    super.preStart()
    // Tenta restaurar último checkpoint
    state = loadStateFromRedis(entityId).getOrElse(initialState)
  }
}
```

**Configuração**:
```hocon
least-shard-allocation-strategy {
  rebalance-threshold = 100         # Moderado
  max-simultaneous-rebalance = 5
}
rebalance-interval = 10.minutes     # Não muito frequente
```

**Vantagens**:
- ⚡ **Alto throughput** (checkpoint async)
- 🔄 **Rebalanceamento** possível
- ✅ **Perda limitada** (apenas desde último checkpoint)

**Desvantagens**:
- 🎯 **Complexidade** adicional
- 💾 **Algum I/O overhead**
- ⏱️ **Estado pode estar defasado** ao restaurar

---

### Opção 4: **Remember Entities + Distributed Data** (Com perda parcial)

```hocon
pekko.cluster.sharding {
  remember-entities = true
  remember-entities-store = "ddata"   # Sem persistence
  
  least-shard-allocation-strategy {
    rebalance-threshold = 50
    max-simultaneous-rebalance = 5
  }
}
```

**O que acontece**:
- ✅ Atores são **lembrados** pelo coordinator
- ✅ Após rebalanceamento, atores são **recriados** no novo nó
- ⚠️ Atores são recriados **com estado inicial vazio**
- ⚠️ Aplicação deve **recarregar estado** no `preStart()`

**Quando usar**:
- Estado pode ser recalculado rapidamente
- Atores precisam existir, mas estado é recuperável

---

## 📊 Comparação de Estratégias

| Estratégia | Throughput | Perda Estado | Adaptativo | Complexidade |
|------------|------------|--------------|------------|--------------|
| **Opção 1: Sem Rebalance** | ⭐⭐⭐⭐⭐ | ✅ Zero | ❌ Não | ⭐ Simples |
| **Opção 2: Stateless + Redis** | ⭐⭐⭐ | ✅ Zero | ✅ Sim | ⭐⭐⭐ Médio |
| **Opção 3: Checkpoint Híbrido** | ⭐⭐⭐⭐ | ⚠️ Parcial | ✅ Sim | ⭐⭐⭐⭐ Alto |
| **Opção 4: Remember Entities** | ⭐⭐⭐⭐ | ⚠️ Inicial | ✅ Sim | ⭐⭐ Baixo |

---

## 🎯 Recomendação para Hyperbolic Time Chamber

### Para Simulações de Tráfego Urbano (Cenário Atual)

**Use Opção 1: Sem Rebalanceamento**

```hocon
pekko.cluster.sharding {
  passivation.strategy = "none"
  remember-entities = false
  snapshot-after = 0
  
  least-shard-allocation-strategy {
    rebalance-threshold = 1000000    # Desabilitado efetivamente
    max-simultaneous-rebalance = 1
  }
  
  rebalance-interval = 1.hour        # Raramente verifica
  state-store-mode = "ddata"
  buffer-size = 100000
}
```

**Por quê**:
1. ✅ **Simulações têm duração finita** (não rodam indefinidamente)
2. ✅ **Cluster estável** durante simulação
3. ✅ **Workload conhecido** antecipadamente (JSON de entrada)
4. ✅ **Estado não é crítico** após simulação (apenas relatórios)
5. 🚀 **Máximo throughput** é prioritário

**Trade-off Aceitável**:
- ⚠️ Se um nó falhar, veículos naquele nó perdem estado
- ✅ Mas simulação pode ser reiniciada ou continuada com novos veículos
- ✅ Relatórios já gerados não são perdidos (salvos em disco)

---

## 🚨 Cenários de Falha

### Com Rebalanceamento Desabilitado

```
Cenário: Nó falha durante simulação

ANTES DA FALHA:
┌─────────┬─────────┬─────────┬─────────┐
│ Nó 1    │ Nó 2    │ Nó 3    │ Nó 4    │
│ 25%     │ 25%     │ 25%     │ 25%     │
│ shards  │ shards  │ shards  │ shards  │
└─────────┴─────────┴─────────┴─────────┘

DEPOIS DA FALHA (Nó 2 caiu):
┌─────────┬─────────┬─────────┐
│ Nó 1    │ Nó 3    │ Nó 4    │
│ 25% ────┼──→ 8%   │         │
│         │  (Nó 2) │         │
│         │ recria  │         │
│         │ VAZIO   │         │
└─────────┴─────────┴─────────┘

Resultado:
- 75% dos veículos: ✅ Continuam normalmente
- 25% dos veículos: ❌ Perdem estado (do Nó 2)
- Novos veículos: ✅ Distribuídos entre 3 nós
```

**Mitigação**:
1. Use hardware confiável (ECC RAM, redundância)
2. Monitore saúde dos nós (health checks)
3. Aceite perda parcial como trade-off de performance

---

## 💡 Implementação Recomendada

### 1. Configuração Base (application.conf)
```hocon
pekko.cluster.sharding {
  # OPÇÃO 1: Sem rebalanceamento (RECOMENDADO)
  passivation.strategy = "none"
  remember-entities = false
  snapshot-after = 0
  
  least-shard-allocation-strategy {
    rebalance-threshold = 1000000
    max-simultaneous-rebalance = 1
  }
  
  rebalance-interval = 1.hour
  state-store-mode = "ddata"
  buffer-size = 100000
}
```

### 2. Se Precisar de Rebalanceamento (Adicione ao BaseActor)

```scala
abstract class BaseActor[T <: BaseState] {
  
  // Hook para salvar estado antes de passivação
  protected def saveStateExternal(): Unit = {
    // Override se precisar salvar no Redis/DB
  }
  
  // Hook para carregar estado ao iniciar
  protected def loadStateExternal(): Option[T] = {
    // Override se precisar carregar do Redis/DB
    None
  }
  
  override def preStart(): Unit = {
    super.preStart()
    // Tenta restaurar estado
    loadStateExternal() match {
      case Some(externalState) => state = externalState
      case None => // Use estado inicial do JSON
    }
  }
  
  override def onDestruct(event: DestructEvent): Unit = {
    // Salva estado se estiver sendo destruído por rebalanceamento
    if (event.reason == DestructReason.Rebalancing) {
      saveStateExternal()
    }
    super.onDestruct(event)
  }
}
```

### 3. Monitoramento

```bash
# Verificar distribuição de shards
curl http://localhost:8558/cluster/shards/mobility.actor.Car | jq '.regions'

# Verificar se houve rebalanceamento
docker logs htc_worker_1 2>&1 | grep -i rebalanc

# Verificar falhas de nós
curl http://localhost:8558/cluster/members | jq '.unreachable'
```

---

## 📚 Conclusão

### Para Simulação de Alto Throughput (Atual):
✅ **Desabilite rebalanceamento** (`threshold = 1000000`)

### Para Sistema de Produção com Alta Disponibilidade:
✅ **Use estado externo** (Redis) + rebalanceamento ativo

### Para Pesquisa/Desenvolvimento:
✅ **Checkpoint híbrido** (melhor de dois mundos)

---

**Configuração Atual Aplicada**: Opção 1 (Sem Rebalanceamento) ✅

Perda de estado só ocorre se nó falhar, não durante operação normal. Para simulações de período fixo, este é o melhor trade-off performance vs confiabilidade.
