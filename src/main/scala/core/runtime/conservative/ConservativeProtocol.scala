package org.interscity.htc
package core.runtime.conservative

import core.entity.event.ActorInteractionEvent
import core.entity.event.control.report.ReportEvent
import core.runtime.EventOrder
import core.runtime.accounting.{JoinRuntime, LogicalProcessId}
import org.apache.pekko.actor.ActorRef

sealed trait ConservativeCommand

final case class ConservativeMembership(epoch: String, managers: Vector[ActorRef]) extends ConservativeCommand
final case class RegisterConservativeProcess(epoch: String, coordinator: ActorRef, process: JoinRuntime) extends ConservativeCommand
final case class ConservativeTransfer(epoch: String, token: Long, target: LogicalProcessId, event: ActorInteractionEvent) extends ConservativeCommand
final case class ConservativeTransferAccepted(epoch: String, token: Long) extends ConservativeCommand
final case class ConservativeDelivery(epoch: String, token: Long, event: AnyRef) extends ConservativeCommand
final case class ConservativeInputAccepted(epoch: String, token: Long, next: Option[EventOrder]) extends ConservativeCommand
final case class CollectConservativeReady(round: Long) extends ConservativeCommand
final case class ConservativeReady(round: Long, next: Option[EventOrder], blocked: Int) extends ConservativeCommand
final case class ExecuteConservativeBatch(round: Long, tick: Long, microstep: Long) extends ConservativeCommand
final case class ConservativeBatchProcessed(round: Long, next: Option[EventOrder], blocked: Int, terminal: Boolean = false) extends ConservativeCommand
final case class ConservativeBatchComplete(round: Long) extends ConservativeCommand
final case class PublishConservativeReport(target: ActorRef, event: ReportEvent) extends ConservativeCommand
final case class StopConservativeRuntime(round: Long, truncated: Boolean, finalHorizon: Option[Long] = None) extends ConservativeCommand
final case class RetireConservativeProcess(round: Long, truncated: Boolean, finalHorizon: Option[Long] = None) extends ConservativeCommand
final case class ConservativeProcessRetired(round: Long) extends ConservativeCommand
final case class ConservativeRuntimeStopped(round: Long, sinks: Set[ActorRef]) extends ConservativeCommand

final case class ConservativeMigrationMember(id: LogicalProcessId, ref: ActorRef, owner: ActorRef) extends ConservativeCommand
final case class FreezeConservativeMigration(epoch: String, requestId: String, targets: Map[String, String], round: Long) extends ConservativeCommand
final case class FreezeConservativeProcess(epoch: String, requestId: String) extends ConservativeCommand
final case class ConservativeProcessFrozen(epoch: String, requestId: String) extends ConservativeCommand
final case class ConservativeMigrationFrozen(epoch: String, requestId: String,
  members: Vector[ConservativeMigrationMember]) extends ConservativeCommand
final case class RebindConservativeProcess(epoch: String, requestId: String, id: LogicalProcessId,
  incarnation: String, oldPath: String) extends ConservativeCommand
final case class ConservativeProcessRebound(epoch: String, requestId: String, coordinator: ActorRef,
  owner: ActorRef, incarnation: String) extends ConservativeCommand
final case class AuthorizeConservativeSourceStop(epoch: String, requestId: String) extends ConservativeCommand
final case class ConservativeSourceStopAuthorized(epoch: String, requestId: String) extends ConservativeCommand
final case class ResumeConservativeMigration(epoch: String, requestId: String) extends ConservativeCommand
final case class EndConservativeProcessMigration(epoch: String, requestId: String) extends ConservativeCommand
final case class ConservativeMigrationResumed(epoch: String, requestId: String) extends ConservativeCommand
final case class ConservativeMigrationDeclined(requestId: String) extends ConservativeCommand
final case class ConservativeQueuedInput(event: AnyRef) extends ConservativeCommand
