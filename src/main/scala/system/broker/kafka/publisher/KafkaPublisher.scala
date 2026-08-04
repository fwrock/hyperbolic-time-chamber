package org.interscity.htc
package system.broker.kafka.publisher

import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.kafka.scaladsl.SendProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.interscity.htc.system.broker.kafka.configuration.publisher.PublisherConfiguration.producerConfig

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** Thin wrapper around a single, long-lived Kafka producer client.
  *
  * Uses `SendProducer` (not `Source.single(...).runWith(Producer.plainSink(...))`) precisely
  * because the latter opens a brand-new underlying `KafkaProducer` — with its own TCP
  * connection and broker handshake — for every single message published. Confirmed the hard
  * way: an end-to-end run against a real Redpanda broker exhausted the broker container's file
  * descriptors ("Too many open files") within seconds, one instance's worth of `report()` calls
  * being enough to do it. `SendProducer` opens one producer for this object's lifetime and
  * reuses it for every `publish()` call.
  */
class KafkaPublisher(implicit
  system: ActorSystem
) {

  private val producerSettings = producerConfig(system)
  private val sendProducer = SendProducer(producerSettings)(system)

  def publish(topic: String, key: String, message: Array[Byte])(implicit
    ec: ExecutionContext
  ): Future[Unit] =
    sendProducer
      .send(new ProducerRecord[String, Array[Byte]](topic, key, message))
      .map(_ => ())

  def close()(implicit ec: ExecutionContext): Future[Done] = sendProducer.close()
}
