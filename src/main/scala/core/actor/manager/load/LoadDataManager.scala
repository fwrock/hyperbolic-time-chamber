package org.interscity.htc
package core.actor.manager.load

import core.actor.manager.load.{CreatorLoadData, CreatorPoolLoadData}
import core.actor.manager.BaseManager
import core.entity.actor.properties.{CreatorProperties, Properties}
import core.entity.configuration.ActorDataSource
import core.entity.event.control.load.*
import core.entity.state.DefaultState
import core.enumeration.{LoadingStrategyEnum, ReportTypeEnum}
import core.util.ManagerConstantsUtil.{LOAD_MANAGER_ACTOR_NAME, POOL_CREATOR_LOAD_DATA_ACTOR_NAME, POOL_CREATOR_POOL_LOAD_DATA_ACTOR_NAME, POST_LOAD_REGISTRATION_MANAGER_ACTOR_NAME}

import org.apache.pekko.actor.{ActorRef, Props}
import org.apache.pekko.cluster.routing.{ClusterRouterPool, ClusterRouterPoolSettings}
import org.apache.pekko.routing.RoundRobinPool
import org.htc.protobuf.core.entity.event.control.execution.{DestructEvent, StopSimulationEvent}
import org.interscity.htc.core.actor.manager.loadbalance.allocation.SpatialShardIdRegistry
import org.interscity.htc.model.hybrid.decision.ScenarioLoadError

import scala.collection.mutable
import scala.compiletime.uninitialized
import scala.util.{Failure, Success}

