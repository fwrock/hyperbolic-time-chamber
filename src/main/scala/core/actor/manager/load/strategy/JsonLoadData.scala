package org.interscity.htc
package core.actor.manager.load.strategy

import org.apache.pekko.actor.ActorRef
import core.util.{ IdUtil, JsonUtil }

import org.interscity.htc.core.entity.actor.{ ActorSimulation, ActorSimulationCreation }
import org.interscity.htc.core.entity.configuration.ActorDataSource
import org.interscity.htc.core.entity.event.control.load.{ CreateActorsEvent, FinishLoadDataEvent, LoadDataCreatorRegisterEvent, LoadDataSourceEvent }

import scala.compiletime.uninitialized
import scala.collection.mutable

class JsonLoadData(timeManager: ActorRef) extends LoadDataStrategy(timeManager = timeManager) {

  private var managerRef: ActorRef = uninitialized
  private var creatorRef: ActorRef = uninitialized

  private val batchSize: Int = 100
  private var totalBatchAmount: Int = 0
  private var currentBatchAmount: Int = 0
  private var isSentAllDataToCreator = false
  private var amountActors = 0L
  private val creators = mutable.Set[ActorRef]()

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
    val content = JsonUtil.readJsonFile(source.dataSource.info("path").asInstanceOf[String])

    var actors = JsonUtil.fromJsonList[ActorSimulation](content)

    val actorsToCreate = actors.map(
      actor =>
        ActorSimulationCreation(
          shardId = IdUtil.format(source.id),
          actor = actor.copy(id = IdUtil.format(actor.id))
        )
    )

    amountActors = actorsToCreate.size

    if (amountActors < batchSize) {
      totalBatchAmount += 1
      creatorRef ! CreateActorsEvent(actors = actorsToCreate, actorRef = self)
    } else {
      actorsToCreate.grouped(batchSize).foreach {
        batch =>
          totalBatchAmount += 1
          creatorRef ! CreateActorsEvent(actors = batch, actorRef = self)
      }
    }

    actors = null

    isSentAllDataToCreator = true

    sendFinishLoadDataEvent()
  }

  private def registerCreators(event: LoadDataCreatorRegisterEvent): Unit = {
    currentBatchAmount += 1
    creators.add(event.actorRef)
    sendFinishLoadDataEvent()
  }

  private def sendFinishLoadDataEvent(): Unit =
    if (currentBatchAmount >= totalBatchAmount && isSentAllDataToCreator) {
      logInfo(s"All data loaded and sent to creators: $amountActors")
      managerRef ! FinishLoadDataEvent(
        actorRef = self,
        amount = amountActors,
        creators = creators
      )
    }
}
