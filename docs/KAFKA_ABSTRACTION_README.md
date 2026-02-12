# HTC Kafka Integration Abstraction

## Visão Geral

Esta abstração fornece uma camada de integração flexível e type-safe para Apache Kafka no sistema Hyperbolic Time Chamber (HTC). Foi projetada para ser facilmente estendida e integrada em diferentes módulos do sistema (core, relatórios, modelos, etc.).

## Características

- **Type-safe**: Serialização/deserialização automática com Jackson
- **Flexível**: Suporte a diferentes padrões (publish-subscribe, request-response)
- **Modular**: Factory pattern para fácil criação de publishers/consumers
- **Configurável**: Configuração hierárquica via application.conf
- **Extensível**: Interfaces abstratas para implementações customizadas
- **Integração HTC**: Componentes específicos para eventos, relatórios, comandos

## Estrutura

```
system/broker/kafka/abstraction/
├── KafkaMessage.scala           # Tipos base e envelopes
├── KafkaAbstraction.scala       # Traits abstratos
├── KafkaSerializer.scala        # Serialização Jackson/String/Binary
├── KafkaAbstractionFactory.scala # Factory para criação
├── impl/
│   ├── DefaultKafkaPublisher.scala
│   ├── DefaultKafkaConsumer.scala
│   └── DefaultKafkaBroker.scala
├── integration/
│   └── HTCKafkaIntegration.scala # Integrações específicas HTC
└── examples/
    └── HTCKafkaExamples.scala    # Exemplos de uso
```

## Configuração

### application.conf

```hocon
htc {
  brokers {
    kafka {
      bootstrap-servers = "localhost:9092"
      
      topics {
        events = "htc.events"
        reports = "htc.reports" 
        commands = "htc.commands"
        # ... mais tópicos
      }
      
      integrations {
        event-streaming.enabled = true
        reporting.enabled = true
        # ... outras integrações
      }
    }
  }
}
```

### Variáveis de Ambiente

```bash
# Servidor Kafka
export HTC_KAFKA_BOOTSTRAP_SERVERS="kafka-cluster:9092"

# Habilitar integrações
export HTC_KAFKA_EVENT_STREAMING_ENABLED=true
export HTC_KAFKA_REPORTING_ENABLED=true

# Tópicos customizados
export HTC_KAFKA_EVENTS_TOPIC="prod.htc.events"
export HTC_KAFKA_REPORTS_TOPIC="prod.htc.reports"
```

## Uso Básico

### 1. Publisher Simples

```scala
import system.broker.kafka.abstraction._

implicit val system: ActorSystem = ActorSystem("htc")
implicit val ec: ExecutionContext = system.dispatcher

// Criar publisher
val publisher = KafkaAbstractionFactory.createPublisher[MyDataClass]()

// Publicar mensagem
val result = publisher.publish(
  message = MyDataClass("data"),
  config = KafkaConfig(topic = "my-topic")
)

result.foreach {
  case KafkaSuccess(_) => println("Sucesso!")
  case KafkaFailure(error) => println(s"Erro: $error")
}
```

### 2. Consumer Simples

```scala
// Criar consumer
val consumer = KafkaAbstractionFactory.createConsumer[MyDataClass](
  topic = "my-topic",
  groupId = "my-group"
)

// Começar a consumir
consumer.startConsuming { (message, metadata) =>
  println(s"Recebido: $message")
  Future.successful(KafkaSuccess(()))
}
```

### 3. Request-Response Pattern

```scala
// Criar broker bidirecional
val broker = KafkaAbstractionFactory.Brokers.forRequestResponse[MyRequest, MyResponse](
  requestTopic = "requests",
  responseTopic = "responses",
  groupId = "request-processor"
)

// Enviar request e aguardar response
val response = broker.requestResponse(
  message = MyRequest("query"),
  responseConfig = KafkaConfig(topic = "responses"),
  publishConfig = KafkaConfig(topic = "requests"),
  timeout = 30000
)

response.foreach {
  case KafkaSuccess(resp) => println(s"Response: $resp")
  case KafkaFailure(error) => println(s"Erro: $error")
}
```

## Integrações Específicas HTC

### 1. Streaming de Eventos

```scala
import system.broker.kafka.integration.HTCKafkaIntegration.EventStreaming._

// Criar publisher de eventos
val eventPublisher = createEventPublisher()

// Publicar evento de movimento de veículo
publishEvent(
  publisher = eventPublisher,
  eventType = "vehicle-movement",
  actorId = "car-123",
  actorType = "Car",
  tick = 1000,
  eventData = movementData,
  simulationId = "sim-001"
)
```

### 2. Relatórios

```scala
import system.broker.kafka.integration.HTCKafkaIntegration.Reporting._

// Criar publisher de relatórios  
val reportPublisher = createReportPublisher()

// Publicar métricas
publishMetrics(
  publisher = reportPublisher,
  simulationId = "sim-001",
  tick = 1000,
  metrics = Map(
    "total_vehicles" -> 1500.0,
    "avg_speed" -> 45.2,
    "congestion_level" -> 0.3
  )
)
```

### 3. Comandos e Controle

