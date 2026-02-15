package org.interscity.htc
package system.broker.kafka.examples

import system.broker.kafka.abstraction._
import system.broker.kafka.integration.HTCKafkaIntegration._

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant

/**
 * Exemplos práticos de como CONSUMIR mensagens usando a abstração Kafka
 */
object KafkaConsumerExamples {

  // Dados de exemplo
  case class VehiclePosition(vehicleId: String, lat: Double, lng: Double, speed: Double, timestamp: Instant)
  case class TrafficReport(linkId: String, congestionLevel: Double, avgSpeed: Double, vehicleCount: Int)
  case class SimulationCommand(action: String, simulationId: String, parameters: Map[String, String])

  /**
   * EXEMPLO 1: Consumer simples - processar mensagens uma por vez
   */
  def exemploConsumerSimples()(implicit system: ActorSystem, ec: ExecutionContext): Unit = {
    
    // Criar consumer para posições de veículos
    val vehicleConsumer = KafkaAbstractionFactory.createConsumer[VehiclePosition](
      topic = "vehicle-positions", 
      groupId = "position-processors"
    )
    
    // Iniciar consumo
    vehicleConsumer.startConsuming { (position, metadata) =>
      println(s"🚗 Veículo ${position.vehicleId} em (${position.lat}, ${position.lng}) - Velocidade: ${position.speed} km/h")
      println(s"📊 Metadata: Topic=${metadata.topic}, Partition=${metadata.partition}, Offset=${metadata.offset}")
      
      // Processar a posição (salvar no banco, atualizar mapa, etc.)
      processVehiclePosition(position)
      
      // Retornar sucesso
      Future.successful(KafkaSuccess(()))
    }
  }

  /**
   * EXEMPLO 2: Consumer com envelope - metadados extras
   */
  def exemploConsumerComEnvelope()(implicit system: ActorSystem, ec: ExecutionContext): Unit = {
    
    val trafficConsumer = KafkaAbstractionFactory.createConsumer[TrafficReport](
      topic = "traffic-reports",
      groupId = "traffic-analyzers"
    )
    
    // Consumir mensagens com envelope (contém metadados extras)
    trafficConsumer.startConsumingEnveloped { (envelope, metadata) =>
      val report = envelope.payload
      
      println(s"📈 Relatório de tráfego:")
      println(s"   Link: ${report.linkId}")
      println(s"   Congestionamento: ${report.congestionLevel * 100}%")
      println(s"   Velocidade média: ${report.avgSpeed} km/h")
      println(s"   Veículos: ${report.vehicleCount}")
      
      println(s"📋 Envelope info:")
      println(s"   Origem: ${envelope.source}")
      println(s"   Tipo: ${envelope.messageType}")
      println(s"   Timestamp: ${envelope.timestamp}")
      envelope.correlationId.foreach(id => println(s"   Correlation ID: $id"))
      
      // Processar o relatório
      analyzeTrafficReport(report)
      
      Future.successful(KafkaSuccess(()))
    }
  }

  /**
   * EXEMPLO 3: Consumer em um Actor (padrão recomendado)
   */
  class VehicleTrackingActor extends Actor with ActorLogging {
    implicit val ec: ExecutionContext = context.dispatcher
    
    // Criar consumer no preStart
    val consumer = KafkaAbstractionFactory.createConsumer[VehiclePosition](
      topic = "vehicle-positions",
      groupId = "vehicle-tracking"
    )
    
    override def preStart(): Unit = {
      super.preStart()
      
      // Iniciar consumo quando o actor inicializa
      consumer.startConsuming { (position, metadata) =>
        // Enviar mensagem para o próprio actor processar
        self ! ProcessVehiclePosition(position, metadata)
        Future.successful(KafkaSuccess(()))
      }
      
      log.info("🚀 VehicleTrackingActor iniciado - consumindo posições de veículos")
    }
    
    override def postStop(): Unit = {
      // Parar o consumer quando o actor termina
      consumer.stopConsuming()
      super.postStop()
    }
    
    override def receive: Receive = {
      case ProcessVehiclePosition(position, metadata) =>
        // Processar a posição no contexto do actor
        log.info(s"Processando posição do veículo ${position.vehicleId}")
        
        // Atualizar estado interno, enviar para outros actors, etc.
        context.parent ! VehiclePositionUpdate(position)
        
      case GetVehicleCount =>
        sender() ! currentVehicleCount
    }
    
    private var currentVehicleCount = 0
  }
  
  // Mensagens do actor
  case class ProcessVehiclePosition(position: VehiclePosition, metadata: MessageMetadata)
  case class VehiclePositionUpdate(position: VehiclePosition)
  case object GetVehicleCount

  /**
   * EXEMPLO 4: Consumer batch com error handling
   */
  def exemploConsumerComErrorHandling()(implicit system: ActorSystem, ec: ExecutionContext): Unit = {
    
    val commandConsumer = KafkaAbstractionFactory.createConsumer[SimulationCommand](
      topic = "simulation-commands",
      groupId = "command-processors"
    )
    
    commandConsumer.startConsuming { (command, metadata) =>
      
      command.action match {
        case "START" =>
          log.info(s"▶️  Iniciando simulação ${command.simulationId}")
          startSimulation(command.simulationId, command.parameters)
          
        case "STOP" =>
          log.info(s"⏹️  Parando simulação ${command.simulationId}")
          stopSimulation(command.simulationId)
          
        case "PAUSE" =>
          log.info(s"⏸️  Pausando simulação ${command.simulationId}")
          pauseSimulation(command.simulationId)
          
        case unknown =>
          log.error(s"❌ Comando desconhecido: $unknown")
          return Future.successful(KafkaFailure(new IllegalArgumentException(s"Comando inválido: $unknown")))
      }
      
      Future.successful(KafkaSuccess(()))
      
    }.recover { case error =>
      log.error(error, "💥 Erro no consumer de comandos")
    }
  }

