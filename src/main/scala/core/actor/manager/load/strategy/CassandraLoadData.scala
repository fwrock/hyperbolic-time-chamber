package org.interscity.htc
package core.actor.manager.load.strategy

import core.entity.configuration.ActorDataSource
import core.entity.event.control.load.LoadDataSourceEvent

import org.apache.pekko.actor.ActorRef

class CassandraLoadData(timeManager: ActorRef) extends LoadDataStrategy(timeManager = timeManager) {
  override def load(actorDataSource: ActorDataSource): Unit =
    println("Loading data from Cassandra")

  override protected def load(event: LoadDataSourceEvent): Unit = ???
}
