package org.interscity.htc
package core.actor.manager.load.strategy

import org.apache.pekko.actor.ActorRef
import core.util.{ IdUtil, JsonUtil }

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.actor.{ ActorSimulation, ActorSimulationCreation }
import org.interscity.htc.core.entity.configuration.ActorDataSource
import org.interscity.htc.core.enumeration.CreationTypeEnum.{ LoadBalancedDistributed, PoolDistributed }
import org.interscity.htc.core.entity.event.control.load.{ CreateActorsEvent, FinishCreationEvent, FinishLoadDataEvent, LoadDataSourceEvent, ProcessBatchesEvent }

import java.io.{ BufferedInputStream, File, FileInputStream }
import java.util.UUID
import scala.compiletime.uninitialized
import scala.collection.mutable
import scala.util.Using

class JsonLoadData(private val properties: Properties)
    extends LoadDataStrategy(properties = properties) {

  private var managerRef: ActorRef = uninitialized
  private var creatorRef: ActorRef = uninitialized
  private var creatorPoolRef: ActorRef = uninitialized

  private val batchSize: Int = 100
  private var totalBatchAmount: Int = 0
  private var currentBatchAmount: Int = 0
  private var isSentAllDataToCreator = false
  private var amountActors = 0L
  private val creators = mutable.Set[ActorRef]()
  private var shardBatches: mutable.Queue[Seq[ActorSimulationCreation]] = uninitialized
  private var poolBatches: mutable.Queue[Seq[ActorSimulationCreation]] = uninitialized
  private var sourceClassType: String = uninitialized

  private val processBatchControl = mutable.Map[String, Boolean]()

  override def handleEvent: Receive = {
    case event: LoadDataSourceEvent => 
      logInfo(s"Received LoadDataSourceEvent for ${event.actorDataSource.dataSource}")
      load(event)
    case _: ProcessBatchesEvent     => 
      logInfo("Received ProcessBatchesEvent")
      handleProcessBatches()
    case event: FinishCreationEvent => 
      logInfo(s"Received FinishCreationEvent for batch ${event.batchId}")
      handleFinishCreation(event)
  }

  override protected def load(event: LoadDataSourceEvent): Unit = {
    logInfo("Starting load process")
    managerRef = event.managerRef
    creatorRef = event.creatorRef
    creatorPoolRef = event.creatorPoolRef
    load(event.actorDataSource)
  }

  override protected def load(source: ActorDataSource): Unit = {
    sourceClassType = source.classType
    val filePath = source.dataSource.info("path").asInstanceOf[String]

    val actors: List[ActorSimulation] =
      Using(new BufferedInputStream(new FileInputStream(new File(filePath)))) {
        inputStream =>
          JsonUtil.fromJsonListStream[ActorSimulation](inputStream)
      }.get

    val actorsToCreate = actors.map(
      actor =>
        ActorSimulationCreation(
          resourceId = IdUtil.format(source.id),
          actor = actor.copy(id = IdUtil.format(actor.id))
        )
    )

    amountActors = actorsToCreate.size

    val shardedActors = actorsToCreate.filter(
      a =>
        a.actor.creationType == null ||
          a.actor.creationType == LoadBalancedDistributed
    )

    shardBatches = createBatches(shardedActors)

    val poolActors = actorsToCreate.filter(
      a => a.actor.creationType == PoolDistributed
    )

    poolBatches = createBatches(poolActors)

    totalBatchAmount = shardBatches.size + poolBatches.size

    self ! ProcessBatchesEvent()

    sendFinishLoadDataEvent()
  }

  private def handleProcessBatches(): Unit = {
    logInfo(s"Processing batches: shardBatches=${shardBatches.size}, poolBatches=${poolBatches.size}")
    if (shardBatches.nonEmpty) {
      val batch = shardBatches.dequeue()
      logInfo(s"Sending shard batch with ${batch.size} actors")
      sendToCreator(creatorRef, batch)
    } else if (poolBatches.nonEmpty) {
      val batch = poolBatches.dequeue()
      logInfo(s"Sending pool batch with ${batch.size} actors")
      sendToCreator(creatorPoolRef, batch)
    }
    logInfo(s"Calling sendFinishLoadDataEvent, processBatchControl=${processBatchControl}")
    sendFinishLoadDataEvent()
  }

  private def handleFinishCreation(event: FinishCreationEvent): Unit = {
    processBatchControl.put(event.batchId, true)
    handleProcessBatches()
  }

  private def sendToCreator(
    creator: ActorRef,
    actorsToCreate: Seq[ActorSimulationCreation]
  ): Unit = {
    // 🎲 Usar UUID determinístico para batch ID
    val batchId = try {
      core.actor.manager.RandomSeedManager.deterministicUUID()
    } catch {
      case _: Exception => UUID.randomUUID().toString
    }
    processBatchControl.put(batchId, false)
    logInfo(s"Sending CreateActorsEvent to creator with ${actorsToCreate.size} actors, batchId=$batchId")
    creator ! CreateActorsEvent(id = batchId, actors = actorsToCreate, actorRef = self)
  }

  private def createBatches(
    actorsToCreate: Seq[ActorSimulationCreation]
  ): mutable.Queue[Seq[ActorSimulationCreation]] = {
    val batches = if (actorsToCreate.size < batchSize) {
      if (actorsToCreate.nonEmpty) {
        Seq(actorsToCreate)
      } else {
        Seq()
      }
    } else {
      actorsToCreate.grouped(batchSize).toSeq
    }
    mutable.Queue(batches: _*)
  }

  private def sendFinishLoadDataEvent(): Unit = {
    val allBatchesProcessed = processBatchControl.values.forall(_.self == true)
    logInfo(s"Checking finish conditions: shardBatches.isEmpty=${shardBatches.isEmpty}, poolBatches.isEmpty=${poolBatches.isEmpty}, allBatchesProcessed=$allBatchesProcessed")
    if (
      shardBatches.isEmpty && poolBatches.isEmpty && allBatchesProcessed
    ) {
      logInfo(s"Sending FinishLoadDataEvent with amount=$amountActors")
      managerRef ! FinishLoadDataEvent(
        actorRef = self,
        amount = amountActors,
        actorClassType = sourceClassType,
        creators = creators
      )
    }
  }
}
