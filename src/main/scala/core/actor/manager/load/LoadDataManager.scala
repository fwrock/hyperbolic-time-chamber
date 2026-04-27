package org.interscity.htc
package core.actor.manager.load

import core.actor.manager.load.{ CreatorLoadData, CreatorPoolLoadData, PostLoadRegistrationCoordinator }
import core.actor.manager.BaseManager
import core.entity.actor.properties.{ CreatorProperties, Properties }
import core.entity.configuration.ActorDataSource
import core.entity.event.control.load.*
import core.entity.state.DefaultState
import core.enumeration.{ LoadingStrategyEnum, ReportTypeEnum }
import core.util.ActorCreatorUtil.createActor
import core.util.ManagerConstantsUtil.{ LOAD_MANAGER_ACTOR_NAME, POOL_CREATOR_LOAD_DATA_ACTOR_NAME, POOL_CREATOR_POOL_LOAD_DATA_ACTOR_NAME }
import core.util.{ ActorCreatorUtil, ManagerConstantsUtil }

import org.apache.pekko.actor.{ ActorRef, Props }
import org.apache.pekko.cluster.routing.{ ClusterRouterPool, ClusterRouterPoolSettings }
import org.apache.pekko.routing.RoundRobinPool
import org.htc.protobuf.core.entity.event.control.execution.{ DestructEvent, StopSimulationEvent }

import scala.collection.mutable
import scala.compiletime.uninitialized
import scala.concurrent.duration.*

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
  private var postLoadCoordinator: ActorRef = null
  private var postLoadRegistrationClassesConfig: Set[String] = Set.empty
  private var postLoadTriggerSent: Boolean = false
  private var postLoadDone: Boolean = false

  private var sourcesToCreate: mutable.Map[String, mutable.Queue[ActorDataSource]] = uninitialized
  private val sourcesInCreation: mutable.Set[String] = mutable.Set[String]()
  private var progressiveSources: List[ActorDataSource] = List.empty

  override def onStart(): Unit =
    reporters = poolReporters

  override def handleEvent: Receive = {
    case event: LoadDataEvent             => loadData(event)
    case event: FinishLoadDataEvent       => handleFinishLoadData(event)
    case _: LoadNextEvent                 => handleLoadNext()
    case _: StopSimulationEvent           => handleStopSimulation()
    case _: PostLoadRegistrationDoneEvent => handlePostLoadRegistrationDone()
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

    postLoadCoordinator = context.actorOf(
      PostLoadRegistrationCoordinator.props(getSelfProxy),
      "post-load-registration-coordinator"
    )

    val totalSources = event.actorsDataSources.size
    creatorRef = createCreatorLoadData(totalSources)
    creatorPoolRef = createCreatorPoolLoadData(totalSources)

    if (eagerSources.isEmpty) {
      logInfo("No EAGER sources. Triggering post-load registration phase.")
      postLoadCoordinator ! TriggerPostLoadRegistrationEvent(actorRef = getSelfProxy)
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
            source.dataSource.sourceType.clazz,
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
            postLoadCoordinator = postLoadCoordinator,
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
            postLoadCoordinator = postLoadCoordinator,
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

    if (isAllDataLoaded && !postLoadTriggerSent) {
      postLoadTriggerSent = true
      logInfo(
        s"All EAGER data loaded! ${progressiveSources.size} PROGRESSIVE sources pending. " +
          s"Triggering post-load registration phase."
      )
      postLoadCoordinator ! TriggerPostLoadRegistrationEvent(actorRef = getSelfProxy)
    } else if (postLoadTriggerSent) {
      logWarn(
        s"TriggerPostLoadRegistration already sent — ignoring duplicate (isAllDataLoaded=$isAllDataLoaded)"
      )
    }
  }

  private def handlePostLoadRegistrationDone(): Unit = {
    if (postLoadDone) {
      logWarn("PostLoadRegistrationDone received more than once — ignoring duplicate.")
      return
    }
    postLoadDone = true
    logInfo(
      "PostLoadRegistrationDone received. All registrations complete. Sending FinishLoadDataEvent to SimulationManager."
    )
    sendFinishToSimulationManager()
  }

  private def sendFinishToSimulationManager(): Unit =
    simulationManager ! FinishLoadDataEvent(
      actorRef = selfProxy,
      amount = loadDataTotalAmount,
      actorClassType = null,
      creators = mutable.Set(),
      progressiveSources = progressiveSources,
      creatorRef = creatorRef,
      creatorPoolRef = creatorPoolRef
    )

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