class LoadDataManager(
  val timeSingletonManager: ActorRef,
  val poolTimeManager: ActorRef,
  val simulationManager: ActorRef,
  val poolReporters: mutable.Map[ReportTypeEnum, ActorRef]
) extends BaseManager[DefaultState](
      timeManager = timeSingletonManager,
      actorId = "load-data-manager"
    ) {

  private val loadDataTotalAmount = 0L
  private var currentLoadDataAmount = 0L
  private var dataSourceAmount: Int = Int.MaxValue
  private var creatorRef: ActorRef = uninitialized
  private var creatorPoolRef: ActorRef = uninitialized
  private val loaders: mutable.Map[ActorRef, Boolean] = mutable.Map[ActorRef, Boolean]()
  private var selfProxy: ActorRef = null
  private val creators = mutable.Map[ActorRef, Boolean]()
  private var postLoadRegistrationClassesConfig: Set[String] = Set.empty
  private var eagerLoadReadySent: Boolean = false

  private var sourcesToCreate: mutable.Map[String, mutable.Queue[ActorDataSource]] = uninitialized
  private val sourcesInCreation: mutable.Set[String] = mutable.Set[String]()
  private var progressiveSources: List[ActorDataSource] = List.empty

  override def onStart(): Unit =
    reporters = poolReporters

  override def handleEvent: Receive = {
    case event: LoadDataEvent                  => loadData(event)
    case event: FinishLoadDataEvent            => handleFinishLoadData(event)
    case _: LoadNextEvent                      => handleLoadNext()
    case _: StopSimulationEvent                => handleStopSimulation()
    case event: LoadDataManager.PreflightDone  => handlePreflightDone(event)
  }

  private def loadData(event: LoadDataEvent): Unit = {
    val (eagerSources, progressive) = event.actorsDataSources.partition(
      _.loadingStrategy == LoadingStrategyEnum.EAGER
    )
    progressiveSources = progressive
    postLoadRegistrationClassesConfig = event.postLoadRegistrationClasses.toSet

    dataSourceAmount = eagerSources.size
    logInfo(
      s"Starting Load data: ${eagerSources.size} EAGER sources, " +
        s"${progressive.size} PROGRESSIVE sources (deferred to simulation)"
    )

    if (dataSourceAmount == 0 && progressive.isEmpty) {
      logWarn("No data sources to load. Simulation will start with no actors.")
      return
    }

    val totalSources = event.actorsDataSources.size
    val personEagerSources = eagerSources.filter(ScenarioPreflightValidator.isPersonSource)

    if (personEagerSources.isEmpty) {
      proceedWithLoad(eagerSources, progressive, totalSources)
    } else {
      runScenarioPreflight(personEagerSources, eagerSources, progressive, totalSources)
    }
  }

  /** Fail-fast, scenario-wide mode-decision-engine check (see
    * `core.actor.manager.load.ScenarioPreflightValidator` /
    * `model.hybrid.decision.ScenarioLoadValidator.validateModeDecisionEngines`) run over every
    * EAGER Person source before `creatorRef`/`creatorPoolRef` — and therefore any actor — is
    * created. Only reachable for EAGER sources; see `ProgressiveLoadDataManager` for the
    * equivalent PROGRESSIVE-mode check.
    */
  private def runScenarioPreflight(
    personEagerSources: List[ActorDataSource],
    eagerSources: List[ActorDataSource],
    progressive: List[ActorDataSource],
    totalSources: Int
  ): Unit = {
    logInfo(
      s"Running scenario-wide mode-decision-engine pre-flight check over " +
        s"${personEagerSources.size} EAGER Person source(s) before creating any actor."
    )
    val ctx = ScenarioPreflightValidator.currentValidationContext()
    val ioEc = context.system.dispatchers.lookup("pekko.actor.io-dispatcher")
    ScenarioPreflightValidator.validate(personEagerSources, ctx)(ioEc).onComplete {
      case Success(result) =>
        self ! LoadDataManager.PreflightDone(result, eagerSources, progressive, totalSources)
      case Failure(cause) =>
        self ! LoadDataManager.PreflightDone(
          Left(ScenarioLoadError(s"Scenario pre-flight check crashed: ${cause.getMessage}")),
          eagerSources,
          progressive,
          totalSources
        )
    }(context.dispatcher)
  }

  private def handlePreflightDone(event: LoadDataManager.PreflightDone): Unit =
    event.result match {
      case Right(()) =>
        proceedWithLoad(event.eagerSources, event.progressive, event.totalSources)
      case Left(error) =>
        logError(s"Scenario load aborted by pre-flight check: ${error.message}")
        simulationManager ! ScenarioPreflightValidationFailedEvent(error)
        selfDestruct()
    }

  /** Continuation of `loadData` once the scenario has either passed pre-flight validation, or
    * didn't need it (no EAGER Person sources referencing any `PendingDecision`).
    */
  private def proceedWithLoad(
    eagerSources: List[ActorDataSource],
    progressive: List[ActorDataSource],
    totalSources: Int
  ): Unit = {
    creatorRef = createCreatorLoadData(totalSources)
    creatorPoolRef = createCreatorPoolLoadData(totalSources)

    if (eagerSources.isEmpty) {
      logInfo("No EAGER sources. Emitting eager-load-ready barrier and waiting for post-load trigger.")
      notifyEagerLoadReady()
      return
    }

    sourcesToCreate = eagerSources
      .groupBy(
        s => s.classType
      )
      .view
      .mapValues(_.to(mutable.Queue))
      .to(mutable.Map)

    getSelfProxy ! LoadNextEvent()
  }

  private def handleLoadNext(): Unit =
    sourcesToCreate.foreach {
      (key, queue) =>
        if (queue.nonEmpty && !sourcesInCreation.contains(key)) {
          val source = queue.dequeue()
          sourcesInCreation.add(key)
          logInfo(
            s"Load data source ${source.dataSource} of type ${source.classType}"
          )
          val props = Props(
            source.dataSource.sourceType.eagerClazz,
            Properties(
              entityId = s"loader-${source.dataSource.hashCode()}",
              resourceId = "",
              timeManagers = mutable.Map("discrete-event" -> poolTimeManager),
              creatorManager = null,
              reporters = mutable.Map.empty
            )
          ).withDispatcher("pekko.actor.io-dispatcher")
          val loader = context.system.actorOf(props)
          loaders.put(loader, false)
          loader ! LoadDataSourceEvent(
            managerRef = getSelfProxy,
            creatorRef = creatorRef,
            creatorPoolRef = creatorPoolRef,
            actorDataSource = source
          )
        }
    }

  private def createCreatorLoadData(amountDataSources: Int): ActorRef = {
    val totalInstances = Math.max(1, amountDataSources)
    val maxInstancesPerNode = Math.max(1, Math.max(10, amountDataSources / 8))
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
            entityId = "creator-load-data",
            loadDataManager = getSelfProxy,
            timeManagers = mutable.Map("discrete-event" -> poolTimeManager),
            reporters = reporters,
            postLoadCoordinator = postLoadRegistrationManagerProxy,
            postLoadRegistrationClasses = postLoadRegistrationClassesConfig
          )
        )
      ),
      name = POOL_CREATOR_LOAD_DATA_ACTOR_NAME
    )
  }

  private def createCreatorPoolLoadData(amountDataSources: Int): ActorRef = {
    val totalInstances = Math.max(1, amountDataSources)
    val maxInstancesPerNode = Math.max(1, Math.max(10, amountDataSources / 8))
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
            entityId = "creator-pool-load-data",
            loadDataManager = getSelfProxy,
            timeManagers = mutable.Map("discrete-event" -> poolTimeManager),
            reporters = reporters,
            postLoadCoordinator = postLoadRegistrationManagerProxy,
            postLoadRegistrationClasses = postLoadRegistrationClassesConfig
          )
        )
      ),
      name = POOL_CREATOR_POOL_LOAD_DATA_ACTOR_NAME
    )
  }

  private def handleFinishLoadData(event: FinishLoadDataEvent): Unit = {
    val actorRef = event.actorRef

    loaders(actorRef) = true
    logInfo(s"Total loaded data: ${loaders.values.count(_ == true)}/${loaders.size}")
    sourcesInCreation.remove(event.actorClassType)

    actorRef ! DestructEvent(actorRef = getPath)

    getSelfProxy ! LoadNextEvent()

    if (isAllDataLoaded && !eagerLoadReadySent) {
      logInfo(
        s"All EAGER data loaded! ${progressiveSources.size} PROGRESSIVE sources pending. " +
          s"Emitting eager-load-ready barrier."
      )
      if (progressiveSources.isEmpty) {
        SpatialShardIdRegistry.clearPositions()
      }
      notifyEagerLoadReady()
    }
  }

  private def notifyEagerLoadReady(): Unit =
    if (!eagerLoadReadySent) {
      eagerLoadReadySent = true
      simulationManager ! EagerLoadDataReadyEvent(
        eagerSourcesLoaded = dataSourceAmount,
        progressiveSources = progressiveSources
      )
    }

  private def postLoadRegistrationManagerProxy: ActorRef =
    createSingletonProxy(POST_LOAD_REGISTRATION_MANAGER_ACTOR_NAME)

  private def isAllDataLoaded: Boolean =
    loaders.values.forall(_ == true) && dataSourceAmount == loaders.size && sourcesToCreate.values
      .forall(_.isEmpty)

  private def getSelfProxy: ActorRef =
    if (selfProxy == null) {
      selfProxy = createSingletonProxy(LOAD_MANAGER_ACTOR_NAME)
      selfProxy
    } else {
      selfProxy
    }

  private def handleStopSimulation(): Unit = {
    logInfo("Received StopSimulationEvent. Stopping load manager gracefully")
    loaders.keys.foreach {
      loaderRef =>
        loaderRef ! DestructEvent(actorRef = getPath)
    }
    selfDestruct()
  }
}

object LoadDataManager {

  /** Internal self-message carrying the result of `ScenarioPreflightValidator.validate`, along
    * with the state `loadData` needs to resume (or abort) once the async scan completes. Never
    * sent by anything outside this actor.
    */
  private[load] final case class PreflightDone(
    result: Either[ScenarioLoadError, Unit],
    eagerSources: List[ActorDataSource],
    progressive: List[ActorDataSource],
    totalSources: Int
  )

  def props(
    timeSingletonManager: ActorRef,
    poolTimeManager: ActorRef,
    simulationManager: ActorRef,
    poolReporters: mutable.Map[ReportTypeEnum, ActorRef]
  ): Props =
    Props(
      classOf[LoadDataManager],
      timeSingletonManager,
      poolTimeManager,
      simulationManager,
      poolReporters
    )
}
