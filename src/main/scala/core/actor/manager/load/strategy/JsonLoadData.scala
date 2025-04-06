package org.interscity.htc
package core.actor.manager.load.strategy

import org.apache.pekko.actor.ActorRef
import core.util.JsonUtil

import org.htc.protobuf.core.entity.event.control.load.{ FinishLoadDataEvent, LoadDataCreatorRegisterEvent }
import org.interscity.htc.core.entity.actor.ActorSimulation
import org.interscity.htc.core.entity.configuration.ActorDataSource
import org.interscity.htc.core.entity.event.control.load.{ CreateActorsEvent, LoadDataSourceEvent }

import scala.compiletime.uninitialized
import scala.collection.mutable

class JsonLoadData(timeManager: ActorRef) extends LoadDataStrategy(timeManager = timeManager) {

  private var managerRef: ActorRef = uninitialized
  private var creatorRef: ActorRef = uninitialized

  private val batchSize: Int = 10
  private var totalBatchAmount: Int = 0
  private var currentBatchAmount: Int = 0
  private var isSentAllDataToCreator = false
  private val creators = mutable.Set[String]()

  override def handleEvent: Receive = {
    case event: LoadDataSourceEvent          => load(event)
    case event: LoadDataCreatorRegisterEvent => registerCreators(event)
  }

  override protected def load(event: LoadDataSourceEvent): Unit = {
    managerRef = event.managerRef
    creatorRef = event.creatorRef
    load(event.actorDataSource)
  }

  override protected def load(source: ActorDataSource): Unit = {
    logEvent(s"Loading data of ${source.classType} from JSON")

    val content = JsonUtil.readJsonFile(source.dataSource.info("path").asInstanceOf[String])

    val actors = JsonUtil.fromJsonList[ActorSimulation](content)

    logEvent(s"Loaded ${actors.size} actors from JSON")

    if (actors.size < batchSize) {
      totalBatchAmount += 1
      creatorRef ! CreateActorsEvent(actors)
    } else {
      actors.grouped(batchSize).foreach {
        batch =>
          totalBatchAmount += 1
          creatorRef ! CreateActorsEvent(batch)
      }
    }

    logEvent(s"Total batch amount: $totalBatchAmount")

    isSentAllDataToCreator = true

    sendFinishLoadDataEvent()

    logEvent(s"Data loaded from JSON")
  }

  private def registerCreators(event: LoadDataCreatorRegisterEvent): Unit = {
    currentBatchAmount += 1
    creators.add(event.actorRef)
    sendFinishLoadDataEvent()
  }

  private def sendFinishLoadDataEvent(): Unit = {
    logEvent(
      s"Current batch amount: $currentBatchAmount, Total batch amount: $totalBatchAmount, isSentAllDataToCreator: $isSentAllDataToCreator"
    )
    if (currentBatchAmount >= totalBatchAmount && isSentAllDataToCreator) {
      logEvent("All data loaded and sent to creators")
      managerRef ! FinishLoadDataEvent(
        actorRef = getPath,
        amount = creators.size,
        creators = creators.toList
      )
    }
  }
}
