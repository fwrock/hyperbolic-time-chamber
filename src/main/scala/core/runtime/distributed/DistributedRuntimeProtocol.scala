package org.interscity.htc
package core.runtime.distributed

import core.entity.event.ActorInteractionEvent
import core.runtime.accounting.{JoinRuntime, LogicalProcessId}
import org.apache.pekko.actor.ActorRef

sealed trait DistributedRuntimeCommand
final case class RuntimeMembership(epoch: String, managers: Vector[ActorRef]) extends DistributedRuntimeCommand
final case class RegisterLocalProcess(epoch: String, process: JoinRuntime) extends DistributedRuntimeCommand
final case class LocalProcessRegistered(id: LogicalProcessId) extends DistributedRuntimeCommand
final case class StartLocalRuntime(startTick: Long, horizon: Long,
  limits: core.runtime.OptimisticExecutionLimits = core.runtime.OptimisticExecutionLimits(),
  inputThrough: Long = Long.MaxValue) extends DistributedRuntimeCommand
final case class BeginInputEnrollment(round: Long) extends DistributedRuntimeCommand
final case class InputEnrollmentOpened(round: Long) extends DistributedRuntimeCommand
final case class CompleteInputEnrollment(round: Long, inputThrough: Long) extends DistributedRuntimeCommand
final case class InputEnrollmentCompleted(round: Long) extends DistributedRuntimeCommand
final case class LoadOptimisticInputWindow(epoch: String, window: Long, fromTick: Long, throughTick: Long) extends DistributedRuntimeCommand
final case class OptimisticInputWindowOpened(epoch: String, window: Long) extends DistributedRuntimeCommand
final case class OptimisticInputWindowReady(epoch: String, window: Long, loadedThrough: Long,
  closed: Boolean, actorsCreated: Long) extends DistributedRuntimeCommand
final case class OptimisticTransfer(epoch: String, token: Long, target: LogicalProcessId, event: ActorInteractionEvent) extends DistributedRuntimeCommand
final case class OptimisticTransferAccepted(epoch: String, token: Long) extends DistributedRuntimeCommand
final case class LocalRuntimeCommitted(round: Long, sinks: Set[ActorRef]) extends DistributedRuntimeCommand
final case class RetireLocalProcesses(round: Long, finalCut: Boolean) extends DistributedRuntimeCommand
final case class LocalProcessesRetired(round: Long) extends DistributedRuntimeCommand
