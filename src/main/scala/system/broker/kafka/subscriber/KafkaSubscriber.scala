package org.interscity.htc
package system.broker.kafka.subscriber

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.kafka.scaladsl.Consumer
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.interscity.htc.system.broker.kafka.configuration.subscriber.SubscriberConfiguration

import scala.concurrent.Future

class KafkaSubscriber(topic: String, groupId: String)(implicit system: ActorSystem) {
  private val consumerSettings = SubscriberConfiguration.consumerConfig(groupId, system)

  def startProcessing(processMessage: ConsumerRecord[_, _] => Future[_]): Unit =
    Consumer
      .plainSource(consumerSettings, org.apache.pekko.kafka.Subscriptions.topics(topic))
      .mapAsync(1)(processMessage)
      .runWith(Sink.ignore)
}
