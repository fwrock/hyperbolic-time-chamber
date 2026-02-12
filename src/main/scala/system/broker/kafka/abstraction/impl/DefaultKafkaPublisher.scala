package org.interscity.htc
package system.broker.kafka.abstraction.impl

import system.broker.kafka.abstraction._

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.kafka.scaladsl.Producer
import org.apache.pekko.kafka.ProducerSettings
import org.apache.pekko.stream.scaladsl.Source
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{ByteArraySerializer, StringSerializer}

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import java.time.Instant
import java.util.UUID
import com.typesafe.config.ConfigFactory

/**
 * Default implementation of KafkaPublisherAbstraction using Pekko Streams
 */
class DefaultKafkaPublisher[T: ClassTag](
  serializer: KafkaSerializer[T]
)(implicit 
  system: ActorSystem,
  ec: ExecutionContext
) extends KafkaPublisherAbstraction[T] {
  
  private val config = ConfigFactory.load().getConfig("htc.brokers.kafka")
  private val producerSettings = ProducerSettings(
    system,
    new StringSerializer,
    new ByteArraySerializer
  ).withBootstrapServers(config.getString("bootstrap-servers"))
  
  override def publish(message: T, config: KafkaConfig): Future[KafkaResult[Unit]] = {
    try {
      val serializedData = serializer.serialize(message)
      val record = new ProducerRecord[String, Array[Byte]](
        config.topic,
        config.partition.map(Int.box).orNull,
        config.key.orNull,
        serializedData
      )
      
      // Add headers if present
      config.headers.foreach { case (key, value) =>
        record.headers().add(key, value.getBytes("UTF-8"))
      }
      
      Source
        .single(record)
        .runWith(Producer.plainSink(producerSettings))
        .map(_ => KafkaSuccess(()))
        .recover { case ex => KafkaFailure(ex) }
    } catch {
      case ex: Exception =>
        Future.successful(KafkaFailure(ex))
    }
  }
  
  override def publishBatch(messages: Seq[T], config: KafkaConfig): Future[KafkaResult[Unit]] = {
    try {
      val records = messages.map { message =>
        val serializedData = serializer.serialize(message)
        val record = new ProducerRecord[String, Array[Byte]](
          config.topic,
          config.partition.map(Int.box).orNull,
          config.key.orNull,
          serializedData
        )
        
        // Add headers if present
        config.headers.foreach { case (key, value) =>
          record.headers().add(key, value.getBytes("UTF-8"))
        }
        
        record
      }
      
      Source(records)
        .runWith(Producer.plainSink(producerSettings))
        .map(_ => KafkaSuccess(()))
        .recover { case ex => KafkaFailure(ex) }
    } catch {
      case ex: Exception =>
        Future.successful(KafkaFailure(ex))
    }
  }
  
  override def publishEnveloped(
    payload: T,
    source: String,
    messageType: String,
    config: KafkaConfig,
    correlationId: Option[String] = None,
    replyTo: Option[String] = None,
    headers: Map[String, String] = Map.empty
  ): Future[KafkaResult[Unit]] = {
    val envelope = KafkaMessageEnvelope(
      messageId = UUID.randomUUID().toString,
      timestamp = Instant.now(),
      source = source,
      messageType = messageType,
      payload = payload,
      headers = headers,
      correlationId = correlationId,
      replyTo = replyTo
    )
    
    // Use envelope serializer for this operation
    val envelopeSerializer = KafkaSerializerFactory.envelope[T]
    val envelopePublisher = new DefaultKafkaPublisher[KafkaMessageEnvelope[T]](envelopeSerializer)
    
    envelopePublisher.publish(envelope, config)
  }
}