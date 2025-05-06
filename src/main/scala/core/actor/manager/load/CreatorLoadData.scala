package org.interscity.htc
package core.actor.manager.load

import core.actor.BaseActor

import org.apache.pekko.actor.{ ActorRef, Props }
import core.util.{ ActorCreatorUtil, IdUtil }
import core.entity.state.DefaultState
import core.util.ActorCreatorUtil.createShardRegion

import org.apache.pekko.cluster.sharding.ShardRegion
import org.htc.protobuf.core.entity.actor.Dependency
import org.htc.protobuf.core.entity.event.control.load.{ InitializeEntityAckEvent, StartCreationEvent }
import org.interscity.htc.core.entity.actor.properties.{ CreatorProperties, Properties }
import org.interscity.htc.core.entity.actor.{ ActorSimulationCreation, Initialization }
import org.interscity.htc.core.entity.event.EntityEnvelopeEvent
import org.interscity.htc.core.entity.event.control.load.{ CreateActorsEvent, FinishCreationEvent, InitializeEvent, ProcessNextCreateChunk }
import org.interscity.htc.core.entity.event.data.InitializeData

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global

class CreatorLoadData(
  private val creatorProperties: CreatorProperties
) extends BaseActor[DefaultState](
      properties = Properties(
        entityId = creatorProperties.entityId,
        shardId = creatorProperties.shardId,
        creatorManager = creatorProperties.creatorManager,
        timeManager = creatorProperties.timeManager,
        reporters = creatorProperties.reporters,
        data = creatorProperties.data,
        actorType = creatorProperties.actorType
      )
    ) {

  private val actorsBuffer: mutable.ListBuffer[ActorSimulationCreation] = mutable.ListBuffer()
  private val initializeData = mutable.Map[String, Initialization]()
  private val initializedAcknowledges = mutable.Map[String, mutable.Seq[String]]()
  private var amountActors = 0

  private val actorsToCreate: mutable.Map[String, List[ActorSimulationCreation]] = mutable.Map.empty

  private val actorsBatches: mutable.Map[String, String] = mutable.Map.empty

  private val batchesToCreate: mutable.Map[String, (ActorRef, Seq[ActorSimulationCreation])] =
    mutable.Map[String, (ActorRef, Seq[ActorSimulationCreation])]()
  private var currentBatch: String = _

  private val CREATE_CHUNK_SIZE = 500
  private val DELAY_BETWEEN_CHUNKS = 1000.milliseconds

  override def handleEvent: Receive = {
    case event: CreateActorsEvent  => handleCreateActors(event)
    case event: StartCreationEvent => handleStartCreation(event)
    case event: ProcessNextCreateChunk =>
      handleProcessNextCreateChunk(event.batchId) // Novo handler
    case event: ShardRegion.StartEntityAck => handleInitialize(event)
    case event: InitializeEntityAckEvent   => handleFinishInitialization(event)
  }

  private def handleCreateActors(event: CreateActorsEvent): Unit = {
    batchesToCreate.put(event.id, (event.actorRef, event.actors))

    self ! StartCreationEvent(batchId = event.id)
  }

  private def handleStartCreation(event: StartCreationEvent): Unit = {
    logInfo(
      s"Received StartCreationEvent. Starting creation process for ${actorsBuffer.size} buffered actors."
    )

    actorsToCreate(event.batchId) = batchesToCreate
      .get(event.batchId)
      .map(_._2.distinctBy(_.actor.id))
      .getOrElse(Seq.empty)
      .toList

    amountActors += actorsToCreate(event.batchId).size

    if (actorsToCreate.nonEmpty) {
      self ! ProcessNextCreateChunk(batchId = event.batchId)
    } else {
      logInfo("No actors to create for this creator.")
      checkAndSendFinish(event.batchId)
    }
  }

  private def handleProcessNextCreateChunk(batchId: String): Unit = {
    val chunk = actorsToCreate(batchId).take(CREATE_CHUNK_SIZE)

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
            reporters = reporters,
            dependencies = mutable.Map[String, Dependency]() ++= actorCreation.actor.dependencies
          )

          addToInitializedAcknowledges(batchId, actorCreation.actor.id)

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

      actorsToCreate(batchId) = actorsToCreate(batchId).drop(chunk.size)

      if (actorsToCreate.nonEmpty) {
        context.system.scheduler.scheduleOnce(
          DELAY_BETWEEN_CHUNKS,
          self,
          ProcessNextCreateChunk(batchId = batchId)
        )
      } else {
        logInfo("All actors created in this chunk.")
        checkAndSendFinish(batchId)
      }
    }
  }

  private def addToInitializedAcknowledges(batchId: String, entityId: String): Unit = {
    initializedAcknowledges.get(batchId) match {
      case Some(acknowledge) =>
        initializedAcknowledges.put(batchId, acknowledge :+ entityId)
      case None =>
        initializedAcknowledges.put(batchId, mutable.Seq(entityId))
    }
    actorsBatches.put(entityId, batchId)
  }

  private def removeOfInitializedAcknowledges(batchId: String, entityId: String): Unit =
    initializedAcknowledges.get(batchId) match {
      case Some(acknowledge) =>
        initializedAcknowledges.put(batchId, acknowledge.filter(_ != entityId))
      case None =>
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
            reporters = data.reporters,
            dependencies = data.dependencies.map {
              case (_, dep) => IdUtil.format(dep.id) -> dep
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
    val batchId = actorsBatches.getOrElse(event.entityId, "")
    removeOfInitializedAcknowledges(batchId, event.entityId)
    checkAndSendFinish(batchId)
  }

  private def checkAndSendFinish(batchId: String): Unit =
    if (
      actorsToCreate(batchId).isEmpty && (!initializedAcknowledges.contains(
        batchId
      ) || initializedAcknowledges(batchId).isEmpty)
    ) {
      logInfo(
        s"All $amountActors actors created and acknowledged initialization. Sending FinishCreationEvent."
      )
      batchesToCreate(batchId)._1 ! FinishCreationEvent(
        actorRef = self,
        batchId = batchId,
        amount = amountActors
      )
      batchesToCreate.remove(batchId)
    }
}

object CreatorLoadData {
  def props(
    creatorProperties: CreatorProperties
  ): Props =
    Props(
      classOf[CreatorLoadData],
      creatorProperties
    )
}
