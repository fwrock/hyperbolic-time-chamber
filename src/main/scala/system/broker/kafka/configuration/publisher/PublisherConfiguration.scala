package org.interscity.htc
package system.broker.kafka.configuration.publisher

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.kafka.ProducerSettings
import org.apache.kafka.common.serialization.{ ByteArraySerializer, StringSerializer }

object PublisherConfiguration {

  // NOTE: application.conf nests this block under "htc.brokers.kafka", not a top-level
  // "brokers.kafka" — using the wrong path here previously threw ConfigException.Missing on
  // first use (i.e. the first real Kafka publish attempt), which — because
  // pekko.jvm-exit-on-fatal-error is enabled — crashed the whole ActorSystem rather than
  // merely failing this one reporter. Confirmed via an actual end-to-end run with the "kafka"
  // reporter strategy enabled, not by inspection alone.
  private val config = ConfigFactory.load().getConfig("htc.brokers.kafka")

  def producerConfig[K, V](system: ActorSystem): ProducerSettings[String, Array[Byte]] =
    ProducerSettings(
      system,
      new StringSerializer,
      new ByteArraySerializer
    ).withBootstrapServers(config.getString("bootstrap-servers"))
}
