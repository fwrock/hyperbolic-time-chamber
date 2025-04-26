package org.interscity.htc
package core.actor.manager.load

import core.actor.BaseActor

import org.apache.pekko.actor.{ ActorRef, Props }
import core.util.{ ActorCreatorUtil, IdUtil }
import core.entity.state.DefaultState
import core.util.ActorCreatorUtil.createPoolActor

import org.htc.protobuf.core.entity.event.control.load.StartCreationEvent
import org.interscity.htc.core.entity.actor.{ ActorSimulationCreation, Initialization }
import org.interscity.htc.core.entity.event.control.load.{ CreateActorsEvent, FinishCreationEvent, LoadDataCreatorRegisterEvent }
import org.interscity.htc.core.enumeration.CreationTypeEnum

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global

class CreatorPoolLoadData(
  loadDataManager: ActorRef,
  timeManager: ActorRef
) extends BaseActor[DefaultState](
      timeManager = timeManager,
      actorId = "creator-pool-load-data"
    ) {

  private val actorsBuffer: mutable.ListBuffer[ActorSimulationCreation] = mutable.ListBuffer()
  private val initializeData = mutable.Map[String, Initialization]()
  private val initializedAcknowledges = mutable.Map[String, Boolean]()
  private var amountActors = 0
  private var finishEventSent: Boolean = false

  private var actorsToCreate: List[ActorSimulationCreation] = List.empty

  private val CREATE_CHUNK_SIZE = 50
  private val DELAY_BETWEEN_CHUNKS = 500.milliseconds

  override def handleEvent: Receive = {
    case event: CreateActorsEvent  => handleCreateActors(event)
    case event: StartCreationEvent => handleStartCreation(event)
    case ProcessNextCreateChunk    => handleProcessNextCreateChunk()
  }

  private def handleCreateActors(event: CreateActorsEvent): Unit = {
    event.actors.foreach {
      actor =>
        actorsBuffer += actor
    }
    event.actorRef ! LoadDataCreatorRegisterEvent(actorRef = self)
  }

  private def handleStartCreation(event: StartCreationEvent): Unit = {
    logInfo(
      s"Received StartCreationEvent. Starting creation process for ${actorsBuffer.size} buffered actors."
    )

    finishEventSent = false

    actorsToCreate = actorsBuffer.distinctBy(_.actor.id).toList
    actorsBuffer.clear()
    amountActors = actorsToCreate.size

    if (actorsToCreate.nonEmpty) {
      self ! ProcessNextCreateChunk
    } else {
      logInfo("No actors to create for this creator.")
      checkAndSendFinish()
    }
  }

  private def handleProcessNextCreateChunk(): Unit = {
    val chunk = actorsToCreate.take(CREATE_CHUNK_SIZE)

    if (chunk.nonEmpty) {

      chunk.foreach {
        actorCreation =>
          createPoolActor(
            system = context.system,
            actorClassName = actorCreation.actor.typeActor,
            entityId = IdUtil.format(actorCreation.actor.id),
            poolConfiguration = actorCreation.actor.poolConfiguration,
            IdUtil.format(actorCreation.actor.id),
            null,
            timeManager,
            self,
            actorCreation.actor.data.content,
            CreationTypeEnum.PoolDistributed
          )
      }

      actorsToCreate = actorsToCreate.drop(chunk.size)

      if (actorsToCreate.nonEmpty) {
        context.system.scheduler.scheduleOnce(DELAY_BETWEEN_CHUNKS, self, ProcessNextCreateChunk)
      } else {
        logInfo("All actors created in this chunk.")
        checkAndSendFinish()
      }
    }
  }

  private def checkAndSendFinish(): Unit =
    if (!finishEventSent && actorsToCreate.isEmpty) {
      loadDataManager ! FinishCreationEvent(actorRef = self, amount = amountActors)
      finishEventSent = true
    }
}

object CreatorPoolLoadData {
  def props(
    loadDataManager: ActorRef,
    timeManager: ActorRef
  ): Props =
    Props(
      classOf[CreatorLoadData],
      loadDataManager,
      timeManager
    )
}
