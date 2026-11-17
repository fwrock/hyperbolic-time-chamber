package org.interscity.htc
package core.metrics

import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, EntityEnvelopeEvent, FinishEvent }
import org.interscity.htc.core.entity.event.control.report.ReportEvent
import org.interscity.htc.core.runtime.accounting.*
import org.interscity.htc.core.runtime.conservative.*
import org.interscity.htc.core.runtime.conservative.partition.PartitionCommand
import org.apache.pekko.actor.{ ActorRef, WrappedMessage }
import org.apache.pekko.remote.artery.RemoteInstrument
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import java.nio.ByteBuffer

/** Opt-in Pekko 2.0 Artery monitoring SPI. It writes no metadata and times no serialization. Counts
  * describe serialized envelopes rather than packets, logical sends or durable delivery.
  */
final class RemoteTrafficInstrument extends RemoteInstrument {
  override def identifier: Byte = 10
  override def remoteWriteMetadata(
    recipient: ActorRef,
    message: Object,
    sender: ActorRef,
    buffer: ByteBuffer
  ): Unit = ()
  override def remoteReadMetadata(
    recipient: ActorRef,
    message: Object,
    sender: ActorRef,
    buffer: ByteBuffer
  ): Unit = ()
  override def remoteMessageSent(
    recipient: ActorRef,
    message: Object,
    sender: ActorRef,
    size: Int,
    time: Long
  ): Unit = RemoteTrafficMetrics.record("sent", RemoteTrafficInstrument.categoryOf(message), size)
  override def remoteMessageReceived(
    recipient: ActorRef,
    message: Object,
    sender: ActorRef,
    size: Int,
    time: Long
  ): Unit =
    RemoteTrafficMetrics.record("received", RemoteTrafficInstrument.categoryOf(message), size)
}

object RemoteTrafficInstrument {

  /** Unwrap only bounded envelope depth; unknown infrastructure remains in one category. */
  private[metrics] def categoryOf(message: Any, depth: Int = 0): String = {
    if (depth >= 8) return "other"
    message match {
      case EntityEnvelopeEvent(_, payload) => categoryOf(payload, depth + 1)
      case wrapped: WrappedMessage         => categoryOf(wrapped.message, depth + 1)
      case delivery: DirectExecute =>
        delivery.event match {
          case _: ActorInteractionEvent => "model"
          case _                        => "scheduling"
        }
      case _: ActorInteractionEvent => "model"
      case _: DirectProcessed | _: DirectAccounted | _: RouteControl | _: ScheduleEvent |
          _: FinishEvent =>
        "scheduling"
      case _: ReportEvent | _: CommittedReportDelivery | _: CommittedReportBatchDelivery |
          _: PublishConservativeReport | _: PublishCommittedReport | _: DrainRuntimeReports |
          _: FlushRuntimeReports | _: RuntimeReportsFlushed =>
        "reporting"
      case _: ConservativeCommand | _: PartitionCommand | _: RuntimeCommand => "coordination"
      case _                                                                => "other"
    }
  }
}
