# 🏷️ Kafka Topic Management & Avro Schemas

## 📋 Overview

Implementação completa de padronização de tópicos Kafka e serialização Avro para o HTC (Hyperbolic Time Chamber) simulation.

### 🎯 Objetivos Alcançados

✅ **Padrão de nomenclatura de tópicos** padronizado e versionado
✅ **Auto-criação de tópicos** Kafka na inicialização
✅ **Schemas Avro** para serialização eficiente
✅ **Scripts de gerenciamento** para operações DevOps

## 📐 Padrão de Nomenclatura

### Formato Padrão
```
{environment}.htc.{domain}.{component}.{version}
```

### Exemplos
```
dev.htc.routing.dynamic-costs.v1      # Custos dinâmicos para roteamento
dev.htc.mobility.vehicle-updates.v1   # Atualizações de posição de veículos  
dev.htc.system.performance-metrics.v1 # Métricas de performance
prod.htc.routing.incidents.v1         # Incidentes de trânsito (produção)
```

### Benefícios
- **Environment isolation:** dev/staging/prod separados
- **Domain organization:** routing, mobility, system
- **Version management:** evolução de schema sem breaking changes
- **Searchable:** fácil filtrar por domínio/componente

## 🏗️ Auto-Criação de Tópicos

### Classes Scala

```scala
// Nomenclatura padronizada
KafkaTopicNaming.Routing.DynamicCosts
// → "dev.htc.routing.dynamic-costs.v1"

// Gerenciador de tópicos
KafkaTopicManager.initializeAllTopics()
// Cria todos os tópicos HTC automaticamente
```

### Script Shell

```bash
# Inicializar todos os tópicos
./scripts/kafka-topics-init.sh init

# Listar tópicos existentes  
./scripts/kafka-topics-init.sh list

# Limpar tópicos HTC
./scripts/kafka-topics-init.sh clean
```

### Configuração Docker

Tópicos são criados automaticamente quando o container inicia:

```yaml
services:
  kafka:
    environment:
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: 'true'
      KAFKA_NUM_PARTITIONS: 12
```

## 📦 Schemas Avro

### Estrutura de Arquivos

```
src/main/avro/
└── model/hybrid/routing/
    └── dynamic_link_cost.avsc    # Schema Avro
    
src/main/scala/model/hybrid/util/avro/
└── DynamicLinkCostAvro.scala     # Wrapper Scala
```

### Schema Avro (`dynamic_link_cost.avsc`)

```json
{
  "type": "record",
  "name": "DynamicLinkCostEvent", 
  "namespace": "org.interscity.htc.avro.model.hybrid.routing",
  "fields": [
    {"name": "linkId", "type": "string"},
    {"name": "staticCost", "type": "double"},
    {"name": "congestionFactor", "type": "double", "default": 1.0},
    {"name": "totalCost", "type": "double"},
    {"name": "simulationMode", "type": {"type": "enum", "name": "SimulationMode", "symbols": ["MESO", "MICRO"]}}
  ]
}
```

### Serialização Eficiente

```scala
// Conversão modelo → Avro → bytes
val cost: DynamicLinkCost = ...
val bytes: Array[Byte] = DynamicLinkCostAvro.serialize(cost).get

// Conversão bytes → Avro → modelo  
val deserialized: DynamicLinkCost = DynamicLinkCostAvro.deserialize(bytes).get
```

## ⚙️ Configuração

### application.conf

```conf
htc {
  kafka {
    bootstrap.servers = "localhost:9092"
    
    topic {
      environment-prefix = "dev"  # dev/staging/prod
    }
    
    topics {
      auto-create = true  # Criar tópicos automaticamente
      creation-timeout = 30
    }
    
    schema-registry {
      url = "http://localhost:8081"
      auto-register = true  # Registrar schemas automaticamente
    }
  }
}
```

### build.sbt

```scala
// Plugin Avro
addSbtPlugin("com.github.sbt" % "sbt-avro" % "3.4.4")

// Dependências
"org.apache.avro" % "avro" % avroVersion,
"io.confluent" % "kafka-avro-serializer" % confluentAvroVersion,

// Configuração Avro
Compile / avroSource := baseDirectory.value / "src" / "main" / "avro"
```

## 🚀 Tópicos Criados

| Tópico | Domínio | Uso | Partições | Retenção |
|--------|---------|-----|-----------|----------|
| `dev.htc.routing.dynamic-costs.v1` | Routing | Custos dinâmicos de links | 12 | 5min |
| `dev.htc.mobility.vehicle-updates.v1` | Mobility | Posições de veículos | 8 | 1h |
| `dev.htc.system.performance-metrics.v1` | System | Métricas de performance | 4 | 24h |
| `dev.htc.routing.incidents.v1` | Routing | Incidentes de trânsito | 6 | 1h |

## 🔧 Operações DevOps

### Inicialização Completa

```bash
# 1. Subir infraestrutura
docker-compose up kafka schema-registry

# 2. Aguardar Kafka estar pronto
./scripts/kafka-topics-init.sh init

# 3. Verificar tópicos criados
./scripts/kafka-topics-init.sh list

# 4. Iniciar simulação
./build-and-run.sh
```

### Monitoramento

```bash
# Kafka UI: http://localhost:8080
# Schema Registry: http://localhost:8081

# Ver tópicos via CLI
docker exec htc-kafka kafka-topics.sh --bootstrap-server localhost:9092 --list

# Monitor mensagens em tempo real
docker exec htc-kafka kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic dev.htc.routing.dynamic-costs.v1 \
  --from-beginning
```

### Troubleshooting

```bash
# Verificar conectividade Kafka
telnet localhost 9092

# Logs do Kafka
docker logs htc-kafka

# Recriar tópicos
./scripts/kafka-topics-init.sh clean
./scripts/kafka-topics-init.sh init
```

## 📊 Benefícios da Implementação

### Performance
- **Serialização Avro**: 40-60% mais compacta que JSON
- **Schema evolution**: compatibilidade backward/forward
- **Batch processing**: múltiplas mensagens por operação

### Operacional
- **Auto-discovery**: tópicos criados automaticamente
- **Environment isolation**: dev/prod separados
- **Monitoring**: Kafka UI integrado
- **Versioning**: schemas versionados para evolução

### Desenvolvimento
- **Type safety**: classes Scala geradas do Avro
- **IDE support**: auto-complete e validação
- **Testing**: round-trip validation automática

## 🎯 Próximos Passos

1. **Schema Registry**: Implementar versionamento automático
2. **Monitoring**: Métricas customizadas via Prometheus
3. **Compression**: Otimizar compressão por tipo de tópico
4. **Retention**: Políticas diferenciadas por ambiente
5. **Security**: SASL/SSL para produção