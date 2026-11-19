package org.interscity.htc
package core.util

import org.apache.pekko.actor.{ ActorRef, ActorRefFactory, ActorSystem }
import org.apache.pekko.cluster.sharding.ClusterSharding
import org.apache.pekko.cluster.singleton.{ ClusterSingletonProxy, ClusterSingletonProxySettings }

object DistributedUtil {

  def getShardRef(shardRegionName: String, system: ActorSystem): ActorRef =
    ClusterSharding(system).shardRegion(shardRegionName)

  def createSingletonProxyUnder(
    factory: ActorRefFactory,
    system: ActorSystem,
    name: String,
    proxyName: Option[String] = None
  ): ActorRef = {
    val props = ClusterSingletonProxy.props(s"/user/$name", ClusterSingletonProxySettings(system))
    proxyName.fold(factory.actorOf(props))(factory.actorOf(props, _))
  }

  def createSingletonProxy(system: ActorSystem, name: String): ActorRef = {
    system.actorOf(ClusterSingletonProxy.props(s"/user/$name", ClusterSingletonProxySettings(system)),
      s"$name-${java.util.UUID.randomUUID()}-proxy")
  }
}