  /**
   * EXEMPLO 5: Consumer para integração HTC específica
   */
  def exemploConsumerEventosHTC()(implicit system: ActorSystem, ec: ExecutionContext): Unit = {
    
    // Consumer especializado para eventos HTC
    val eventConsumer = EventStreaming.createEventConsumer(
      topic = "htc.events",
      groupId = "htc-event-processors"
    )
    
    eventConsumer.startConsuming { (htcEvent, metadata) =>
      
      htcEvent.eventType match {
        case "vehicle-movement" =>
          println(s"🚗 Movimento: Actor ${htcEvent.actorId} no tick ${htcEvent.tick}")
          
        case "link-congestion" =>
          println(s"🚧 Congestionamento: Link ${htcEvent.actorId} no tick ${htcEvent.tick}")
          
        case "simulation-start" =>
          println(s"🏁 Simulação iniciada: ${htcEvent.simulationId}")
          
        case "simulation-end" =>
          println(s"🏁 Simulação finalizada: ${htcEvent.simulationId}")
          
        case _ =>
          println(s"📨 Evento: ${htcEvent.eventType} de ${htcEvent.actorId}")
      }
      
      Future.successful(KafkaSuccess(()))
    }
  }

  /**
   * EXEMPLO 6: Multiple consumers (fan-out pattern)
   */
  def exemploMultipleConsumers()(implicit system: ActorSystem, ec: ExecutionContext): Unit = {
    
    val topic = "vehicle-events"
    
    // Consumer 1: Para análise de tráfego
    val trafficAnalysisConsumer = KafkaAbstractionFactory.createConsumer[VehiclePosition](
      topic = topic,
      groupId = "traffic-analysis"  // Grupo diferente = recebe todas as mensagens
    )
    
    trafficAnalysisConsumer.startConsuming { (position, metadata) =>
      println(s"🔍 [Análise] Analisando veículo ${position.vehicleId}")
      analyzeTrafficPattern(position)
      Future.successful(KafkaSuccess(()))
    }
    
    // Consumer 2: Para alertas de velocidade
    val speedAlertConsumer = KafkaAbstractionFactory.createConsumer[VehiclePosition](
      topic = topic,
      groupId = "speed-alerts"  // Grupo diferente = recebe todas as mensagens
    )
    
    speedAlertConsumer.startConsuming { (position, metadata) =>
      if (position.speed > 80.0) {
        println(s"⚠️  [Alerta] Velocidade alta: ${position.vehicleId} a ${position.speed} km/h")
        sendSpeedAlert(position)
      }
      Future.successful(KafkaSuccess(()))
    }
    
    // Consumer 3: Para backup/armazenamento
    val storageConsumer = KafkaAbstractionFactory.createConsumer[VehiclePosition](
      topic = topic,
      groupId = "data-storage"  // Grupo diferente = recebe todas as mensagens
    )
    
    storageConsumer.startConsuming { (position, metadata) =>
      println(s"💾 [Storage] Salvando posição do veículo ${position.vehicleId}")
      saveToDatabase(position)
      Future.successful(KafkaSuccess(()))
    }
  }

  // Helper methods (simuladas)
  private def processVehiclePosition(position: VehiclePosition): Future[Unit] = Future.successful(())
  private def analyzeTrafficReport(report: TrafficReport): Future[Unit] = Future.successful(())
  private def startSimulation(simId: String, params: Map[String, String]): Future[Unit] = Future.successful(())
  private def stopSimulation(simId: String): Future[Unit] = Future.successful(())
  private def pauseSimulation(simId: String): Future[Unit] = Future.successful(())
  private def analyzeTrafficPattern(position: VehiclePosition): Future[Unit] = Future.successful(())
  private def sendSpeedAlert(position: VehiclePosition): Future[Unit] = Future.successful(())
  private def saveToDatabase(position: VehiclePosition): Future[Unit] = Future.successful(())
  
  private def log = org.slf4j.LoggerFactory.getLogger(getClass)
}

/**
 * Como executar os exemplos
 */
object RunConsumerExamples extends App {
  
  implicit val system: ActorSystem = ActorSystem("kafka-consumer-examples")
  implicit val ec: ExecutionContext = system.dispatcher
  
  println("🚀 Iniciando exemplos de consumers Kafka...")
  
  // Executar os exemplos
  KafkaConsumerExamples.exemploConsumerSimples()
  KafkaConsumerExamples.exemploConsumerComEnvelope()
  KafkaConsumerExamples.exemploConsumerComErrorHandling()
  KafkaConsumerExamples.exemploConsumerEventosHTC()
  KafkaConsumerExamples.exemploMultipleConsumers()
  
  // Criar o actor de tracking
  val vehicleTracker = system.actorOf(Props[KafkaConsumerExamples.VehicleTrackingActor], "vehicle-tracker")
  
  println("✅ Consumers iniciados! Aguardando mensagens...")
  
  // Manter o sistema rodando
  scala.sys.addShutdownHook {
    println("🛑 Finalizando consumers...")
    system.terminate()
  }
}