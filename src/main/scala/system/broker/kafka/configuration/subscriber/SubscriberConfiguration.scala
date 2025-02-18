package org.interscity.htc
package system.broker.kafka.configuration.subscriber

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.kafka.ConsumerSettings
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.{ ByteArrayDeserializer, StringDeserializer }

object SubscriberConfiguration {

  private val config = ConfigFactory.load().getConfig("brokers.kafka")

  def consumerConfig(groupId: String, system: ActorSystem): ConsumerSettings[String, Array[Byte]] =
    ConsumerSettings(
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
}
