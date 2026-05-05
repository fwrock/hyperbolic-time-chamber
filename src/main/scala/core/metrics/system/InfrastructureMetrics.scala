package org.interscity.htc
package core.metrics.system

import io.prometheus.client.Counter

/** System infrastructure metrics.
  *
  *   - htc_dead_letters_total{recipient_type, message_type} — dead letters by destination actor type
  *     and message class. recipient_type is extracted from the shard region name in the actor path
  *     (e.g. Car, Bus, Link); message_type is the simple class name of the undelivered message.
  *   - htc_kafka_messages_sent_total — Kafka messages sent counter
  */
object InfrastructureMetrics {

  val deadLetters: Counter = Counter
    .build()
    .name("htc_dead_letters_total")
    .help("Total dead letters by destination actor type and message class")
    .labelNames("recipient_type", "message_type")
    .register()

  val kafkaMessagesSent: Counter = Counter
    .build()
    .name("htc_kafka_messages_sent_total")
    .help("Total Kafka messages sent")
    .labelNames("topic")
    .register()
}
