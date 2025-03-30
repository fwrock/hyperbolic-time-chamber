package org.interscity.htc
package core.actor.manager.load.strategy

import core.actor.BaseActor

import org.apache.pekko.actor.ActorRef
import core.entity.state.DefaultState

import org.htc.protobuf.core.entity.actor.ActorDataSource
import org.htc.protobuf.core.entity.event.control.load.LoadDataSourceEvent

import scala.collection.mutable

abstract class LoadDataStrategy(timeManager: ActorRef)
    extends BaseActor[DefaultState](
      timeManager = timeManager,
      actorId = "load-data-strategy-manager",
      data = null,
      dependencies = mutable.Map.empty
    ) {
  protected def load(event: LoadDataSourceEvent): Unit
  protected def load(actorDataSource: ActorDataSource): Unit
}
