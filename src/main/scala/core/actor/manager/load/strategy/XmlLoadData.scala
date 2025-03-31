package org.interscity.htc
package core.actor.manager.load.strategy

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.configuration.ActorDataSource
import org.interscity.htc.core.entity.event.control.load.LoadDataSourceEvent

class XmlLoadData(timeManager: ActorRef) extends LoadDataStrategy(timeManager = timeManager) {
  override def load(actorDataSource: ActorDataSource): Unit =
    println("Loading data from XML")

  override protected def load(event: LoadDataSourceEvent): Unit = ???
}
