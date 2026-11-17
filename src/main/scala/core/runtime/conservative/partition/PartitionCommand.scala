package org.interscity.htc
package core.runtime.conservative.partition

import core.runtime.accounting.LogicalProcessId
import core.runtime.conservative.{ DirectExecute, DirectWorkId }
import org.apache.pekko.actor.ActorRef

/** Node dispatcher/partition protocol; all records are scoped to an immutable run epoch. */
sealed trait PartitionCommand
final case class PartitionOwners(epoch: String, id: LogicalProcessId, owner: ActorRef)
    extends PartitionCommand
final case class PartitionCohort(epoch: String, workers: Vector[ActorRef]) extends PartitionCommand
final case class ConfigurePartition(epoch: String, nodes: Vector[ActorRef]) extends PartitionCommand
final case class StoreWake(epoch: String, wake: TickWake) extends PartitionCommand
final case class WakeStored(epoch: String, wake: TickWake) extends PartitionCommand
final case class DrainPartition(epoch: String, token: Long, tick: Long) extends PartitionCommand
final case class WakeClaimed(epoch: String, wake: TickWake) extends PartitionCommand
final case class PartitionDrained(epoch: String, token: Long) extends PartitionCommand
final case class StartPartitionRoot(
  epoch: String,
  target: ActorRef,
  delivery: DirectExecute,
  wake: Option[TickWake]
) extends PartitionCommand
final case class PartitionRootsSettled(epoch: String, roots: Vector[DirectWorkId])
    extends PartitionCommand
