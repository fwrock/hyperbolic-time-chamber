package org.interscity.htc
package core.util

import org.apache.pekko.actor.{ ActorRef, ActorSystem }
import org.apache.pekko.cluster.sharding.ClusterSharding
import org.apache.pekko.cluster.singleton.{ ClusterSingletonProxy, ClusterSingletonProxySettings }

import java.util.UUID

object DistributedUtil {

  def getShardRef(shardRegionName: String, system: ActorSystem): ActorRef =
    ClusterSharding(system).shardRegion(shardRegionName)

  def createSingletonProxy(
    system: ActorSystem,
    name: String,
    suffix: String = UUID.randomUUID().toString
  ): ActorRef =
    system.actorOf(
      ClusterSingletonProxy.props(
        singletonManagerPath = s"/user/$name",
        settings = ClusterSingletonProxySettings(system)
      ),
      name = s"$name-$suffix-proxy"
    )
}
