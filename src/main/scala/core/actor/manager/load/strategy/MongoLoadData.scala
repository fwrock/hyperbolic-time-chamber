package org.interscity.htc
package core.actor.manager.load.strategy

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.ActorDataSource
import org.htc.protobuf.core.entity.event.control.load.LoadDataSourceEvent

class MongoLoadData(timeManager: ActorRef) extends LoadDataStrategy(timeManager = timeManager) {
  override def load(actorDataSource: ActorDataSource): Unit =
    println("Loading data from Mongo")

  override protected def load(event: LoadDataSourceEvent): Unit = ???
}
