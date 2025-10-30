package org.interscity.htc
package core.actor.manager.load.strategy

import core.actor.BaseActor
import core.entity.state.DefaultState

import org.interscity.htc.core.entity.actor.properties.{DefaultBaseProperties, Properties}
import org.interscity.htc.core.entity.configuration.ActorDataSource
import org.interscity.htc.core.entity.event.control.load.LoadDataSourceEvent

abstract class LoadDataStrategy(
  private val properties: DefaultBaseProperties
) extends BaseActor[DefaultState](
      properties = properties
    ) {
  protected def load(event: LoadDataSourceEvent): Unit
  protected def load(actorDataSource: ActorDataSource): Unit
}
