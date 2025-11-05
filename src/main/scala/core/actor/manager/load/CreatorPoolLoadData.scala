package org.interscity.htc
package core.actor.manager.load

import core.actor.BaseActor

import org.apache.pekko.actor.{ ActorRef, Props }
import core.util.{ ActorCreatorUtil, IdUtil }
import core.entity.state.DefaultState
import core.util.ActorCreatorUtil.createPoolActor

import org.htc.protobuf.core.entity.actor.Dependency
import org.htc.protobuf.core.entity.event.control.load.{ StartCreationEvent, StartEntityAckEvent }
import org.interscity.htc.core.entity.actor.properties.{ CreatorProperties, Properties }
import org.interscity.htc.core.entity.actor.{ ActorSimulationCreation, Initialization }
import org.interscity.htc.core.entity.event.control.load.{ CreateActorsEvent, FinishCreationEvent, ProcessNextCreateChunk }
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.core.enumeration.CreationTypeEnum.PoolDistributed

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global

class CreatorPoolLoadData(
  private val creatorProperties: CreatorProperties
) extends BaseActor[DefaultState](
      properties = Properties(
        entityId = creatorProperties.entityId,
        resourceId = creatorProperties.shardId,
        data = creatorProperties.data
      )
    ) {

  // Fields needed for actor creation (not for simulation)
  private val timeManagers: mutable.Map[String, ActorRef] = creatorProperties.timeManagers
  private val creatorManager: ActorRef = creatorProperties.creatorManager
  private val reporters: mutable.Map[org.interscity.htc.core.enumeration.ReportTypeEnum, ActorRef] = 
    creatorProperties.reporters

  private val actorsBuffer: mutable.ListBuffer[ActorSimulationCreation] = mutable.ListBuffer()
  private val initializeData = mutable.Map[String, Initialization]()
  private val startedAcknowledges = mutable.Map[String, mutable.Seq[String]]()
  private var amountActors = 0
  private var finishEventSent: Boolean = false

  private val actorsToCreate: mutable.Map[String, List[ActorSimulationCreation]] = mutable.Map.empty

  private val actorsBatches: mutable.Map[String, String] = mutable.Map.empty

  private val batchesLoad: mutable.Map[String, ActorRef] = mutable.Map.empty

  private val batchesToCreate: mutable.Map[String, Seq[ActorSimulationCreation]] =
    mutable.Map[String, Seq[ActorSimulationCreation]]()
  private var currentBatch: String = _

  private val CREATE_CHUNK_SIZE = 50
  private val DELAY_BETWEEN_CHUNKS = 500.milliseconds

  override def handleEvent: Receive = {
    case event: CreateActorsEvent      => handleCreateActors(event)
    case event: StartCreationEvent     => handleStartCreation(event)
    case event: StartEntityAckEvent    => handleFinishStart(event)
    case event: ProcessNextCreateChunk => handleProcessNextCreateChunk(event.batchId)
  }

  private def handleCreateActors(event: CreateActorsEvent): Unit = {
    batchesToCreate.put(event.id, event.actors)
    batchesLoad.put(event.id, event.actorRef)

    self ! StartCreationEvent(batchId = event.id)
  }

  private def handleStartCreation(event: StartCreationEvent): Unit = {
    finishEventSent = false

    actorsToCreate(event.batchId) = batchesToCreate
      .get(event.batchId)
      .map(_.distinctBy(_.actor.id))
      .getOrElse(Seq.empty)
      .toList

    amountActors = actorsToCreate.size

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
          logInfo(
            s"Creating actor $actorCreation"
          )
          val actor = createPoolActor(
            system = context.system,
            actorClassName = actorCreation.actor.typeActor,
            entityId = IdUtil.format(actorCreation.actor.id),
            poolConfiguration = actorCreation.actor.poolConfiguration,
            resourceId = IdUtil.format(actorCreation.resourceId),
            timeManagers = timeManagers,
            creatorManager = self,
            reporters = creatorProperties.reporters,
            data = actorCreation.actor.data.content,
            dependencies = mutable.Map[String, Dependency](),
            creationType = PoolDistributed
          )

          addToInitializedAcknowledges(batchId, actorCreation.actor.id)
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
    startedAcknowledges.get(batchId) match {
      case Some(acknowledge) =>
        startedAcknowledges.put(batchId, acknowledge :+ entityId)
      case None =>
        startedAcknowledges.put(batchId, mutable.Seq(entityId))
    }
    actorsBatches.put(entityId, batchId)
  }

  private def removeOfStartedAcknowledges(batchId: String, entityId: String): Unit =
    startedAcknowledges.get(batchId) match {
      case Some(acknowledge) =>
        startedAcknowledges.put(batchId, acknowledge.filter(_ != entityId))
      case None =>
    }

  private def handleFinishStart(event: StartEntityAckEvent): Unit = {
    val batchId = actorsBatches.getOrElse(event.entityId, "")
    removeOfStartedAcknowledges(batchId, event.entityId)
    checkAndSendFinish(batchId)
  }

  private def checkAndSendFinish(batchId: String): Unit =
    if (
      actorsToCreate(batchId).isEmpty && (!startedAcknowledges.contains(
        batchId
      ) || startedAcknowledges(batchId).isEmpty)
    ) {
//      logInfo(
//        s"All pool actors created and acknowledged initialization from $batchId. Sending FinishCreationEvent."
//      )
      batchesLoad(batchId) ! FinishCreationEvent(
        actorRef = self,
        batchId = batchId,
        amount = amountActors
      )
      batchesToCreate.remove(batchId)
    }
}

object CreatorPoolLoadData {
  def props(
    properties: CreatorProperties
  ): Props =
    Props(
      classOf[CreatorPoolLoadData],
      properties
    )
}
