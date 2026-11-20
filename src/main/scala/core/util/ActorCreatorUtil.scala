package org.interscity.htc
package core.util

import org.apache.pekko.actor.{ ActorRef, ActorRefFactory, ActorSystem, Props }
import core.actor.BaseActor
import core.entity.event.EntityEnvelopeEvent
import core.entity.event.control.migration.MigrationHandoffStopEvent

import org.apache.pekko.cluster.routing.{ ClusterRouterPool, ClusterRouterPoolSettings }
import org.apache.pekko.cluster.sharding.{ ClusterSharding, ClusterShardingSettings, ShardRegion }
import org.apache.pekko.cluster.singleton.{ ClusterSingletonManager, ClusterSingletonManagerSettings }
import org.apache.pekko.routing.RoundRobinPool
import org.interscity.htc.core.entity.actor.ShardActorId
import org.interscity.htc.core.actor.manager.loadbalance.allocation.{ ShardAllocatorRegistry, SpatialShardIdRegistry }
import org.interscity.htc.core.entity.actor.PoolDistributedConfiguration
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.enumeration.{ CreationTypeEnum, ReportTypeEnum }

import scala.collection.mutable

object ActorCreatorUtil {

  private val extractEntityId: ShardRegion.ExtractEntityId = {
    case EntityEnvelopeEvent(id, payload) => (id, payload)
  }

  /** Shard ID extractor that uses spatial assignments when available.
    *
    * If the entity has been registered with LoadBalanceManager and its spatial shard ID is stored
    * in [[SpatialShardIdRegistry]], that ID is used for routing. Otherwise, falls back to
    * hash-based assignment (id.hashCode % 1000).
    *
    * This enables spatially-aware initial placement when load balancing is enabled, while remaining
    * fully backward-compatible when it is not.
    */
  private val extractShardId: ShardRegion.ExtractShardId = {
    case EntityEnvelopeEvent(id, _) =>
      SpatialShardIdRegistry.getShardId(id).getOrElse((id.hashCode % 1000).toString)
    case ShardRegion.StartEntity(id) =>
      SpatialShardIdRegistry.getShardId(id).getOrElse((id.hashCode % 1000).toString)
  }

  def createActor[T](system: ActorSystem, actorClass: Class[T], args: AnyRef*): ActorRef = {
    val props = Props(actorClass, args: _*)
    system.actorOf(props)
  }

  def createActor[T](
    system: ActorSystem,
    actorClass: Class[T],
    properties: Properties
  ): ActorRef = {
    val props = Props(actorClass, properties)
    system.actorOf(props)
  }

  def createActor[T](system: ActorSystem, actorClass: Class[T]): ActorRef = {
    val props = Props(actorClass)
    system.actorOf(props)
  }

  def createActor(system: ActorSystem, actorClassName: String, args: AnyRef*): ActorRef = {
    val clazz = Class.forName(actorClassName).asInstanceOf[Class[BaseActor[?]]]
    val props = Props(clazz, args: _*)
    system.actorOf(props)
  }

  def createActor(system: ActorSystem, actorClassName: String): ActorRef = {
    val clazz = Class.forName(actorClassName).asInstanceOf[Class[BaseActor[?]]]
    val props = Props(clazz)
    system.actorOf(props)
  }

