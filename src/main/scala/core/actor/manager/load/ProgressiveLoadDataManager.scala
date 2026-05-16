package org.interscity.htc
package core.actor.manager.load

import core.actor.manager.load.strategy.ProgressiveJsonLoadData
import core.actor.manager.load.{ CreatorLoadData, CreatorPoolLoadData }
import core.actor.manager.BaseManager
import core.entity.actor.properties.{ CreatorProperties, Properties }
import core.entity.configuration.ActorDataSource
import core.entity.event.control.load.*
import core.entity.state.DefaultState
import core.enumeration.ReportTypeEnum
import core.types.Tick
import core.util.ManagerConstantsUtil
import core.util.ManagerConstantsUtil.{ POOL_PROGRESSIVE_CREATOR_LOAD_DATA_ACTOR_NAME, POOL_PROGRESSIVE_CREATOR_POOL_LOAD_DATA_ACTOR_NAME, PROGRESSIVE_LOAD_MANAGER_ACTOR_NAME }

import org.apache.pekko.actor.{ ActorRef, Deploy, Props }
import org.apache.pekko.cluster.routing.{ ClusterRouterPool, ClusterRouterPoolSettings }
import org.apache.pekko.cluster.{ Cluster, MemberStatus }
import org.apache.pekko.remote.RemoteScope
import org.apache.pekko.routing.RoundRobinPool
import org.htc.protobuf.core.entity.event.control.execution.{ DestructEvent, StopSimulationEvent }
import org.interscity.htc.core.metrics.core.ProgressiveLoadingMetrics

import scala.collection.immutable.TreeMap
import scala.collection.mutable
import scala.compiletime.uninitialized

/** Progressive Load Data Manager - coordinates tick-windowed actor creation during simulation.
  *
  * This manager receives TickWindowRequest events from the GlobalTimeManager and ensures that all
  * actors with startTick <= horizonTick are created before the simulation reaches those ticks. It
  * coordinates with ProgressiveJsonLoadData actors that hold tick-indexed views of JSON data files.
  *
  * Architecture:
  *   - GlobalTimeManager sends TickWindowRequest(currentTick, horizonTick)
  *   - This manager dispatches LoadActorsForTickRange to each progressive loader
  *   - Each loader sends back TickRangeLoadedEvent when done
  *   - When all loaders report done, sends TickWindowReady back to GlobalTimeManager
  *
  * The manager tracks what has already been loaded to avoid duplicate creation. It operates as a
  * ClusterSingleton alongside the GlobalTimeManager.
  */
