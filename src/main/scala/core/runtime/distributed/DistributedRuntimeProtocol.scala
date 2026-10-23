package org.interscity.htc
package core.runtime.distributed

import core.entity.event.ActorInteractionEvent
import core.runtime.accounting.{JoinRuntime, LogicalProcessId}
import org.apache.pekko.actor.ActorRef

sealed trait DistributedRuntimeCommand
final case class RuntimeMembership(epoch: String, managers: Vector[ActorRef]) extends DistributedRuntimeCommand
final case class RegisterLocalProcess(epoch: String, process: JoinRuntime) extends DistributedRuntimeCommand
final case class LocalProcessRegistered(id: LogicalProcessId) extends DistributedRuntimeCommand
final case class StartLocalRuntime(startTick: Long, horizon: Long) extends DistributedRuntimeCommand
final case class OptimisticTransfer(epoch: String, token: Long, target: LogicalProcessId, event: ActorInteractionEvent) extends DistributedRuntimeCommand
final case class OptimisticTransferAccepted(epoch: String, token: Long) extends DistributedRuntimeCommand
final case class LocalRuntimeCommitted(round: Long, sinks: Set[ActorRef]) extends DistributedRuntimeCommand
final case class RetireLocalProcesses(round: Long, finalCut: Boolean) extends DistributedRuntimeCommand
final case class LocalProcessesRetired(round: Long) extends DistributedRuntimeCommand
