package org.interscity.htc
package core.actor.manager

import core.entity.state.DefaultState

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.cluster.Cluster
import core.util.SimulationUtil.loadSimulationConfig

import scala.concurrent.duration.*
import scala.concurrent.Future
import scala.util.{Failure, Success}
import org.htc.protobuf.core.entity.event.control.execution.{DestructEvent, PrepareSimulationEvent, StartSimulationTimeEvent, StopSimulationEvent}
import org.htc.protobuf.core.entity.event.control.execution.data.StartSimulationTimeData
import org.interscity.htc.core.actor.manager.load.{LoadDataManager, ProgressiveLoadDataManager}
import org.interscity.htc.core.actor.manager.report.ReportManager
import org.interscity.htc.core.actor.manager.time.GlobalTimeManager
import org.interscity.htc.core.entity.configuration.Simulation
import org.interscity.htc.core.entity.event.control.execution.TimeManagerRegisterEvent
import org.interscity.htc.core.entity.event.control.load.{FinishLoadDataEvent, LoadDataEvent, ProgressiveLoadingCompleteEvent, RegisterProgressiveLoadManagerEvent, SimulationConfigLoadFailedEvent, SimulationConfigLoadedEvent, StartProgressiveLoadingEvent}
import org.interscity.htc.core.entity.event.control.report.RegisterReportersEvent
import org.interscity.htc.core.entity.event.control.loadbalance.LoadBalanceReadyEvent
import org.interscity.htc.core.actor.manager.loadbalance.LoadBalanceManager
import org.interscity.htc.core.actor.manager.loadbalance.strategy.StrategyConfig
import org.interscity.htc.core.entity.control.loadbalance.SpatialBounds
import org.interscity.htc.core.enumeration.LoadBalanceStrategyEnum
import org.interscity.htc.core.util.ManagerConstantsUtil
import org.interscity.htc.core.util.ManagerConstantsUtil.{GLOBAL_TIME_MANAGER_ACTOR_NAME, LOAD_MANAGER_ACTOR_NAME, LOAD_BALANCE_MANAGER_ACTOR_NAME, PROGRESSIVE_LOAD_MANAGER_ACTOR_NAME, REPORT_MANAGER_ACTOR_NAME, SIMULATION_MANAGER_ACTOR_NAME, SNAPSHOT_MANAGER_ACTOR_NAME}

import scala.collection.mutable
import scala.compiletime.uninitialized

