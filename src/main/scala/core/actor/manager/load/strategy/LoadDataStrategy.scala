package org.interscity.htc
package core.actor.manager.load.strategy

import core.entity.configuration.ActorDataSource
import core.actor.BaseActor

import org.apache.pekko.actor.ActorRef
import core.entity.event.control.load.LoadDataSourceEvent
import core.entity.state.DefaultState

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
