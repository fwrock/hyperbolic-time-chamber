package org.interscity.htc
package core.actor.manager.load.strategy

import org.apache.pekko.actor.ActorRef
import core.util.JsonUtil

import org.htc.protobuf.core.entity.actor.{ActorDataSource, ActorSimulation}
import org.htc.protobuf.core.entity.event.control.load.{CreateActorsEvent, FinishLoadDataEvent, LoadDataSourceEvent}

import scala.compiletime.uninitialized

class JsonLoadData(timeManager: ActorRef) extends LoadDataStrategy(timeManager = timeManager) {

  private var managerRef: ActorRef = uninitialized
  private var creatorRef: ActorRef = uninitialized

  override def handleEvent: Receive = {
    case event: LoadDataSourceEvent => load(event)
  }

  override protected def load(event: LoadDataSourceEvent): Unit = {
    managerRef = getActorRef(event.managerRef)
    creatorRef = getActorRef(event.creatorRef)
    event.actorDataSource.foreach(load)
  }

  override protected def load(source: ActorDataSource): Unit = {
    log.info(s"Loading data of ${source.classType} from JSON")

    val content = JsonUtil.readJsonFile(source.dataSource.get.info("path").asInstanceOf[String])

    val actors = JsonUtil.fromJsonList[ActorSimulation](content)

    creatorRef ! CreateActorsEvent(actors)

    log.info(s"Data loaded from JSON")

    managerRef ! FinishLoadDataEvent(actorRef = getPath)
  }

}
