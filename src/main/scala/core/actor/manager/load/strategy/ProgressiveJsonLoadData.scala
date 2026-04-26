package org.interscity.htc
package core.actor.manager.load.strategy

import org.apache.pekko.actor.ActorRef
import core.util.{ IdUtil, JsonStreamingUtil, TickIndexUtil }

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.actor.{ ActorSimulation, ActorSimulationCreation }
import org.interscity.htc.core.entity.configuration.ActorDataSource
import org.interscity.htc.core.entity.event.control.load.*
import org.interscity.htc.core.enumeration.CreationTypeEnum.PoolDistributed
import org.interscity.htc.core.util.TickIndexUtil.LightTickIndex

import java.io.{ BufferedInputStream, File, FileInputStream, InputStream }
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

/**
 * Progressive JSON data loader using lightweight tick indexing and chunked file streaming.
 *
 * Two-phase approach that keeps memory usage bounded:
 *
 * Phase 1 — Light index:
 *   Streams through JSON, extracts only startTick from each actor, builds a
 *   counts-only map (tick → count). No ActorSimulation objects are retained.
 *   Memory footprint: just a Map[Tick, Int].
 *
 * Phase 2 — Chunked streaming:
 *   When a tick range is requested, opens the JSON file and streams actors
 *   in small chunks (CHUNK_SIZE at a time). Each chunk is sent to creators
 *   with back-pressure — the next chunk is only read after creators confirm
 *   the current chunk. This keeps at most CHUNK_SIZE actors in memory per loader.
 *
 * The file handle stays open between chunks and is closed when all actors
 * in the range have been streamed (or the file is exhausted).
 */
