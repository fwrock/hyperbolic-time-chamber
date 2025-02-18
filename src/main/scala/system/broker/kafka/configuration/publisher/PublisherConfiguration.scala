package org.interscity.htc
package system.broker.kafka.configuration.publisher

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.kafka.ProducerSettings
import org.apache.kafka.common.serialization.{ ByteArraySerializer, StringSerializer }

object PublisherConfiguration {

  private val config = ConfigFactory.load().getConfig("brokers.kafka")

  def producerConfig[K, V](system: ActorSystem): ProducerSettings[String, Array[Byte]] =
    ProducerSettings(
      system,
      new StringSerializer,
      new ByteArraySerializer
    ).withBootstrapServers(config.getString("bootstrap-servers"))
}
