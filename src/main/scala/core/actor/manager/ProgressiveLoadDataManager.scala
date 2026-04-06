package org.interscity.htc
package core.actor.manager

import org.apache.pekko.actor.{ ActorRef, Props }
import core.actor.manager.load.{ CreatorLoadData, CreatorPoolLoadData }
import core.entity.state.DefaultState
import core.util.ManagerConstantsUtil

import org.apache.pekko.cluster.routing.{ ClusterRouterPool, ClusterRouterPoolSettings }
import org.apache.pekko.routing.RoundRobinPool
import org.htc.protobuf.core.entity.event.control.execution.{ DestructEvent, StopSimulationEvent }
import org.interscity.htc.core.entity.actor.properties.{ CreatorProperties, Properties }
import org.interscity.htc.core.entity.configuration.ActorDataSource
import org.interscity.htc.core.entity.event.control.load.*
import org.interscity.htc.core.actor.manager.load.strategy.ProgressiveJsonLoadData
import org.interscity.htc.core.enumeration.ReportTypeEnum
import org.interscity.htc.core.types.Tick
import org.interscity.htc.core.util.ManagerConstantsUtil.PROGRESSIVE_LOAD_MANAGER_ACTOR_NAME

import scala.collection.mutable
import scala.collection.immutable.TreeMap
import scala.compiletime.uninitialized

/**
 * Progressive Load Data Manager - coordinates tick-windowed actor creation during simulation.
 *
 * This manager receives TickWindowRequest events from the GlobalTimeManager and ensures
 * that all actors with startTick <= horizonTick are created before the simulation reaches
 * those ticks. It coordinates with ProgressiveJsonLoadData actors that hold tick-indexed
 * views of JSON data files.
 *
 * Architecture:
 * - GlobalTimeManager sends TickWindowRequest(currentTick, horizonTick)
 * - This manager dispatches LoadActorsForTickRange to each progressive loader
 * - Each loader sends back TickRangeLoadedEvent when done
 * - When all loaders report done, sends TickWindowReady back to GlobalTimeManager
 *
 * The manager tracks what has already been loaded to avoid duplicate creation.
 * It operates as a ClusterSingleton alongside the GlobalTimeManager.
 */
