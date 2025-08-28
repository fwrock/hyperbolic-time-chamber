# Abordagem Escalável para Gerenciamento de Tempo

## Problema Atual: TimeManagerRouter como Gargalo

O `TimeManagerRouter` atual pode se tornar um gargalo porque:
- Todos os registros de atores passam por ele
- É um ponto único de falha
- Não escala horizontalmente

## Soluções Escaláveis Recomendadas

### 1. **ClusterSharding com Pekko** (Recomendado)

```scala
// Usar ClusterSharding para distribuir LTMs por nodes
val sharding = ClusterSharding(system)

// Configurar sharding por tipo de paradigma
val desShardRegion = sharding.init(Entity(
  typeKey = EntityTypeKey[Command]("DesTimeManager")
)(entityContext => DiscreteEventSimulationTimeManager(entityContext.entityId))
  .withRole("time-manager"))
```

**Benefícios:**
- Distribuição automática por nodes do cluster
- Balanceamento automático de carga
- Resiliência a falhas de nodes
- Escalabilidade horizontal

### 2. **Receptionist Pattern** (Para descoberta de serviços)

```scala
// LTMs se registram automaticamente
context.system.receptionist ! Receptionist.Register(
  ServiceKey[RegisterActorEvent]("des-time-manager"), 
  self
)

// Atores descobrem LTMs diretamente
context.system.receptionist ! Receptionist.Find(
  ServiceKey[RegisterActorEvent]("des-time-manager")
)
```

**Benefícios:**
- Descoberta descentralizada
- Sem ponto único de falha
- Registro automático de serviços

### 3. **Registro Direto durante Criação de Atores**

```scala
// Durante criação do ator, determinar LTM diretamente
val ltmRef = determineLTMForActor(actorType)
val actor = context.actorOf(ActorProps.props(ltmRef), name)
```

**Benefícios:**
- Elimina completamente o router
- Performance máxima
- Zero overhead de roteamento

### 4. **Consistent Hashing**

```scala
// Usar hash consistente baseado em ID do ator
val ltmIndex = Math.abs(actorId.hashCode) % poolSize
val ltm = ltmPool(ltmIndex)
```

**Benefícios:**
- Distribuição uniforme
- Previsibilidade
- Baixa latência

## Implementação Futura Recomendada

Para eliminar o gargalo do TimeManagerRouter:

1. **Fase 1**: Implementar ClusterSharding
2. **Fase 2**: Usar Receptionist para descoberta
3. **Fase 3**: Registro direto na criação de atores
4. **Fase 4**: Consistent hashing para ultra-performance

## Configuração de Exemplo

```hocon
pekko {
  cluster {
    sharding {
      number-of-shards = 100
      guardian-name = sharding
      role = "time-manager"
    }
  }
}
```

## Métricas de Performance Esperadas

- **Atual**: ~1000 registros/segundo (gargalo no router)
- **ClusterSharding**: ~10,000 registros/segundo (distribuído)
- **Registro Direto**: ~100,000 registros/segundo (sem overhead)
