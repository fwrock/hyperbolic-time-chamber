package org.interscity.htc
package system.broker.kafka.publisher

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.kafka.scaladsl.Producer
import org.apache.pekko.kafka.ProducerSettings
import org.apache.pekko.stream.scaladsl.Source
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{StringSerializer, ByteArraySerializer}
import scala.concurrent.ExecutionContext

import scala.concurrent.Future

class KafkaPublisher(
                      implicit system: ActorSystem
                    ) {

  private val producerSettings = ProducerSettings(system, new StringSerializer, new ByteArraySerializer)
    .withBootstrapServers("localhost:9092")

  def publish(topic: String, key: String, message: Array[Byte])(implicit ec: ExecutionContext): Future[Unit] = {
    Source.single(new ProducerRecord[String, Array[Byte]](topic, key, message))
      .runWith(Producer.plainSink(producerSettings))
      .map(_ => ())
  }
}
