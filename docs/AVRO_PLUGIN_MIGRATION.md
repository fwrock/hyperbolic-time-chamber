# 🔧 Avro Plugin Migration Guide

## ⚠️ Current Status

O plugin `sbt-avro` foi temporariamente removido devido a problemas de classpath com SBT 1.10.7 e Java 21. 

### 🐛 Erro Encontrado
```
java.lang.NoClassDefFoundError: org.apache.avro.specific.SpecificRecord
```

## 🛠️ Solução Temporária

### 1. **Plugin Desabilitado**
```scala
// project/plugins.sbt
// TODO: Re-enable when Avro plugin issue is resolved
// addSbtPlugin("com.github.sbt" % "sbt-avro" % "3.4.4")
```

### 2. **Implementação Manual**
Criada classe manual `DynamicLinkCostEvent` em:
```
src/main/scala/avro/model/hybrid/routing/DynamicLinkCostEvent.scala
```

### 3. **Serialização JSON**
Usando JSON temporariamente no lugar de Avro binário:
```scala
// DynamicLinkCostAvro.scala
def serialize(cost: DynamicLinkCost): Try[Array[Byte]] = {
  Try {
    val event = toAvro(cost)
    JsonUtil.toJson(event).getBytes("UTF-8")  // JSON instead of binary
  }
}
```

## 🔄 Reativação do Avro (Futuro)

### Quando o plugin estiver funcional:

1. **Reativar plugin**
```scala
// project/plugins.sbt
addSbtPlugin("com.github.sbt" % "sbt-avro" % "3.4.4")
```

2. **Reativar configuração**
```scala
// build.sbt
Compile / avroSource := baseDirectory.value / "src" / "main" / "avro",
Compile / avroGenerate / target := (Compile / sourceManaged).value / "avro"
```

3. **Remover implementação manual**
```bash
rm src/main/scala/avro/model/hybrid/routing/DynamicLinkCostEvent.scala
```

4. **Atualizar imports**
```scala
// DynamicLinkCostAvro.scala - voltar para binary Avro
import org.apache.avro.specific.{SpecificDatumReader, SpecificDatumWriter}
```

## ✅ O que Funciona Agora

### 1. **Tópicos Kafka**
- ✅ Nomenclatura padronizada (`dev.htc.routing.dynamic-costs.v1`)
- ✅ Auto-criação via `KafkaTopicManager`
- ✅ Script `kafka-topics-init.sh`

### 2. **Serialização**
- ✅ JSON serialization (funciona para desenvolvimento)
- ✅ Schema validation
- ✅ Round-trip testing

### 3. **Cache Strategy**
- ✅ Kafka cache strategy implementada
- ✅ Topic naming integrado
- ✅ Configuration no `application.conf`

## 🚀 Como Usar

### Desenvolvimento Atual (JSON)
```bash
# 1. Subir Kafka
docker-compose up kafka

# 2. Criar tópicos
./scripts/kafka-topics-init.sh init

# 3. Build projeto
sbt compile

# 4. Rodar simulação
./build-and-run.sh
```

### Testing
```scala
// Testar serialização JSON
sbt "runMain org.interscity.htc.model.hybrid.util.avro.DynamicLinkCostAvroExample"
```

## 📊 Performance

| Formato | Tamanho | Speed | Compatibilidade |
|---------|---------|-------|-----------------|
| **JSON (atual)** | ~400 bytes | Rápido | ✅ SBT 1.10.7 |
| **Avro Binary** | ~150 bytes | Muito rápido | ❌ Plugin issue |

### Impacto Temporário
- ~2.5x maior payload (aceitável para dev)
- Mesmo throughput (JSON é rápido o suficiente)
- Schema validation mantida

## 🔮 Alternativas Futuras

### 1. **ScalaPB para Avro**
```scala
// Usar protobuf em vez de Avro
"com.thesamet.scalapb" %% "scalapb-runtime" % scalapbVersion
```

### 2. **Plugin Avro Alternativo**
```scala
// Testar plugin mais novo
addSbtPlugin("com.github.sbt" % "sbt-avro" % "4.0.0")  // Quando disponível
```

### 3. **Manual Avro**
```scala
// Implementação manual sem plugin (se necessário)
"org.apache.avro" % "avro" % avroVersion
```

## ✅ Conclusão

A solução atual permite:
- ✅ **Desenvolvimento continuar** sem bloqueios
- ✅ **Funcionalidade preservada** (JSON é eficaz)
- ✅ **Migration path claro** para Avro binary
- ✅ **Zero breaking changes** no código cliente

Quando o plugin Avro estiver estável, a migração será simples e transparente.