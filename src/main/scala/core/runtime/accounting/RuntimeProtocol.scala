package org.interscity.htc
package core.runtime.accounting

import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import core.entity.event.control.report.ReportEvent
import core.runtime.EventOrder
import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.Identify

sealed trait RuntimeCommand

final case class LogicalProcessId(model: String, id: Long)
final case class JoinRuntime(identity: Identify, ref: ActorRef, initialWake: Option[EventOrder], startTick: Long = 0L, incarnation: String = "",
  birth: Option[RuntimeBirthOrigin] = None) extends RuntimeCommand
final case class RuntimeJoined(epoch: String, coordinator: ActorRef, owner: ActorRef, distributed: Boolean = false) extends RuntimeCommand
final case class SetRuntimeHorizon(horizon: Long) extends RuntimeCommand
final case class SetOptimisticExecutionLimits(frontier: Long, ceiling: Long, limits: core.runtime.OptimisticExecutionLimits) extends RuntimeCommand
final case class JoinRuntimeManager(ref: ActorRef) extends RuntimeCommand
final case class RegisterOwnedProcess(identity: Identify, ref: ActorRef, initialWake: Option[EventOrder], logicalIdentity: Option[Identify] = None) extends RuntimeCommand
final case class RouteInteraction(epoch: String, target: LogicalProcessId, event: ActorInteractionEvent) extends RuntimeCommand
final case class RouteControl(epoch: String, target: ActorRef, event: AnyRef, tick: Long) extends RuntimeCommand
final case class RouteDispatch(target: LogicalProcessId, event: SpontaneousEvent) extends RuntimeCommand
final case class RuntimeDelivery(epoch: String, token: Long, event: AnyRef) extends RuntimeCommand
final case class DeliveryAccepted(epoch: String, token: Long) extends RuntimeCommand
final case class FreezeRuntime(round: Long) extends RuntimeCommand
final case class RuntimeFrozen(round: Long) extends RuntimeCommand
final case class CollectRuntimeSnapshot(round: Long) extends RuntimeCommand
final case class RuntimeRetention(checkpoints: Long = 0L, events: Long = 0L, reads: Long = 0L,
  processedIds: Long = 0L, cancellations: Long = 0L, reports: Long = 0L, speculativeEvents: Long = 0L) {
  def +(other: RuntimeRetention): RuntimeRetention = RuntimeRetention(checkpoints + other.checkpoints,
    events + other.events, reads + other.reads, processedIds + other.processedIds,
    cancellations + other.cancellations, reports + other.reports, speculativeEvents + other.speculativeEvents)
}
final case class RuntimeSnapshot(round: Long, minimum: Option[Long], blocked: Int, callbacks: Int,
  queuedDispatches: Int = 0, retention: Option[RuntimeRetention] = None) extends RuntimeCommand
final case class CommitRuntime(round: Long, gvt: Long) extends RuntimeCommand
final case class RuntimeCommitted(round: Long, mayRetire: Boolean) extends RuntimeCommand
final case class ResumeRuntime(round: Long) extends RuntimeCommand
final case class RetireRuntimeProcess(round: Long, finalCut: Boolean = false) extends RuntimeCommand
final case class RuntimeProcessRetired(round: Long) extends RuntimeCommand
final case class StartRuntime(startTick: Long) extends RuntimeCommand
final case class RequestRuntimeRound() extends RuntimeCommand
final case class RuntimeAdvanced(round: Long, gvt: Long) extends RuntimeCommand
final case class RuntimeFinished(gvt: Long, truncated: Boolean) extends RuntimeCommand
final case class RuntimeFailure(reason: String) extends RuntimeCommand
final case class RuntimeStopManagers(round: Long) extends RuntimeCommand
final case class PublishCommittedReport(target: ActorRef, event: ReportEvent) extends RuntimeCommand
final case class CommittedReportDelivery(epoch: String, token: Long, event: ReportEvent, ackTo: ActorRef) extends RuntimeCommand
final case class BeginRuntimeCreation(key: String, tick: Long) extends RuntimeCommand
final case class EndRuntimeCreation(key: String) extends RuntimeCommand
final case class DrainRuntimeReports(round: Long, replyTo: ActorRef, targets: Set[ActorRef] = Set.empty) extends RuntimeCommand
final case class FlushRuntimeReports(round: Long, replyTo: ActorRef) extends RuntimeCommand
final case class RuntimeReportsFlushed(round: Long, error: Option[String] = None) extends RuntimeCommand

final case class PauseRuntimeExecution() extends RuntimeCommand
final case class ResumeRuntimeExecution() extends RuntimeCommand
final case class RuntimeExecutionPaused(round: Long) extends RuntimeCommand
