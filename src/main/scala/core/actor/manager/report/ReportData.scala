package org.interscity.htc
package core.actor.manager.report

import core.actor.BaseActor
import core.runtime.accounting.*
import core.entity.state.DefaultState

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.control.report.ReportEvent

import java.time.LocalDateTime
import scala.concurrent.Future
import org.apache.pekko.pattern.pipe

abstract class ReportData(
  val id: String = "",
  val reportManager: ActorRef = null,
  val startRealTime: LocalDateTime
) extends BaseActor[DefaultState](
      properties = Properties(
        entityId = id
      )
    ) {

  protected def onCommittedReport(event: ReportEvent): Unit = onReport(event)
  protected def flushCommittedReports(): Future[Unit] =
    Future.failed(new UnsupportedOperationException(s"${getClass.getName} has no acknowledged flush implementation"))

  override def handleEvent: Receive = {
    case CommittedReportDelivery(epoch, token, event, ackTo) =>
      try { onCommittedReport(event); ackTo ! DeliveryAccepted(epoch, token) }
      catch { case scala.util.control.NonFatal(e) => ackTo ! RuntimeFailure(s"report write failed: ${e.getMessage}") }
    case FlushRuntimeReports(round, replyTo) =>
      implicit val ec: scala.concurrent.ExecutionContext = context.dispatcher
      val flush = try flushCommittedReports() catch { case scala.util.control.NonFatal(e) => Future.failed(e) }
      flush.map(_ => ReportData.FlushDone(round, replyTo, None))
        .recover { case scala.util.control.NonFatal(e) => ReportData.FlushDone(round, replyTo, Some(s"report flush failed: ${e.getMessage}")) }
        .pipeTo(self)
    case ReportData.FlushDone(round, replyTo, error) => replyTo ! RuntimeReportsFlushed(round, error)
    case event: ReportEvent =>
      onReport(event)
    case _ =>
      logInfo("Event not handled")
  }

  protected def onReport(event: ReportEvent): Unit = {}
}

object ReportData {
  private[report] final case class FlushDone(round: Long, replyTo: ActorRef, error: Option[String])
}