class ProgressiveLoadDataManager(
  val poolTimeManager: ActorRef,
  val simulationManager: ActorRef,
  val poolReporters: mutable.Map[ReportTypeEnum, ActorRef]
) extends BaseManager[DefaultState](
      actorId = PROGRESSIVE_LOAD_MANAGER_ACTOR_NAME,
      timeManager = null
    ) {

  private var creatorRef: ActorRef = uninitialized
  private var creatorPoolRef: ActorRef = uninitialized
  private var globalTimeManagerRef: ActorRef = uninitialized

  private val loaders = mutable.Map[String, ActorRef]()
  private val loaderSources = mutable.Map[String, ActorDataSource]()
  private val indexedSources = mutable.Set[String]()

  private var loadedUpToTick: Tick = -1L
  private var maxLookAheadTicks: Tick = 1000L
  private var totalActorsCreated: Long = 0L
  private var allSourcesFullyLoaded = false

  private var aggregatedTickCounts: TreeMap[Tick, Int] = TreeMap.empty
  private val TARGET_ACTORS_PER_WINDOW: Int = 50_000
  private val MIN_LOOK_AHEAD_TICKS: Tick = 100

  private var pendingWindowRequest: Option[TickWindowRequest] = None
  private val pendingRangeResponses = mutable.Map[String, Boolean]()
  private var pendingRangeActorsCount: Long = 0L

  private val LOAD_BATCH_SIZE = 10
  private var pendingLoadQueue: List[String] = List.empty
  private var currentLoadFromTick: Tick = 0L
  private var currentLoadToTick: Tick = 0L

  private var loadInFlight = false

  private val sourceMaxTicks = mutable.Map[String, Tick]()

  private var selfProxy: ActorRef = null
  private var fullyIndexed = false

  private val INDEX_BUILD_BATCH_SIZE = 30
  private var pendingIndexSources: List[String] = List.empty
  private var currentIndexBatchSize: Int = 0

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

  /** Initialize progressive loading. Creates loader actors for each progressive data source and
    * triggers tick index building.
    */
  private def startProgressiveLoading(event: StartProgressiveLoadingEvent): Unit = {
    this.globalTimeManagerRef = event.timeManagerRef
    this.maxLookAheadTicks = event.lookAheadTicks

    if (event.progressiveSources.isEmpty) {
      logInfo("No progressive data sources configured. Progressive loading complete.")
      allSourcesFullyLoaded = true
      notifyTimeManagerReady(0L, 0)
      return
    }

    val totalSources = event.progressiveSources.size
    this.creatorRef = createCreatorLoadData(totalSources)
    this.creatorPoolRef = createCreatorPoolLoadData(totalSources)

    logInfo(
      s"Starting progressive loading for ${event.progressiveSources.size} sources " +
        s"with maxLookAhead=$maxLookAheadTicks ticks, targetActorsPerWindow=$TARGET_ACTORS_PER_WINDOW, " +
        s"indexBuildBatchSize=$INDEX_BUILD_BATCH_SIZE"
    )

    val cluster = Cluster(context.system)
    val upMembers = cluster.state.members
      .filter(_.status == MemberStatus.Up)
      .toSeq
      .sortBy(_.address)

    logInfo(
      s"Distributing ${event.progressiveSources.size} progressive loaders across " +
        s"${upMembers.size} cluster members"
    )

    event.progressiveSources.zipWithIndex.foreach {
      case (source, idx) =>
        val targetAddress = upMembers(idx % upMembers.size).address
        val loaderProps = Props(
          classOf[ProgressiveJsonLoadData],
          Properties(
            entityId = s"progressive-loader-${source.id.hashCode}",
            resourceId = "",
            timeManagers = mutable.Map("discrete-event" -> poolTimeManager),
            creatorManager = null,
            reporters = mutable.Map.empty
          )
        ).withDeploy(
          Deploy(scope = RemoteScope(targetAddress))
        ).withDispatcher("pekko.actor.io-dispatcher")

        val loader = context.system.actorOf(loaderProps)
        loaders.put(source.id, loader)
        loaderSources.put(source.id, source)

        if (idx < upMembers.size || idx % 50 == 0) {
          logInfo(s"  Loader ${source.id} → ${targetAddress}")
        }
    }

    pendingIndexSources = event.progressiveSources.map(_.id)
    startNextIndexBatch()
  }

  /** Start the next batch of index builds. Takes up to INDEX_BUILD_BATCH_SIZE sources from the
    * pending queue and sends them LoadDataSourceEvent.
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

    batch.foreach {
      sourceId =>
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

  /** Called when a loader has finished building its tick index. Aggregates tick density data across
    * all sources. Once all loaders are indexed, we have a complete density map for adaptive window
    * sizing.
    */
  private def handleTickIndexBuilt(event: TickIndexBuiltEvent): Unit = {
    indexedSources.add(event.sourceId)
    sourceMaxTicks.put(event.sourceId, event.maxTick)

    val mutableCounts = mutable.TreeMap.from(aggregatedTickCounts)
    event.tickCounts.asInstanceOf[Map[Any, Any]].foreach {
      case (rawTick, rawCount) =>
        val tick: Tick = rawTick match {
          case s: String           => s.toLong
          case n: java.lang.Number => n.longValue()
          case other               => other.toString.toLong
        }
        val count: Int = rawCount match {
          case s: String           => s.toInt
          case n: java.lang.Number => n.intValue()
          case other               => other.toString.toInt
        }
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

      pendingWindowRequest.foreach {
        req =>
          processTickWindowRequest(req)
      }
    } else if (pendingIndexSources.nonEmpty) {
      val totalSent = loaders.size - pendingIndexSources.size
      if (indexedSources.size >= totalSent) {
        startNextIndexBatch()
      }
    }
  }

  /** Handle a tick window request from the GlobalTimeManager. Loads all actors with startTick in
    * [loadedUpToTick+1, horizonTick].
    *
    * Requests arriving while a load is in-flight are queued (only the latest is kept — an older
    * horizon is always superseded by a newer one). The queued request is processed as soon as the
    * current load completes.
    */
  private def handleTickWindowRequest(request: TickWindowRequest): Unit = {
    if (allSourcesFullyLoaded) {
      notifyTimeManagerReady(request.horizonTick, 0)
      return
    }

    if (!fullyIndexed || loadInFlight) {
      if (!fullyIndexed)
        logInfo(s"Tick window request received but indexing not complete. Queueing...")
      else
        logInfo(
          s"Tick window request (horizon=${request.horizonTick}) arrived while load in-flight. " +
            s"Queuing (replaces any earlier pending horizon)."
        )
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
      notifyTimeManagerReady(maxHorizon, 0)
      return
    }

    val toTick = calculateAdaptiveHorizon(fromTick, maxHorizon)

    logInfo(
      s"Processing tick window: currentTick=${request.currentTick}, " +
        s"adaptive range [$fromTick, $toTick] (requested max=$maxHorizon)"
    )

    pendingRangeResponses.clear()
    pendingRangeActorsCount = 0

    val relevantLoaders = loaders.filter {
      case (sourceId, _) =>
        sourceMaxTicks.get(sourceId).exists(_ >= fromTick)
    }

    if (relevantLoaders.isEmpty) {
      loadedUpToTick = toTick
      checkAllSourcesFullyLoaded(toTick)
      notifyTimeManagerReady(toTick, 0)
      return
    }

    relevantLoaders.foreach {
      case (sourceId, _) =>
        pendingRangeResponses.put(sourceId, false)
    }

    val allIds = relevantLoaders.keys.toList
    val (firstBatch, rest) = allIds.splitAt(LOAD_BATCH_SIZE)
    pendingLoadQueue = rest
    currentLoadFromTick = fromTick
    currentLoadToTick = toTick

    logInfo(
      s"Starting load with ${firstBatch.size} concurrent loaders " +
        s"(${rest.size} queued, LOAD_BATCH_SIZE=$LOAD_BATCH_SIZE)"
    )

    loadInFlight = true
    firstBatch.foreach {
      sourceId =>
        loaders(sourceId) ! LoadActorsForTickRange(fromTick = fromTick, toTick = toTick)
    }
  }

  /** Handle response from a loader for a tick range. Uses sliding-window: as each loader completes,
    * the next one from the queue starts.
    */
  private def handleTickRangeLoaded(event: TickRangeLoadedEvent): Unit = {
    pendingRangeResponses.put(event.sourceId, true)
    pendingRangeActorsCount += event.actorsLoaded
    totalActorsCreated += event.actorsLoaded

    ProgressiveLoadingMetrics.progressiveActorsCreated.inc(event.actorsLoaded.toDouble)

    val completed = pendingRangeResponses.count(_._2)
    val total = pendingRangeResponses.size

    logInfo(
      s"Source ${event.sourceId} loaded ${event.actorsLoaded} actors " +
        s"for ticks [${event.fromTick}, ${event.toTick}]. " +
        s"Progress: $completed/$total (${pendingLoadQueue.size} queued)"
    )

    if (pendingLoadQueue.nonEmpty) {
      val nextId = pendingLoadQueue.head
      pendingLoadQueue = pendingLoadQueue.tail
      loaders(nextId) ! LoadActorsForTickRange(
        fromTick = currentLoadFromTick,
        toTick = currentLoadToTick
      )
    }

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

      loadInFlight = false
      pendingWindowRequest.foreach {
        pending =>
          pendingWindowRequest = None
          processTickWindowRequest(pending)
      }
    }
  }

  /** Calculate the adaptive horizon tick based on actor density.
    *
    * Instead of a fixed tick range, walk through aggregated tick counts accumulating actors until
    * we hit the TARGET_ACTORS_PER_WINDOW limit. This ensures:
    *   - Dense windows (many actors per tick) have shorter tick ranges → faster to load
    *   - Sparse windows (few actors per tick) extend further ahead → better prefetch
    *   - MIN_LOOK_AHEAD_TICKS guarantees a minimum range even for very dense ticks
    *
    * @param fromTick
    *   first tick to load (inclusive)
    * @param maxHorizon
    *   absolute maximum horizon (from TickWindowRequest)
    * @return
    *   the adaptive horizon tick (inclusive)
    */
  private def calculateAdaptiveHorizon(fromTick: Tick, maxHorizon: Tick): Tick =
    if (aggregatedTickCounts.isEmpty) {
      logInfo(s"No density data available, using max horizon $maxHorizon")
      maxHorizon
    } else {
      var actorCount = 0
      var lastTickWithActors = fromTick
      var exceeded = false
      val minHorizon = fromTick + MIN_LOOK_AHEAD_TICKS
      val cappedMaxHorizon = Math.min(maxHorizon, fromTick + maxLookAheadTicks)

      val relevantTicks = aggregatedTickCounts.rangeFrom(fromTick).rangeTo(cappedMaxHorizon)

      val iter = relevantTicks.iterator
      while (iter.hasNext && !exceeded) {
        val (tick, count) = iter.next()
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
        logInfo(
          s"Adaptive horizon: $cappedMaxHorizon " +
            s"(all $actorCount actors in range fit within target $TARGET_ACTORS_PER_WINDOW)"
        )
        cappedMaxHorizon
      }
    }

  /** Check if all progressive sources have been fully loaded (loadedUpToTick >= maxTick for all
    * sources).
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
      // Memory-optimisation: no more entities will be created after this point, so the
      // entity→position cache used by CreatorLoadData.extractSpatialPosition can be released.
      // Shard assignments and class names remain in the registry (still required for routing).
      org.interscity.htc.core.actor.manager.loadbalance.allocation.SpatialShardIdRegistry
        .clearPositions()
      simulationManager ! ProgressiveLoadingCompleteEvent(totalActorsCreated)
    }
  }

  /** Notify the GlobalTimeManager that actors up to the given tick are ready.
    */
  private def notifyTimeManagerReady(readyUpToTick: Tick, actorsCreated: Long): Unit = {
    ProgressiveLoadingMetrics.progressiveLoadedUpToTick.set(readyUpToTick.toDouble)
    ProgressiveLoadingMetrics.progressiveWindowsLoaded.inc()
    globalTimeManagerRef ! TickWindowReady(
      readyUpToTick = readyUpToTick,
      actorsCreated = actorsCreated
    )
  }

  private def handleFinishCreation(event: FinishCreationEvent): Unit = {}

  private def handleSourceFinished(event: FinishLoadDataEvent): Unit =
    logInfo(s"Progressive source finished: ${event.actorClassType}, actors: ${event.amount}")

  private def handleStopSimulation(): Unit = {
    logInfo("Received StopSimulationEvent. Stopping progressive load manager gracefully.")
    loaders.values.foreach {
      loaderRef =>
        loaderRef ! DestructEvent(actorRef = getPath)
    }
    if (creatorRef != null) creatorRef ! DestructEvent(actorRef = getPath)
    if (creatorPoolRef != null) creatorPoolRef ! DestructEvent(actorRef = getPath)
    selfDestruct()
  }

  /** Create an independent CreatorLoadData pool for progressive actor creation. These are children
    * of THIS manager, so they live as long as progressive loading is active.
    */
  private def createCreatorLoadData(amountDataSources: Int): ActorRef = {
    val totalInstances = Math.max(1, amountDataSources)
    val maxInstancesPerNode = Math.max(1, Math.max(10, amountDataSources / 8))
    logInfo(
      s"Creating progressive CreatorLoadData pool: totalInstances=$totalInstances, " +
        s"maxInstancesPerNode=$maxInstancesPerNode"
    )
    context.actorOf(
      ClusterRouterPool(
        local = RoundRobinPool(0),
        settings = ClusterRouterPoolSettings(
          totalInstances = totalInstances,
          maxInstancesPerNode = maxInstancesPerNode,
          allowLocalRoutees = true
        )
      ).props(
        CreatorLoadData.props(
          CreatorProperties(
            entityId = "progressive-creator-load-data",
            loadDataManager = self,
            timeManagers = mutable.Map("discrete-event" -> poolTimeManager),
            reporters = poolReporters
          )
        )
      ),
      name = POOL_PROGRESSIVE_CREATOR_LOAD_DATA_ACTOR_NAME
    )
  }

  /** Create an independent CreatorPoolLoadData pool for progressive pool-distributed actor
    * creation.
    */
  private def createCreatorPoolLoadData(amountDataSources: Int): ActorRef = {
    val totalInstances = Math.max(1, amountDataSources)
    val maxInstancesPerNode = Math.max(1, Math.max(10, amountDataSources / 8))
    logInfo(
      s"Creating progressive CreatorPoolLoadData pool: totalInstances=$totalInstances, " +
        s"maxInstancesPerNode=$maxInstancesPerNode"
    )
    context.actorOf(
      ClusterRouterPool(
        local = RoundRobinPool(0),
        settings = ClusterRouterPoolSettings(
          totalInstances = totalInstances,
          maxInstancesPerNode = maxInstancesPerNode,
          allowLocalRoutees = true
        )
      ).props(
        CreatorPoolLoadData.props(
          CreatorProperties(
            entityId = "progressive-creator-pool-load-data",
            loadDataManager = self,
            timeManagers = mutable.Map("discrete-event" -> poolTimeManager),
            reporters = poolReporters
          )
        )
      ),
      name = POOL_PROGRESSIVE_CREATOR_POOL_LOAD_DATA_ACTOR_NAME
    )
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
