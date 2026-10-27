package org.interscity.htc
package core.util

import org.apache.pekko.actor.{ ActorRef, ActorSystem }
import org.apache.pekko.cluster.sharding.ClusterSharding
import org.apache.pekko.cluster.singleton.{ ClusterSingletonProxy, ClusterSingletonProxySettings }

object DistributedUtil {

  def getShardRef(shardRegionName: String, system: ActorSystem): ActorRef =
    ClusterSharding(system).shardRegion(shardRegionName)

  def createSingletonProxy(system: ActorSystem, name: String): ActorRef = {
    system.actorOf(ClusterSingletonProxy.props(s"/user/$name", ClusterSingletonProxySettings(system)),
      s"$name-${java.util.UUID.randomUUID()}-proxy")
  }
}
