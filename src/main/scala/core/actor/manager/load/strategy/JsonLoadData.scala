package org.interscity.htc
package core.actor.manager.load.strategy

import org.apache.pekko.actor.ActorRef
import core.util.JsonUtil

import org.htc.protobuf.core.entity.event.control.load.FinishLoadDataEvent
import org.interscity.htc.core.entity.actor.ActorSimulation
import org.interscity.htc.core.entity.configuration.ActorDataSource
import org.interscity.htc.core.entity.event.control.load.{ CreateActorsEvent, LoadDataSourceEvent }

import scala.compiletime.uninitialized

class JsonLoadData(timeManager: ActorRef) extends LoadDataStrategy(timeManager = timeManager) {

  private var managerRef: ActorRef = uninitialized
  private var creatorRef: ActorRef = uninitialized

  override def handleEvent: Receive = {
    case event: LoadDataSourceEvent => load(event)
  }

  override protected def load(event: LoadDataSourceEvent): Unit = {
    managerRef = event.managerRef
    creatorRef = event.creatorRef
    load(event.actorDataSource)
  }

  override protected def load(source: ActorDataSource): Unit = {
    log.info(s"Loading data of ${source.classType} from JSON")

    val content = JsonUtil.readJsonFile(source.dataSource.info("path").asInstanceOf[String])

    val actors = JsonUtil.fromJsonList[ActorSimulation](content)

    creatorRef ! CreateActorsEvent(actors)

    log.info(s"Data loaded from JSON")

    managerRef ! FinishLoadDataEvent(actorRef = getPath)
  }

}
