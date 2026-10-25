package org.interscity.htc
package core.actor.manager.report

import avro.simulation.{ SimulationReportEvent, SimulationReportEventAvro }
import core.entity.event.control.report.ReportEvent
import core.kafka.KafkaTopicNaming
import system.broker.kafka.publisher.KafkaPublisher

import org.apache.pekko.actor.ActorRef

import java.time.LocalDateTime
import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

/** Reporter actor that streams simulation metric events to Redpanda/Kafka.
  *
  * Each actor instance maintains a local buffer and flushes in batch via the KafkaPublisher.
  * Running fire-and-forget on the io-dispatcher means Kafka back-pressure never blocks the
  * simulation actor pool.
  *
  * Legacy reporting is best effort. Accounted commitment retains write failures and only
  * acknowledges a drain after the broker has acknowledged every batch.
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

  private var strictCommit = false
  private var pendingWrites: Future[Unit] = Future.successful(())
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

  override protected def onCommittedReport(event: ReportEvent): Unit = {
    strictCommit = true
    onReport(event)
  }

  override protected def flushCommittedReports(): Future[Unit] = {
    strictCommit = true
    flush()
    pendingWrites
  }

  override def postStop(): Unit = {
    implicit val ec: ExecutionContext = context.system.dispatchers.lookup("pekko.actor.io-dispatcher")
    flush().transformWith(_ => publisher.close()).failed.foreach(err => logWarn(s"Kafka close failed: ${err.getMessage}"))
    super.postStop()
  }

  private def flush(): Future[Unit] = {
    if (buffer.isEmpty) return pendingWrites
    val snapshot = buffer.toList
    buffer.clear()
    val strict = strictCommit
    implicit val ec: ExecutionContext = context.system.dispatchers.lookup("pekko.actor.io-dispatcher")
    val batch = () => Future.sequence(snapshot.map { event =>
      SimulationReportEventAvro.serialize(event) match {
        case scala.util.Success(bytes) => publisher.publish(topicFor(event.label.getOrElse("")), event.entityId, bytes)
        case scala.util.Failure(error) => Future.failed(error)
      }
    }).map(_ => ())
    val write = pendingWrites.flatMap(_ => batch())
    pendingWrites = if (strict) write else write.recover { case err => logWarn(s"Kafka publish failed: ${err.getMessage}") }
    pendingWrites
  }

  private def topicFor(label: String): String = label match {
    case l if l != null && l.contains("link")    => KafkaTopicNaming.Simulation.LinkStates
    case l if l != null && l.contains("vehicle") => KafkaTopicNaming.Simulation.VehicleStates
    case l if l != null && l.contains("person")  => KafkaTopicNaming.Simulation.PersonStates
    case _                                        => KafkaTopicNaming.Simulation.Metrics
  }
}
