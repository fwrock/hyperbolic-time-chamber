package org.interscity.htc
package core.util

import org.apache.pekko.actor.{ ActorRef, ActorSystem, Props }
import core.actor.BaseActor
import core.entity.event.Command

import org.apache.pekko.cluster.sharding.{ ClusterSharding, ClusterShardingSettings, ShardRegion }

object ActorCreatorUtil {

  def createActor[T](system: ActorSystem, actorClass: Class[T], args: AnyRef*): ActorRef = {
    val constructor = actorClass.getConstructors.head
    val instance = constructor.newInstance(args*).asInstanceOf[BaseActor[?]]
    val props = Props(instance)
    system.actorOf(props)
  }

  def createActor[T](system: ActorSystem, actorClass: Class[T]): ActorRef = {
    val constructor = actorClass.getConstructors.head
    val instance = constructor.newInstance().asInstanceOf[BaseActor[?]]
    val props = Props(instance)
    system.actorOf(props)
  }

  def createActor(system: ActorSystem, actorClassName: String, args: AnyRef*): ActorRef = {
    val clazz = Class.forName(actorClassName)
    val constructor = clazz.getConstructors.head
    val instance = constructor.newInstance(args*).asInstanceOf[BaseActor[?]]
    val props = Props(instance)
    system.actorOf(props)
  }

  def createActor(system: ActorSystem, actorClassName: String): ActorRef = {
    val clazz = Class.forName(actorClassName)
    val constructor = clazz.getConstructors.head
    val instance = constructor.newInstance().asInstanceOf[BaseActor[?]]
    val props = Props(instance)
    system.actorOf(props)
  }

  def createShardedActor(
    system: ActorSystem,
    actorClassName: String,
    entityId: String,
    constructorParams: AnyRef*
  ): ActorRef = {
    val clazz = Class.forName(actorClassName)
    val constructor = clazz.getConstructor(classOf[String])
    val actorInstance =
      constructor.newInstance(entityId, constructorParams).asInstanceOf[BaseActor[?]]

    val sharding = ClusterSharding(system)

    val extractEntityId: ShardRegion.ExtractEntityId = {
      case cmd: Command => (entityId, cmd)
    }

    val extractShardId: ShardRegion.ExtractShardId = {
      case cmd: Command => (entityId.hashCode % 10).toString
    }

    sharding.start(
      typeName = s"$actorClassName",
      entityProps = Props(actorInstance),
      settings = ClusterShardingSettings(system),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId
    )
  }

  def createShardedActor[T <: BaseActor[_]](
    system: ActorSystem,
    actorClass: Class[T],
    entityId: String,
    constructorParams: AnyRef*
  ): ActorRef = {
    val constructor = actorClass.getConstructor(classOf[String])
    val actorInstance =
      constructor.newInstance(entityId, constructorParams).asInstanceOf[BaseActor[?]]

    val sharding = ClusterSharding(system)

    val extractEntityId: ShardRegion.ExtractEntityId = {
      case cmd: Command => (entityId, cmd)
    }

    val extractShardId: ShardRegion.ExtractShardId = {
      case cmd: Command => (entityId.hashCode % 10).toString
    }

    sharding.start(
      typeName = s"${actorClass.getName}",
      entityProps = Props(actorInstance),
      settings = ClusterShardingSettings(system),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId
    )
  }

}
