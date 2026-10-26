package org.interscity.htc
package core.actor.manager.load

import core.entity.event.EntityEnvelopeEvent
import core.entity.event.control.load.InitializeEvent
import core.entity.event.data.InitializeData
import core.runtime.accounting.*
import core.util.ActorCreatorUtil
import core.actor.manager.loadbalance.allocation.SpatialPositionUtil
import core.entity.control.loadbalance.SpatialEntityData
import core.entity.event.control.loadbalance.{ BatchShardAssignmentResponse, RegisterSpatialEntitiesBatchEvent }
import core.util.ManagerConstantsUtil.LOAD_BALANCE_MANAGER_ACTOR_NAME
import org.apache.pekko.actor.{Actor, ActorRef, Props}
import org.apache.pekko.cluster.singleton.{ ClusterSingletonProxy, ClusterSingletonProxySettings }
import org.apache.pekko.cluster.sharding.ShardRegion
import org.htc.protobuf.core.entity.event.control.load.InitializeEntityAckEvent
import scala.collection.mutable

/** One bounded initialization handshake; the owner retains the birth's transport obligation. */
class RuntimeBirthInitializer(owner: ActorRef, coordinator: ActorRef, target: LogicalProcessId,
  origin: RuntimeBirthOrigin, data: RuntimeBirthData) extends Actor {
  private val managers = mutable.Map.from(data.timeManagers)
  private val reporters = mutable.Map.from(data.reporters)
  private var region: ActorRef = _
  private val publicationId = java.util.UUID.randomUUID().toString
  override def preStart(): Unit = {
    if (context.system.settings.config.getBoolean("htc.load-balance-manager.enabled")) {
      val proxy = context.actorOf(ClusterSingletonProxy.props(s"/user/$LOAD_BALANCE_MANAGER_ACTOR_NAME",
        ClusterSingletonProxySettings(context.system)))
      val position = SpatialPositionUtil.resolve(data.stateJson, context.system.settings.config)
      proxy ! RegisterSpatialEntitiesBatchEvent(Vector(SpatialEntityData(target.id.toString,
        position._1, position._2, entityClassType = target.model)), publicationId)
    } else startEntity()
  }
  private def startEntity(): Unit = {
    region = ActorCreatorUtil.createShardRegion(context.system, target.model, target.id.toString,
      data.resourceId, managers, self, reporters, runtimeCoordinator = Some(coordinator))
    region ! ShardRegion.StartEntity(target.id.toString)
  }
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    owner ! RuntimeFailure(s"birth initializer restart for $target: ${reason.getMessage}")
    super.preRestart(reason, message)
  }
  override def receive: Receive = {
    case event: BatchShardAssignmentResponse if event.batchId == publicationId && event.chunkIndex == 0 && region == null =>
      require(event.assignments.keySet == Set(target.id.toString), "birth spatial assignment cohort mismatch")
      startEntity()
    case ack: ShardRegion.StartEntityAck =>
      require(ack.entityId == target.id.toString, "birth startup acknowledgement mismatch")
      region ! EntityEnvelopeEvent(target.id.toString, InitializeEvent(target.id.toString, self,
        InitializeData(data.stateJson, data.resourceId, self, reporters,
          mutable.Map.from(data.relationships), managers, Some(coordinator), Some(origin))))
    case ack: InitializeEntityAckEvent =>
      require(ack.entityId == target.id.toString, "birth initialization acknowledgement mismatch")
      owner ! RuntimeBirthInitialized(target, sender())
    case StopRuntimeBirthInitializer if sender() == owner => context.stop(self)
  }
}

object RuntimeBirthInitializer {
  def props(owner: ActorRef, coordinator: ActorRef, target: LogicalProcessId,
    origin: RuntimeBirthOrigin, data: RuntimeBirthData): Props =
    Props(new RuntimeBirthInitializer(owner, coordinator, target, origin, data))
}
