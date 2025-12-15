package org.interscity.htc
package core.actor.manager.load.strategy

import org.apache.pekko.actor.ActorRef
import core.util.{IdUtil, JsonStreamingUtil, JsonUtil}

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.actor.{ActorSimulation, ActorSimulationCreation}
import org.interscity.htc.core.entity.configuration.ActorDataSource
import org.interscity.htc.core.entity.event.control.load.*
import org.interscity.htc.core.enumeration.CreationTypeEnum.PoolDistributed
import com.fasterxml.jackson.core.JsonParser

import java.io.{BufferedInputStream, File, FileInputStream, InputStream}
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import scala.jdk.CollectionConverters.*

// ==========================================
// 3. Implementação do Ator
// ==========================================
class JsonLoadData(private val properties: Properties)
  extends LoadDataStrategy(properties = properties) {

  private implicit def ec: ExecutionContext = context.dispatcher
  // Dispatcher de IO (Obrigatório ter no application.conf)
  // Referências
  private var managerRef: ActorRef = _
  private var creatorRef: ActorRef = _
  private var creatorPoolRef: ActorRef = _

  // --- ESTADO MUTÁVEL PRIVADO (Nunca sai deste ator) ---
  private var currentInputStream: InputStream = _
  private var currentIterator: Iterator[ActorSimulation] = _
  private var sourceFilePath: String = _

  // Controle de Fluxo
  private val CHUNK_SIZE = 100
  private val activeBatches = mutable.Set[String]()
  private var totalLoadedActors = 0L
  private var sourceClassType: String = _
  private var sourceId: String = _
  private val creators = mutable.Set[ActorRef]()

  override def handleEvent: Receive = {
    case event: LoadDataSourceEvent => load(event)
    case StartLoadingFile           => openFileAndStart()
    case ProcessNextChunk           => readNextChunkAsync() // O motor do loop
    case ChunkLoaded(data)          => sendBatch(data)
    case CloseAndFinish             => finishLoading()
    case event: FinishCreationEvent => handleFinishCreation(event)
  }

  // 1. Inicialização
  override protected def load(event: LoadDataSourceEvent): Unit = {
    this.managerRef = event.managerRef
    this.creatorRef = event.creatorRef
    this.creatorPoolRef = event.creatorPoolRef
    load(event.actorDataSource)
  }

  override protected def load(source: ActorDataSource): Unit = {
    this.sourceClassType = source.classType
    this.sourceId = source.id
    this.sourceFilePath = source.dataSource.info("path").asInstanceOf[String]

    self ! StartLoadingFile
  }

  private def openFileAndStart(): Unit = {
    logInfo(s"Abrindo arquivo: $sourceFilePath")

    Future {
      val is = new BufferedInputStream(new FileInputStream(new File(sourceFilePath)))
      val (parser, iter) = JsonStreamingUtil.createParser(is)
      (is, iter)
    }.onComplete {
      case Success((is, iter)) =>
        this.synchronized {
          currentInputStream = is
          currentIterator = iter
        }
        self ! ProcessNextChunk

      case Failure(e) =>
        logError(s"Erro fatal ao abrir $sourceFilePath", e)
        self ! CloseAndFinish
    }
  }

  private def readNextChunkAsync(): Unit = {
    Future {
      this.synchronized {
        if (currentIterator != null && currentIterator.hasNext) {
          currentIterator.take(CHUNK_SIZE).toList
        } else {
          List.empty
        }
      }
    }.onComplete {
      case Success(actors) =>
        if (actors.nonEmpty) {
          self ! ChunkLoaded(actors)
        } else {
          self ! CloseAndFinish
        }
      case Failure(e) =>
        logError("Erro de I/O na leitura do JSON", e)
        self ! CloseAndFinish
    }
  }

  // 4. Processamento dos Dados
  private def sendBatch(actors: List[ActorSimulation]): Unit = {
    totalLoadedActors += actors.size

    val actorsToCreate = actors.map(actor =>
      ActorSimulationCreation(
        resourceId = IdUtil.format(sourceId),
        actor = actor.copy(id = IdUtil.format(actor.id))
      )
    )

    val (poolDistributed, loadBalanced) = actorsToCreate.partition(_.actor.creationType == PoolDistributed)

    if (loadBalanced.nonEmpty) {
      val batchId = UUID.randomUUID().toString
      activeBatches.add(batchId)
      creators.add(creatorRef)
      creatorRef ! CreateActorsEvent(id = batchId, actors = loadBalanced, actorRef = self)
    }

    if (poolDistributed.nonEmpty) {
      val batchId = UUID.randomUUID().toString
      activeBatches.add(batchId)
      creators.add(creatorPoolRef)
      creatorPoolRef ! CreateActorsEvent(id = batchId, actors = poolDistributed, actorRef = self)
    }

    if (activeBatches.isEmpty) {
      self ! ProcessNextChunk
    }
  }

  private def handleFinishCreation(event: FinishCreationEvent): Unit = {
    activeBatches.remove(event.batchId)

    if (activeBatches.isEmpty) {
      self ! ProcessNextChunk
    }
  }

  private def finishLoading(): Unit = {
    logInfo(s"Fim do arquivo. Total: $totalLoadedActors")

    if (currentInputStream != null) {
      try currentInputStream.close() catch { case _: Exception => }
      currentInputStream = null
      currentIterator = null
    }

    managerRef ! FinishLoadDataEvent(
      actorRef = self,
      amount = totalLoadedActors,
      actorClassType = sourceClassType,
      creators = creators
    )
  }

  // Garante fechamento se o ator for parado abruptamente
  override def postStop(): Unit = {
    if (currentInputStream != null) {
      try currentInputStream.close() catch { case _: Exception => }
    }
    super.postStop()
  }
}