package org.interscity.htc
package core.actor.manager.loadbalance.allocation

import org.apache.pekko.actor.{ Actor, ActorLogging, ActorRef, Props }
import org.apache.pekko.cluster.{ Cluster, MemberStatus }
import scala.collection.mutable

class AllocationAuthority extends Actor with ActorLogging {
  private val epoch = java.util.UUID.randomUUID().toString
  private case class Registration(region: ActorRef, incarnation: String)
  private val registrations = mutable.Map.empty[(String, String), Registration]
  private val snapshots = mutable.Map.empty[String, Vector[AllocationRegion]]
  private val owners = mutable.Map.empty[String, (String, String, Long)]
  private val intents = mutable.Map.empty[String, InstallAllocationIntent]
  private val placements = mutable.Map.empty[(String, String), ActorRef]
  private val retired = mutable.Map.empty[String, (String, String)]
  private var controller: Option[(ActorRef, String)] = None
  private var failure: Option[String] = None

  private def checkEpoch(value: String): Unit =
    if (failure.nonEmpty || value != epoch)
      throw new IllegalStateException(failure.getOrElse("allocation authority epoch changed"))
  private def checkController(value: String, id: String): Unit = {
    checkEpoch(value)
    require(controller.exists(_._2 == id), "allocation controller changed or is not bound")
  }
  private def up(address: String): Boolean = Cluster(context.system).state.members.exists(m =>
    m.status == MemberStatus.Up && m.address.toString == address)
  private def reject(id: String, reason: String, fatal: Boolean = false): Unit = {
    sender() ! AllocationRejected(id, epoch, reason)
    if (fatal) {
      failure = Some(reason)
      controller.foreach { case (ref, id) => ref ! AllocationAuthorityFailure(id, reason) }
    }
  }

  private def observe(typeName: String, address: String, incarnation: String, sequence: Long,
    regions: Vector[AllocationRegion]): Unit = {
    require(registrations.get(typeName -> address).exists(_.incarnation == incarnation), "callback adapter is not registered")
    require(sequence > 0L && regions.nonEmpty && regions.map(_.region).distinct.size == regions.size,
      "invalid allocation callback cohort")
    require(regions.forall(r => registrations.get(typeName -> r.address).exists(_.region == r.region)),
      "callback contains an unregistered region")
    owners.get(typeName) match {
      case Some((node, owner, _)) if node != address || owner != incarnation =>
        throw new IllegalStateException("allocation callback owner changed; coordinated recovery is required")
      case Some((_, _, last)) if sequence <= last => ()
      case _ =>
        owners(typeName) = (address, incarnation, sequence)
        snapshots(typeName) = regions
    }
  }

  private def resolve(shardId: String, source: String, target: String, types: Vector[String]): Vector[AllocationTarget] = {
    require(source != target && up(source) && up(target), "allocation source/target must be distinct Up members")
    require(types.nonEmpty && types.distinct.size == types.size, "missing or duplicate physical shard types")
    types.sorted.map { name =>
      val rows = snapshots.getOrElse(name, Vector.empty)
      val from = rows.filter(_.shards.contains(shardId))
      val to = rows.filter(_.address == target)
      require(from.size == 1 && from.head.address == source, s"no unique observed source for $name/$shardId")
      require(to.size == 1, s"no registered target in callback cohort for $name/$shardId")
      AllocationTarget(name, from.head.region, to.head.region)
    }
  }

