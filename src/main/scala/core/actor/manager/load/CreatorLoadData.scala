package org.interscity.htc
package core.actor.manager.load

import core.actor.BaseActor

import org.apache.pekko.actor.{ActorRef, Props}
import core.util.ActorCreatorUtil
import core.entity.state.DefaultState
import core.util.ActorCreatorUtil.createShardRegion

import org.apache.pekko.cluster.sharding.ShardRegion
import org.htc.protobuf.core.entity.actor.Dependency
import org.htc.protobuf.core.entity.event.control.load.{InitializeEntityAckEvent, StartCreationEvent}
import org.interscity.htc.core.entity.actor.{ActorSimulationCreation, Initialization}
import org.interscity.htc.core.entity.event.EntityEnvelopeEvent
import org.interscity.htc.core.entity.event.control.load.{CreateActorsEvent, FinishCreationEvent, InitializeEvent, LoadDataCreatorRegisterEvent}
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
  actorId = "creator-load-data",
) {

  private val actorsBuffer: mutable.ListBuffer[ActorSimulationCreation] = mutable.ListBuffer()
  private val initializeData = mutable.Map[String, Initialization]()
  private var amountActors = 0
  private var finishEventSent: Boolean = false

  // Lista imutável dos atores que ainda precisam ter StartEntity enviado (para throttling)
  private var actorsToCreate: List[ActorSimulationCreation] = List.empty

  private val CREATE_CHUNK_SIZE = 50      // Quantos atores processar por vez
  private val DELAY_BETWEEN_CHUNKS = 500.milliseconds // Pausa entre os blocos

  override def handleEvent: Receive = {
    case event: CreateActorsEvent          => handleCreateActors(event)
    case event: StartCreationEvent         => handleStartCreation(event)
    case ProcessNextCreateChunk            => handleProcessNextCreateChunk() // Novo handler
    case event: ShardRegion.StartEntityAck => handleInitialize(event)
    case event: InitializeEntityAckEvent   => handleFinishInitialization(event)
  }

  private def handleCreateActors(event: CreateActorsEvent): Unit = {
    event.actors.foreach { actor =>
      actorsBuffer += actor
    }
    event.actorRef ! LoadDataCreatorRegisterEvent(actorRef = self)
  }

  // Inicia o processo de criação (disparado pelo LoadDataManager)
  private def handleStartCreation(event: StartCreationEvent): Unit = {
    logInfo(s"Received StartCreationEvent. Starting creation process for ${actorsBuffer.size} buffered actors.")

    // Garante estado limpo caso o ator seja reutilizado (pouco provável em pool)
    initializeData.clear()
    finishEventSent = false

    // Copia os atores acumulados para a lista de processamento e limpa o buffer
    // Usar distinctBy para o caso de receber atores duplicados dos loaders
    actorsToCreate = actorsBuffer.distinctBy(_.actor.id).toList
    actorsBuffer.clear()
    amountActors = actorsToCreate.size

    if (actorsToCreate.nonEmpty) {
      logInfo(s"Will create $amountActors unique actors in chunks of $CREATE_CHUNK_SIZE with ${DELAY_BETWEEN_CHUNKS} delay.")
      // Dispara o processamento do primeiro chunk
      self ! ProcessNextCreateChunk
    } else {
      logInfo("No actors to create for this creator.")
      // Se não há atores, talvez deva enviar FinishCreation imediatamente?
      // Ou a lógica em handleFinishInitialization (verificando listas vazias) já cobre isso?
      // Por segurança, vamos verificar e enviar se necessário:
      checkAndSendFinish()
    }
  }

  // Processa um bloco (chunk) de atores
  private def handleProcessNextCreateChunk(): Unit = {
    // Pega o próximo chunk da lista
    val chunk = actorsToCreate.take(CREATE_CHUNK_SIZE)

    if (chunk.nonEmpty) {
//      logEvent(s"Processing creation chunk of ${chunk.size} actors. Remaining: ${actorsToCreate.size - chunk.size}")

      // Processa cada ator no chunk atual
      chunk.foreach { actorCreation =>
        // Guarda os dados para a inicialização posterior (após StartEntityAck)
        initializeData(actorCreation.actor.id) = Initialization(
          id = actorCreation.actor.id,
          shardId = actorCreation.shardId,
          classType = actorCreation.actor.typeActor,
          data = actorCreation.actor.data.content,
          timeManager = timeManager,
          creatorManager = self, // Passa a própria ref para o ator entidade saber a quem responder
          dependencies = mutable.Map[String, Dependency]() ++= actorCreation.actor.dependencies
        )

        // Obtem/Cria a ShardRegion - ATENÇÃO: Otimizar chamada sharding.start!
        // Idealmente, sharding.start é chamado uma vez por tipo fora deste loop.
        // createShardRegion deve apenas fazer sharding.shardRegion(...) na maioria das vezes.
        val shardRegion = createShardRegion(
          system = context.system,
          shardId = actorCreation.shardId,
          actorClassName = actorCreation.actor.typeActor,
          entityId = actorCreation.actor.id, // entityId não é usado por createShardRegion, só actorClassName
          timeManager = timeManager,
          creatorManager = self
        )

        shardRegion ! ShardRegion.StartEntity(actorCreation.actor.id)
      }
      

      // Atualiza a lista de atores restantes
      actorsToCreate = actorsToCreate.drop(chunk.size)

      // Se ainda há atores, agenda o próximo chunk
      if (actorsToCreate.nonEmpty) {
        context.system.scheduler.scheduleOnce(DELAY_BETWEEN_CHUNKS, self, ProcessNextCreateChunk)
      } else {
        logInfo("All creation chunks have been scheduled.")
        // O processo de envio de StartEntity terminou, mas ainda precisamos esperar
        // todos os InitializeEntityAckEvent em handleFinishInitialization.
        // A lógica de checkAndSendFinish só será chamada a partir de handleFinishInitialization.
      }
    } else {
      logInfo("ProcessNextCreateChunk called but no actors remaining to create.")
    }
  }

  private def handleInitialize(event: ShardRegion.StartEntityAck): Unit = {
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
            dependencies = data.dependencies.map { case (_, dep) => dep.id -> dep }
          )
        )
        getShardRef(data.shardId) ! EntityEnvelopeEvent(
          entityId = event.entityId,
          event = initializeEvent
        )
        initializeData.remove(event.entityId)
      case None =>
        log.warning(s"Received StartEntityAck for ${event.entityId}, but no initialization data found (maybe already processed or error?).")
    }
  }

  // Chamado pelo ator entidade final após se inicializar
  private def handleFinishInitialization(event: InitializeEntityAckEvent): Unit = {
    // logEvent(s"Received InitializeEntityAckEvent for ${event.actorRef}") // TODO: Ack deveria identificar a entidade?
    // A lógica principal é verificar se todos os que esperávamos inicializar (mapa initializeData)
    // terminaram. O buffer actorsToCreate já deve estar vazio se todos os chunks foram processados.
    checkAndSendFinish()
  }

  // Verifica se todas as condições para finalizar foram atingidas
  private def checkAndSendFinish(): Unit = {
    // Condição: Nenhuma inicialização pendente E nenhum erro impediu o envio antes
    if (!finishEventSent && initializeData.isEmpty) {
      // Verifica também se a lista de criação foi esvaziada (garante que handleStartCreation foi chamado e processado)
      // Embora actorsToCreate deva estar vazio se initializeData está vazio (assumindo fluxo normal)
      // adicionar a checagem pode ser uma segurança extra.
      if (actorsToCreate.isEmpty) {
        logInfo(s"All $amountActors actors created and acknowledged initialization. Sending FinishCreationEvent.")
        loadDataManager ! FinishCreationEvent(actorRef = self, amount = amountActors)
        finishEventSent = true
        // Considerar parar o ator após enviar o evento final
        // context.stop(self)
      } else {
        // Estado inconsistente - não deveria acontecer se a lógica estiver correta
//        log.warning(s"initializeData is empty but actorsToCreate is not (${actorsToCreate.size} remaining). Cannot send FinishCreationEvent yet.")
      }
    } else if (finishEventSent) {
      // Apenas ignora, já finalizou
      logInfo(s"Ignoring checkAndSendFinish call, finish already sent.") // Log opcional
    } else {
      // Ainda esperando inicializações
      // logEvent(s"Check finish: Not yet. Initializations pending: ${initializeData.size}. Actors to create list size: ${actorsToCreate.size}") // Log opcional
    }
  }
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