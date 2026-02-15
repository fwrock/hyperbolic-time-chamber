package org.interscity.htc
package system.broker.kafka.abstraction.impl

import system.broker.kafka.abstraction._

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.reflect.ClassTag
import scala.util.{Success, Failure}
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration._

/**
 * Default implementation of KafkaBrokerAbstraction combining publisher and consumer
 */
class DefaultKafkaBroker[TPublish: ClassTag, TConsume: ClassTag](
  publishTopic: String,
  consumeTopic: String,
  groupId: String,
  publishSerializer: KafkaSerializer[TPublish],
  consumeSerializer: KafkaSerializer[TConsume]
)(implicit 
  system: org.apache.pekko.actor.ActorSystem,
  ec: ExecutionContext
) extends KafkaBrokerAbstraction[TPublish, TConsume] {
  
  private val publisher = new DefaultKafkaPublisher[TPublish](publishSerializer)
  private val consumer = new DefaultKafkaConsumer[TConsume](consumeTopic, groupId, consumeSerializer)
  
  // Store pending request-response operations
  private val pendingRequests = new ConcurrentHashMap[String, Promise[KafkaResult[TConsume]]]()
  
  // Publisher methods
  override def publish(message: TPublish, config: KafkaConfig): Future[KafkaResult[Unit]] = {
    publisher.publish(message, config.copy(topic = publishTopic))
  }
  
  override def publishBatch(messages: Seq[TPublish], config: KafkaConfig): Future[KafkaResult[Unit]] = {
    publisher.publishBatch(messages, config.copy(topic = publishTopic))
  }
  
  override def publishEnveloped(
    payload: TPublish,
    source: String,
    messageType: String,
    config: KafkaConfig,
    correlationId: Option[String] = None,
    replyTo: Option[String] = None,
    headers: Map[String, String] = Map.empty
  ): Future[KafkaResult[Unit]] = {
    publisher.publishEnveloped(
      payload, source, messageType, 
      config.copy(topic = publishTopic),
      correlationId, replyTo, headers
    )
  }
  
  // Consumer methods
  override def startConsuming(
    processor: (TConsume, MessageMetadata) => Future[KafkaResult[Unit]]
  ): Future[Unit] = {
    consumer.startConsuming(processor)
  }
  
  override def stopConsuming(): Future[Unit] = {
    consumer.stopConsuming()
  }
  
  override def startConsumingEnveloped(
    processor: (KafkaMessageEnvelope[TConsume], MessageMetadata) => Future[KafkaResult[Unit]]
  ): Future[Unit] = {
    
    // Enhanced processor that handles request-response pattern
    val enhancedProcessor = (envelope: KafkaMessageEnvelope[TConsume], metadata: MessageMetadata) => {
      
      // Check if this is a response to a pending request
      envelope.correlationId.foreach { correlationId =>
        Option(pendingRequests.remove(correlationId)).foreach { promise =>
          promise.success(KafkaSuccess(envelope.payload))
        }
      }
      
      // Continue with normal processing
      processor(envelope, metadata)
    }
    
    consumer.startConsumingEnveloped(enhancedProcessor)
  }
  
  // Request-response pattern
  override def requestResponse(
    message: TPublish,
    responseConfig: KafkaConfig,
    publishConfig: KafkaConfig,
    timeout: Long = 30000
  ): Future[KafkaResult[TConsume]] = {
    
    val correlationId = UUID.randomUUID().toString
    val promise = Promise[KafkaResult[TConsume]]()
    
    // Store the promise for when the response arrives
    pendingRequests.put(correlationId, promise)
    
    // Set timeout
    system.scheduler.scheduleOnce(timeout.millis) {
      Option(pendingRequests.remove(correlationId)).foreach { timeoutPromise =>
        timeoutPromise.success(KafkaFailure(new Exception(s"Request timeout after ${timeout}ms")))
      }
    }
    
    // Publish the request with correlation ID and reply-to topic
    val publishResult = publishEnveloped(
      payload = message,
      source = "request-response",
      messageType = "request",
      config = publishConfig.copy(topic = publishTopic),
      correlationId = Some(correlationId),
      replyTo = Some(responseConfig.topic)
    )
    
    publishResult.flatMap {
      case KafkaSuccess(_) => promise.future
      case KafkaFailure(error) => 
        pendingRequests.remove(correlationId)
        Future.successful(KafkaFailure(error))
    }
  }
}