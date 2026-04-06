package org.interscity.htc
package core.actor.manager

import org.apache.pekko.actor.{ ActorRef, Props }
import core.actor.manager.load.{ CreatorLoadData, CreatorPoolLoadData }
import core.entity.state.DefaultState
import core.util.{ ActorCreatorUtil, ManagerConstantsUtil }
import core.util.ActorCreatorUtil.createActor

import org.apache.pekko.cluster.routing.{ ClusterRouterPool, ClusterRouterPoolSettings }
import org.apache.pekko.routing.RoundRobinPool
import org.htc.protobuf.core.entity.event.control.execution.DestructEvent
import org.htc.protobuf.core.entity.event.control.execution.StopSimulationEvent
import org.interscity.htc.core.entity.actor.properties.{ CreatorProperties, Properties }
import org.interscity.htc.core.entity.configuration.ActorDataSource
import org.interscity.htc.core.entity.event.control.load.{ FinishCreationEvent, FinishLoadDataEvent, LoadDataEvent, LoadDataSourceEvent, LoadNextEvent }
import org.interscity.htc.core.util.ManagerConstantsUtil.POOL_CREATOR_POOL_LOAD_DATA_ACTOR_NAME
import org.interscity.htc.core.enumeration.{ LoadingStrategyEnum, ReportTypeEnum }
import org.interscity.htc.core.util.ManagerConstantsUtil.{ LOAD_MANAGER_ACTOR_NAME, POOL_CREATOR_LOAD_DATA_ACTOR_NAME }

import scala.collection.mutable
import scala.compiletime.uninitialized

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

  private var sourcesToCreate: mutable.Map[String, mutable.Queue[ActorDataSource]] = uninitialized
  private val sourcesInCreation: mutable.Set[String] = mutable.Set[String]()
  private var progressiveSources: List[ActorDataSource] = List.empty

  override def onStart(): Unit =
    reporters = poolReporters

  override def handleEvent: Receive = {
    case event: LoadDataEvent       => loadData(event)
    case event: FinishLoadDataEvent => handleFinishLoadData(event)
    case _: LoadNextEvent           => handleLoadNext()
    case _: StopSimulationEvent     => handleStopSimulation()
  }

  private def loadData(event: LoadDataEvent): Unit = {
    // Split data sources into EAGER (loaded before simulation) and PROGRESSIVE (loaded during simulation)
    val (eagerSources, progressive) = event.actorsDataSources.partition(
      _.loadingStrategy == LoadingStrategyEnum.EAGER
    )
    progressiveSources = progressive

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
    creatorRef = createCreatorLoadData(totalSources)
    creatorPoolRef = createCreatorPoolLoadData(totalSources)

    if (eagerSources.isEmpty) {
      logInfo("No EAGER sources. Proceeding directly to simulation start.")
      simulationManager ! FinishLoadDataEvent(
        actorRef = getSelfProxy,
        amount = 0L,
        actorClassType = null,
        creators = mutable.Set(),
        progressiveSources = progressiveSources,
        creatorRef = creatorRef,
        creatorPoolRef = creatorPoolRef
      )
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

  private def handleLoadNext(): Unit = {
    logInfo(s"handleLoadNext called. sourcesToCreate keys: ${sourcesToCreate.keys.mkString(", ")}")
    logInfo(s"sourcesInCreation: ${sourcesInCreation.mkString(", ")}")

    sourcesToCreate.foreach {
      (key, queue) =>
        logInfo(
          s"Checking key=$key, queue.nonEmpty=${queue.nonEmpty}, !sourcesInCreation.contains(key)=${!sourcesInCreation
              .contains(key)}"
        )
        if (queue.nonEmpty && !sourcesInCreation.contains(key)) {
          val source = queue.dequeue()
          sourcesInCreation.add(key)
          logInfo(
            s"Load data source ${source.dataSource} of type ${source.classType}"
          )
          // Create loader with io-dispatcher for I/O-bound file operations
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
            reporters = reporters
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
            reporters = reporters
          )
        )
      ),
      name = POOL_CREATOR_POOL_LOAD_DATA_ACTOR_NAME
    )
  }

  private def handleFinishLoadData(event: FinishLoadDataEvent): Unit = {
    val actorRef = event.actorRef

    loaders(actorRef) = true
    logInfo(s"Total loaded data: ${loaders.values.count(_.self == true)}/${loaders.size}")
    sourcesInCreation.remove(event.actorClassType)
    logInfo(
      s"Removed ${event.actorClassType} from sourcesInCreation. Remaining: ${sourcesInCreation.mkString(", ")}"
    )

    actorRef ! DestructEvent(actorRef = getPath)

    logInfo(s"Sending LoadNextEvent to process remaining sources")
    getSelfProxy ! LoadNextEvent()

    if (isAllDataLoaded) {
      logInfo(
        s"All EAGER data loaded! Sending FinishLoadDataEvent to SimulationManager. " +
          s"${progressiveSources.size} PROGRESSIVE sources pending."
      )
      simulationManager ! FinishLoadDataEvent(
        actorRef = selfProxy,
        amount = loadDataTotalAmount,
        actorClassType = null,
        creators = mutable.Set(),
        progressiveSources = progressiveSources,
        creatorRef = creatorRef,
        creatorPoolRef = creatorPoolRef
      )
    } else {
      logInfo(s"Not all data loaded yet. isAllDataLoaded=${isAllDataLoaded}")
    }
  }

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
