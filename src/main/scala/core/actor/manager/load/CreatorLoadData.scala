package org.interscity.htc
package core.actor.manager.load

import core.actor.BaseActor

import org.apache.pekko.actor.{ActorRef, Props}
import core.util.{ActorCreatorUtil, IdUtil, StringUtil}
import core.entity.state.DefaultState
import core.util.ActorCreatorUtil.createShardRegion

import org.apache.pekko.cluster.sharding.ShardRegion
import org.htc.protobuf.core.entity.actor.Dependency
import org.htc.protobuf.core.entity.event.control.load.{InitializeEntityAckEvent, StartCreationEvent}
import org.interscity.htc.core.entity.actor.properties.{CreatorProperties, Properties}
import org.interscity.htc.core.entity.actor.{ActorSimulationCreation, Initialization}
import org.interscity.htc.core.entity.event.EntityEnvelopeEvent
import org.interscity.htc.core.entity.event.control.load.{CreateActorsEvent, FinishCreationEvent, InitializeEvent, ProcessNextCreateChunk, RetryPendingAcks}
import org.interscity.htc.core.entity.event.data.InitializeData

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global

class CreatorLoadData(
                       private val creatorProperties: CreatorProperties
                     ) extends BaseActor[DefaultState](
  properties = Properties(
    entityId = creatorProperties.entityId,
    resourceId = creatorProperties.shardId,
    creatorManager = creatorProperties.creatorManager,
    timeManager = creatorProperties.timeManager,
    reporters = creatorProperties.reporters,
    data = creatorProperties.data,
    actorType = creatorProperties.actorType
  )
) {

  private val initializeData = mutable.Map[String, mutable.Map[String, Initialization]]()
  private val initializedAcknowledges = mutable.Map[String, mutable.Seq[String]]()
  private var amountActors = 0

  private val actorsToCreate: mutable.Map[String, List[ActorSimulationCreation]] = mutable.Map.empty
  private val actorsBatches: mutable.Map[String, String] = mutable.Map.empty
  private val batchesLoad: mutable.Map[String, ActorRef] = mutable.Map.empty
  private val batchesToCreate: mutable.Map[String, Seq[ActorSimulationCreation]] = mutable.Map.empty

  private val CREATE_CHUNK_SIZE = 1000
  private val DELAY_BETWEEN_CHUNKS = 100.milliseconds

  private var retryTask: org.apache.pekko.actor.Cancellable = _

  override def onStart(): Unit = {
    super.onStart()
    retryTask = context.system.scheduler.scheduleWithFixedDelay(
      initialDelay = 5.seconds,
      delay = 5.seconds,
      receiver = self,
      message = RetryPendingAcks
    )
  }

  override def postStop(): Unit = {
    if (retryTask != null) retryTask.cancel()
    super.postStop()
  }

  override def handleEvent: Receive = {
    case event: CreateActorsEvent  => handleCreateActors(event)
    case event: StartCreationEvent => handleStartCreation(event)
    case event: ProcessNextCreateChunk => handleProcessNextCreateChunk(event.batchId)
    case event: ShardRegion.StartEntityAck => handleInitialize(event)
    case event: InitializeEntityAckEvent   => handleFinishInitialization(event)
    case RetryPendingAcks => handleRetryPendingAcks()

    // CORREÇÃO DO ERRO #2 (MatchError):
    // Ignora mensagens internas do Pekko Persistence/Snapshot que vazam do BaseActor
    case _ => // Ignora silenciamente RecoveryPermitGranted, LoadSnapshotResult, etc.
  }

  private def handleRetryPendingAcks(): Unit = {
    var pendingCount = 0
    initializeData.foreach { case (_, entitiesMap) =>
      entitiesMap.foreach { case (entityId, initialization) =>
        val shardRegion = getShardRef(StringUtil.getModelClassName(initialization.classType))
        shardRegion ! ShardRegion.StartEntity(entityId)
        pendingCount += 1
      }
    }
    // if (pendingCount > 0) logWarn(s"Watchdog: Reenviando StartEntity para $pendingCount atores.")
  }

  private def handleCreateActors(event: CreateActorsEvent): Unit = {
    batchesToCreate.put(event.id, event.actors)
    batchesLoad.put(event.id, event.actorRef)
    self ! StartCreationEvent(batchId = event.id)
  }

  private def handleStartCreation(event: StartCreationEvent): Unit = {
    actorsToCreate(event.batchId) = batchesToCreate
      .get(event.batchId)
      .map(_.distinctBy(_.actor.id))
      .getOrElse(Seq.empty)
      .toList

    amountActors += actorsToCreate(event.batchId).size

    if (actorsToCreate.nonEmpty) {
      self ! ProcessNextCreateChunk(batchId = event.batchId)
    } else {
      checkAndSendFinish(event.batchId)
    }
  }

  private def handleProcessNextCreateChunk(batchId: String): Unit = {
    // CORREÇÃO DO ERRO #1 (NoSuchElementException):
    // Usa .getOrElse para evitar crash se o batch já foi finalizado por uma race condition
    val currentActors = actorsToCreate.getOrElse(batchId, List.empty)
    val chunk = currentActors.take(CREATE_CHUNK_SIZE)

    if (chunk.nonEmpty) {
      chunk.foreach { actorCreation =>
        val initialization = Initialization(
          id = actorCreation.actor.id,
          resourceId = actorCreation.resourceId,
          classType = actorCreation.actor.typeActor,
          data = actorCreation.actor.data.content,
          timeManager = timeManager,
          creatorManager = self,
          reporters = reporters,
          dependencies = mutable.Map[String, Dependency]() ++= actorCreation.actor.dependencies
        )

        addInitializeData(actorCreation.actor.id, batchId, initialization)
        addToInitializedAcknowledges(batchId, actorCreation.actor.id)

        val shardRegion = createShardRegion(
          system = context.system,
          resourceId = actorCreation.resourceId,
          actorClassName = actorCreation.actor.typeActor,
          entityId = actorCreation.actor.id,
          timeManager = timeManager,
          creatorManager = self
        )

        shardRegion ! ShardRegion.StartEntity(actorCreation.actor.id)
      }

      // Atualiza a lista de forma segura
      actorsToCreate(batchId) = actorsToCreate(batchId).drop(chunk.size)

      if (actorsToCreate(batchId).nonEmpty) {
        context.system.scheduler.scheduleOnce(
          DELAY_BETWEEN_CHUNKS,
          self,
          ProcessNextCreateChunk(batchId = batchId)
        )
      }
    } else {
      // Se entrou aqui mas a lista está vazia ou nula, verifica se já acabou
      checkAndSendFinish(batchId)
    }
  }

  private def addInitializeData(entityId: String, batchId: String, initialization: Initialization): Unit =
    initializeData.get(batchId) match {
      case Some(data) => data.put(entityId, initialization)
      case None => initializeData.put(batchId, mutable.Map(entityId -> initialization))
    }

  private def addToInitializedAcknowledges(batchId: String, entityId: String): Unit = {
    initializedAcknowledges.get(batchId) match {
      case Some(acknowledge) => initializedAcknowledges.put(batchId, acknowledge :+ entityId)
      case None => initializedAcknowledges.put(batchId, mutable.Seq(entityId))
    }
    actorsBatches.put(entityId, batchId)
  }

  private def removeOfInitializedAcknowledges(batchId: String, entityId: String): Unit =
    initializedAcknowledges.get(batchId) match {
      case Some(acknowledge) => initializedAcknowledges.put(batchId, acknowledge.filter(_ != entityId))
      case None =>
    }

  // Handler CRÍTICO: Onde ocorria o key not found
  private def handleInitialize(event: ShardRegion.StartEntityAck): Unit = {
    // 1. Busca segura do batchId. Se não achar (Ack duplicado), retorna None.
    actorsBatches.get(event.entityId) match {
      case Some(batchId) =>
        // 2. Busca segura dos dados de inicialização
        initializeData.get(batchId).flatMap(_.get(event.entityId)) match {
          case Some(data) =>
            val initializeEvent = InitializeEvent(
              id = data.id,
              actorRef = self,
              data = InitializeData(
                data = data.data,
                resourceId = data.resourceId,
                timeManager = data.timeManager,
                creatorManager = data.creatorManager,
                reporters = data.reporters,
                dependencies = data.dependencies.map { case (_, dep) => IdUtil.format(dep.id) -> dep }
              )
            )

            getShardRef(StringUtil.getModelClassName(data.classType)) ! EntityEnvelopeEvent(
              entityId = event.entityId,
              event = initializeEvent
            )

            initializeData(batchId).remove(event.entityId)
            if (initializeData(batchId).isEmpty) initializeData.remove(batchId)

          case None =>
          // Ack duplicado ou já processado. Ignora.
        }
      case None =>
      // O batch já foi finalizado e limpo, mas chegou um Ack atrasado. Ignora.
    }
  }

  private def handleFinishInitialization(event: InitializeEntityAckEvent): Unit = {
    val batchId = actorsBatches.getOrElse(event.entityId, "")
    // Se batchId for vazio (não achou), o removeOf... lida com isso suavemente ou ignora
    if (batchId.nonEmpty) {
      removeOfInitializedAcknowledges(batchId, event.entityId)
      checkAndSendFinish(batchId)
    }
  }

  private def checkAndSendFinish(batchId: String): Unit = {
    // Verificação defensiva: Se as chaves não existirem nos mapas, considera como "vazio/sucesso"
    val hasPendingCreation = actorsToCreate.get(batchId).exists(_.nonEmpty)
    val hasPendingAcks = initializedAcknowledges.get(batchId).exists(_.nonEmpty)
    val hasPendingInitData = initializeData.contains(batchId)

    if (!hasPendingCreation && !hasPendingAcks && !hasPendingInitData) {
//      logInfo(s"Batch $batchId finalizado com sucesso. Notificando JsonLoadData.")

      batchesLoad.get(batchId).foreach { ref =>
        ref ! FinishCreationEvent(
          actorRef = self,
          batchId = batchId,
          amount = amountActors
        )
      }

      // Limpeza segura
      batchesLoad.remove(batchId)
      batchesToCreate.remove(batchId)
      actorsToCreate.remove(batchId)

      // Limpeza reversa do mapa actorsBatches (pode ser pesado, mas necessário para não vazar memória)
      // Se a performance cair aqui, podemos ignorar, pois actorsBatches é apenas String->String
      actorsBatches.filterInPlace((_, bId) => bId != batchId)
    }
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
