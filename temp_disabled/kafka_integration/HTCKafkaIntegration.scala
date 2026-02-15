package org.interscity.htc
package system.broker.kafka.integration

import system.broker.kafka.abstraction._
import core.entity.event.data.BaseEventData
import core.entity.state.BaseState
import core.actor.ActorSerializable

import org.apache.pekko.actor.{ActorSystem, ActorRef}
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import java.time.Instant
import java.util.UUID

/**
 * Integration layer for HTC Core modules with Kafka
 * Provides specialized publishers and consumers for different HTC components
 */
object HTCKafkaIntegration {
  
  /**
   * Event streaming for real-time monitoring and reporting
   */
  object EventStreaming {
    
    case class HTCEventMessage(
      eventType: String,
      actorId: String,
      actorType: String,
      tick: Long,
      eventData: BaseEventData,
      simulationId: String,
      nodeId: String = "unknown"
    ) extends KafkaMessage {
      override def messageId: String = s"${actorId}_${tick}_${UUID.randomUUID().toString.take(8)}"
      override def timestamp: Instant = Instant.now()
      override def source: String = s"htc-node-$nodeId"
      override def messageType: String = eventType
    }
    
    /**
     * Create a publisher for HTC events
     */
    def createEventPublisher(
      topic: String = "htc.events"
    )(implicit system: ActorSystem, ec: ExecutionContext): KafkaPublisherAbstraction[HTCEventMessage] = {
      KafkaAbstractionFactory.createPublisher[HTCEventMessage]()
    }
    
    /**
     * Create a consumer for HTC events
     */
    def createEventConsumer(
      topic: String = "htc.events",
      groupId: String = "htc-event-processors"
    )(implicit system: ActorSystem, ec: ExecutionContext): KafkaConsumerAbstraction[HTCEventMessage] = {
      KafkaAbstractionFactory.createConsumer[HTCEventMessage](topic, groupId)
    }
    
    /**
     * Helper to publish an HTC event
     */
    def publishEvent(
      publisher: KafkaPublisherAbstraction[HTCEventMessage],
      eventType: String,
      actorId: String,
      actorType: String,
      tick: Long,
      eventData: BaseEventData,
      simulationId: String,
      nodeId: String = "unknown",
      topic: String = "htc.events"
    ): Future[KafkaResult[Unit]] = {
      
      val event = HTCEventMessage(
        eventType = eventType,
        actorId = actorId,
        actorType = actorType,
        tick = tick,
        eventData = eventData,
        simulationId = simulationId,
        nodeId = nodeId
      )
      
      publisher.publish(event, KafkaConfig(topic = topic))
    }
  }
  
  /**
   * State synchronization for distributed simulations
   */
  object StateSync {
    
    case class HTCStateMessage[T <: BaseState](
      actorId: String,
      actorType: String,
      state: T,
      tick: Long,
      simulationId: String,
      nodeId: String = "unknown"
    ) extends KafkaMessage {
      override def messageId: String = s"state_${actorId}_${tick}"
      override def timestamp: Instant = Instant.now()
      override def source: String = s"htc-node-$nodeId"
      override def messageType: String = "state-sync"
    }
    
    /**
     * Create a publisher for state synchronization
     */
    def createStatePublisher[T <: BaseState: ClassTag](
      topic: String = "htc.state-sync"
    )(implicit system: ActorSystem, ec: ExecutionContext): KafkaPublisherAbstraction[HTCStateMessage[T]] = {
      KafkaAbstractionFactory.createPublisher[HTCStateMessage[T]]()
    }
    
    /**
     * Create a consumer for state synchronization
     */
    def createStateConsumer[T <: BaseState: ClassTag](
      topic: String = "htc.state-sync",
      groupId: String = "htc-state-sync"
    )(implicit system: ActorSystem, ec: ExecutionContext): KafkaConsumerAbstraction[HTCStateMessage[T]] = {
      KafkaAbstractionFactory.createConsumer[HTCStateMessage[T]](topic, groupId)
    }
  }
  
