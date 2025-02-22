package org.interscity.htc
package core.util

import org.apache.pekko.actor.{ ActorRef, ActorSystem, Props }
import core.actor.BaseActor
import core.entity.event.{ Command, EntityEnvelopeEvent }

import org.apache.pekko.cluster.routing.{ ClusterRouterPool, ClusterRouterPoolSettings }
import org.apache.pekko.cluster.sharding.{ ClusterSharding, ClusterShardingSettings, ShardRegion }
import org.apache.pekko.cluster.singleton.{ ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings }
import org.apache.pekko.routing.RoundRobinPool
import org.interscity.htc.core.entity.actor.{ Identify, PoolDistributedConfiguration }
import org.interscity.htc.core.entity.event.control.execution.DestructEvent

import scala.collection.mutable

object ActorCreatorUtil {

  def createActor[T](system: ActorSystem, actorClass: Class[T], args: AnyRef*): ActorRef = {
    val props = Props(actorClass, args: _*)
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

    /*
    val extractEntityId: ShardRegion.ExtractEntityId = {
      case cmd: Command => (entityId, cmd)
    }

    val extractShardId: ShardRegion.ExtractShardId = {
      case cmd: Command => (entityId.hashCode % 10).toString
    }

     */
    val extractEntityId: ShardRegion.ExtractEntityId = {
      case msg @ EntityEnvelopeEvent(id, _) => (id, msg)
    }

    val extractShardId: ShardRegion.ExtractShardId = {
      case EntityEnvelopeEvent(id, _) => (id.hashCode % 10).toString
    }

    sharding.start(
      typeName = s"$actorClassName",
      entityProps = Props(clazz, entityId, timeManager, data, dependencies),
      settings = ClusterShardingSettings(system),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId
    )
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
      DestructEvent(tick = 0, actorRef = null),
      constructorParams
    )

    createSingletonProxy(system, entityId)
  }

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
    constructorParams: AnyRef*
  ): ActorRef = {
    val clazz = Class.forName(actorClassName)
    val constructor = clazz.getConstructor(classOf[String])
    val actorInstance =
      constructor.newInstance(entityId, constructorParams).asInstanceOf[BaseActor[?]]

    createPoolManagerActor(
      system = system,
      entityId = entityId,
      poolConfiguration = poolConfiguration,
      actorClass = clazz,
      constructorParams
    )
  }

  private def createPoolManagerActor[T](
    system: ActorSystem,
    entityId: String,
    poolConfiguration: PoolDistributedConfiguration,
    actorClass: Class[T],
    constructorParams: AnyRef*
  ): ActorRef =
    system.actorOf(
      ClusterRouterPool(
        RoundRobinPool(poolConfiguration.roundRobinPool),
        ClusterRouterPoolSettings(
          totalInstances = poolConfiguration.totalInstances,
          maxInstancesPerNode = poolConfiguration.maxInstancesPerNode,
          allowLocalRoutees = poolConfiguration.allowLocalRoutes,
          useRoles = poolConfiguration.useRoles
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