  def createShardRegion(
    system: ActorSystem,
    actorClassName: String,
    entityId: String,
    resourceId: String,
    timeManagers: mutable.Map[String, ActorRef],
    creatorManager: ActorRef,
    reporters: mutable.Map[ReportTypeEnum, ActorRef] = mutable.Map.empty,
    runtimeCoordinator: Option[ActorRef] = None
  ): ActorRef = {
    val clazz = Class.forName(StringUtil.getModelClassName(actorClassName))
    val shardName = StringUtil.getModelClassName(actorClassName)

    def propsFor(): Props = Props(
      clazz,
      Properties(
        entityId = entityId,
        resourceId = resourceId,
        timeManagers = timeManagers,
        runtimeCoordinator = runtimeCoordinator,
        creatorManager = creatorManager,
        reporters = reporters,
        actorType = CreationTypeEnum.LoadBalancedDistributed
      )
    )

    if (RuntimeMode.forSystem(system) == RuntimeMode.SingleNode)
      return LocalRegions.getOrCreate(system, shardName, propsFor())

    val sharding = ClusterSharding(system)

    if (!sharding.shardTypeNames.contains(shardName)) {
      system.log.info(
        s"Creating shard region for $actorClassName with id $shardName entityId $entityId"
      )

      val entityProps = propsFor()

      val distributedAllocation = core.actor.manager.loadbalance.allocation.AllocationRuntime(system)
      if (distributedAllocation.enabled) {
        distributedAllocation.start()
        val publishedShardId: ShardRegion.ExtractShardId = {
          case EntityEnvelopeEvent(id, _) => SpatialShardIdRegistry.requirePublishedShardId(id, shardName)
          case ShardRegion.StartEntity(id) => SpatialShardIdRegistry.requirePublishedShardId(id, shardName)
        }
        val region = sharding.start(typeName = shardName, entityProps = entityProps,
          settings = ClusterShardingSettings(system), extractEntityId = extractEntityId,
          extractShardId = publishedShardId, allocationStrategy = distributedAllocation.strategy(shardName),
          handOffStopMessage = MigrationHandoffStopEvent())
        distributedAllocation.register(shardName, region)
        region
      } else ShardAllocatorRegistry.get match {
        case Some(allocator) =>
          val typedAllocator = allocator.forType(shardName)
          val region = sharding.start(
            typeName = shardName,
            entityProps = entityProps,
            settings = ClusterShardingSettings(system),
            extractEntityId = extractEntityId,
            extractShardId = extractShardId,
            allocationStrategy = typedAllocator,
            handOffStopMessage = MigrationHandoffStopEvent()
          )
          typedAllocator.registerLocalRegion(region, org.apache.pekko.cluster.Cluster(system).selfAddress)
          region
        case None =>
          sharding.start(
            typeName = shardName,
            entityProps = entityProps,
            settings = ClusterShardingSettings(system),
            extractEntityId = extractEntityId,
            extractShardId = extractShardId
          )
      }
    } else {
      sharding.shardRegion(shardName)
    }
  }

  def createSingletonManager(
    system: ActorSystem,
    manager: Props,
    name: String,
    terminateMessage: Any
  ): ActorRef = RuntimeMode.forSystem(system) match {
    case RuntimeMode.Cluster =>
      system.actorOf(
        ClusterSingletonManager.props(
          singletonProps = manager,
          terminationMessage = terminateMessage,
          settings = ClusterSingletonManagerSettings(system)
        ),
        name = name
      )
    case RuntimeMode.SingleNode =>
      LocalSingletons.register(system, name, system.actorOf(manager, name))
  }

  /** Single-node mode has no cluster router to supply routees, so the local pool is sized to what a
    * one-node cluster would have created: the global count capped by the per-node cap.
    */
  def createRouterPool(
    factory: ActorRefFactory,
    system: ActorSystem,
    routee: Props,
    name: String,
    totalInstances: Int,
    maxInstancesPerNode: Int,
    allowLocalRoutees: Boolean = true,
    clusterLocalInstances: Int = 0
  ): ActorRef = {
    val router = RuntimeMode.forSystem(system) match {
      case RuntimeMode.Cluster =>
        ClusterRouterPool(
          RoundRobinPool(clusterLocalInstances),
          ClusterRouterPoolSettings(totalInstances, maxInstancesPerNode, allowLocalRoutees)
        ).props(routee)
      case RuntimeMode.SingleNode =>
        RoundRobinPool(Math.max(1, Math.min(totalInstances, maxInstancesPerNode))).props(routee)
    }
    factory.actorOf(router, name)
  }

  def createPoolActor(
    system: ActorSystem,
    actorClassName: String,
    entityId: String,
    poolConfiguration: PoolDistributedConfiguration,
    resourceId: String,
    timeManagers: mutable.Map[String, ActorRef],
    creatorManager: ActorRef,
    reporters: mutable.Map[ReportTypeEnum, ActorRef],
    data: Any,
    dependencies: mutable.Map[String, ShardActorId],
    creationType: CreationTypeEnum,
    runtimeCoordinator: Option[ActorRef] = None
  ): ActorRef = {
    val clazz = Class.forName(actorClassName)
    createRouterPool(
      system,
      system,
      Props(
        clazz,
        Properties(
          entityId = entityId,
          resourceId = resourceId,
          timeManagers = timeManagers,
          runtimeCoordinator = runtimeCoordinator,
          creatorManager = creatorManager,
          reporters = reporters,
          data = data,
          actorType = creationType
        )
      ),
      entityId,
      poolConfiguration.totalInstances,
      poolConfiguration.maxInstancesPerNode,
      poolConfiguration.allowLocalRoutes,
      poolConfiguration.roundRobinPool
    )
  }
}