  /**
   * Report publishing for monitoring and analytics
   */
  object Reporting {
    
    case class HTCReportMessage(
      reportType: String,
      simulationId: String,
      tick: Long,
      data: Map[String, Any],
      metrics: Map[String, Double],
      nodeId: String = "unknown"
    ) extends KafkaMessage {
      override def messageId: String = s"report_${reportType}_${tick}_${UUID.randomUUID().toString.take(8)}"
      override def timestamp: Instant = Instant.now()
      override def source: String = s"htc-node-$nodeId"
      override def messageType: String = reportType
    }
    
    /**
     * Create a publisher for reports
     */
    def createReportPublisher(
      topic: String = "htc.reports"
    )(implicit system: ActorSystem, ec: ExecutionContext): KafkaPublisherAbstraction[HTCReportMessage] = {
      KafkaAbstractionFactory.createPublisher[HTCReportMessage]()
    }
    
    /**
     * Create a consumer for reports
     */
    def createReportConsumer(
      topic: String = "htc.reports",
      groupId: String = "htc-report-processors"
    )(implicit system: ActorSystem, ec: ExecutionContext): KafkaConsumerAbstraction[HTCReportMessage] = {
      KafkaAbstractionFactory.createConsumer[HTCReportMessage](topic, groupId)
    }
    
    /**
     * Helper to publish simulation metrics
     */
    def publishMetrics(
      publisher: KafkaPublisherAbstraction[HTCReportMessage],
      simulationId: String,
      tick: Long,
      metrics: Map[String, Double],
      nodeId: String = "unknown",
      topic: String = "htc.reports"
    ): Future[KafkaResult[Unit]] = {
      
      val report = HTCReportMessage(
        reportType = "simulation-metrics",
        simulationId = simulationId,
        tick = tick,
        data = Map("node" -> nodeId),
        metrics = metrics,
        nodeId = nodeId
      )
      
      publisher.publish(report, KafkaConfig(topic = topic))
    }
  }
  
  /**
   * Command and control for distributed simulations
   */
  object Command {
    
    sealed trait HTCCommand extends KafkaMessage {
      def commandType: String
      def targetNodeId: Option[String]
      def simulationId: String
      override def messageType: String = commandType
    }
    
    case class StartSimulationCommand(
      simulationId: String,
      configFile: String,
      targetNodeId: Option[String] = None
    ) extends HTCCommand {
      override def commandType: String = "start-simulation"
      override def messageId: String = s"start_${simulationId}_${UUID.randomUUID().toString.take(8)}"
      override def timestamp: Instant = Instant.now()
      override def source: String = "htc-controller"
    }
    
    case class StopSimulationCommand(
      simulationId: String,
      targetNodeId: Option[String] = None
    ) extends HTCCommand {
      override def commandType: String = "stop-simulation"
      override def messageId: String = s"stop_${simulationId}_${UUID.randomUUID().toString.take(8)}"
      override def timestamp: Instant = Instant.now()
      override def source: String = "htc-controller"
    }
    
    case class PauseSimulationCommand(
      simulationId: String,
      targetNodeId: Option[String] = None
    ) extends HTCCommand {
      override def commandType: String = "pause-simulation"
      override def messageId: String = s"pause_${simulationId}_${UUID.randomUUID().toString.take(8)}"
      override def timestamp: Instant = Instant.now()
      override def source: String = "htc-controller"
    }
    
    /**
     * Create a command publisher
     */
    def createCommandPublisher(
      topic: String = "htc.commands"
    )(implicit system: ActorSystem, ec: ExecutionContext): KafkaPublisherAbstraction[HTCCommand] = {
      KafkaAbstractionFactory.createPublisher[HTCCommand]()
    }
    
    /**
     * Create a command consumer
     */
    def createCommandConsumer(
      topic: String = "htc.commands",
      groupId: String = "htc-command-processors"
    )(implicit system: ActorSystem, ec: ExecutionContext): KafkaConsumerAbstraction[HTCCommand] = {
      KafkaAbstractionFactory.createConsumer[HTCCommand](topic, groupId)
    }
  }
}