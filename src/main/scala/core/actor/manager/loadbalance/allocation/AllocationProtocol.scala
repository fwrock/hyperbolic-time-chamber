package org.interscity.htc
package core.actor.manager.loadbalance.allocation

import org.apache.pekko.actor.ActorRef

sealed trait AllocationWire
case class AllocationRegion(region: ActorRef, address: String, shards: Vector[String]) extends AllocationWire
case class AllocationTarget(typeName: String, source: ActorRef, target: ActorRef) extends AllocationWire
case class RegisterAllocationRegion(requestId: String, epoch: String, typeName: String,
  address: String, incarnation: String, region: ActorRef) extends AllocationWire
case class AllocationRegionRegistered(requestId: String, epoch: String, typeName: String) extends AllocationWire
case class BindAllocationController(requestId: String, epoch: String, controller: ActorRef,
  controllerId: String) extends AllocationWire
case class AllocationControllerBound(requestId: String, epoch: String, controllerId: String) extends AllocationWire
case class AllocateShardRequest(requestId: String, epoch: String, typeName: String, address: String,
  incarnation: String, sequence: Long, requester: ActorRef, shardId: String,
  regions: Vector[AllocationRegion]) extends AllocationWire
case class AllocateShardReply(requestId: String, epoch: String, region: ActorRef) extends AllocationWire
case class RebalanceShardsRequest(requestId: String, epoch: String, typeName: String, address: String,
  incarnation: String, sequence: Long, regions: Vector[AllocationRegion], inProgress: Set[String]) extends AllocationWire
case class RebalanceShardsReply(requestId: String, epoch: String, shards: Set[String]) extends AllocationWire
case class ResolveAllocationPlacement(requestId: String, epoch: String, controllerId: String,
  shardId: String, sourceAddress: String, targetAddress: String, typeNames: Vector[String]) extends AllocationWire
case class AllocationPlacementResolved(requestId: String, epoch: String, targets: Vector[AllocationTarget]) extends AllocationWire
case class InstallAllocationIntent(requestId: String, epoch: String, controllerId: String, batchId: String,
  attemptId: String, shardId: String, sourceAddress: String, targetAddress: String,
  targets: Vector[AllocationTarget]) extends AllocationWire
case class AllocationIntentInstalled(requestId: String, epoch: String, batchId: String, attemptId: String) extends AllocationWire
case class RetireAllocationIntent(requestId: String, epoch: String, controllerId: String,
  batchId: String, attemptId: String, shardId: String) extends AllocationWire
case class AllocationIntentRetired(requestId: String, epoch: String, batchId: String, attemptId: String) extends AllocationWire
case class AllocationRejected(requestId: String, epoch: String, reason: String) extends AllocationWire
case class AllocationAuthorityFailure(controllerId: String, reason: String) extends AllocationWire
case object GetAllocationClientStatus extends AllocationWire
case class AllocationClientStatus(epoch: String, incarnation: String, address: String,
  registeredTypes: Set[String], failure: Option[String]) extends AllocationWire
case class GetAllocationAuthorityStatus(requestId: String, epoch: String) extends AllocationWire
case class AllocationAuthorityStatus(requestId: String, epoch: String, callbackOwners: Map[String, String],
  controllerAddress: Option[String], registered: Map[String, Set[String]], failure: Option[String],
  allocations: Map[String, Vector[AllocationRegion]] = Map.empty) extends AllocationWire
