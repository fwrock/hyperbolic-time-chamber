package org.interscity.htc
package core.util

import org.apache.pekko.actor.{ ActorRef, ActorRefFactory, ActorSystem }
import org.apache.pekko.cluster.sharding.ClusterSharding
import org.apache.pekko.cluster.singleton.{ ClusterSingletonProxy, ClusterSingletonProxySettings }

object DistributedUtil {

  def getShardRef(shardRegionName: String, system: ActorSystem): ActorRef =
    RuntimeMode.from(system.settings.config) match {
      case RuntimeMode.Cluster    => ClusterSharding(system).shardRegion(shardRegionName)
      case RuntimeMode.SingleNode => LocalRegions.get(system, shardRegionName)
    }

  def createSingletonProxyUnder(
    factory: ActorRefFactory,
    system: ActorSystem,
    name: String,
    proxyName: Option[String] = None
  ): ActorRef = RuntimeMode.from(system.settings.config) match {
    case RuntimeMode.Cluster =>
      val props = ClusterSingletonProxy.props(s"/user/$name", ClusterSingletonProxySettings(system))
      proxyName.fold(factory.actorOf(props))(factory.actorOf(props, _))
    case RuntimeMode.SingleNode =>
      LocalSingletons.proxy(factory, system, name, proxyName)
  }

  def createSingletonProxy(system: ActorSystem, name: String): ActorRef =
    createSingletonProxyUnder(system, system, name, Some(s"$name-${java.util.UUID.randomUUID()}-proxy"))
}
