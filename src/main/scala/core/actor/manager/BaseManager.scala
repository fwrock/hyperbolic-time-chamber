package org.interscity.htc
package core.actor.manager

import core.actor.BaseActor
import core.entity.state.BaseState

import org.apache.pekko.actor.{ActorRef, Props}
import org.apache.pekko.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import org.interscity.htc.core.entity.actor.Identify

import scala.collection.mutable

abstract class BaseManager[T <: BaseState](
  actorId: String = null,
  timeManager: ActorRef = null,
  data: String = null,
  dependencies: mutable.Map[String, Identify] = mutable.Map[String, Identify]()
)(implicit m: Manifest[T])
    extends BaseActor[T](
      actorId = actorId,
      timeManager = timeManager,
      data = data,
      dependencies = dependencies
    ) {

  protected def createSingletonManager(
    manager: Props,
    name: String,
    terminateMessage: Any
  ): ActorRef =
    context.system.actorOf(
      ClusterSingletonManager.props(
        singletonProps = manager,
        terminationMessage = terminateMessage,
        settings = ClusterSingletonManagerSettings(context.system)
      ),
      name = name
    )

  protected def createSingletonProxy(name: String, suffix: String = ""): ActorRef =
    context.system.actorOf(
      ClusterSingletonProxy.props(
        singletonManagerPath = s"/user/$name",
        settings = ClusterSingletonProxySettings(context.system)
      ),
      name = s"$name$suffix-proxy"
    )
}
