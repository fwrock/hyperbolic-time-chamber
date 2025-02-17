package org.interscity.htc
package system.broker.kafka.subscriber

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.kafka.ConsumerSettings
import org.apache.pekko.kafka.scaladsl.Consumer
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.{StringDeserializer, ByteArrayDeserializer}

import scala.concurrent.Future

class KafkaSubscriber(topic: String, groupId: String)(implicit system: ActorSystem) {
  private val consumerSettings = ConsumerSettings(system, new StringDeserializer, new ByteArrayDeserializer)
    .withBootstrapServers("localhost:9092")
    .withGroupId(groupId)
    .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

  def startProcessing(processMessage: ConsumerRecord[String, Array[Byte]] => Future[Unit]): Unit = {
    Consumer
      .plainSource(consumerSettings, org.apache.pekko.kafka.Subscriptions.topics(topic))
      .mapAsync(1)(processMessage)
      .runWith(Sink.ignore)
  }
}
