package org.interscity.htc
package core.util

import org.apache.pekko.actor.{ ActorRef, ActorSystem, Props }
import core.actor.BaseActor
import core.entity.event.{ Command, EntityEnvelopeEvent }

import org.apache.pekko.cluster.routing.{ ClusterRouterPool, ClusterRouterPoolSettings }
import org.apache.pekko.cluster.sharding.{ ClusterSharding, ClusterShardingSettings, ShardRegion }
import org.apache.pekko.cluster.singleton.{ ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings }
import org.apache.pekko.routing.RoundRobinPool
import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.actor.ShardActorId
import org.htc.protobuf.core.entity.event.control.execution.DestructEvent
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
    * If the entity has been registered with LoadBalanceManager and its spatial shard ID
    * is stored in [[SpatialShardIdRegistry]], that ID is used for routing. Otherwise,
    * falls back to hash-based assignment (id.hashCode % 1000).
    *
    * This enables spatially-aware initial placement when load balancing is enabled,
    * while remaining fully backward-compatible when it is not.
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

  def createShardedActorSeveralArgs(
    system: ActorSystem,
    actorClassName: String,
    entityId: String,
    constructorParams: AnyRef*
  ): ActorRef = {
    val clazz = Class.forName(actorClassName)
    val sharding = ClusterSharding(system)

    val extractEntityId: ShardRegion.ExtractEntityId = {
      case cmd: Command => (entityId, cmd)
    }

    val extractShardId: ShardRegion.ExtractShardId = {
      case cmd: Command => (entityId.hashCode % 10).toString
    }

    sharding.start(
      typeName = s"$actorClassName",
      entityProps = Props(clazz, constructorParams: _*),
      settings = ClusterShardingSettings(system),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId
    )
  }

  def createShardedActor(
    system: ActorSystem,
    actorClassName: String,
    entityId: String,
    timeManager: ActorRef,
    data: Any = null,
    dependencies: mutable.Map[String, Identify] = mutable.Map[String, Identify]()
  ): ActorRef = {
    val clazz = Class.forName(actorClassName)
    val sharding = ClusterSharding(system)

    val extractEntityId: ShardRegion.ExtractEntityId = {
      case EntityEnvelopeEvent(id, payload) => (id, payload)
      case ShardRegion.StartEntity(id)      => (id, ShardRegion.StartEntity(id))
    }

    val extractShardId: ShardRegion.ExtractShardId = {
      case EntityEnvelopeEvent(id, _)  => (id.hashCode % 10).toString
      case ShardRegion.StartEntity(id) => (id.hashCode % 10).toString
    }

    println(s"Creating sharded actor $actorClassName with entityId $entityId and with data $data")

    sharding.start(
      typeName = s"$actorClassName",
      entityProps = Props(clazz, entityId, timeManager, data, dependencies),
      settings = ClusterShardingSettings(system),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId
    )
  }

  def createShardRegion(
    system: ActorSystem,
    actorClassName: String,
    entityId: String,
    resourceId: String,
    timeManagers: mutable.Map[String, ActorRef],
    creatorManager: ActorRef,
    reporters: mutable.Map[ReportTypeEnum, ActorRef] = mutable.Map.empty
  ): ActorRef = {
    val clazz = Class.forName(StringUtil.getModelClassName(actorClassName))
    val sharding = ClusterSharding(system)

    val shardName = StringUtil.getModelClassName(actorClassName)

    if (!sharding.shardTypeNames.contains(shardName)) {
      system.log.info(
        s"Creating shard region for $actorClassName with id $shardName entityId $entityId"
      )

      val entityProps = Props(
        clazz,
        Properties(
          entityId = entityId,
          resourceId = resourceId,
          timeManagers = timeManagers,
          creatorManager = creatorManager,
          reporters = reporters,
          actorType = CreationTypeEnum.LoadBalancedDistributed
        )
      )

      // Use custom allocator from LoadBalanceManager if registered, otherwise Pekko default
      ShardAllocatorRegistry.get match {
        case Some(allocator) =>
          sharding.start(
            typeName = shardName,
            entityProps = entityProps,
            settings = ClusterShardingSettings(system),
            extractEntityId = extractEntityId,
            extractShardId = extractShardId,
            allocationStrategy = allocator,
            handOffStopMessage = org.htc.protobuf.core.entity.event.control.execution.DestructEvent(actorRef = "")
          )
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

  def createShardedActorSeveralArgs[T <: BaseActor[_]](
    system: ActorSystem,
    actorClass: Class[T],
    entityId: String,
    constructorParams: AnyRef*
  ): ActorRef = {
    val sharding = ClusterSharding(system)

    val extractEntityId: ShardRegion.ExtractEntityId = {
      case cmd: Command => (entityId, cmd)
    }

    val extractShardId: ShardRegion.ExtractShardId = {
      case cmd: Command => (entityId.hashCode % 10).toString
    }

    sharding.start(
      typeName = s"${actorClass.getName}",
      entityProps = Props(actorClass, constructorParams: _*),
      settings = ClusterShardingSettings(system),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId
    )
  }

  def createSingletonActor(
    system: ActorSystem,
    actorClassName: String,
    entityId: String,
    constructorParams: AnyRef*
  ): ActorRef = {
    val clazz = Class.forName(actorClassName)
    val constructor = clazz.getConstructor(classOf[String])
    val actorInstance =
      constructor.newInstance(entityId, constructorParams).asInstanceOf[BaseActor[?]]

    createSingleton(
      system,
      clazz,
      entityId,
      DestructEvent(actorRef = null),
      constructorParams
    )

    createSingletonProxy(system, entityId)
  }

  def createSingletonManager(
    system: ActorSystem,
    manager: Props,
    name: String,
    terminateMessage: Any
  ): ActorRef =
    system.actorOf(
      ClusterSingletonManager.props(
        singletonProps = manager,
        terminationMessage = terminateMessage,
        settings = ClusterSingletonManagerSettings(system)
      ),
      name = name
    )

  private def createSingleton[T](
    system: ActorSystem,
    actorClass: Class[T],
    name: String,
    terminateMessage: Any,
    constructorParams: AnyRef*
  ): ActorRef =
    system.actorOf(
      ClusterSingletonManager.props(
        singletonProps = Props(actorClass, constructorParams: _*),
        terminationMessage = terminateMessage,
        settings = ClusterSingletonManagerSettings(system)
      ),
      name = name
    )

  private def createSingletonProxy(system: ActorSystem, name: String): ActorRef =
    system.actorOf(
      ClusterSingletonProxy.props(
        singletonManagerPath = s"/user/$name",
        settings = ClusterSingletonProxySettings(system)
      ),
      name = s"${name}-proxy"
    )

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
    creationType: CreationTypeEnum
  ): ActorRef = {
    val clazz = Class.forName(actorClassName)
    system.actorOf(
      ClusterRouterPool(
        RoundRobinPool(poolConfiguration.roundRobinPool),
        ClusterRouterPoolSettings(
          totalInstances = poolConfiguration.totalInstances,
          maxInstancesPerNode = poolConfiguration.maxInstancesPerNode,
          allowLocalRoutees = poolConfiguration.allowLocalRoutes
        )
      ).props(
        Props(
          clazz,
          Properties(
            entityId = entityId,
            resourceId = resourceId,
            timeManagers = timeManagers,
            creatorManager = creatorManager,
            reporters = reporters,
            data = data,
            actorType = creationType
          )
        )
      ),
      name = entityId
    )
  }

  private def createPoolManagerActor[T](
    system: ActorSystem,
    entityId: String,
    poolConfiguration: PoolDistributedConfiguration,
    actorClass: Class[T],
    constructorParams: Any*
  ): ActorRef =
    system.actorOf(
      ClusterRouterPool(
        RoundRobinPool(poolConfiguration.roundRobinPool),
        ClusterRouterPoolSettings(
          totalInstances = poolConfiguration.totalInstances,
          maxInstancesPerNode = poolConfiguration.maxInstancesPerNode,
          allowLocalRoutees = poolConfiguration.allowLocalRoutes
        )
      ).props(
        Props(
          actorClass,
          constructorParams: _*
        )
      ),
      name = entityId
    )
}
