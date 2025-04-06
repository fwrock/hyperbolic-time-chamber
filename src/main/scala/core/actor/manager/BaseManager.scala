package org.interscity.htc
package core.actor.manager

import core.actor.BaseActor
import core.entity.state.BaseState

import org.htc.protobuf.core.entity.actor.Dependency
import org.apache.pekko.actor.{ ActorRef, Props }
import org.interscity.htc.core.util.{ ActorCreatorUtil, DistributedUtil }

import scala.collection.mutable

abstract class BaseManager[T <: BaseState](
  actorId: String = null,
  timeManager: ActorRef = null,
  data: String = null,
  dependencies: mutable.Map[String, Dependency] = mutable.Map[String, Dependency]()
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
  ): ActorRef = ActorCreatorUtil.createSingletonManager(
    system = context.system,
    manager = manager,
    name = name,
    terminateMessage = terminateMessage
  )

  protected def createSingletonProxy(name: String): ActorRef =
    DistributedUtil.createSingletonProxy(context.system, name)
}