class ProgressiveJsonLoadData(private val properties: Properties)
    extends LoadDataStrategy(properties = properties) {

  private implicit def ec: ExecutionContext =
    context.system.dispatchers.lookup("pekko.actor.io-dispatcher")

  private var managerRef: ActorRef = _
  private var creatorRef: ActorRef = _
  private var creatorPoolRef: ActorRef = _

  private var sourceFilePath: String = _
  private var sourceClassType: String = _
  private var sourceId: String = _

  private var lightIndex: LightTickIndex = _

  private var fullyConsumed = false

  private var activeInputStream: InputStream = _
  private var activeIterator: Iterator[ActorSimulation] = _
  private var streamExhausted: Boolean = false

  private val CHUNK_SIZE = 500
  private val CREATE_EVENT_MAX_ACTORS = 25
  private val activeBatches = mutable.Set[String]()
  private var totalLoadedActors = 0L
  private val creators = mutable.Set[ActorRef]()

  private var activeRequest: LoadActorsForTickRange = _
  private var pendingActorsSent = 0

  override def handleEvent: Receive = {
    case event: LoadDataSourceEvent    => load(event)
    case _: BuildTickIndex             => buildTickIndexAsync()
    case event: TickIndexBuiltEvent    => handleTickIndexBuilt(event)
    case event: LoadActorsForTickRange => handleLoadForTickRange(event)
    case FileChunkReady(actors, done)  => handleFileChunkReady(actors, done)
    case event: FinishCreationEvent    => handleFinishCreation(event)
    case CloseAndFinish()              => finishLoading()
  }

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

    logInfo(s"ProgressiveJsonLoadData: starting light index build for $sourceFilePath")
    self ! BuildTickIndex()
  }

  /**
   * Asynchronously scan the entire JSON file and build a lightweight tick index.
   * Only extracts startTick counts — does NOT retain ActorSimulation objects.
   */
  private def buildTickIndexAsync(): Unit = {
    Future {
      TickIndexUtil.buildLightIndex(sourceFilePath)
    }.onComplete {
      case Success(result) =>
        logInfo(
          s"Tick index built for $sourceFilePath: " +
            s"${result.totalActors} actors, " +
            s"ticks ${result.minTick}-${result.maxTick}, " +
            s"${result.tickCounts.size} unique ticks"
        )
        this.lightIndex = result
        self ! TickIndexBuiltEvent(
          sourceId = sourceId,
          tickCounts = result.tickCounts,
          totalActors = result.totalActors,
          maxTick = result.maxTick
        )

      case Failure(e) =>
        logError(s"Failed to build tick index for $sourceFilePath", e)
        self ! CloseAndFinish()
    }
  }

  private def handleTickIndexBuilt(event: TickIndexBuiltEvent): Unit = {
    managerRef ! event
  }
  
  /**
   * Start streaming actors for a tick range. Opens the file and kicks off
   * the first chunk read.
   */
  private def handleLoadForTickRange(request: LoadActorsForTickRange): Unit = {
    if (lightIndex == null) {
      logWarn(s"Tick index not built yet, cannot load range [${request.fromTick}, ${request.toTick}]")
      managerRef ! TickRangeLoadedEvent(
        sourceId = sourceId,
        fromTick = request.fromTick,
        toTick = request.toTick,
        actorsLoaded = 0
      )
      return
    }

    if (fullyConsumed) {
      managerRef ! TickRangeLoadedEvent(
        sourceId = sourceId,
        fromTick = request.fromTick,
        toTick = request.toTick,
        actorsLoaded = 0
      )
      return
    }
    
    val expectedCount = TickIndexUtil.countActorsInRange(
      lightIndex.tickCounts, request.fromTick, request.toTick
    )

    if (expectedCount == 0) {
      managerRef ! TickRangeLoadedEvent(
        sourceId = sourceId,
        fromTick = request.fromTick,
        toTick = request.toTick,
        actorsLoaded = 0
      )
      return
    }

    logInfo(
      s"Streaming ~$expectedCount actors for tick range " +
        s"[${request.fromTick}, ${request.toTick}] from $sourceId"
    )

    activeInputStream = new BufferedInputStream(new FileInputStream(new File(sourceFilePath)))
    val (_, iter) = JsonStreamingUtil.createParser(activeInputStream)
    activeIterator = iter
    activeRequest = request
    pendingActorsSent = 0
    streamExhausted = false

    startNextChunkRead()
  }

  /**
   * Launch an async read of the next chunk from the open file.
   * Runs on the io-dispatcher to avoid blocking the actor thread.
   */
  private def startNextChunkRead(): Unit = {
    val iter = activeIterator
    val from = activeRequest.fromTick
    val to = activeRequest.toTick
    val chunkSize = CHUNK_SIZE

    Future {
      TickIndexUtil.readMatchingChunk(iter, from, to, chunkSize)
    }.onComplete {
      case Success((actors, exhausted)) =>
        self ! FileChunkReady(actors, exhausted)
      case Failure(e) =>
        logError(s"Error reading chunk from $sourceFilePath", e)
        self ! FileChunkReady(Nil, true)
    }
  }

  /**
   * Handle a chunk read from the file. Send matching actors to creators
   * (with back-pressure) or finish if file is exhausted.
   */
  private def handleFileChunkReady(actors: List[ActorSimulation], isDone: Boolean): Unit = {
    streamExhausted = isDone

    if (actors.nonEmpty) {
      sendActorsToCreators(actors)
    } else {
      closeStreamAndReport()
    }
  }

  /**
   * Send a chunk of actors to creators. Tracks active batches for back-pressure.
   */
  private def sendActorsToCreators(actors: List[ActorSimulation]): Unit = {
    totalLoadedActors += actors.size
    pendingActorsSent += actors.size

    val actorsToCreate = actors.map(actor =>
      ActorSimulationCreation(
        resourceId = IdUtil.format(sourceId),
        actor = actor.copy(id = IdUtil.format(actor.id))
      )
    )

    val (poolDistributed, loadBalanced) =
      actorsToCreate.partition(_.actor.creationType == PoolDistributed)

    if (loadBalanced.nonEmpty) {
      creators.add(creatorRef)
      loadBalanced.grouped(CREATE_EVENT_MAX_ACTORS).foreach { group =>
        val batchId = UUID.randomUUID().toString
        activeBatches.add(batchId)
        creatorRef ! CreateActorsEvent(id = batchId, actors = group, actorRef = self)
      }
    }

    if (poolDistributed.nonEmpty) {
      creators.add(creatorPoolRef)
      poolDistributed.grouped(CREATE_EVENT_MAX_ACTORS).foreach { group =>
        val batchId = UUID.randomUUID().toString
        activeBatches.add(batchId)
        creatorPoolRef ! CreateActorsEvent(id = batchId, actors = group, actorRef = self)
      }
    }

    if (activeBatches.isEmpty) {
      if (!streamExhausted) startNextChunkRead()
      else closeStreamAndReport()
    }
  }

  /**
   * Handle back-pressure from creators. When all batches for the current chunk
   * are complete, either read the next chunk or close the stream.
   */
  private def handleFinishCreation(event: FinishCreationEvent): Unit = {
    activeBatches.remove(event.batchId)

    if (activeBatches.isEmpty) {
      if (!streamExhausted) {
        startNextChunkRead()
      } else {
        closeStreamAndReport()
      }
    }
  }

  /**
   * Close the active file stream and report completion to the manager.
   */
  private def closeStreamAndReport(): Unit = {
    if (activeInputStream != null) {
      try activeInputStream.close()
      catch { case _: Exception => }
      activeInputStream = null
      activeIterator = null
    }

    if (lightIndex != null && activeRequest != null && activeRequest.toTick >= lightIndex.maxTick) {
      fullyConsumed = true
    }

    if (activeRequest != null) {
      logInfo(
        s"Finished streaming $pendingActorsSent actors for tick range " +
          s"[${activeRequest.fromTick}, ${activeRequest.toTick}] from $sourceId"
      )
      managerRef ! TickRangeLoadedEvent(
        sourceId = sourceId,
        fromTick = activeRequest.fromTick,
        toTick = activeRequest.toTick,
        actorsLoaded = pendingActorsSent
      )
      activeRequest = null
    }
  }

  private def finishLoading(): Unit = {
    logInfo(
      s"ProgressiveJsonLoadData: finished, total actors loaded: $totalLoadedActors"
    )

    if (activeInputStream != null) {
      try activeInputStream.close()
      catch { case _: Exception => }
      activeInputStream = null
      activeIterator = null
    }

    lightIndex = null

    managerRef ! FinishLoadDataEvent(
      actorRef = self,
      amount = totalLoadedActors,
      actorClassType = sourceClassType,
      creators = creators
    )
  }
}

/**
 * Internal message: a chunk of actors was read from the file.
 */
private[strategy] case class FileChunkReady(
  actors: List[ActorSimulation],
  isDone: Boolean
)
