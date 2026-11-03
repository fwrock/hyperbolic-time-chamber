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
import org.interscity.htc.core.modifications.ScenarioModifications
import org.interscity.htc.core.partial.PartialFilter
import org.interscity.htc.model.hybrid.decision.ScenarioLoadError

import scala.collection.mutable
import scala.compiletime.uninitialized
import scala.util.{Failure, Success}

class LoadDataManager(
  val timeSingletonManager: ActorRef,
  val poolTimeManager: ActorRef,
  val timeManagerType: String,
  val simulationManager: ActorRef,
  val poolReporters: mutable.Map[ReportTypeEnum, ActorRef],
  val runtimeCoordinator: Option[ActorRef] = None
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
    case event: LoadDataManager.PartialFilterReady => handlePartialFilterReady(event)
    case event: LoadDataManager.ModificationsReady => handleModificationsReady(event)
    case event: ScenarioPreflightValidationFailedEvent =>
      simulationManager ! event
      selfDestruct()
  }

  private def loadData(event: LoadDataEvent): Unit = {
    val filter = scala.util.Try(ActorLoadFilter.configure(context.system.settings.config)) match {
      case Success(settings) => settings
      case Failure(error) =>
        simulationManager ! ScenarioPreflightValidationFailedEvent(ScenarioLoadError(s"Load filter: ${error.getMessage}"))
        selfDestruct()
        return
    }
    val includedSources = ActorLoadFilter.sources(event.actorsDataSources, filter)
    if (filter.enabled)
      logInfo(s"[LOAD-FILTER] ${event.actorsDataSources.size - includedSources.size} sources excluded; include=${filter.includedClasses.toList.sorted} exclude=${filter.excludedClasses.toList.sorted} transforms=${filter.transforms.map(_.getClass.getName)}")
    if (includedSources.isEmpty && event.actorsDataSources.nonEmpty) {
      simulationManager ! ScenarioPreflightValidationFailedEvent(ScenarioLoadError("Load filter excluded every actor source"))
      selfDestruct()
      return
    }
    val (eagerSources, progressive) = includedSources.partition(
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

    // what-if modifications first (they define the scenario the partial filter then sees)
    val mods = event.modifications.map(m => ScenarioModifications.settings(m)).getOrElse(ScenarioModifications.settings(context.system.settings.config))
    if (!mods.enabled) ScenarioModifications.clear()
    if (mods.enabled) {
      logInfo(s"Scenario modifications: ${mods.files.size} file(s) ${mods.files.mkString(", ")}")
      val ioEc = context.system.dispatchers.lookup("pekko.actor.io-dispatcher")
      scala.concurrent.Future(ScenarioModifications.prepare(event.actorsDataSources, mods))(ioEc).onComplete {
        case Success(result) => self ! LoadDataManager.ModificationsReady(result, event, eagerSources, progressive)
        case Failure(cause) =>
          self ! LoadDataManager.ModificationsReady(Left(s"modifications crashed: ${cause.getMessage}"), event, eagerSources, progressive)
      }(context.dispatcher)
    } else afterModifications(event, eagerSources, progressive, includedSources)
  }

  private def handleModificationsReady(m: LoadDataManager.ModificationsReady): Unit =
    m.result match {
      case Right((summary, extra)) =>
        logInfo(
          s"Scenario modifications applied: ${summary.operations} operation(s) from ${summary.files} file(s): " +
            s"${summary.added} added, ${summary.removed} removed, ${summary.edited} edited"
        )
        summary.warnings.foreach(w => logWarn(s"Scenario modifications: $w"))
        // the added actors load from generated EAGER sources, one per class
        val includedExtra = ActorLoadFilter.sources(extra)
        dataSourceAmount = m.eagerSources.size + includedExtra.size
        afterModifications(m.event, m.eagerSources ++ includedExtra, m.progressive,
          ActorLoadFilter.sources(m.event.actorsDataSources ++ includedExtra))
      case Left(message) =>
        logError(s"Scenario load aborted by the modifications: $message")
        simulationManager ! ScenarioPreflightValidationFailedEvent(ScenarioLoadError(s"Modifications: $message"))
        selfDestruct()
    }

  private def afterModifications(
    event: LoadDataEvent,
    eagerSources: List[ActorDataSource],
    progressive: List[ActorDataSource],
    allSources: List[ActorDataSource]
  ): Unit = {
    // simulation.json's partial block wins; application.conf / env is the fallback (command-line runs)
    val partial = event.partial.map(p => PartialFilter.settings(p)).getOrElse(PartialFilter.settings(context.system.settings.config))
    if (!partial.enabled) PartialFilter.clear()
    if (partial.enabled) {
      preparePartialFilter(partial, allSources, eagerSources, progressive)
    } else {
      continueAfterFilter(eagerSources, progressive, allSources.size)
    }
  }

  /** Partial simulation: scan every source (EAGER and PROGRESSIVE), compute the kept set and activate
    * [[PartialFilter]] before any loader runs or any actor is created; abort the load if the scenario's
    * composition relations are inconsistent. Only reached when `htc.partial.selection-file` is set.
    */
  private def preparePartialFilter(
    partial: PartialFilter.Settings,
    allSources: List[ActorDataSource],
    eagerSources: List[ActorDataSource],
    progressive: List[ActorDataSource]
  ): Unit = {
    logInfo(s"Partial simulation: scanning ${allSources.size} source(s) with selection ${partial.selectionFile}")
    val ioEc = context.system.dispatchers.lookup("pekko.actor.io-dispatcher")
    scala.concurrent.Future(PartialFilter.prepare(allSources, partial))(ioEc).onComplete {
      case Success(result) =>
        self ! LoadDataManager.PartialFilterReady(result, partial, eagerSources, progressive, allSources.size)
      case Failure(cause) =>
        self ! LoadDataManager.PartialFilterReady(
          Left(s"partial filter crashed: ${cause.getMessage}"), partial, eagerSources, progressive, allSources.size
        )
    }(context.dispatcher)
  }

  private def handlePartialFilterReady(event: LoadDataManager.PartialFilterReady): Unit =
    event.result match {
      case Right(summary) =>
        logInfo(
          s"Partial simulation: ${summary.kept}/${summary.filterable} filterable actors kept " +
            s"(${summary.removed} removed), ${summary.selected} units selected, by reason ${summary.byReason}"
        )
        if (summary.selectedNotFound.nonEmpty)
          logWarn(s"Partial simulation: ${summary.selectedNotFound.size} selected unit(s) do not exist in the scenario")
        if (summary.nonNumericUnits.nonEmpty)
          logWarn(s"Partial simulation: ${summary.nonNumericUnits.size} selected unit id(s) are not numeric and match no actor")
        PartialFilter.writeReport(event.settings, summary) match {
          case Some(path) => logInfo(s"Partial simulation report: $path")
          case None       => logWarn("Partial simulation: could not write the report file")
        }
        continueAfterFilter(event.eagerSources, event.progressive, event.totalSources)
      case Left(message) =>
        logError(s"Scenario load aborted by the partial filter: $message")
        simulationManager ! ScenarioPreflightValidationFailedEvent(ScenarioLoadError(s"Partial simulation: $message"))
        selfDestruct()
    }

  private def continueAfterFilter(
    eagerSources: List[ActorDataSource],
    progressive: List[ActorDataSource],
    totalSources: Int
  ): Unit = {
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
              timeManagers = mutable.Map(timeManagerType -> poolTimeManager),
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
            timeManagers = mutable.Map(timeManagerType -> poolTimeManager),
            runtimeCoordinator = runtimeCoordinator,
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
            timeManagers = mutable.Map(timeManagerType -> poolTimeManager),
            runtimeCoordinator = runtimeCoordinator,
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

  /** Internal self-message: result of reading/validating the scenario modifications. */
  private[load] final case class ModificationsReady(
    result: Either[String, (ScenarioModifications.Summary, List[ActorDataSource])],
    event: LoadDataEvent,
    eagerSources: List[ActorDataSource],
    progressive: List[ActorDataSource]
  )

  /** Internal self-message: result of the partial-filter scan, with what `loadData` needs to resume. */
  private[load] final case class PartialFilterReady(
    result: Either[String, PartialFilter.Summary],
    settings: PartialFilter.Settings,
    eagerSources: List[ActorDataSource],
    progressive: List[ActorDataSource],
    totalSources: Int
  )

  def props(
    timeSingletonManager: ActorRef,
    poolTimeManager: ActorRef,
    timeManagerType: String,
    simulationManager: ActorRef,
    poolReporters: mutable.Map[ReportTypeEnum, ActorRef],
    runtimeCoordinator: Option[ActorRef] = None
  ): Props =
    Props(
      classOf[LoadDataManager],
      timeSingletonManager,
      poolTimeManager,
      timeManagerType,
      simulationManager,
      poolReporters,
      runtimeCoordinator
    )
}
