package org.interscity.htc
package system.broker.kafka.examples

import system.broker.kafka.abstraction._
import system.broker.kafka.integration.HTCKafkaIntegration._

import core.entity.event.data.BaseEventData
import model.mobility.entity.event.data._

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import scala.concurrent.{ExecutionContext, Future}

/**
 * Example implementations showing how different HTC modules can integrate with Kafka
 */

/**
 * Example 1: Enhanced Report Manager that publishes to Kafka
 */
class KafkaReportManager(
  reportPublisher: KafkaPublisherAbstraction[Reporting.HTCReportMessage]
)(implicit ec: ExecutionContext) extends Actor with ActorLogging {
  
  import Reporting._
  
  override def receive: Receive = {
    case simulationMetrics: Map[String, Double] =>
      // Publish simulation metrics to Kafka for real-time monitoring
      publishMetrics(
        reportPublisher,
        simulationId = "current-simulation",
        tick = System.currentTimeMillis(),
        metrics = simulationMetrics,
        nodeId = context.system.settings.config.getString("clustering.cluster.name")
      ).foreach {
        case KafkaSuccess(_) => 
          log.info("Successfully published metrics to Kafka")
        case KafkaFailure(error) => 
          log.error(error, "Failed to publish metrics to Kafka")
      }
      
    case customReport: CustomReportData =>
      // Publish custom report data
      val reportMessage = HTCReportMessage(
        reportType = "custom-report",
        simulationId = "current-simulation",
        tick = customReport.tick,
        data = Map(
          "reportType" -> customReport.reportType,
          "data" -> customReport.data
        ),
        metrics = customReport.metrics
      )
      
      reportPublisher.publish(reportMessage, KafkaConfig(topic = "htc.custom-reports"))
        .foreach {
          case KafkaSuccess(_) => 
            log.info(s"Published custom report: ${customReport.reportType}")
          case KafkaFailure(error) => 
            log.error(error, s"Failed to publish custom report: ${customReport.reportType}")
        }
  }
}

/**
 * Example 2: Real-time Event Streamer for monitoring vehicle movements
 */
class VehicleEventStreamer(
  eventPublisher: KafkaPublisherAbstraction[EventStreaming.HTCEventMessage]
)(implicit ec: ExecutionContext) extends Actor with ActorLogging {
  
  import EventStreaming._
  
  override def receive: Receive = {
    case vehicleEvent: VehicleMovementEvent =>
      // Stream vehicle movements for real-time monitoring
      publishEvent(
        eventPublisher,
        eventType = "vehicle-movement",
        actorId = vehicleEvent.actorId,
        actorType = "Car",
        tick = vehicleEvent.tick,
        eventData = vehicleEvent.data,
        simulationId = "current-simulation"
      ).foreach {
        case KafkaSuccess(_) => 
          log.debug(s"Streamed vehicle movement: ${vehicleEvent.actorId}")
        case KafkaFailure(error) => 
          log.error(error, s"Failed to stream vehicle event: ${vehicleEvent.actorId}")
      }
      
    case linkEvent: LinkStateUpdateEvent =>
      // Stream link state changes for traffic analysis
      publishEvent(
        eventPublisher,
        eventType = "link-state-update",
        actorId = linkEvent.linkId,
        actorType = "Link",
        tick = linkEvent.tick,
        eventData = linkEvent.data,
        simulationId = "current-simulation"
      ).foreach {
        case KafkaSuccess(_) => 
          log.debug(s"Streamed link update: ${linkEvent.linkId}")
        case KafkaFailure(error) => 
          log.error(error, s"Failed to stream link event: ${linkEvent.linkId}")
      }
  }
}

/**
 * Example 3: External System Integration for traffic light control
 */
class ExternalTrafficControlIntegration(
  commandConsumer: KafkaConsumerAbstraction[Command.HTCCommand],
  statusPublisher: KafkaPublisherAbstraction[String]
)(implicit ec: ExecutionContext) extends Actor with ActorLogging {
  
  import Command._
  
  override def preStart(): Unit = {
    super.preStart()
    
    // Start consuming commands from external systems
    commandConsumer.startConsumingEnveloped { (envelope, metadata) =>
      envelope.payload match {
        case cmd: StartSimulationCommand =>
          self ! cmd
        case cmd: StopSimulationCommand =>
          self ! cmd
        case cmd: PauseSimulationCommand =>
          self ! cmd
        case _ =>
          log.warning(s"Unknown command type: ${envelope.messageType}")
      }
      Future.successful(KafkaSuccess(()))
    }
  }
  
  override def receive: Receive = {
    case StartSimulationCommand(simulationId, configFile, targetNodeId) =>
      log.info(s"Received start simulation command: $simulationId")
      // Start simulation logic here...
      publishStatus(s"Simulation $simulationId started")
      
    case StopSimulationCommand(simulationId, targetNodeId) =>
      log.info(s"Received stop simulation command: $simulationId")
      // Stop simulation logic here...
      publishStatus(s"Simulation $simulationId stopped")
      
    case PauseSimulationCommand(simulationId, targetNodeId) =>
      log.info(s"Received pause simulation command: $simulationId")
      // Pause simulation logic here...
      publishStatus(s"Simulation $simulationId paused")
  }
  
  private def publishStatus(status: String): Unit = {
    statusPublisher.publish(status, KafkaConfig(topic = "htc.status"))
      .foreach {
        case KafkaSuccess(_) => 
          log.info(s"Published status: $status")
        case KafkaFailure(error) => 
          log.error(error, s"Failed to publish status: $status")
      }
  }
}

