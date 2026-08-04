package org.interscity.htc
package core.actor.manager.report

import avro.simulation.{ SimulationReportEvent, SimulationReportEventAvro }
import core.entity.event.control.report.ReportEvent
import core.kafka.KafkaTopicNaming
import system.broker.kafka.publisher.KafkaPublisher

import org.apache.pekko.actor.ActorRef

import java.time.LocalDateTime
import scala.collection.mutable
import scala.concurrent.ExecutionContext

/** Reporter actor that streams simulation metric events to Redpanda/Kafka.
  *
  * Each actor instance maintains a local buffer and flushes in batch via the KafkaPublisher.
  * Running fire-and-forget on the io-dispatcher means Kafka back-pressure never blocks the
  * simulation actor pool.
  *
  * Fail-safe: if Kafka is unreachable during a flush, a warning is logged and the events are
  * dropped. The simulation continues unaffected.
  */
class KafkaReportData(
  override val reportManager: ActorRef,
  override val startRealTime: LocalDateTime
) extends ReportData(
      id = "kafka-report-data",
      reportManager = reportManager,
      startRealTime = startRealTime
    ) {

  private val batchSize: Int =
    try config.getInt("htc.report-manager.kafka.batch-size")
    catch { case _: Exception => 1000 }

  private val buffer = new mutable.ArrayBuffer[SimulationReportEvent](batchSize)

  private lazy val publisher = new KafkaPublisher()(context.system)

  private lazy val simulationId: String = {
    val fromSimConfig =
      try Some(core.util.SimulationUtil.loadSimulationConfig()).flatMap(_.id)
      catch { case _: Exception => None }
    val fromEnv = sys.env.get("HTC_SIMULATION_ID")
    val fromConf =
      try Some(config.getString("htc.simulation.id"))
      catch { case _: Exception => None }
    fromSimConfig.orElse(fromEnv).orElse(fromConf).getOrElse("sim")
  }

  override def onReport(event: ReportEvent): Unit = {
    val avroEvent = SimulationReportEventAvro.fromReportEvent(event, simulationId)
    buffer += avroEvent
    if (buffer.size >= batchSize) flush()
  }

  override def postStop(): Unit = {
    if (buffer.nonEmpty) flush()
    implicit val ec: ExecutionContext = context.system.dispatchers.lookup("pekko.actor.io-dispatcher")
    publisher.close().failed.foreach { err =>
      logWarn(s"Error closing Kafka producer: ${err.getMessage}")
    }
    super.postStop()
  }

  private def flush(): Unit = {
    if (buffer.isEmpty) return
    val snapshot = buffer.toList
    buffer.clear()
    implicit val ec: ExecutionContext =
      context.system.dispatchers.lookup("pekko.actor.io-dispatcher")
    snapshot.foreach { ev =>
      SimulationReportEventAvro.serialize(ev) match {
        case scala.util.Success(bytes) =>
          publisher
            .publish(topicFor(ev.label.getOrElse("")), ev.entityId, bytes)
            .failed
            .foreach { err =>
              logWarn(s"Kafka publish failed for entity ${ev.entityId}: ${err.getMessage}")
            }
        case scala.util.Failure(err) =>
          logWarn(s"Kafka serialization failed for entity ${ev.entityId}: ${err.getMessage}")
      }
    }
  }

  private def topicFor(label: String): String = label match {
    case l if l != null && l.contains("link")    => KafkaTopicNaming.Simulation.LinkStates
    case l if l != null && l.contains("vehicle") => KafkaTopicNaming.Simulation.VehicleStates
    case l if l != null && l.contains("person")  => KafkaTopicNaming.Simulation.PersonStates
    case _                                        => KafkaTopicNaming.Simulation.Metrics
  }
}