class SimulationManager(
  val simulationPath: String = null
) extends BaseManager[DefaultState](
      actorId = SIMULATION_MANAGER_ACTOR_NAME,
      timeManager = null
    ) {

  private var timeSingletonManager: ActorRef = uninitialized
  private var poolTimeManager: ActorRef = uninitialized
  private var loadManager: ActorRef = uninitialized
  private var reportManager: ActorRef = uninitialized
  private var loadBalanceManager: ActorRef = _
  private var snapshotManager: ActorRef = _
  private var progressiveLoadManager: ActorRef = _
  private var configuration: Simulation = uninitialized
  private var selfProxy: ActorRef = null
  private var simulationPrepared: Boolean = false
  private var clusterRetryScheduled: Boolean = false
  private var configLoadInProgress: Boolean = false

  private case object RetryPrepareSimulation

  override def handleEvent: Receive = {
    case event: PrepareSimulationEvent               => prepareSimulation(event)
    case event: FinishLoadDataEvent                  => startSimulation(event)
    case event: TimeManagerRegisterEvent             => registerPoolTimeManager(event)
    case event: RegisterReportersEvent               => registerReporters(event)
    case event: ProgressiveLoadingCompleteEvent      => handleProgressiveLoadingComplete(event)
    case event: LoadBalanceReadyEvent    => handleLoadBalanceReady(event)
    case _: StopSimulationEvent                      => handleStopSimulation()
    case RetryPrepareSimulation                      =>
      clusterRetryScheduled = false
      prepareSimulation()
    case event: SimulationConfigLoadedEvent               => onSimulationConfigLoaded(event)
    case event: SimulationConfigLoadFailedEvent           => onSimulationConfigLoadFailed(event)
  }

  override def onStart(): Unit = {
    val apiEnabled = sys.env
      .getOrElse("HTC_API_ENABLED",
        try context.system.settings.config.getString("htc.api.enabled")
        catch { case _: Exception => "false" })
      .equalsIgnoreCase("true")

    if (apiEnabled) {
      logInfo("Simulator API enabled — simulation is IDLE. Waiting for POST /api/v1/simulation/start")
    } else {
      getSelfProxy ! PrepareSimulationEvent(configuration = simulationPath)
    }
  }

  private def startSimulation(event: FinishLoadDataEvent): Unit = {
    loadManager ! DestructEvent(actorRef = getPath)

    val globalTimeManagerProxy = createSingletonProxy(GLOBAL_TIME_MANAGER_ACTOR_NAME)

    if (event.progressiveSources.nonEmpty) {
      logInfo(
        s"Setting up progressive loading for ${event.progressiveSources.size} sources"
      )

      val lookAheadTicks = configuration.duration match {
        case d if d > 10000 => 10_000L
        case d if d > 1000  => 5_000L
        case _              => 1_000L
      }

      progressiveLoadManager = createSingletonProgressiveLoadManager()
      val progressiveProxy = createSingletonProxy(PROGRESSIVE_LOAD_MANAGER_ACTOR_NAME)

      globalTimeManagerProxy ! RegisterProgressiveLoadManagerEvent(
        progressiveLoadManager = progressiveProxy,
        lookAheadTicks = lookAheadTicks
      )

      progressiveProxy ! StartProgressiveLoadingEvent(
        progressiveSources = event.progressiveSources,
        timeManagerRef = globalTimeManagerProxy,
        lookAheadTicks = lookAheadTicks
      )
    }

    logInfo("Start simulation")
    globalTimeManagerProxy ! StartSimulationTimeEvent(
      startTick = configuration.startTick,
      actorRef = getPath,
      data = Some(StartSimulationTimeData(startTime = System.currentTimeMillis()))
    )
  }

  private def getSelfProxy: ActorRef =
    if (selfProxy == null) {
      selfProxy = createSingletonProxy(SIMULATION_MANAGER_ACTOR_NAME)
      selfProxy
    } else {
      selfProxy
    }

  private def registerReporters(event: RegisterReportersEvent): Unit = {
    reporters = event.reporters
    startLoadData()
  }

  private def registerPoolTimeManager(event: TimeManagerRegisterEvent): Unit = {
    poolTimeManager = event.actorRef
    startLoadData()
  }

  private def startLoadData(): Unit =
    if (poolTimeManager != null && reporters != null) {
      loadManager = createSingletonLoadManager()
      createSingletonProxy(SNAPSHOT_MANAGER_ACTOR_NAME) ! SnapshotManager.RegisterSnapshotContextEvent(
        timeManagers = mutable.Map("discrete-event" -> poolTimeManager),
        reporters = reporters
      )
      logInfo(s"Sending LoadDataEvent with ${configuration.actorsDataSources.size} data sources")
      createSingletonProxy(LOAD_MANAGER_ACTOR_NAME) ! LoadDataEvent(
        actorRef = selfProxy,
        actorsDataSources = configuration.actorsDataSources,
        postLoadRegistrationClasses = configuration.postLoadRegistrationClasses
      )
    } else {
      logWarn(
        s"Not ready to load data: poolTimeManager=${poolTimeManager != null}, reporters=${reporters != null}"
      )
    }

  private def prepareSimulation(event: PrepareSimulationEvent): Unit = {
    if (configuration == null && !configLoadInProgress) {
      configLoadInProgress = true
      val ioDispatcher = context.system.dispatchers.lookup("pekko.actor.io-dispatcher")
      Future(loadSimulationConfig(event.configuration))(ioDispatcher).onComplete {
        case Success(loadedConfiguration) =>
          self ! SimulationConfigLoadedEvent(loadedConfiguration)
        case Failure(cause) =>
          self ! SimulationConfigLoadFailedEvent(cause)
      }(context.system.dispatcher)
      logInfo("Loading simulation configuration on io-dispatcher...")
      return
    }

    if (configuration == null) {
      return
    }

    prepareSimulation()
  }

  private def onSimulationConfigLoaded(event: SimulationConfigLoadedEvent): Unit = {
    configuration = event.config
    configLoadInProgress = false
    prepareSimulation()
  }

  private def onSimulationConfigLoadFailed(event: SimulationConfigLoadFailedEvent): Unit = {
    configLoadInProgress = false
    logError(s"Failed to load simulation configuration: ${event.cause.getMessage}", event.cause)
    selfDestruct()
  }

  private def prepareSimulation(): Unit = {
    if (simulationPrepared) {
      return
    }

    val cluster = Cluster(context.system)
    val members = cluster.state.members.filter(_.status == org.apache.pekko.cluster.MemberStatus.Up)
    val minMembers = context.system.settings.config.getInt("pekko.cluster.min-nr-of-members")
    logInfo(
      s"Cluster state at simulation startup: ${members.size} Up members " +
        s"(min-nr-of-members=$minMembers), selfAddress=${cluster.selfAddress}, " +
        s"leader=${cluster.state.leader.getOrElse("none")}"
    )


    if (members.size < minMembers) {
      if (!clusterRetryScheduled) {
        implicit val ec: scala.concurrent.ExecutionContext = context.system.dispatcher
        clusterRetryScheduled = true
        logWarn(
          s"Waiting for cluster quorum before starting simulation: " +
            s"${members.size}/$minMembers Up members. Retrying in 5 seconds."
        )
        context.system.scheduler.scheduleOnce(5.seconds, self, RetryPrepareSimulation)
      }
      return
    }

    clusterRetryScheduled = false
    simulationPrepared = true

    logInfo(
      s"Run simulation - Configuration loaded with ${configuration.actorsDataSources.size} data sources"
    )
    timeSingletonManager = createSingletonTimeManager()
    reportManager = createSingletonReportManager()
    snapshotManager = createSingletonSnapshotManager()
    createLoadBalanceManagerIfEnabled()
  }

  private def createSingletonTimeManager(): ActorRef =
    createSingletonManager(
      manager = GlobalTimeManager.props(
        simulationDuration = configuration.duration,
        extendSimulationIfPendingEventsAfterEnd =
          configuration.extendSimulationIfPendingEventsAfterEnd,
        simulationManager = getSelfProxy
      ),
      name = GLOBAL_TIME_MANAGER_ACTOR_NAME,
      terminateMessage = StopSimulationEvent()
    )

  private def createSingletonReportManager(): ActorRef =
    createSingletonManager(
      manager = ReportManager.props(
        simulationManager = getSelfProxy,
        timeManager = createSingletonProxy(LOAD_MANAGER_ACTOR_NAME),
        startRealTime = configuration.startRealTime
      ),
      name = REPORT_MANAGER_ACTOR_NAME,
      terminateMessage = StopSimulationEvent()
    )

  private def createSingletonSnapshotManager(): ActorRef =
    createSingletonManager(
      manager = SnapshotManager.props(
        timeManager = createSingletonProxy(GLOBAL_TIME_MANAGER_ACTOR_NAME)
      ),
      name = SNAPSHOT_MANAGER_ACTOR_NAME,
      terminateMessage = StopSimulationEvent()
    )

  private def createSingletonLoadManager(): ActorRef =
    createSingletonManager(
      manager = LoadDataManager.props(
        timeSingletonManager = timeSingletonManager,
        poolTimeManager = poolTimeManager,
        simulationManager = selfProxy,
        poolReporters = reporters
      ),
      name = LOAD_MANAGER_ACTOR_NAME,
      terminateMessage = StopSimulationEvent()
    )

  /** Creates the LoadBalanceManager singleton if enabled in configuration. */
  private def createLoadBalanceManagerIfEnabled(): Unit = {
    val loadBalanceEnabled = try {
      config.getBoolean("htc.load-balance-manager.enabled")
    } catch {
      case _: Exception => false
    }

    if (loadBalanceEnabled) {
      val strategyName = try {
        config.getString("htc.load-balance-manager.strategy")
      } catch {
        case _: Exception => "hybrid"
      }

      val strategyType = strategyName.toLowerCase match {
        case "hybrid"      => LoadBalanceStrategyEnum.Hybrid
        case "type-aware"  => LoadBalanceStrategyEnum.TypeAware
        case "geo-affinity" => LoadBalanceStrategyEnum.GeoAffinity
        case "default"     => LoadBalanceStrategyEnum.Default
        case "disabled"    => LoadBalanceStrategyEnum.Disabled
        case _             => LoadBalanceStrategyEnum.Hybrid
      }

      val worldBounds = try {
        SpatialBounds(
          minX = config.getDouble("htc.load-balance-manager.world-bounds.min-x"),
          minY = config.getDouble("htc.load-balance-manager.world-bounds.min-y"),
          maxX = config.getDouble("htc.load-balance-manager.world-bounds.max-x"),
          maxY = config.getDouble("htc.load-balance-manager.world-bounds.max-y")
        )
      } catch {
        case _: Exception =>
          SpatialBounds(-180.0, -90.0, 180.0, 90.0)
      }

      val strategyConfig = try {
        StrategyConfig(
          maxDepth                 = config.getInt("htc.load-balance-manager.quadtree.max-depth"),
          maxEntitiesPerShard      = config.getInt("htc.load-balance-manager.quadtree.max-actors-per-shard"),
          minEntitiesPerShard      = config.getInt("htc.load-balance-manager.quadtree.min-actors-per-shard"),
          loadThreshold            = config.getDouble("htc.load-balance-manager.kdtree.load-threshold"),
          predictionWindowSeconds  = config.getDouble("htc.load-balance-manager.prediction.prediction-window-seconds"),
          rebalanceIntervalSeconds = config.getDouble("htc.load-balance-manager.rebalance-interval-seconds"),
          enablePrediction         = config.getBoolean("htc.load-balance-manager.prediction.enabled"),
          enableTwoToOneBalance    = config.getBoolean("htc.load-balance-manager.quadtree.enable-two-to-one-balance"),
          maxConcurrentMigrations  = config.getInt("htc.load-balance-manager.migration.max-concurrent"),
          flowVectorSamples        = config.getInt("htc.load-balance-manager.prediction.flow-vector-samples"),
          geographicAffinityWeight = config.getDouble("htc.load-balance-manager.geo-affinity.weight")
        )
      } catch {
        case _: Exception => StrategyConfig()
      }

      loadBalanceManager = createSingletonManager(
        manager = LoadBalanceManager.props(
          timeManager = timeSingletonManager,
          simulationManager = getSelfProxy,
          strategyType = strategyType,
          worldBounds = worldBounds,
          strategyConfig = strategyConfig
        ),
        name = LOAD_BALANCE_MANAGER_ACTOR_NAME,
        terminateMessage = StopSimulationEvent()
      )

      logInfo(s"LoadBalanceManager created with strategy: $strategyName")
    } else {
      logInfo("LoadBalanceManager disabled. Using default Pekko shard allocation.")
    }
  }

  /** Handles notification that LoadBalanceManager is ready. */
  private def handleLoadBalanceReady(event: LoadBalanceReadyEvent): Unit = {
    logInfo("LoadBalanceManager is ready and operational.")
  }

  private def createSingletonProgressiveLoadManager(): ActorRef =
    createSingletonManager(
      manager = ProgressiveLoadDataManager.props(
        poolTimeManager = poolTimeManager,
        simulationManager = getSelfProxy,
        poolReporters = reporters
      ),
      name = PROGRESSIVE_LOAD_MANAGER_ACTOR_NAME,
      terminateMessage = StopSimulationEvent()
    )

  private def handleProgressiveLoadingComplete(event: ProgressiveLoadingCompleteEvent): Unit = {
    logInfo(
      s"Progressive loading complete: ${event.totalActorsCreated} actors created during simulation"
    )
    val globalTimeManagerProxy = createSingletonProxy(GLOBAL_TIME_MANAGER_ACTOR_NAME)
    globalTimeManagerProxy ! org.interscity.htc.core.entity.event.control.load.TickWindowReady(
      readyUpToTick = Long.MaxValue,
      actorsCreated = 0
    )
  }

  private def handleStopSimulation(): Unit = {
    logInfo("Received StopSimulationEvent. Stopping simulation managers gracefully")
    try {
      if (loadBalanceManager != null) {
        createSingletonProxy(LOAD_BALANCE_MANAGER_ACTOR_NAME) ! StopSimulationEvent()
      }
      if (loadManager != null) {
        createSingletonProxy(LOAD_MANAGER_ACTOR_NAME) ! StopSimulationEvent()
      }
      if (progressiveLoadManager != null) {
        createSingletonProxy(PROGRESSIVE_LOAD_MANAGER_ACTOR_NAME) ! StopSimulationEvent()
      }
      if (reportManager != null) {
        createSingletonProxy(REPORT_MANAGER_ACTOR_NAME) ! StopSimulationEvent()
      }
      if (timeSingletonManager != null) {
        createSingletonProxy(GLOBAL_TIME_MANAGER_ACTOR_NAME) ! StopSimulationEvent()
      }
    } catch {
      case e: Exception =>
        logError(s"Error while forwarding StopSimulationEvent: ${e.getMessage}", e)
    }
    selfDestruct()
  }
}