/**
 * Example 4: Request-Response pattern for dynamic route calculation
 */
class DynamicRouteService(
  routeBroker: KafkaBrokerAbstraction[RouteRequest, RouteResponse]
)(implicit ec: ExecutionContext) extends Actor with ActorLogging {
  
  override def preStart(): Unit = {
    super.preStart()
    
    // Start consuming route requests
    routeBroker.startConsumingEnveloped { (envelope, metadata) =>
      val request = envelope.payload
      
      // Calculate route (simplified example)
      val route = calculateRoute(request.origin, request.destination, request.preferences)
      
      // Send response
      val response = RouteResponse(
        requestId = request.requestId,
        route = route,
        estimatedTime = route.length * 60, // simplified calculation
        status = "success"
      )
      
      routeBroker.publishEnveloped(
        payload = response,
        source = "route-service",
        messageType = "route-response",
        config = KafkaConfig(topic = "htc.route-responses"),
        correlationId = envelope.correlationId,
        replyTo = envelope.replyTo
      ).map(_ => KafkaSuccess(()))
    }
  }
  
  override def receive: Receive = Actor.emptyBehavior
  
  private def calculateRoute(origin: String, destination: String, preferences: Map[String, String]): List[String] = {
    // Simplified route calculation
    List(origin, "intermediate", destination)
  }
}

// Supporting case classes for examples
case class CustomReportData(reportType: String, tick: Long, data: Map[String, Any], metrics: Map[String, Double])
case class VehicleMovementEvent(actorId: String, tick: Long, data: BaseEventData)
case class LinkStateUpdateEvent(linkId: String, tick: Long, data: BaseEventData)
case class RouteRequest(requestId: String, origin: String, destination: String, preferences: Map[String, String]) extends KafkaMessage {
  override def messageId: String = requestId
  override def timestamp: java.time.Instant = java.time.Instant.now()
  override def source: String = "client"
  override def messageType: String = "route-request"
}
case class RouteResponse(requestId: String, route: List[String], estimatedTime: Long, status: String) extends KafkaMessage {
  override def messageId: String = requestId
  override def timestamp: java.time.Instant = java.time.Instant.now()
  override def source: String = "route-service"
  override def messageType: String = "route-response"
}

/**
 * Factory object for creating example actors with Kafka integration
 */
object KafkaExampleActors {
  
  def createReportManager()(implicit system: ActorSystem, ec: ExecutionContext): ActorRef = {
    val reportPublisher = KafkaAbstractionFactory.Publishers.forReports[Reporting.HTCReportMessage]()
    system.actorOf(Props(new KafkaReportManager(reportPublisher)), "kafka-report-manager")
  }
  
  def createEventStreamer()(implicit system: ActorSystem, ec: ExecutionContext): ActorRef = {
    val eventPublisher = KafkaAbstractionFactory.Publishers.forEvents()
      .asInstanceOf[KafkaPublisherAbstraction[EventStreaming.HTCEventMessage]]
    system.actorOf(Props(new VehicleEventStreamer(eventPublisher)), "kafka-event-streamer")
  }
  
  def createExternalIntegration()(implicit system: ActorSystem, ec: ExecutionContext): ActorRef = {
    val commandConsumer = KafkaAbstractionFactory.Consumers.forEvents("htc.commands", "external-integration")
      .asInstanceOf[KafkaConsumerAbstraction[Command.HTCCommand]]
    val statusPublisher = KafkaAbstractionFactory.Publishers.forStrings()
    system.actorOf(Props(new ExternalTrafficControlIntegration(commandConsumer, statusPublisher)), "external-integration")
  }
  
  def createRouteService()(implicit system: ActorSystem, ec: ExecutionContext): ActorRef = {
    val routeBroker = KafkaAbstractionFactory.Brokers.forRequestResponse[RouteRequest, RouteResponse](
      "htc.route-requests",
      "htc.route-responses", 
      "route-service"
    )
    system.actorOf(Props(new DynamicRouteService(routeBroker)), "dynamic-route-service")
  }
}