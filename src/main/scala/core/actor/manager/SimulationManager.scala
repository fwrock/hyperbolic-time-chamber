package org.interscity.htc
package core.actor.manager

import core.entity.state.DefaultState

import org.apache.pekko.actor.ActorRef
import core.util.SimulationUtil.loadSimulationConfig

import org.htc.protobuf.core.entity.event.control.execution.{ DestructEvent, PrepareSimulationEvent, StartSimulationTimeEvent, StopSimulationEvent }
import org.htc.protobuf.core.entity.event.control.execution.data.StartSimulationTimeData
import org.interscity.htc.core.entity.configuration.Simulation
import org.interscity.htc.core.entity.event.control.execution.TimeManagerRegisterEvent
import org.interscity.htc.core.entity.event.control.load.{ FinishLoadDataEvent, LoadDataEvent }
import org.interscity.htc.core.entity.event.control.report.RegisterReportersEvent
import org.interscity.htc.core.entity.event.control.loadbalance.LoadBalanceReadyEvent
import org.interscity.htc.core.actor.manager.loadbalance.LoadBalanceManager
import org.interscity.htc.core.actor.manager.loadbalance.strategy.StrategyConfig
import org.interscity.htc.core.entity.control.loadbalance.SpatialBounds
import org.interscity.htc.core.enumeration.LoadBalanceStrategyEnum
import org.interscity.htc.core.util.ManagerConstantsUtil
import org.interscity.htc.core.util.ManagerConstantsUtil.{ GLOBAL_TIME_MANAGER_ACTOR_NAME, LOAD_BALANCE_MANAGER_ACTOR_NAME, LOAD_MANAGER_ACTOR_NAME, REPORT_MANAGER_ACTOR_NAME, SIMULATION_MANAGER_ACTOR_NAME }

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
  private var configuration: Simulation = uninitialized
  private var selfProxy: ActorRef = null

  override def handleEvent: Receive = {
    case event: PrepareSimulationEvent   => prepareSimulation(event)
    case event: FinishLoadDataEvent      => startSimulation()
    case event: TimeManagerRegisterEvent => registerPoolTimeManager(event)
    case event: RegisterReportersEvent   => registerReporters(event)
    case event: LoadBalanceReadyEvent    => handleLoadBalanceReady(event)
    case _: StopSimulationEvent          => handleStopSimulation()
  }

  override def onStart(): Unit =
    getSelfProxy ! PrepareSimulationEvent(
      configuration = simulationPath
    )

  private def startSimulation(): Unit = {
    loadManager ! DestructEvent(actorRef = getPath)
    logInfo("Start simulation")
    createSingletonProxy(GLOBAL_TIME_MANAGER_ACTOR_NAME) ! StartSimulationTimeEvent(
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
      logInfo(s"Sending LoadDataEvent with ${configuration.actorsDataSources.size} data sources")
      createSingletonProxy(LOAD_MANAGER_ACTOR_NAME) ! LoadDataEvent(
        actorRef = selfProxy,
        actorsDataSources = configuration.actorsDataSources
      )
    } else {
      logWarn(
        s"Not ready to load data: poolTimeManager=${poolTimeManager != null}, reporters=${reporters != null}"
      )
    }

  private def prepareSimulation(event: PrepareSimulationEvent): Unit = {
    configuration = loadSimulationConfig(event.configuration)
    logInfo(
      s"Run simulation - Configuration loaded with ${configuration.actorsDataSources.size} data sources"
    )
    configuration.actorsDataSources.foreach {
      source =>
        logInfo(s"  - Data source: ${source.id} (${source.classType}): ${source.dataSource}")
    }
    timeSingletonManager = createSingletonTimeManager()
    reportManager = createSingletonReportManager()
    // LoadBalanceManager handles shard REBALANCING (migration between nodes).
    // LoadDataManager handles shard CREATION (initial actor placement).
    // TODO: Wire LoadDataManager to query LoadBalanceManager for shard ID assignment
    // during creation, so initial placement is spatially-aware. Currently, shards use
    // Pekko's default hash-based allocation. The integration point is:
    //   CreatorLoadData → RegisterSpatialEntityEvent → LoadBalanceManager → ShardAssignmentResponse
    // which returns a logical shard ID that CreatorLoadData should use in createShardRegion().
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
        case "hybrid"   => LoadBalanceStrategyEnum.Hybrid
        case "default"  => LoadBalanceStrategyEnum.Default
        case "disabled" => LoadBalanceStrategyEnum.Disabled
        case _          => LoadBalanceStrategyEnum.Hybrid
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
          // Default world bounds (roughly covers most cities)
          SpatialBounds(-180.0, -90.0, 180.0, 90.0)
      }

      val strategyConfig = try {
        StrategyConfig(
          maxDepth = config.getInt("htc.load-balance-manager.quadtree.max-depth"),
          maxEntitiesPerShard = config.getInt("htc.load-balance-manager.quadtree.max-actors-per-shard"),
          minEntitiesPerShard = config.getInt("htc.load-balance-manager.quadtree.min-actors-per-shard"),
          loadThreshold = config.getDouble("htc.load-balance-manager.kdtree.load-threshold"),
          predictionWindowSeconds = config.getDouble("htc.load-balance-manager.prediction.prediction-window-seconds"),
          enablePrediction = config.getBoolean("htc.load-balance-manager.prediction.enabled"),
          maxConcurrentMigrations = config.getInt("htc.load-balance-manager.migration.max-concurrent"),
          flowVectorSamples = config.getInt("htc.load-balance-manager.prediction.flow-vector-samples")
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

  private def handleStopSimulation(): Unit = {
    logInfo("Received StopSimulationEvent. Stopping simulation managers gracefully")
    try {
      if (loadBalanceManager != null) {
        createSingletonProxy(LOAD_BALANCE_MANAGER_ACTOR_NAME) ! StopSimulationEvent()
      }
      if (loadManager != null) {
        createSingletonProxy(LOAD_MANAGER_ACTOR_NAME) ! StopSimulationEvent()
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
