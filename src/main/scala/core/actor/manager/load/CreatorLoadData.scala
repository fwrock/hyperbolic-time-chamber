package org.interscity.htc
package core.actor.manager.load

import core.actor.BaseActor

import org.apache.pekko.actor.{ ActorRef, Props }
import core.util.{ ActorCreatorUtil, JsonUtil }
import core.entity.state.DefaultState
import core.util.ActorCreatorUtil.createShardRegion

import org.apache.pekko.cluster.sharding.ShardRegion
import org.htc.protobuf.core.entity.actor.{ Dependency, Identify }
import org.htc.protobuf.core.entity.event.control.execution.RegisterActorEvent
import org.htc.protobuf.core.entity.event.control.load.{ InitializeEntityAckEvent, StartCreationEvent }
import org.interscity.htc.core.entity.actor.{ ActorSimulationCreation, Initialization }
import org.interscity.htc.core.entity.event.EntityEnvelopeEvent
import org.interscity.htc.core.entity.event.control.load.{ CreateActorsEvent, FinishCreationEvent, InitializeEvent, LoadDataCreatorRegisterEvent }
import org.interscity.htc.core.entity.event.data.InitializeData

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global
case object ProcessNextCreateChunk

class CreatorLoadData(
  loadDataManager: ActorRef,
  timeManager: ActorRef
) extends BaseActor[DefaultState](
      timeManager = timeManager,
      actorId = "creator-load-data"
    ) {

  private val actorsBuffer: mutable.ListBuffer[ActorSimulationCreation] = mutable.ListBuffer()
  private val initializeData = mutable.Map[String, Initialization]()
  private val initializedAcknowledges = mutable.Map[String, Boolean]()
  private var amountActors = 0
  private var finishEventSent: Boolean = false

  private var actorsToCreate: List[ActorSimulationCreation] = List.empty

  private val CREATE_CHUNK_SIZE = 50 // Quantos atores processar por vez
  private val DELAY_BETWEEN_CHUNKS = 500.milliseconds // Pausa entre os blocos

  override def handleEvent: Receive = {
    case event: CreateActorsEvent          => handleCreateActors(event)
    case event: StartCreationEvent         => handleStartCreation(event)
    case ProcessNextCreateChunk            => handleProcessNextCreateChunk() // Novo handler
    case event: ShardRegion.StartEntityAck => handleInitialize(event)
    case event: InitializeEntityAckEvent   => handleFinishInitialization(event)
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

    initializeData.clear()
    finishEventSent = false

    actorsToCreate = actorsBuffer.distinctBy(_.actor.id).toList
    actorsBuffer.clear()
    amountActors = actorsToCreate.size

    if (actorsToCreate.nonEmpty) {
      logInfo(
        s"Will create $amountActors unique actors in chunks of $CREATE_CHUNK_SIZE with ${DELAY_BETWEEN_CHUNKS} delay."
      )
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
          initializeData(actorCreation.actor.id) = Initialization(
            id = actorCreation.actor.id,
            shardId = actorCreation.shardId,
            classType = actorCreation.actor.typeActor,
            data = actorCreation.actor.data.content,
            timeManager = timeManager,
            creatorManager = self,
            dependencies = mutable.Map[String, Dependency]() ++= actorCreation.actor.dependencies
          )

          initializedAcknowledges.put(actorCreation.actor.id, false)

          val shardRegion = createShardRegion(
            system = context.system,
            shardId = actorCreation.shardId,
            actorClassName = actorCreation.actor.typeActor,
            entityId = actorCreation.actor.id,
            timeManager = timeManager,
            creatorManager = self
          )

          shardRegion ! ShardRegion.StartEntity(actorCreation.actor.id)
      }

      actorsToCreate = actorsToCreate.drop(chunk.size)

      if (actorsToCreate.nonEmpty) {
        context.system.scheduler.scheduleOnce(DELAY_BETWEEN_CHUNKS, self, ProcessNextCreateChunk)
      } else {
        logInfo("All creation chunks have been scheduled.")
      }
    } else {
      logInfo("ProcessNextCreateChunk called but no actors remaining to create.")
    }
  }

  private def handleInitialize(event: ShardRegion.StartEntityAck): Unit =
    initializeData.get(event.entityId) match {
      case Some(data) =>
        val initializeEvent = InitializeEvent(
          id = data.id,
          actorRef = self,
          data = InitializeData(
            data = data.data,
            shardId = data.shardId,
            timeManager = data.timeManager,
            creatorManager = data.creatorManager,
            dependencies = data.dependencies.map {
              case (_, dep) => dep.id -> dep
            }
          )
        )
        getShardRef(data.shardId) ! EntityEnvelopeEvent(
          entityId = event.entityId,
          event = initializeEvent
        )
        initializeData.remove(event.entityId)
      case None =>
        log.warning(
          s"Received StartEntityAck for ${event.entityId}, but no initialization data found (maybe already processed or error?)."
        )
    }

  private def handleFinishInitialization(event: InitializeEntityAckEvent): Unit = {
    logInfo(s"Received InitializeEntityAck for ${event.entityId}.")
    initializedAcknowledges.put(event.entityId, true)
    checkAndSendFinish()
  }

  private def checkAndSendFinish(): Unit =
    if (!finishEventSent && initializeData.isEmpty) {
      if (actorsToCreate.isEmpty && initializedAcknowledges.values.forall(_ == true)) {
        logInfo(
          s"All $amountActors actors created and acknowledged initialization. Sending FinishCreationEvent."
        )
        loadDataManager ! FinishCreationEvent(actorRef = self, amount = amountActors)
        finishEventSent = true
      } else {}
    } else if (finishEventSent) {
      logInfo(s"Ignoring checkAndSendFinish call, finish already sent.") // Log opcional
    } else {}
}

object CreatorLoadData {
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
