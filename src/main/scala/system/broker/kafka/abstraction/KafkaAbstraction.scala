package org.interscity.htc
package system.broker.kafka.abstraction

import scala.concurrent.Future

/**
 * Abstract trait for Kafka publishers
 * Provides type-safe message publishing with serialization
 */
trait KafkaPublisherAbstraction[T] {
  
  /**
   * Publish a message to Kafka
   */
  def publish(message: T, config: KafkaConfig): Future[KafkaResult[Unit]]
  
  /**
   * Publish multiple messages in batch
   */
  def publishBatch(messages: Seq[T], config: KafkaConfig): Future[KafkaResult[Unit]]
  
  /**
   * Publish with automatic envelope wrapping
   */
  def publishEnveloped(
    payload: T, 
    source: String, 
    messageType: String,
    config: KafkaConfig,
    correlationId: Option[String] = None,
    replyTo: Option[String] = None,
    headers: Map[String, String] = Map.empty
  ): Future[KafkaResult[Unit]]
}

/**
 * Abstract trait for Kafka consumers
 * Provides type-safe message consumption with deserialization
 */
trait KafkaConsumerAbstraction[T] {
  
  /**
   * Start consuming messages from Kafka
   * The processor function handles each message and returns processing result
   */
  def startConsuming(
    processor: (T, MessageMetadata) => Future[KafkaResult[Unit]]
  ): Future[Unit]
  
  /**
   * Stop consuming messages
   */
  def stopConsuming(): Future[Unit]
  
  /**
   * Consume messages with envelope unwrapping
   */
  def startConsumingEnveloped(
    processor: (KafkaMessageEnvelope[T], MessageMetadata) => Future[KafkaResult[Unit]]
  ): Future[Unit]
}

/**
 * Combined publisher and consumer for bidirectional communication
 */
trait KafkaBrokerAbstraction[TPublish, TConsume] 
  extends KafkaPublisherAbstraction[TPublish] 
  with KafkaConsumerAbstraction[TConsume] {
    
  /**
   * Request-response pattern: publish a message and wait for response
   */
  def requestResponse(
    message: TPublish,
    responseConfig: KafkaConfig,
    publishConfig: KafkaConfig,
    timeout: Long = 30000
  ): Future[KafkaResult[TConsume]]
}