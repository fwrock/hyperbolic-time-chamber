package org.interscity.htc
package core.actor.manager.loadbalance.allocation

import org.apache.pekko.actor.ActorRef

sealed trait SpatialDirectoryWire
case class SpatialAssignment(entityId: String, shardId: String, typeName: String, x: Double, y: Double)
    extends SpatialDirectoryWire
case class RegisterSpatialReplica(requestId: String, address: String, incarnation: String, replica: ActorRef)
    extends SpatialDirectoryWire
case class InstallSpatialAssignments(requestId: String, epoch: String, revision: Long,
  entries: Vector[SpatialAssignment], replace: Boolean) extends SpatialDirectoryWire
case class SpatialAssignmentsInstalled(requestId: String, epoch: String, revision: Long,
  address: String, incarnation: String) extends SpatialDirectoryWire
case class SpatialInstallFailed(requestId: String, epoch: String, address: String,
  incarnation: String, reason: String) extends SpatialDirectoryWire
case class SpatialReplicaRegistered(requestId: String, epoch: String, revision: Long) extends SpatialDirectoryWire
case class PublishSpatialAssignments(requestId: String, epoch: String, address: String,
  incarnation: String, entries: Vector[SpatialAssignment]) extends SpatialDirectoryWire
case class SpatialAssignmentsPublished(requestId: String, epoch: String, revision: Long) extends SpatialDirectoryWire
case class SpatialDirectoryRejected(requestId: String, reason: String) extends SpatialDirectoryWire
case class SpatialDirectoryFailed(epoch: String, reason: String) extends SpatialDirectoryWire
case object GetSpatialDirectoryStatus extends SpatialDirectoryWire
case class SpatialDirectoryStatus(epoch: String, revision: Long, ready: Boolean,
  entryCount: Int, failure: Option[String]) extends SpatialDirectoryWire
