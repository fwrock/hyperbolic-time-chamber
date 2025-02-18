package org.interscity.htc
package system.broker.kafka.actor

import system.actor.BaseActorSystem

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.pekko.actor.Props
import org.interscity.htc.system.broker.kafka.publisher.KafkaPublisher
import org.interscity.htc.system.broker.kafka.subscriber.KafkaSubscriber

import scala.concurrent.{ ExecutionContext, Future }

class SampleKafka(
  kafkaPublisher: KafkaPublisher,
  kafkaSubscriber: KafkaSubscriber
)(implicit ec: ExecutionContext)
    extends BaseActorSystem {

  override def receive: Receive = {
    case msg: String =>
      log.info(s"Publishing message in kafka: $msg")
      kafkaPublisher.publish("simple-kafka-events", self.path.name, msg.getBytes)
  }

  private def processEvent(record: ConsumerRecord[String, Array[Byte]]): Future[Unit] = {
    log.info(s"Receive event from kafka: ${new String(record.value())}")
    Future.successful(())
  }

}

object SampleKafka {
  def props(kafkaPublisher: KafkaPublisher, kafkaSubscriber: KafkaSubscriber)(implicit
    ec: ExecutionContext
  ): Props =
    Props(new SampleKafka(kafkaPublisher, kafkaSubscriber))
}