class ProgressiveLoadDataManager(
  val poolTimeManager: ActorRef,
  val simulationManager: ActorRef,
  val poolReporters: mutable.Map[ReportTypeEnum, ActorRef]
) extends BaseManager[DefaultState](
      actorId = PROGRESSIVE_LOAD_MANAGER_ACTOR_NAME,
      timeManager = null
    ) {

  // References to creator pools (shared with LoadDataManager)
  private var creatorRef: ActorRef = uninitialized
  private var creatorPoolRef: ActorRef = uninitialized
  private var globalTimeManagerRef: ActorRef = uninitialized

  // Progressive loader actors (one per data source file)
  private val loaders = mutable.Map[String, ActorRef]() // sourceId -> loaderActorRef
  private val loaderSources = mutable.Map[String, ActorDataSource]() // sourceId -> source config
  private val indexedSources = mutable.Set[String]() // sources that have completed indexing

  // Tick window management
  private var loadedUpToTick: Tick = -1L
  private var maxLookAheadTicks: Tick = 1000L
  private var totalActorsCreated: Long = 0L
  private var allSourcesFullyLoaded = false

  // Adaptive window sizing — density-aware horizon calculation
  // Aggregated actor counts per tick across all progressive sources.
  // Built from TickIndexBuiltEvent data after all sources complete indexing.
  private var aggregatedTickCounts: TreeMap[Tick, Int] = TreeMap.empty
  private val TARGET_ACTORS_PER_WINDOW: Int = 50_000
  private val MIN_LOOK_AHEAD_TICKS: Tick = 100

  // Pending tick window request tracking
  private var pendingWindowRequest: Option[TickWindowRequest] = None
  private val pendingRangeResponses = mutable.Map[String, Boolean]() // sourceId -> isDone
  private var pendingRangeActorsCount: Long = 0L

  // Sliding-window load batching — limits concurrent file re-reads.
  // Each loader that has actors in the range re-reads its entire file, so we
  // limit how many do this concurrently to control memory usage.
  private val LOAD_BATCH_SIZE = 10
  private var pendingLoadQueue: List[String] = List.empty // sourceIds awaiting load
  private var currentLoadFromTick: Tick = 0L
  private var currentLoadToTick: Tick = 0L

  // Max ticks per source (to know when all actors have been loaded)
  private val sourceMaxTicks = mutable.Map[String, Tick]()

  private var selfProxy: ActorRef = null
  private var fullyIndexed = false

  // Batched index building — avoid GC pressure by limiting concurrent index builds.
  // Light indexing is cheap (only counts, no ActorSimulation retention), so we can
  // run more in parallel than before. Still batched to avoid I/O contention.
  private val INDEX_BUILD_BATCH_SIZE = 30
  private var pendingIndexSources: List[String] = List.empty // sourceIds awaiting index build
  private var currentIndexBatchSize: Int = 0 // number of sources in the current batch

  override def onStart(): Unit =
    reporters = poolReporters

  override def handleEvent: Receive = {
    case event: StartProgressiveLoadingEvent => startProgressiveLoading(event)
    case event: TickIndexBuiltEvent          => handleTickIndexBuilt(event)
    case event: TickWindowRequest            => handleTickWindowRequest(event)
    case event: TickRangeLoadedEvent         => handleTickRangeLoaded(event)
    case event: FinishCreationEvent          => handleFinishCreation(event)
    case event: FinishLoadDataEvent          => handleSourceFinished(event)
    case _: StopSimulationEvent              => handleStopSimulation()
  }

  /**
   * Initialize progressive loading. Creates loader actors for each progressive data source
   * and triggers tick index building.
   */
  private def startProgressiveLoading(event: StartProgressiveLoadingEvent): Unit = {
    this.creatorRef = event.creatorRef
    this.creatorPoolRef = event.creatorPoolRef
    this.globalTimeManagerRef = event.timeManagerRef
    this.maxLookAheadTicks = event.lookAheadTicks

    if (event.progressiveSources.isEmpty) {
      logInfo("No progressive data sources configured. Progressive loading complete.")
      allSourcesFullyLoaded = true
      notifyTimeManagerReady(0L, 0)
      return
    }

    logInfo(
      s"Starting progressive loading for ${event.progressiveSources.size} sources " +
        s"with maxLookAhead=$maxLookAheadTicks ticks, targetActorsPerWindow=$TARGET_ACTORS_PER_WINDOW, " +
        s"indexBuildBatchSize=$INDEX_BUILD_BATCH_SIZE"
    )

    // Create all loader actors but DON'T trigger indexing yet.
    // Index building parses entire JSON files into memory, so building all
    // sources concurrently causes OOM. We batch index builds instead.
    event.progressiveSources.foreach { source =>
      val loaderProps = Props(
        classOf[ProgressiveJsonLoadData],
        Properties(
          entityId = s"progressive-loader-${source.id.hashCode}",
          resourceId = "",
          timeManagers = mutable.Map("discrete-event" -> poolTimeManager),
          creatorManager = null,
          reporters = mutable.Map.empty
        )
      ).withDispatcher("pekko.actor.io-dispatcher")

      val loader = context.system.actorOf(loaderProps)
      loaders.put(source.id, loader)
      loaderSources.put(source.id, source)
    }

    // Queue all source IDs and kick off the first batch of index builds
    pendingIndexSources = event.progressiveSources.map(_.id).toList
    startNextIndexBatch()
  }

  /**
   * Start the next batch of index builds. Takes up to INDEX_BUILD_BATCH_SIZE
   * sources from the pending queue and sends them LoadDataSourceEvent.
   */
  private def startNextIndexBatch(): Unit = {
    if (pendingIndexSources.isEmpty) return

    val batch = pendingIndexSources.take(INDEX_BUILD_BATCH_SIZE)
    pendingIndexSources = pendingIndexSources.drop(INDEX_BUILD_BATCH_SIZE)
    currentIndexBatchSize = batch.size

    logInfo(
      s"Starting index build batch: ${batch.size} sources " +
        s"(${pendingIndexSources.size} remaining in queue, " +
        s"${indexedSources.size}/${loaders.size} total indexed)"
    )

    batch.foreach { sourceId =>
      val loader = loaders(sourceId)
      val source = loaderSources(sourceId)
      loader ! LoadDataSourceEvent(
        managerRef = self,
        creatorRef = creatorRef,
        creatorPoolRef = creatorPoolRef,
        actorDataSource = source
      )
    }
  }

  /**
   * Called when a loader has finished building its tick index.
   * Aggregates tick density data across all sources. Once all loaders are indexed,
   * we have a complete density map for adaptive window sizing.
   */
  private def handleTickIndexBuilt(event: TickIndexBuiltEvent): Unit = {
    indexedSources.add(event.sourceId)
    sourceMaxTicks.put(event.sourceId, event.maxTick)

    // Aggregate tick counts from this source into the global density map.
    // This enables adaptive window sizing — instead of a fixed tick range,
    // we walk through ticks accumulating actors until reaching the target.
    val mutableCounts = mutable.TreeMap.from(aggregatedTickCounts)
    event.tickCounts.foreach { case (tick, count) =>
      mutableCounts.updateWith(tick) {
        case Some(existing) => Some(existing + count)
        case None           => Some(count)
      }
    }
    aggregatedTickCounts = TreeMap.from(mutableCounts)

    logInfo(
      s"Source ${event.sourceId} indexed: ${event.totalActors} actors, maxTick=${event.maxTick}. " +
        s"Progress: ${indexedSources.size}/${loaders.size}. " +
        s"Aggregated density: ${aggregatedTickCounts.size} unique ticks"
    )

    if (indexedSources.size == loaders.size) {
      fullyIndexed = true
      val globalMaxTick = sourceMaxTicks.values.maxOption.getOrElse(0L)
      val totalAggregated = aggregatedTickCounts.values.sum
      logInfo(
        s"All ${loaders.size} progressive sources indexed. " +
          s"Global maxTick=$globalMaxTick, total actors=$totalAggregated, " +
          s"unique ticks=${aggregatedTickCounts.size}. Ready for adaptive tick window requests."
      )

      // If there's a pending request that came in before indexing completed, process it now
      pendingWindowRequest.foreach { req =>
        processTickWindowRequest(req)
      }
    } else if (pendingIndexSources.nonEmpty) {
      // Check if the current batch is fully indexed; if so, start the next batch.
      // A batch is complete when (indexedSources.size % INDEX_BUILD_BATCH_SIZE == 0)
      // or we've indexed all sources sent so far.
      val totalSent = loaders.size - pendingIndexSources.size
      if (indexedSources.size >= totalSent) {
        startNextIndexBatch()
      }
    }
  }

  /**
   * Handle a tick window request from the GlobalTimeManager.
   * Loads all actors with startTick in [loadedUpToTick+1, horizonTick].
   */
  private def handleTickWindowRequest(request: TickWindowRequest): Unit = {
    if (allSourcesFullyLoaded) {
      // All progressive actors have been created, just acknowledge
      notifyTimeManagerReady(request.horizonTick, 0)
      return
    }

    if (!fullyIndexed) {
      logInfo(s"Tick window request received but indexing not complete. Queueing...")
      pendingWindowRequest = Some(request)
      return
    }

    processTickWindowRequest(request)
  }

  private def processTickWindowRequest(request: TickWindowRequest): Unit = {
    pendingWindowRequest = None

    val fromTick = loadedUpToTick + 1
    val maxHorizon = request.horizonTick

    if (fromTick > maxHorizon) {
      // Already loaded up to this horizon
      notifyTimeManagerReady(maxHorizon, 0)
      return
    }

    // Calculate the adaptive horizon based on actor density instead of using
    // the fixed horizon from the request. This ensures windows with many actors
    // are smaller (faster to load) while sparse windows extend further ahead.
    val toTick = calculateAdaptiveHorizon(fromTick, maxHorizon)

    logInfo(
      s"Processing tick window: currentTick=${request.currentTick}, " +
        s"adaptive range [$fromTick, $toTick] (requested max=$maxHorizon)"
    )

    // Track which loaders need to respond
    pendingRangeResponses.clear()
    pendingRangeActorsCount = 0

    // Only send to loaders whose maxTick >= fromTick (they might have actors in range)
    val relevantLoaders = loaders.filter {
      case (sourceId, _) =>
        sourceMaxTicks.get(sourceId).exists(_ >= fromTick)
    }

    if (relevantLoaders.isEmpty) {
      // No more actors to load from any source in this range
      loadedUpToTick = toTick
      checkAllSourcesFullyLoaded(toTick)
      notifyTimeManagerReady(toTick, 0)
      return
    }

    // Register all relevant loaders as pending
    relevantLoaders.foreach {
      case (sourceId, _) =>
        pendingRangeResponses.put(sourceId, false)
    }

    // Use sliding-window batching: only LOAD_BATCH_SIZE loaders re-read files
    // concurrently. As each finishes, the next one starts. This caps peak memory
    // during the load phase.
    val allIds = relevantLoaders.keys.toList
    val (firstBatch, rest) = allIds.splitAt(LOAD_BATCH_SIZE)
    pendingLoadQueue = rest
    currentLoadFromTick = fromTick
    currentLoadToTick = toTick

    logInfo(
      s"Starting load with ${firstBatch.size} concurrent loaders " +
        s"(${rest.size} queued, LOAD_BATCH_SIZE=$LOAD_BATCH_SIZE)"
    )

    firstBatch.foreach { sourceId =>
      loaders(sourceId) ! LoadActorsForTickRange(fromTick = fromTick, toTick = toTick)
    }
  }

  /**
   * Handle response from a loader for a tick range.
   * Uses sliding-window: as each loader completes, the next one from the queue starts.
   */
  private def handleTickRangeLoaded(event: TickRangeLoadedEvent): Unit = {
    pendingRangeResponses.put(event.sourceId, true)
    pendingRangeActorsCount += event.actorsLoaded
    totalActorsCreated += event.actorsLoaded

    val completed = pendingRangeResponses.count(_._2)
    val total = pendingRangeResponses.size

    logInfo(
      s"Source ${event.sourceId} loaded ${event.actorsLoaded} actors " +
        s"for ticks [${event.fromTick}, ${event.toTick}]. " +
        s"Progress: $completed/$total (${pendingLoadQueue.size} queued)"
    )

    // Sliding window: start the next queued loader
    if (pendingLoadQueue.nonEmpty) {
      val nextId = pendingLoadQueue.head
      pendingLoadQueue = pendingLoadQueue.tail
      loaders(nextId) ! LoadActorsForTickRange(
        fromTick = currentLoadFromTick,
        toTick = currentLoadToTick
      )
    }

    // Check if all loaders have responded for this range
    if (completed == total) {
      val toTick = currentLoadToTick
      loadedUpToTick = toTick
      checkAllSourcesFullyLoaded(toTick)

      logInfo(
        s"All loaders completed for tick range up to $toTick. " +
          s"Total actors in window: $pendingRangeActorsCount, " +
          s"cumulative: $totalActorsCreated"
      )

      notifyTimeManagerReady(toTick, pendingRangeActorsCount)
    }
  }

  /**
   * Calculate the adaptive horizon tick based on actor density.
   *
   * Instead of a fixed tick range, walk through aggregated tick counts accumulating
   * actors until we hit the TARGET_ACTORS_PER_WINDOW limit. This ensures:
   * - Dense windows (many actors per tick) have shorter tick ranges → faster to load
   * - Sparse windows (few actors per tick) extend further ahead → better prefetch
   * - MIN_LOOK_AHEAD_TICKS guarantees a minimum range even for very dense ticks
   *
   * @param fromTick     first tick to load (inclusive)
   * @param maxHorizon   absolute maximum horizon (from TickWindowRequest)
   * @return the adaptive horizon tick (inclusive)
   */
  private def calculateAdaptiveHorizon(fromTick: Tick, maxHorizon: Tick): Tick = {
    if (aggregatedTickCounts.isEmpty) {
      logInfo(s"No density data available, using max horizon $maxHorizon")
      maxHorizon
    } else {
      var actorCount = 0
      var lastTickWithActors = fromTick
      var exceeded = false
      val minHorizon = fromTick + MIN_LOOK_AHEAD_TICKS
      val cappedMaxHorizon = Math.min(maxHorizon, fromTick + maxLookAheadTicks)

      // Walk through ticks in sorted order, accumulating actor counts
      val relevantTicks = aggregatedTickCounts.rangeFrom(fromTick).rangeTo(cappedMaxHorizon)

      val iter = relevantTicks.iterator
      while (iter.hasNext && !exceeded) {
        val (tick, count) = iter.next()
        // If adding this tick would exceed the target AND we've already passed the minimum range,
        // stop at the previous tick boundary to keep the window manageable.
        if (actorCount + count > TARGET_ACTORS_PER_WINDOW && tick > minHorizon) {
          logInfo(
            s"Adaptive horizon: $lastTickWithActors " +
              s"(accumulated $actorCount actors, next tick $tick would add $count, exceeding target $TARGET_ACTORS_PER_WINDOW)"
          )
          exceeded = true
        } else {
          actorCount += count
          lastTickWithActors = tick
        }
      }

      if (exceeded) {
        lastTickWithActors
      } else {
        // All ticks in range fit within the target — extend up to max horizon
        logInfo(
          s"Adaptive horizon: $cappedMaxHorizon " +
            s"(all $actorCount actors in range fit within target $TARGET_ACTORS_PER_WINDOW)"
        )
        cappedMaxHorizon
      }
    }
  }

  /**
   * Check if all progressive sources have been fully loaded
   * (loadedUpToTick >= maxTick for all sources).
   */
  private def checkAllSourcesFullyLoaded(currentHorizon: Tick): Unit = {
    val allDone = sourceMaxTicks.forall {
      case (_, maxTick) => currentHorizon >= maxTick
    }
    if (allDone && !allSourcesFullyLoaded) {
      allSourcesFullyLoaded = true
      logInfo(
        s"All progressive sources fully loaded! " +
          s"Total actors created: $totalActorsCreated"
      )
      simulationManager ! ProgressiveLoadingCompleteEvent(totalActorsCreated)
    }
  }

  /**
   * Notify the GlobalTimeManager that actors up to the given tick are ready.
   */
  private def notifyTimeManagerReady(readyUpToTick: Tick, actorsCreated: Long): Unit = {
    globalTimeManagerRef ! TickWindowReady(
      readyUpToTick = readyUpToTick,
      actorsCreated = actorsCreated
    )
  }

  private def handleFinishCreation(event: FinishCreationEvent): Unit = {
    // Forward to the appropriate loader (they track their own batches)
  }

  private def handleSourceFinished(event: FinishLoadDataEvent): Unit = {
    logInfo(s"Progressive source finished: ${event.actorClassType}, actors: ${event.amount}")
  }

  private def handleStopSimulation(): Unit = {
    logInfo("Received StopSimulationEvent. Stopping progressive load manager gracefully.")
    loaders.values.foreach { loaderRef =>
      loaderRef ! DestructEvent(actorRef = getPath)
    }
    selfDestruct()
  }
}

object ProgressiveLoadDataManager {
  def props(
    poolTimeManager: ActorRef,
    simulationManager: ActorRef,
    poolReporters: mutable.Map[ReportTypeEnum, ActorRef]
  ): Props =
    Props(
      classOf[ProgressiveLoadDataManager],
      poolTimeManager,
      simulationManager,
      poolReporters
    )
}
