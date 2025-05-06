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
import org.interscity.htc.core.entity.actor.properties.{ CreatorProperties, Properties }
import org.interscity.htc.core.entity.configuration.ActorDataSource
import org.interscity.htc.core.entity.event.control.load.{ FinishCreationEvent, FinishLoadDataEvent, LoadDataEvent, LoadDataSourceEvent, LoadNextEvent }
import org.interscity.htc.core.util.ManagerConstantsUtil.POOL_CREATOR_POOL_LOAD_DATA_ACTOR_NAME
import org.interscity.htc.core.enumeration.ReportTypeEnum
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

  override def onStart(): Unit =
    reporters = poolReporters

  override def handleEvent: Receive = {
    case event: LoadDataEvent       => loadData(event)
    case event: FinishLoadDataEvent => handleFinishLoadData(event)
    case event: FinishCreationEvent => handleFinishCreation(event)
    case _: LoadNextEvent           => handleLoadNext()
  }

  private def loadData(event: LoadDataEvent): Unit = {
    dataSourceAmount = event.actorsDataSources.size
    logInfo(s"Starting Load data, dataSourceAmount = $dataSourceAmount")
    creatorRef = createCreatorLoadData(dataSourceAmount)
    creatorPoolRef = createCreatorPoolLoadData(dataSourceAmount)

    sourcesToCreate = event.actorsDataSources
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
          val loader = createActor(
            context.system,
            source.dataSource.sourceType.clazz,
            properties = Properties(
              timeManager = poolTimeManager
            )
          )
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
    val totalInstances = amountDataSources
    val maxInstancesPerNode = Math.max(10, amountDataSources / 8)
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
            timeManager = poolTimeManager,
            reporters = reporters
          )
        )
      ),
      name = POOL_CREATOR_LOAD_DATA_ACTOR_NAME
    )
  }

  private def createCreatorPoolLoadData(amountDataSources: Int): ActorRef = {
    val totalInstances = amountDataSources
    val maxInstancesPerNode = Math.max(10, amountDataSources / 8)
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
            timeManager = poolTimeManager,
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
    sourcesInCreation.remove(event.actorClassType)

    actorRef ! DestructEvent(actorRef = getPath)

    getSelfProxy ! LoadNextEvent()

    if (isAllDataLoaded) {
      simulationManager ! FinishLoadDataEvent(
        actorRef = selfProxy,
        amount = loadDataTotalAmount,
        actorClassType = null,
        creators = mutable.Set()
      )
    }
  }

  private def handleFinishCreation(event: FinishCreationEvent): Unit = {
    creators.get(event.actorRef) match
      case Some(flag) =>
        if (!flag) {
          currentLoadDataAmount += event.amount
          creators(event.actorRef) = true
        } else {
          logInfo(s"Creator already finished ${event.actorRef} with ${event.amount}")
        }
      case None =>
        logInfo(s"Creator not found ${event.actorRef}")
    if (loadDataTotalAmount == currentLoadDataAmount && creators.values.forall(_.self == true)) {
      logInfo("Finish creation fully")
      simulationManager ! FinishLoadDataEvent(
        actorRef = selfProxy,
        amount = loadDataTotalAmount,
        actorClassType = null,
        creators = mutable.Set()
      )
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
