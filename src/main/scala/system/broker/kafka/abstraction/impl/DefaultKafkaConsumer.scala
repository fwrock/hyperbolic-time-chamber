package org.interscity.htc
package system.broker.kafka.abstraction.impl

import system.broker.kafka.abstraction._

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.kafka.scaladsl.Consumer
import org.apache.pekko.kafka.{ConsumerSettings, Subscriptions}
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecord}
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, StringDeserializer}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.reflect.ClassTag
import scala.util.{Success, Failure}
import com.typesafe.config.ConfigFactory

/**
 * Default implementation of KafkaConsumerAbstraction using Pekko Streams
 */
class DefaultKafkaConsumer[T: ClassTag](
  topic: String,
  groupId: String,
  serializer: KafkaSerializer[T]
)(implicit 
  system: ActorSystem,
  ec: ExecutionContext
) extends KafkaConsumerAbstraction[T] {
  
  private val config = ConfigFactory.load().getConfig("htc.brokers.kafka")
  private val consumerSettings = ConsumerSettings(
    system,
    new StringDeserializer,
    new ByteArrayDeserializer
  )
    .withBootstrapServers(config.getString("bootstrap-servers"))
    .withGroupId(s"$groupId-${config.getString("consumer.group-id-suffix")}")
    .withProperty(
      ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
      config.getString("consumer.auto-offset-reset")
    )
  
  @volatile private var consumerControl: Option[Consumer.Control] = None
  
  override def startConsuming(
    processor: (T, MessageMetadata) => Future[KafkaResult[Unit]]
  ): Future[Unit] = {
    
    val control = Consumer
      .plainSource(consumerSettings, Subscriptions.topics(topic))
      .mapAsync(1) { record =>
        processRecord(record, processor)
      }
      .toMat(Sink.ignore)(Consumer.DrainingControl.apply)
      .run()
    
    consumerControl = Some(control)
    Future.successful(())
  }
  
  override def stopConsuming(): Future[Unit] = {
    consumerControl match {
      case Some(control) =>
        control.shutdown()
      case None =>
        Future.successful(())
    }
  }
  
  override def startConsumingEnveloped(
    processor: (KafkaMessageEnvelope[T], MessageMetadata) => Future[KafkaResult[Unit]]
  ): Future[Unit] = {
    
    val envelopeSerializer = KafkaSerializerFactory.envelope[T]
    
    val control = Consumer
      .plainSource(consumerSettings, Subscriptions.topics(topic))
      .mapAsync(1) { record =>
        processEnvelopedRecord(record, processor, envelopeSerializer)
      }
      .toMat(Sink.ignore)(Consumer.DrainingControl.apply)
      .run()
    
    consumerControl = Some(control)
    Future.successful(())
  }
  
  private def processRecord(
    record: ConsumerRecord[String, Array[Byte]],
    processor: (T, MessageMetadata) => Future[KafkaResult[Unit]]
  ): Future[Unit] = {
    
    val metadata = MessageMetadata(
      topic = record.topic(),
      partition = record.partition(),
      offset = record.offset(),
      timestamp = record.timestamp(),
      key = Option(record.key()),
      headers = extractHeaders(record)
    )
    
    serializer.deserialize(record.value()) match {
      case Success(message) =>
        processor(message, metadata)
          .map {
            case KafkaSuccess(_) => ()
            case KafkaFailure(error) =>
              system.log.error(error, s"Failed to process message at ${record.topic()}:${record.partition()}:${record.offset()}")
          }
          .recover { case ex =>
            system.log.error(ex, s"Processor threw exception for message at ${record.topic()}:${record.partition()}:${record.offset()}")
          }
      case Failure(error) =>
        system.log.error(error, s"Failed to deserialize message at ${record.topic()}:${record.partition()}:${record.offset()}")
        Future.successful(())
    }
  }
  
  private def processEnvelopedRecord(
    record: ConsumerRecord[String, Array[Byte]],
    processor: (KafkaMessageEnvelope[T], MessageMetadata) => Future[KafkaResult[Unit]],
    envelopeSerializer: KafkaSerializer[KafkaMessageEnvelope[T]]
  ): Future[Unit] = {
    
    val metadata = MessageMetadata(
      topic = record.topic(),
      partition = record.partition(),
      offset = record.offset(),
      timestamp = record.timestamp(),
      key = Option(record.key()),
      headers = extractHeaders(record)
    )
    
    envelopeSerializer.deserialize(record.value()) match {
      case Success(envelope) =>
        processor(envelope, metadata)
          .map {
            case KafkaSuccess(_) => ()
            case KafkaFailure(error) =>
              system.log.error(error, s"Failed to process enveloped message at ${record.topic()}:${record.partition()}:${record.offset()}")
          }
          .recover { case ex =>
            system.log.error(ex, s"Processor threw exception for enveloped message at ${record.topic()}:${record.partition()}:${record.offset()}")
          }
      case Failure(error) =>
        system.log.error(error, s"Failed to deserialize enveloped message at ${record.topic()}:${record.partition()}:${record.offset()}")
        Future.successful(())
    }
  }
  
  private def extractHeaders(record: ConsumerRecord[String, Array[Byte]]): Map[String, String] = {
    import scala.jdk.CollectionConverters._
    record.headers().asScala.map { header =>
      header.key() -> new String(header.value(), "UTF-8")
    }.toMap
  }
}