```scala
import system.broker.kafka.integration.HTCKafkaIntegration.Command._

// Criar consumer de comandos
val commandConsumer = createCommandConsumer()

// Processar comandos
commandConsumer.startConsumingEnveloped { (envelope, metadata) =>
  envelope.payload match {
    case StartSimulationCommand(simId, configFile, _) =>
      println(s"Iniciando simulação: $simId")
      // Lógica para iniciar simulação
      
    case StopSimulationCommand(simId, _) =>
      println(s"Parando simulação: $simId")
      // Lógica para parar simulação
  }
  
  Future.successful(KafkaSuccess(()))
}
```

## Exemplo de Actor Integrado

```scala
class ReportManagerWithKafka extends Actor with ActorLogging {
  import system.broker.kafka.integration.HTCKafkaIntegration.Reporting._
  
  implicit val ec: ExecutionContext = context.dispatcher
  
  // Criar publisher ao inicializar
  val reportPublisher = createReportPublisher()
  
  override def receive: Receive = {
    case metrics: SimulationMetrics =>
      // Processar métricas localmente
      processMetricsLocally(metrics)
      
      // Publicar no Kafka para monitoramento externo
      publishMetrics(
        reportPublisher,
        simulationId = metrics.simulationId,
        tick = metrics.tick,
        metrics = metrics.data
      ).foreach {
        case KafkaSuccess(_) => 
          log.info("Métricas publicadas no Kafka")
        case KafkaFailure(error) => 
          log.error(error, "Falha ao publicar métricas")
      }
  }
}
```

## Casos de Uso

### 1. **Monitoramento em Tempo Real**
- Stream de eventos de veículos para dashboards externos
- Métricas de performance para sistemas de monitoring
- Alertas de congestionamento ou incidentes

### 2. **Integração com Sistemas Externos**
- Receber comandos de sistemas de gestão de tráfego
- Enviar dados para sistemas de tomada de decisão
- Integração com APIs de terceiros

### 3. **Análise e Data Lakes**
- Enviar dados brutos para sistemas de Big Data
- Feed para modelos de machine learning
- Armazenamento de dados históricos

### 4. **Simulações Distribuídas**
- Sincronização de estado entre nós
- Coordenação de simulações multi-região
- Balanceamento de carga dinâmico

### 5. **Request-Response Services**
- Cálculo de rotas dinâmicas
- Consultas de otimização de tráfego
- Serviços de predição

## Extensão para Novos Módulos

### 1. Criar Mensagens Específicas

```scala
case class MyModuleMessage(
  moduleId: String,
  data: MyData,
  timestamp: Instant
) extends KafkaMessage {
  override def messageId: String = s"${moduleId}_${timestamp.toEpochMilli}"
  override def source: String = "my-module"
  override def messageType: String = "my-data"
}
```

### 2. Criar Factory Methods

```scala
object MyModuleKafkaIntegration {
  def createPublisher()(implicit system: ActorSystem, ec: ExecutionContext) = {
    KafkaAbstractionFactory.createPublisher[MyModuleMessage]()
  }
  
  def createConsumer(topic: String, groupId: String)(implicit system: ActorSystem, ec: ExecutionContext) = {
    KafkaAbstractionFactory.createConsumer[MyModuleMessage](topic, groupId)
  }
}
```

### 3. Integrar no Actor

```scala
class MyModuleActor extends Actor {
  val publisher = MyModuleKafkaIntegration.createPublisher()
  
  override def receive: Receive = {
    case data: MyData =>
      val message = MyModuleMessage(self.path.name, data, Instant.now())
      publisher.publish(message, KafkaConfig(topic = "my-module-topic"))
  }
}
```

## Configuração Docker

Para usar com Docker, adicione o Kafka ao docker-compose.yml:

```yaml
services:
  kafka:
    image: confluentinc/cp-kafka:latest
    environment:
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    ports:
      - "9092:9092"
      
  zookeeper:
    image: confluentinc/cp-zookeeper:latest
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
    ports:
      - "2181:2181"

  node1:
    # ... configuração existente
    environment:
      # ... outras variáveis
      HTC_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      HTC_KAFKA_EVENT_STREAMING_ENABLED: true
      HTC_KAFKA_REPORTING_ENABLED: true
    depends_on:
      - kafka
      - zookeeper
```

## Performance e Boas Práticas

1. **Batching**: Use `publishBatch` para múltiplas mensagens
2. **Serialização**: Jackson é otimizado, mas considere Protobuf para alta performance
3. **Particionamento**: Use keys consistentes para garantir ordem
4. **Configuração**: Ajuste batch-size e linger-ms conforme workload
5. **Monitoring**: Monitore lag de consumers e throughput
6. **Error Handling**: Implemente retry logic e dead letter queues

## Troubleshooting

- **Serialização**: Verificar ClassTag e estrutura das mensagens
- **Conexão**: Validar bootstrap-servers e conectividade
- **Permissions**: Verificar ACLs do Kafka se configuradas
- **Memory**: Ajustar batch-size se OutOfMemory
- **Lag**: Monitorar consumer lag e ajustar max-poll-records