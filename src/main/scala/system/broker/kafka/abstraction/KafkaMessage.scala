package org.interscity.htc
package system.broker.kafka.abstraction

import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant

/** Base trait for all Kafka messages in the HTC system Provides common metadata and serialization
  * support
  */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
trait KafkaMessage {
  def messageId: String
  def timestamp: Instant
  def source: String
  def messageType: String
}

/** Envelope for wrapping any data in a Kafka message
  */
case class KafkaMessageEnvelope[T](
  messageId: String,
  timestamp: Instant,
  source: String,
  messageType: String,
  payload: T,
  headers: Map[String, String] = Map.empty,
  correlationId: Option[String] = None,
  replyTo: Option[String] = None
) extends KafkaMessage

/** Result of a Kafka operation
  */
sealed trait KafkaResult[+T]
case class KafkaSuccess[T](value: T) extends KafkaResult[T]
case class KafkaFailure(error: Throwable) extends KafkaResult[Nothing]

/** Configuration for Kafka operations
  */
case class KafkaConfig(
  topic: String,
  key: Option[String] = None,
  partition: Option[Int] = None,
  headers: Map[String, String] = Map.empty,
  timeout: Option[Long] = None
)

/** Metadata about a processed message
  */
case class MessageMetadata(
  topic: String,
  partition: Int,
  offset: Long,
  timestamp: Long,
  key: Option[String] = None,
  headers: Map[String, String] = Map.empty
)
