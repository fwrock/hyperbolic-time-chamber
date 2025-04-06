package org.interscity.htc
package core.util

import org.apache.pekko.actor.{ ActorNotFound, ActorRef, ActorSystem }
import org.apache.pekko.cluster.sharding.ClusterSharding
import org.apache.pekko.cluster.singleton.{ ClusterSingletonProxy, ClusterSingletonProxySettings }
import org.apache.pekko.util.Timeout

import java.util.concurrent.TimeUnit
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration.Duration

object DistributedUtil {

  def getShardRef(shardRegionName: String, system: ActorSystem): ActorRef =
    ClusterSharding(system).shardRegion(shardRegionName)

  def createSingletonProxy(system: ActorSystem, name: String): ActorRef = {
    val proxyName = s"$name-${System.nanoTime()}-proxy"
    val proxyPath = s"/user/$proxyName"
    Await.result(
      createSingletonProxy(name, proxyName, proxyPath, system),
      Duration.create(6, TimeUnit.SECONDS)
    )
  }

  private def createSingletonProxy(
    name: String,
    proxyName: String,
    proxyPath: String,
    system: ActorSystem
  ): Future[ActorRef] = {
    implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global
    implicit val timeout: Timeout = Timeout(5, TimeUnit.SECONDS)
    system
      .actorSelection(proxyPath)
      .resolveOne()
      .recover {
        case _: ActorNotFound =>
          system.actorOf(
            ClusterSingletonProxy.props(
              singletonManagerPath = s"/user/$name",
              settings = ClusterSingletonProxySettings(system)
            ),
            name = proxyName
          )
      }
  }
}