  override def receive: Receive = {
    case e: GetAllocationAuthorityStatus =>
      if (e.epoch != epoch) reject(e.requestId, "allocation authority epoch changed")
      else sender() ! AllocationAuthorityStatus(e.requestId, epoch, owners.view.mapValues(_._1).toMap,
        controller.map { case (ref, _) =>
          if (ref.path.address.hasLocalScope) Cluster(context.system).selfAddress.toString else ref.path.address.toString
        }, registrations.keys.groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap, failure, snapshots.toMap)
    case e: RegisterAllocationRegion =>
      try {
        require(failure.isEmpty, failure.getOrElse("allocation authority failed"))
        require(e.epoch.isEmpty || e.epoch == epoch, "allocation authority epoch changed")
        require(e.typeName.nonEmpty && e.incarnation.nonEmpty && e.region != null, "invalid region registration")
        val key = e.typeName -> e.address
        require(registrations.get(key).forall(r => r.region == e.region && r.incarnation == e.incarnation),
          "allocation region/incarnation changed")
        registrations(key) = Registration(e.region, e.incarnation)
        sender() ! AllocationRegionRegistered(e.requestId, epoch, e.typeName)
      } catch { case scala.util.control.NonFatal(error) => reject(e.requestId, error.getMessage, true) }

    case e: BindAllocationController =>
      try {
        require(failure.isEmpty, failure.getOrElse("allocation authority failed"))
        require(e.epoch.isEmpty || e.epoch == epoch, "allocation authority epoch changed")
        require(e.controller != null && e.controllerId.nonEmpty, "invalid controller binding")
        require(controller.forall(_ == (e.controller -> e.controllerId)), "allocation controller incarnation changed")
        controller = Some(e.controller -> e.controllerId)
        sender() ! AllocationControllerBound(e.requestId, epoch, e.controllerId)
      } catch { case scala.util.control.NonFatal(error) => reject(e.requestId, error.getMessage, true) }

    case e: AllocateShardRequest =>
      try {
        checkEpoch(e.epoch)
        observe(e.typeName, e.address, e.incarnation, e.sequence, e.regions)
        val chosen = placements.get(e.typeName -> e.shardId) match {
          case Some(target) =>
            require(e.regions.exists(r => r.region == target && up(r.address)), "explicit allocation target is unavailable")
            target
          case None =>
            val eligible = e.regions.filter(r => up(r.address))
            require(eligible.nonEmpty, "no eligible registered allocation regions")
            eligible.minBy(r => (r.shards.size, if (r.region == e.requester) 0 else 1, r.address)).region
        }
        sender() ! AllocateShardReply(e.requestId, epoch, chosen)
      } catch { case scala.util.control.NonFatal(error) => reject(e.requestId, error.getMessage, error.isInstanceOf[IllegalStateException]) }

    case e: RebalanceShardsRequest =>
      try {
        checkEpoch(e.epoch)
        observe(e.typeName, e.address, e.incarnation, e.sequence, e.regions)
        val rows = snapshots(e.typeName)
        val moves = intents.valuesIterator.filter { intent =>
          !e.inProgress.contains(intent.shardId) && intent.targets.exists(t => t.typeName == e.typeName &&
            rows.exists(r => r.region == t.source && r.shards.contains(intent.shardId)) &&
            rows.exists(r => r.region == t.target && up(r.address)))
        }.map(_.shardId).toSet
        // A reply can be lost; observation and correlated retirement, not RPC delivery, consume intent.
        sender() ! RebalanceShardsReply(e.requestId, epoch, moves)
      } catch { case scala.util.control.NonFatal(error) => reject(e.requestId, error.getMessage, error.isInstanceOf[IllegalStateException]) }

    case e: ResolveAllocationPlacement =>
      try {
        checkController(e.epoch, e.controllerId)
        sender() ! AllocationPlacementResolved(e.requestId, epoch,
          resolve(e.shardId, e.sourceAddress, e.targetAddress, e.typeNames))
      } catch { case scala.util.control.NonFatal(error) => reject(e.requestId, error.getMessage) }

    case e: InstallAllocationIntent =>
      try {
        checkController(e.epoch, e.controllerId)
        require(e.batchId.nonEmpty && e.attemptId.nonEmpty && e.targets.nonEmpty, "missing allocation intent identity")
        intents.get(e.shardId) match {
          case Some(existing) =>
            require(existing.copy(requestId = e.requestId) == e, "conflicting allocation intent")
          case None =>
            require(!retired.get(e.shardId).contains(e.batchId -> e.attemptId), "allocation intent already retired")
            require(resolve(e.shardId, e.sourceAddress, e.targetAddress, e.targets.map(_.typeName)) == e.targets,
              "allocation registration changed after preflight")
            intents(e.shardId) = e
            e.targets.foreach(t => placements(t.typeName -> e.shardId) = t.target)
        }
        sender() ! AllocationIntentInstalled(e.requestId, epoch, e.batchId, e.attemptId)
      } catch { case scala.util.control.NonFatal(error) => reject(e.requestId, error.getMessage) }

    case e: RetireAllocationIntent =>
      try {
        checkController(e.epoch, e.controllerId)
        intents.get(e.shardId) match {
          case Some(current) =>
            require(current.batchId == e.batchId && current.attemptId == e.attemptId, "stale allocation retirement")
            intents -= e.shardId
            retired(e.shardId) = e.batchId -> e.attemptId
          case None => require(retired.get(e.shardId).contains(e.batchId -> e.attemptId), "unknown allocation retirement")
        }
        sender() ! AllocationIntentRetired(e.requestId, epoch, e.batchId, e.attemptId)
      } catch { case scala.util.control.NonFatal(error) => reject(e.requestId, error.getMessage) }
    case _ => ()
  }
}

object AllocationAuthority {
  def props(): Props = Props(classOf[AllocationAuthority])
}
